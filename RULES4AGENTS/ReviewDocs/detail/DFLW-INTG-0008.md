# DFLW-INTG-0008: RecordRepository JSON 损坏全丢

| 属性 | 值 |
|------|-----|
| 优先级 | P2 |
| 分类 | Data Flow Integrity |
| 关联 | DFLW-INTG-0009 |

## 问题描述

`RecordRepository` 读取 JSON 文件时，如果 JSON 损坏，会丢弃文件中所有记录（而非部分恢复）。

## 涉及文件

- `data/RecordRepository.kt`

## 修复方案

损坏文件备份 + 逐条部分恢复：

```kotlin
// 1. 损坏文件备份为 .corrupted.{timestamp}
corruptedFile.copyTo(backupFile)

// 2. 逐条 fromJson() 尝试恢复
val recovered = mutableListOf<Record>()
for (line in corruptedFile.readLines()) {
    try { recovered += Json.decodeFromString<Record>(line) }
    catch (e: Exception) { /* skip corrupted line */ }
}
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 备份 + 逐条恢复机制实现 → ✅ Fixed |
