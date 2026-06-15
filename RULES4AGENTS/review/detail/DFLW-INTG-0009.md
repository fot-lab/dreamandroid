# DFLW-INTG-0009: RecordRepository 并发写入不安全

| 属性 | 值 |
|------|-----|
| 优先级 | P3 |
| 分类 | Data Flow Integrity |
| 关联 | DFLW-INTG-0008 |

## 问题描述

`RecordRepository` 写入 JSON 文件时不保护并发：多协程同时写入 → 数据交错损坏。

## 涉及文件

- `data/RecordRepository.kt`

## 修复方案

添加 `Mutex` + 原子写入：

```kotlin
private val writeMutex = Mutex()

suspend fun save(record: Record) {
    writeMutex.withLock {
        // 原子写入：临时文件 → rename
        val tempFile = File(filePath + ".tmp")
        tempFile.writeText(json)
        tempFile.renameTo(originalFile)
    }
}
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | Mutex + 原子写入实现 → ✅ Fixed |
