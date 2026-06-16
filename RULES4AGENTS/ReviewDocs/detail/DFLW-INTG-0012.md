# DFLW-INTG-0012: 进程被杀 PENDING 全丢

| 属性 | 值 |
|------|-----|
| 优先级 | P0 |
| 分类 | Data Flow Integrity |
| 关联 | QUEU-SYST-0005 |

## 问题描述

进程被系统杀死后，所有 PENDING/PROCESSING 任务永久丢失。`QueueRepository` 为纯内存实现，无持久化。

## 涉及文件

- `service/QueueRepository.kt`
- `data/QueueModels.kt`

## 修复方案 (已实施)

Phase C 实施：统一 `TaskEntity` Room 持久化方案 (详见 QUEU-SYST-0005)。

- `QueueRepository` 启动时从 `taskDao().getRestorableQueueTasks()` 恢复 PENDING/PROCESSING 任务
- 写操作同步 Room + StateFlow
- 数据库 v3→v4 迁移合并旧表至统一 `tasks` 表

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 方案设计完成，待 Room 集成 |
| 2026-06-16 | Phase C 完成：统一 TaskEntity 持久化，队列可恢复 → ✅ Fully Fixed |
