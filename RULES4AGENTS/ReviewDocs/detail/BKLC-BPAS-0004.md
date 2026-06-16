# BKLC-BPAS-0004: ModelRunScreen BackgroundGenerationService 绕过

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Backend Lifecycle Bypass |
| 关联 | BKLC-BPAS-0001, BKND-PROC-0001 |

## 问题描述

`ModelRunScreen` 批量生成时通过 `startForegroundService(batchIntent)` 启动独立的 `BackgroundGenerationService`，而非通过 `backendManager.generate()` 的 SSE Flow。

位置：`ModelRunScreen.kt` L2638

## 涉及文件

- `ui/screens/ModelRunScreen.kt`

## 修复方案


批量生成迁移到 `backendManager.generate()` SSE Flow，或通过 `QueueProcessingService` + `GenerationWorker` 路径。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 初始发现 |
| 2026-06-16 | Phase A1 完成：批量生成迁移至 `backendManager.generate()` SSE Flow + `QueueProcessingService`；旧 `BackgroundGenerationService.kt` 已删除 → ✅ Fully Fixed |
