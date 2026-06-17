# ACTION.md — Build & CI/CD 行为规范

> 版本: 1.3
> 更新日期: 2026-06-17

## 核心原则

**本仓库没有本地构建工具链。所有构建在云端 CI/CD 完成，禁止 Agent 在本地执行 Gradle 构建。**

## CI/CD 工作流

### 工作流协作关系

```
push/PR to master
  └─ build.yml: apk job
       ├─ assembleBasicRelease (release APK)
       ├─ assembleBasicDebug + assembleBasicDebugAndroidTest (debug APKs for test)
       └─ upload debug-apks artifact
            │
            └─ build.yml: test job (needs: [apk], if: success)
                 └─ uses: test.yml (workflow_call)
                      ├─ download debug-apks artifact
                      ├─ KVM + emulator (AVD snapshot cache)
                      └─ adb install → am instrument
```

- **test.yml 不会重复编译** — 直接从 build.yml 下载预构建的 debug APK
- **test.yml 仅在 build.yml apk job 成功时触发** — 通过 `needs: [apk]` + `if: needs.apk.result == 'success'`
- **不再使用 `workflow_run`** — 避免 build 失败时产生无意义的 skip 记录

### `build.yml` — 编译 & 发布 (`.github/workflows/build.yml`)

### 触发规则

| 触发事件 | 触发的 Job | 行为 |
|----------|-----------|------|
| push to `master`/`main` (不含 `v*` tag) | `apk` | Kotlin 编译 + APK 打包，无 native，无 Release |
| push `v*` tag | `native` → `apk` → `github-release` | 全量 native C++ 编译 + APK + GitHub Release |
| Pull Request to `master`/`main` | `apk` | Kotlin 编译 + APK 打包，无 native，无 Release |
| `workflow_dispatch` (`build_native=true`) | `native` → `apk` | 手动触发 native 编译 |
| `workflow_dispatch` (`release=true`) | `native` → `apk` → `github-release` | 手动触发发布 |

### 路径过滤

以下文件变更**不触发** CI：`**.md`、`LICENSE`、`.gitignore`。

### 构建产物

| Artifact | 条件 | 保留 |
|----------|------|------|
| `dreamandroid-APK` | tag push / manual dispatch (release=true) | 30 天 |
| `debug-apks` | `apk` job 成功 | 1 天 |
| `build-gradle.log` | `apk` job 运行 | 7 天 |
| `build-native.log` | native job 运行 | 7 天 |

### 关键配置

| 配置项 | 值 |
|--------|-----|
| Runner | `ubuntu-24.04` |
| JDK | 17 (Temurin) |
| Android SDK | `platforms;android-36`, `build-tools;36.0.0` |
| NDK | `28.2.13676358` (仅 native job) |
| Gradle 任务 | `assembleBasicRelease` → `assembleBasicDebug` + `assembleBasicDebugAndroidTest` |
| APK 输出 | `app/build/outputs/apk/basic/release/*.apk` |
| Debug APK 输出 | `app/build/outputs/apk/**/debug/*.apk` |

### Version 管理

- `VERSION_NAME`: 格式 `YYYY.MM.DD.HH.mm`（如 `2026.06.13.15.08`），在 `apk` job 中校验
- `VERSION_CODE`: 递增整数（当前 `245`）
- Release 时 APK 重命名为 `DreamHub-{version}-arm64-v8a-release.apk`

### `test.yml` — 模拟器启动测试 (`.github/workflows/test.yml`)

`test.yml` 是**可重用工作流 (reusable workflow)**，通过 `workflow_call` 被 `build.yml` 的 `test` job 调用，也支持手动 `workflow_dispatch`。

#### 触发规则

| 触发事件 | 行为 |
|----------|------|
| `workflow_call` (build.yml `test` job → `needs: [apk]`, `if: success`) | 仅在 apk job 成功时调用 → 下载 `debug-apks` → 模拟器测试 |
| `workflow_dispatch` | 手动触发，需提供 `build-run-id` |

#### build.yml 中的 test job

```yaml
test:
  needs: [apk]
  if: needs.apk.result == 'success'
  uses: ./.github/workflows/test.yml
  with:
    build-run-id: ${{ github.run_id }}
```

- `needs: [apk]` + `if: success` 确保 apk 失败时完全不触发 test，不会产生 skip 记录
- 传入 `github.run_id` 供 test.yml 下载同一次 build 产出的 `debug-apks` artifact

#### 工作流特点

- **无 checkout** — 不拉代码
- **无 Gradle** — 不编译，直接使用 build.yml 产物
- **adb install + am instrument** — 绕过 Gradle 直接运行测试

#### 关键配置

| 配置项 | 值 |
|--------|-----|
| Runner | `ubuntu-latest` |
| 模拟器 Action | `reactivecircus/android-emulator-runner@v2` |
| 系统镜像 | `system-images;android-30;google_apis;x86_64` |
| KVM | 通过 udev 规则启用 |
| 测试方式 | `adb install` APK → `am instrument -e class AppLaunchInstrumentationTest` |
| AVD 快照 | 两步模式 (generate snapshot → load + test) |

#### 缓存策略

- AVD 数据 (`~/.android/avd/*`, `~/.android/adb*`) — key: `avd-{api-level}-{arch}-{target}-v2`

#### 产物

- Logcat (`logcat-{api-level}-{target}`): 保留 7 天

#### 构建配置配合

Debug APK 需包含 `x86_64` ABI（`app/build.gradle.kts` debug block 已配置），Release APK 保持 `arm64-v8a` only 不变。

#### 测试覆盖

见 `app/src/androidTest/java/io/github/dreamandroid/local/AppLaunchInstrumentationTest.kt`：
- Application 类型正确 & onCreate 不崩溃
- Activity 全链路启动
- 关键依赖 (database/backendService/queueRepository) 逐个初始化不崩溃

## Agent 行为规则

### 禁止事项

1. **禁止 `./gradlew assemble*`** — 不在本地执行任何 Gradle 构建命令
2. **禁止 `./gradlew build`** — 同上
3. **禁止 `./gradlew compile*`** — 同上
4. **禁止安装/配置本地 Android SDK / NDK / JDK 用于构建** — 无本地构建需求

### 允许事项

1. **读取 `build-log.txt`** — 查看上次 CI 构建日志，分析编译错误
2. **读取 `.github/workflows/build.yml`** — 理解 CI 工作流配置
3. **读取 `VERSION_NAME` / `VERSION_CODE`** — 了解当前版本号
4. **修改 `VERSION_NAME` / `VERSION_CODE`** — 当需要更新版本号时

### 编译验证流程

1. 编写代码修改 → 提交并推送
2. CI 自动触发 Kotlin 编译 (push to master)
3. 从 CI Artifacts 下载 `build-gradle.log` 查看编译结果
4. 如有错误，读取日志、分析、修复、重新提交

### 查询 CI Workflow 状态

1. 通过 GitHub REST API 获取最新 workflow 运行状态：\
   `GET https://api.github.com/repos/{owner}/{repo}/actions/runs?per_page=1`
2. 从响应中提取：
   - `workflow_runs[0].status` — 运行状态 (`queued` / `in_progress` / `completed`)
   - `workflow_runs[0].conclusion` — 运行结论 (`success` / `failure` / `cancelled`)
   - `workflow_runs[0].html_url` — 浏览器链接
   - `workflow_runs[0].name` — workflow 名称
3. 规则说明中不得提及任何 GitHub 用户名或个人账号信息

### 通过 `gh` CLI 读取 CI Run 日志

> 前提：`gh` CLI 已安装。若 `gh` 不在 PATH 中，先搜寻安装路径（常见位置 `C:\Program Files\GitHub CLI\gh.exe`），PowerShell 用 `&` 调用完整路径；若未安装则提示用户安装。

#### Step 1 — 获取最新 Run 列表（带 `databaseId`）

```
gh run list --limit 3 --json databaseId,headBranch,status,conclusion,displayTitle,createdAt
```

`databaseId` 是数字 Run ID（如 `27663340915`），后续所有操作都依赖它。

#### Step 2 — 获取 Run 内 Jobs 及其 `databaseId`

```
gh run view <run-databaseId> --json jobs --jq '.jobs[] | {name, status, conclusion, databaseId}'
```

每个 Job 有自己的 `databaseId`（如 `81812591585`），查日志需要用到它。

#### Step 3 — 查看失败 Job 的失败步骤日志

```
gh run view <run-databaseId> --log-failed --job <job-databaseId>
```

#### Step 4 — 搜索日志中的特定内容

在 PowerShell 中无 `grep`/`head`，可用 `Select-String`：

```
gh run view <run-databaseId> --log --job <job-databaseId> | Select-String -Pattern "Error|APK count"
```

#### 常见陷阱

| 错误操作 | 正确做法 |
|----------|----------|
| 用 commit SHA 当 run ID | 必须用 `databaseId`（数字） |
| `--run-id` flag | 该 flag 不存在，run ID 是 positional 参数 |
| 用 run databaseId 当 `--job` 参数 | 必须用 Job 自己的 `databaseId` |
| `\| head` / `\| grep` | PowerShell 中用 `Select-String -Pattern` |
| `gh` 不在 PATH | 用完整路径 `& "C:\Program Files\GitHub CLI\gh.exe"` |

### Release 发布流程

1. 更新 `VERSION_NAME` 和 `VERSION_CODE`
2. 提交并推送
3. 创建 `v{version}` tag 并推送（如 `v2026.06.16.15.00`）
4. CI 自动执行 native 编译 → APK 打包 → GitHub Release

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-17 | 添加 `gh` CLI 读取 CI Run 日志指南（含常见陷阱）；补充 gh 不在 PATH 时的搜寻说明 |
| 2026-06-16 | 添加 CI Workflow 状态查询规则（GitHub REST API） |
| 2026-06-16 | 初始创建。声明无本地构建工具链，所有构建在云端 CI/CD 完成 |
