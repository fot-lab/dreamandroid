# UILA-COMP-0005: UI 层直接 HTTP

| 属性 | 值 |
|------|-----|
| 优先级 | P2 |
| 分类 | UI Layer |
| 关联 | HTTP-CLNT-0004, UILA-COMP-0001 |
| 状态 | Fully Fixed |

## 问题描述

UI 层 (Screen + ViewModel) 直接持有 `BackendManager` 引用并调用 HTTP 方法:
- `GenerateScreen` 直接调用 `backendManager.tokenize()`
- `ModelsViewModel` 直接持有 `backendManager` 并调用 `startDiffusion()` / `stop()` / `startUpscaler()`
- `UpscaleViewModel` 直接持有 `backendManager`
- `ModelRunScreen` 直接调用 `backendManager.healthCheck()` / `tokenize()` / `startDiffusion()` / `stop()`
- `UpscaleScreen` 直接持有 `backendManager` 观察状态
- `AppContent.kt` 直接访问 `modelsViewModel.backendManager.state`
- `ImageUtils.performUpscale()` 直接调用 `app.backendManager.upscale()`

UI 层直接进行网络调用违反分层原则：
- 无错误处理统一
- 无重试/超时策略
- 难以测试

## 涉及文件

- `ui/viewmodel/GenerateViewModel.kt`
- `ui/viewmodel/ModelsViewModel.kt`
- `ui/viewmodel/UpscaleViewModel.kt`
- `ui/screens/ModelRunScreen.kt`
- `ui/screens/UpscaleScreen.kt`
- `ui/orchestrator/AppContent.kt`
- `ui/screens/run/ModelRunGeneration.kt`
- `ui/frontend/GenerateSection.kt`
- `utils/ImageUtils.kt`

## 修复方案

分两步执行：

**Phase E5 (Screen level):** GenerateScreen 不再直接持有 BackendManager:
- tokenize HTTP 调用移入 GenerateViewModel
- UI 通过 `onTokenizePrompt` / `onTokenizeNegativePrompt` 回调委托给 ViewModel

**Phase F (ViewModel level + BackendService):** 创建 `BackendService` 作为 HTTP 中间件：
- 所有 ViewModel/Screen/Utils 通过 `BackendService` 代理 HTTP，禁止直接引用 `BackendManager`
- `BackendService` 由 `DreamAndroidApplication` 管理: `app.backendService`
- 架构: `UI → BackendService (middleware) → BackendManager (implementation) → OkHttp`

## 执行结果

**Phase E5 (2026-06-16)**: GenerateScreen 不再直接持有 BackendManager 进行 HTTP 调用。

**Phase F (2026-06-16)**: BackendService 创建并全面替换:
- 新建 `service/backend/BackendService.kt` — 薄代理，封装所有 BackendManager HTTP 方法
- `GenerateViewModel`: `backendManager` → `backendService`
- `ModelsViewModel`: `backendManager` → `backendService`
- `UpscaleViewModel`: `backendManager` → `backendService`
- `AppContent.kt`: `modelsViewModel.backendManager.state` → `backendService.state`
- `ModelRunScreen.kt`: 所有 `backendManager.xxx()` → `backendService.xxx()`
- `ModelRunGeneration.kt`: `cleanupModelRun` 参数 `BackendManager` → `BackendService`
- `UpscaleScreen.kt`: `backendManager` → `backendService`
- `ImageUtils.kt`: `app.backendManager.upscale()` → `app.backendService.upscale()`

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-13 | 初始发现 |
| 2026-06-15 | 依赖 ViewModel 拆分 (UILA-COMP-0001) 解决 |
| 2026-06-16 | Phase E: UI 层 HTTP 调用依赖 GenerateViewModel/UpscaleViewModel → Blocked on Phase D |
| 2026-06-16 | Phase E5: GenerateScreen tokenize 移入 GenerateViewModel |
| 2026-06-16 | Phase F: BackendService 创建 + 全部 ViewModel/Screen/Utils 迁移 → Fully Fixed |
