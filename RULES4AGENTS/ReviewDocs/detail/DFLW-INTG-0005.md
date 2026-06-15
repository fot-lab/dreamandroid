# DFLW-INTG-0005: Health check 双路径策略不一致

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Data Flow Integrity |
| 关联 | DFLW-INTG-0006, UILA-COMP-0003 |

## 问题描述

双队列处理路径健康检查失败后策略相反：
- `GenerationWorker`: 暂停轮询 `waitForBackend()`
- `QueueProcessingService`: 曾直接重启（策略不一致）

## 涉及文件

- `service/queue/GenerationWorker.kt`
- `service/queue/QueueProcessingService.kt`

## 修复方案

`QueueProcessingService` 改为使用 `waitForBackend()` 暂停轮询策略，与 `GenerationWorker` 统一。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | QueueProcessingService 统一为 waitForBackend 暂停轮询 → ✅ Fixed |
