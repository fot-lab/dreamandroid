# QUEU-SYST-0007: 大 Bitmap 未主动回收

| 属性 | 值 |
|------|-----|
| 优先级 | P2 |
| 分类 | Queue Processing |
| 关联 | DFLW-INTG-0007 |

## 问题描述

代码中未找到 `recycle()` 调用，仅依赖 GC 回收 Bitmap。`resultBitmap` 在 `GenerationTask` 中保持引用，大图可能积累内存。

## 涉及文件

- `service/QueueRepository.kt`
- `data/QueueModels.kt`

## 修复方案 (已实施)

在 `QueueRepository` 的任务删除/清理路径中，主动调用 `bitmap.recycle()`：

- `removeTask(id)`: recycle 被删除任务的 bitmap
- `removeBatch(batchGroupId)`: recycle 批次中所有 bitmap
- `clearCompleted()`: recycle 所有 COMPLETED/ERROR/CANCELLED 任务的 bitmap

```kotlin
fun removeTask(id: String) {
    _tasks.value.firstOrNull { it.id == id }?.resultBitmap?.recycle()
    _tasks.update { it.filterNot { t -> t.id == id } }
    ...
}
```

**后续优化**: 将 `resultBitmap: Bitmap?` 改为 `resultBitmapPath: String?` (参见 DFLW-INTG-0007)

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 初始发现，方案设计完成 |
| 2026-06-16 | Phase E: QueueRepository removeTask/removeBatch/clearCompleted 添加 recycle() → ✅ Fully Fixed |
