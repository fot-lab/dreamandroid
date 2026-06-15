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

## 修复方案

两阶段修复：

1. **近期**: 在任务完成后主动调用 `bitmap.recycle()`：
   ```kotlin
   // Complete → consume → recycle()
   task.resultBitmap?.let { bitmap ->
       historyManager.saveGeneratedImage(bitmap, ...)
       bitmap.recycle()
   }
   ```

2. **长期**: 将 `resultBitmap: Bitmap?` 改为 `resultBitmapPath: String?`（参见 DFLW-INTG-0007）

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 初始发现，方案设计完成 |
