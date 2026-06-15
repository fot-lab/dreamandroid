# BKND-PROC-0002: 状态机不统一

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Backend Process Management |
| 关联 | BKND-PROC-0001, BKLC-BPAS-0007 |

## 问题描述

旧架构中存在两个独立的 StateFlow 描述同一端口 8081 上的后端状态：
- `BackendService` 的状态流
- `UpscaleBackendManager._state`

两个状态机可能产生不一致窗口。

## 涉及文件

- `service/BackendService.kt`
- `service/UpscaleBackendManager.kt`
- `service/backend/BackendManager.kt`

## 修复方案

`BackendManager.State` 统一了 `Idle`/`Starting`/`Running`/`Error`，单一 `StateFlow<State>` 作为唯一状态源。

```kotlin
sealed class State {
    object Idle : State()
    data class Starting(val mode: Mode, val modelId: String) : State()
    data class Running(val mode: Mode, val modelId: String) : State()
    data class Error(val message: String) : State()
}
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-13 | 初始发现 |
| 2026-06-15 | BackendManager.State 统一状态机实现并验证 → ✅ Fixed |
