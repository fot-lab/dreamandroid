# ACTION.md — Build & CI/CD 行为规范

> 版本: 1.2
> 更新日期: 2026-06-16

## 核心原则

**本仓库没有本地构建工具链。所有构建在云端 CI/CD 完成，禁止 Agent 在本地执行 Gradle 构建。**

## CI/CD 工作流

### 工作流协作关系

```
push/PR to master
  └─ build.yml
       ├─ cmake (native C++ libs, cached)
       ├─ gradle-debug (debug APK + androidTest APK)
       ├─ gradle-release (release APK, R8 minify on)
       ├─ test (Stage 3, needs: gradle-debug)
       │    └─ apk-test.yml: instrumentation-test job
       │         ├─ download debug-apks artifact
       │         ├─ KVM + emulator (AVD snapshot cache)
       │         ├─ Phase 1: QueueProgressCrashTest (dedicated, with crash logcat)
       │         ├─ Phase 2: remaining tests (notClass filter)
       │         └─ upload: screenshots + logcat-crash + logcat + test-reports
       └─ github-release (needs: gradle-release)
```

- **test.yml 不会重复编译** — 直接从 build.yml 下载预构建的 debug APK
- **test.yml 仅在 build.yml 成功时触发** (`workflow_run` + 结论=success)

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

### `apk-test.yml` — 模拟器仪表化测试 (`.github/workflows/apk-test.yml`)

#### 触发规则

| 触发事件 | 行为 |
|----------|------|
| `build.yml` Stage 3 调度 | `needs: gradle-debug` → 下载 `debug-apks` → 模拟器测试 |
| `workflow_dispatch` | 手动触发，需提供 `build-run-id` |

#### 工作流特点

- **无 checkout** — 不拉代码
- **无 Gradle** — 不编译，直接使用 build.yml 产物
- **两阶段测试** — QueueProgressCrashTest 先跑（带 crash logcat），其余后跑

#### 关键配置

| 配置项 | 值 |
|--------|-----|
| Runner | `ubuntu-latest` |
| 模拟器 Action | `reactivecircus/android-emulator-runner@v2` |
| 系统镜像 | `system-images;android-30;google_apis;x86_64` |
| KVM | 通过 udev 规则启用 |
| 测试方式 | `adb install` APK → `am instrument -e class <TestClass>` |
| AVD 快照 | 两步模式 (generate snapshot → load + test) |

#### 两阶段测试

| 阶段 | 命令 | 测试类 | 目的 |
|------|------|--------|------|
| Phase 1 | `am instrument -e class QueueProgressCrashTest` | `QueueProgressCrashTest` | 虚假后端 HTTP 进度 + Queue 崩溃检测，独立 logcat 前后采集 |
| Phase 2 | `am instrument -e package ... -e notClass QueueProgressCrashTest` | `AppLaunchInstrumentationTest` 等 | 其余所有测试，干净重装 APK |

#### 缓存策略

- AVD 数据 (`~/.android/avd/*`, `~/.android/adb*`) — key: `avd-{api-level}-{arch}-{target}-v2`

#### 产物

| Artifact | 内容 |
|----------|------|
| `logcat-crash-{api}-{target}` | QueueProgressCrashTest before/after logcat |
| `logcat-{api}-{target}` | 剩余测试 + final logcat |
| `tab-screenshots-{api}-{target}` | 5 个 Tab 截图 |
| `test-reports-{api}-{target}` | AndroidTest HTML 报告 |

#### 构建配置配合

Debug APK 需包含 `x86_64` ABI（`app/build.gradle.kts` debug block 已配置），Release APK 保持 `arm64-v8a` only 不变。

#### 测试覆盖

见 `app/src/androidTest/java/io/github/dreamandroid/local/`：

**`QueueProgressCrashTest.kt`** (5 阶段):
- Phase 1: 单任务 10%→90% 渐进进度 → COMPLETED
- Phase 2: 第一任务完成后注入第二任务，混合展示
- Phase 3: 20 步快速进度更新（Queue 页面实时观察）
- Phase 4: Tab 切换压力测试 (Models↔Queue↔Generate↔Browse)
- Phase 5: 冷启动时 Room DB 预存 PROCESSING 任务 + restoreFromDb

**`AppLaunchInstrumentationTest.kt`**:
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

### Release 发布流程

1. 更新 `VERSION_NAME` 和 `VERSION_CODE`
2. 提交并推送
3. 创建 `v{version}` tag 并推送（如 `v2026.06.16.15.00`）
4. CI 自动执行 native 编译 → APK 打包 → GitHub Release

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-26 | `apk-test.yml` 改为两阶段测试：Phase 1 `QueueProgressCrashTest` 独立运行 + crash logcat；Phase 2 其余测试 |
| 2026-06-26 | 更新工作流协作关系图，反映 cmake → gradle-debug → test → release 四阶段管道 |
| 2026-06-16 | 添加 CI Workflow 状态查询规则（GitHub REST API） |
| 2026-06-16 | 初始创建。声明无本地构建工具链，所有构建在云端 CI/CD 完成 |
