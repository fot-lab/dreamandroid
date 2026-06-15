# DFLW-INTG-0011: QueueController.stop() 竞态

| 属性 | 值 |
|------|-----|
| 优先级 | P2 |
| 分类 | Data Flow Integrity |
| 关联 | DFLW-INTG-0010 |

## 问题描述

`QueueController.stop()` 存在竞态条件：cancel worker → 立即 cancelAllPending，但 worker 可能仍在执行中，导致状态不一致。

## 涉及文件

- `service/queue/QueueController.kt`

## 修复方案

改为 `suspend fun stop()` 顺序等待：

```kotlin
suspend fun stop() {
    // 1. cancel worker
    workManager.cancelAllWorkByTag(TAG)
    // 2. wait for CANCELLED (max 5s)
    withTimeout(5000) { waitForCancelled() }
    // 3. then cancel all pending
    queueRepository.cancelAllPending()
}
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | suspend stop() 顺序等待 → ✅ Fixed |
