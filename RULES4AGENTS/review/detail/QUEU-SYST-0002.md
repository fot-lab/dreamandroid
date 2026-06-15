# QUEU-SYST-0002: 队列循环依赖 UI 生命周期

| 属性 | 值 |
|------|-----|
| 优先级 | P0 |
| 分类 | Queue Processing |
| 关联 | QUEU-SYST-0001, UILA-COMP-0001 |

## 问题描述

旧架构中队列处理循环在 MainActivity 的 LaunchedEffect 中：

```kotlin
// MainActivity
LaunchedEffect {
    // Health Check → 重启 BackendService → 启 BgGenService
    // 等 generationState.first { Complete|Error }
    // 保存 HistoryManager → 等 isServiceRunning → false
    // 下一轮...
}
```

队列处理依赖 Activity 生命周期：Activity 销毁 → 队列处理中断，PENDING 任务丢失。

## 涉及文件

- `MainActivity.kt`
- `service/queue/QueueProcessingService.kt`
- `service/queue/GenerationWorker.kt`

## 修复方案

队列处理已移至独立组件：
- `QueueProcessingService` (Foreground Service) — 持久后台处理
- `GenerationWorker` (WorkManager) — 系统管理的后台任务

不再依赖 Activity 生命周期。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 迁移至 QueueProcessingService + GenerationWorker → ✅ Fixed |
