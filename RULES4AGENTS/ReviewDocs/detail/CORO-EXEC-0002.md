# CORO-EXEC-0002: 协程 Scope 泄漏（3 处）

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Coroutine & Lifecycle |
| 关联 | CORO-EXEC-0001 |

## 问题描述

3 处协程 Scope 无 Job、无取消机制，存在泄漏风险：

| 位置 | 问题 |
|------|------|
| `UpscaleBackendManager` | `CoroutineScope(Dispatchers.IO)` 无 Job 无取消 |
| `ModelRepository.init` | init 中启动观察协程，无取消 |
| `LogCapture` | 协程未绑定生命周期 |

## 涉及文件

- `service/UpscaleBackendManager.kt`
- `data/ModelRepository.kt`
- `utils/LogCapture.kt`

## 修复方案


| 位置 | 修复 |
|------|------|
| `UpscaleBackendManager` | `CoroutineScope(SupervisorJob() + Dispatchers.IO)`，`stop()` 中 `scope.cancel()` |
| `ModelRepository.init` | 从 init 中移出，改为 `fun startObserving(scope: CoroutineScope)` 由调用方传 scope |
| `LogCapture` | 绑定 Application 生命周期，或在适当时机 cancel |

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 修复方案设计完成，尚未验证修复 |
