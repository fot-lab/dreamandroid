# ACTION.md — Build & CI/CD 行为规范

> 版本: 1.0
> 更新日期: 2026-06-16

## 核心原则

**本仓库没有本地构建工具链。所有构建在云端 CI/CD 完成，禁止 Agent 在本地执行 Gradle 构建。**

## CI/CD 工作流 (`.github/workflows/build.yml`)

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

- **APK Artifact** (`dreamandroid-APK`): 仅在 tag push 或 manual dispatch 时上传，保留 30 天
- **Build Log** (`build-gradle.log`): 每次构建上传，保留 7 天
- **Native Build Log** (`build-native.log`): native job 执行后上传，保留 7 天

### 关键配置

| 配置项 | 值 |
|--------|-----|
| Runner | `ubuntu-24.04` |
| JDK | 17 (Temurin) |
| Android SDK | `platforms;android-36`, `build-tools;36.0.0` |
| NDK | `28.2.13676358` (仅 native job) |
| Gradle 任务 | `assembleBasicRelease` (skip ktlint/detekt) |
| APK 输出 | `app/build/outputs/apk/basic/release/*.apk` |

### Version 管理

- `VERSION_NAME`: 格式 `YYYY.MM.DD.HH.mm`（如 `2026.06.13.15.08`），在 `apk` job 中校验
- `VERSION_CODE`: 递增整数（当前 `245`）
- Release 时 APK 重命名为 `DreamHub-{version}-arm64-v8a-release.apk`

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

### Release 发布流程

1. 更新 `VERSION_NAME` 和 `VERSION_CODE`
2. 提交并推送
3. 创建 `v{version}` tag 并推送（如 `v2026.06.16.15.00`）
4. CI 自动执行 native 编译 → APK 打包 → GitHub Release

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-16 | 初始创建。声明无本地构建工具链，所有构建在云端 CI/CD 完成 |
