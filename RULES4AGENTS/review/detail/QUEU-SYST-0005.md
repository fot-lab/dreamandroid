# QUEU-SYST-0005: 队列无持久化

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Queue Processing |
| 关联 | DFLW-INTG-0012, DATA-STOR-0001 |

## 问题描述

`QueueRepository` 为纯内存 `MutableStateFlow`，无 Room/文件持久化。进程被杀后所有 PENDING/PROCESSING 任务永久丢失。

## 涉及文件

- `service/QueueRepository.kt`
- `data/QueueModels.kt`

## 修复方案

需要新建 Room 持久化：
- `QueueDao` + `QueueTaskEntity`
- DB migration
- `QueueRepository` 改为 Room-backed

```kotlin
@Entity(tableName = "queue_tasks")
data class QueueTaskEntity(
    @PrimaryKey val taskId: String,
    val status: TaskStatus,  // PENDING/PROCESSING/COMPLETED/ERROR
    val paramsJson: String,
    val createdAt: Long
)
```


## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-13 | 初始发现 |
| 2026-06-15 | 方案设计完成，待后续 Room 集成 |
