# HTTP-CLNT-0004: UI 层处理 HTTP 错误

| 属性 | 值 |
|------|-----|
| 优先级 | P3 |
| 分类 | HTTP/Network |
| 关联 | UILA-COMP-0005 |
| 状态 | Fully Fixed |

## 问题描述

UI 层直接通过 `BackendManager` 调用 HTTP，HTTP 错误在 UI 层处理（绕过 ViewModel/错误统一层）。包括：
- `GenerateScreen` 通过 `backendManager.tokenize()` 直接调用 HTTP
- `ModelsViewModel` 直接持有 `backendManager`，错误处理分散
- `ModelRunScreen` 多个 `LaunchedEffect` 直接 `backendManager.xxx()` + catch

## 涉及文件

- `ui/screens/GenerateScreen.kt`
- `ui/viewmodel/ModelsViewModel.kt`
- `ui/screens/ModelRunScreen.kt`

## 修复方案

**Phase F (BackendService):**
- 所有 UI 层 HTTP 调用统一通过 `BackendService` 代理
- 错误通过 `AppError` 密封类统一处理 (UILA-COMP-0003)
- ViewModel 通过 `BackendService` 暴露错误状态，UI 只渲染状态

## 执行结果

**Phase E5 (2026-06-16)**: GenerateScreen tokenize HTTP 调用已移入 GenerateViewModel。

**Phase F (2026-06-16)**: BackendService 创建，所有 ViewModel/Screen/Utils 通过 BackendService 代理 HTTP：
- GenerateViewModel/ModelsViewModel/UpscaleViewModel 均使用 `BackendService`
- ModelRunScreen/UpscaleScreen 均使用 `BackendService`
- AppContent 通过 `backendService.state` 观察后端状态

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 依赖 ViewModel 拆分 (UILA-COMP-0001) 解决 |
| 2026-06-16 | Phase E: HTTP 错误统一处理依赖 GenerateViewModel → Blocked on Phase D |
| 2026-06-16 | Phase E5: 随 UILA-COMP-0003 + UILA-COMP-0005 自动解决 |
| 2026-06-16 | Phase F: BackendService 中间件全面替换 → Fully Fixed |
