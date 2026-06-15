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

## 修复方案

需要 Room 持久化队列（参见 QUEU-SYST-0005）：

- 新建 `QueueDao` + `QueueTaskEntity`
- DB migration
- 启动时从 DB 恢复 PENDING/PROCESSING 任务

方案设计已完成，待后续实施。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 方案设计完成，待 Room 集成 → 📅 TODO |
