# DFLW-INTG-0007: resultBitmap 内存累积

| 属性 | 值 |
|------|-----|
| 优先级 | P2 |
| 分类 | Data Flow Integrity |
| 关联 | QUEU-SYST-0007 |

## 问题描述

完成的 Bitmap 驻留在 `GenerationTask.resultBitmap` 中，任务完成后不释放。大图（如 1024×1024 RGB = 4MB+）在多个 COMPLETED 任务中累积。

## 涉及文件

- `data/QueueModels.kt`
- `service/QueueRepository.kt`

## 修复方案

将 `resultBitmap: Bitmap?` 改为 `resultBitmapPath: String?`：

```kotlin
// Before
data class GenerationTask(
    val resultBitmap: Bitmap? = null  // 内存驻留
)

// After
data class GenerationTask(
    val resultBitmapPath: String? = null  // 文件路径
)
```

UI 层从文件路径加载缩略图，按需解码。方案设计已完成，涉及 QueueScreen UI 层配合改动，待后续实施。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 方案设计完成，待后续实施 → 📅 TODO |
| 2026-06-16 | Phase E: 近期修复通过 `QueueRepository.recycle()` 缓解 (QUEU-SYST-0007)；长期方案 (resultBitmapPath) 推迟至 Phase D+ |
