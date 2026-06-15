# DFLW-INTG-0001: saveGeneratedImage 返回值被忽略

| 属性 | 值 |
|------|-----|
| 优先级 | P0 |
| 分类 | Data Flow Integrity |
| 关联 | DFLW-INTG-0002 |

## 问题描述

`saveGeneratedImage()` 返回 `HistoryItem?`（null 表示保存失败），但调用方 `GenerationWorker` 和 `QueueProcessingService` 忽略返回值，无论保存成功与否都标记任务 COMPLETED。

这导致：保存失败（磁盘满、权限拒绝等）→ 任务被错误标记 COMPLETED → 无法重试 → 生成结果永久丢失。

## 涉及文件

- `service/queue/GenerationWorker.kt`
- `service/queue/QueueProcessingService.kt`
- `data/HistoryManager.kt`

## 修复方案

调用方检查 `HistoryItem?` 返回值：

```kotlin
val historyItem = historyManager.saveGeneratedImage(...)
if (historyItem != null) {
    queueRepository.markCompleted(taskId)
} else {
    queueRepository.markError(taskId, "Save failed")
    bitmap.recycle()
}
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | GenerationWorker 和 QueueProcessingService 检查返回值 → ✅ Fixed |
