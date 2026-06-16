# BKLC-BPAS-0003: ModelRunScreen cleanup() stopService 绕过

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Backend Lifecycle Bypass |
| 关联 | BKLC-BPAS-0001, BKND-PROC-0003, BKND-PROC-0006 |

## 问题描述

`ModelRunScreen.cleanup()` 调用 `stopService(BackendService)`，绕过 `backendManager.stop()` 的 3 步优雅关闭流程。

位置：`ModelRunScreen.kt` L1324

这意味着后端进程可能被非优雅终止：
- 无 `SIGTERM → waitFor(5s) → SIGKILL` 流程
- 无僵尸进程防护 (BKND-PROC-0006)

## 涉及文件

- `ui/screens/ModelRunScreen.kt`

## 修复方案


```kotlin
// ModelRunScreen.cleanup() — 迁移后
fun cleanup() {
    backendManager.stop()  // 3 步优雅关闭
}
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 初始发现 |
| 2026-06-16 | Phase A1 完成：`cleanupModelRun()` 迁移至 `backendManager.stop()` → ✅ Fully Fixed |
