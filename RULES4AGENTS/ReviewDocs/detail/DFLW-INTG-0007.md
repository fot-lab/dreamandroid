# DFLW-INTG-0007: resultBitmap 内存累积

| 属性 | 值 |
|------|-----|
| 优先级 | P2 |
| 分类 | Data Flow Integrity |
| 关联 | QUEU-SYST-0007, QUEU-SYST-0008 |

## 问题描述

完成的 Bitmap 驻留在 `GenerationTask.resultBitmap` 中，任务完成后不释放。大图（如 1024×1024 RGB = 4MB+）在多个 COMPLETED 任务中累积。

## 涉及文件

- `data/QueueModels.kt`
- `service/queue/GenerationWorker.kt`

## 实际修复 (Phase F 伴随 GenerationWorker 重构)

`resultBitmap: Bitmap?` 已替换为 `resultBitmapPath: String?`，并提供 `loadResultBitmap()` 按需加载：

```kotlin
// current code (QueueModels.kt)
data class GenerationTask(
    val resultBitmapPath: String? = null,  // 磁盘路径，非 Bitmap 对象
)

fun loadResultBitmap(): Bitmap? =
    resultBitmapPath?.let { BitmapFactory.decodeFile(it) }
```

GenerationWorker 中写完缓存文件后立即 `bitmap.recycle()`，只传 `cachePath` 给 `markTaskComplete()`。UI 层通过 `loadResultBitmap()` 按需解码，调用方负责回收。

**结论：架构重构 (GenerationWorker 统一队列路径) 自然消解了此问题。**

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 方案设计完成，待后续实施 |
| 2026-06-16 | Phase E: 近期修复通过 `QueueRepository.recycle()` 缓解；长期方案推迟至 Phase D+ |
| 2026-06-16 | Phase F: 随 GenerationWorker 重构自然解决。resultBitmapPath 替代 resultBitmap，标记 Fully Fixed |
