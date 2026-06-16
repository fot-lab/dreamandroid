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

## 修复方案 (已实施)

Phase C 实施时采用更优方案：将 Queue Task 与 History Task 统一为 `TaskEntity`，共用一张 `tasks` Room 表，通过 `task_type` 字段区分 (`QUEUE` / `HISTORY`)。

**核心变更：**
- 新建 `data/db/TaskEntity.kt` — 统一 Entity (31字段，含queue特有10字段 + history特有5字段)
- 新建 `data/db/TaskDao.kt` — 统一 DAO (含队列/历史双套查询)
- `AppDatabase` v3→v4 迁移合并旧 `queue_tasks` + `generation_history` → 新 `tasks` 表
- `QueueRepository` 改为 Room-backed：写操作同时写 Room + StateFlow，启动时从 `taskDao().getRestorableQueueTasks()` 恢复
- 删除旧 `QueueTaskEntity.kt`、`QueueDao.kt`、`HistoryEntity.kt`、`HistoryDao.kt`

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-13 | 初始发现 |
| 2026-06-15 | 方案设计完成，待后续 Room 集成 |
| 2026-06-16 | Phase C 完成：统一 TaskEntity Room 持久化 (Queue+History共享表) → ✅ Fully Fixed |
