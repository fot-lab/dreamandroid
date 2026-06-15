# DFLW-INTG-0006: 生成异常双路径策略不一致

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Data Flow Integrity |
| 关联 | DFLW-INTG-0005, UILA-COMP-0003 |

## 问题描述

双队列处理路径生成异常后策略相反：
- `GenerationWorker`: 重试策略 `resetTaskToPending()`
- `QueueProcessingService`: 曾直接标记错误

## 涉及文件

- `service/queue/GenerationWorker.kt`
- `service/queue/QueueProcessingService.kt`

## 修复方案

`QueueProcessingService` 异常处理改为 `resetTaskToPending()` 重试策略，与 `GenerationWorker` 统一。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | QueueProcessingService 统一为重试策略 → ✅ Fixed |
