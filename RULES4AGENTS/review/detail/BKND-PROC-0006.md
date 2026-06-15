# BKND-PROC-0006: 僵尸进程风险

| 属性 | 值 |
|------|-----|
| 优先级 | P0 |
| 分类 | Backend Process Management |
| 关联 | BKND-PROC-0001, BKLC-BPAS-0003, BKLC-BPAS-0006 |

## 问题描述

旧架构中进程退出仅调用 `destroy()` 无 `waitFor()`，可能导致僵尸进程残留：

```kotlin
// Before (BackendService.stopBackend):
process.destroy()
// 缺少 waitFor() → 僵尸进程风险
```

## 涉及文件

- `service/BackendService.kt` — 旧代码，待删除
- `service/backend/BackendManager.kt` — 已修复

## 修复方案

`BackendManager.stopProcess()` 已实现完整 4 步关闭流程：

```kotlin
fun stopProcess() {
    process.destroy()           // 1. SIGTERM
    process.waitFor(5, SECONDS) // 2. 等 5s
    if (process.isAlive) {
        process.destroyForcibly() // 3. SIGKILL
        process.waitFor()         // 4. 等进程退出
    }
}
```

注意：旧 `BackendService.stopBackend()` 仍缺少第二个 `waitFor()`，但 `BackendService.kt` 为待删除代码。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-13 | 初始发现 |
| 2026-06-15 | BackendManager.stopProcess() 实现 4 步关闭流程 → ✅ Fixed |
