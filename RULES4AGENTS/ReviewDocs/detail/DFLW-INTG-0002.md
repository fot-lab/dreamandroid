# DFLW-INTG-0002: HistoryManager 文件-DB 写入不一致

| 属性 | 值 |
|------|-----|
| 优先级 | P2 |
| 分类 | Data Flow Integrity |
| 关联 | DFLW-INTG-0001 |

## 问题描述

旧 `HistoryManager.saveGeneratedImage()` 先写文件后写 Room，如果文件写入成功但 DB 写入失败，产生孤儿文件（文件存在但 DB 无记录 → 不可见）。

## 涉及文件

- `data/HistoryManager.kt`

## 修复方案

改为 DB-first 写入顺序：

```kotlin
// 1. 先写 Room
val historyItem = historyDao.insert(...)
try {
    // 2. 再写文件
    saveBitmapToFile(bitmap, historyItem.filePath)
} catch (e: Exception) {
    // 文件失败 → 回滚 Room
    historyDao.deleteById(historyItem.id)
    throw e
}
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | DB-first 写入顺序 + 回滚机制 → ✅ Fixed |
