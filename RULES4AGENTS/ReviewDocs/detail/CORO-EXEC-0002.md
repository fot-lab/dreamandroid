# CORO-EXEC-0002: 协程 Scope 泄漏（3 处）

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Coroutine & Lifecycle |
| 关联 | CORO-EXEC-0001 |

## 问题描述

原有 3 处协程 Scope 无 Job、无取消机制，存在泄漏风险：

| 位置 | 原问题 | Phase B 状态 |
|------|--------|-------------|
| `UpscaleBackendManager` | `CoroutineScope(Dispatchers.IO)` 无 Job 无取消 | ✅ Phase A2 已删除文件 → Auto-resolved |
| `ModelRepository.init` | init 中启动观察协程，无取消 | ✅ 当前代码无 init 块/协程 → Already resolved |
| `LogCapture` | 协程未绑定生命周期 | ✅ 已使用 `SupervisorJob` + `captureJob?.cancel()` + `captureScope?.cancel()` → Already resolved |

## 涉及文件

- ~~`service/UpscaleBackendManager.kt`~~ (Phase A2 已删除)
- ~~`data/ModelRepository.kt`~~ (已无泄漏代码)
- `utils/LogCapture.kt` (已正确管理)

## 额外发现: ModelDownloadService

`ModelDownloadService.serviceScope` 使用 `CoroutineScope(Dispatchers.IO + Job())` 并在 `onDestroy()` 中调用 `serviceScope.cancel()` — 正确管理，无泄漏。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 初始发现，修复方案设计完成 |
| 2026-06-16 | Phase B2 完成：UpscaleBackendManager (A2 删除) + ModelRepository (已无泄漏) + LogCapture (已正确管理) → 3/3 全部已解决 → ✅ Fully Fixed |
