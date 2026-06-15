# QUEU-SYST-0001: per-task Service 开销大

| 属性 | 值 |
|------|-----|
| 优先级 | P0 |
| 分类 | Queue Processing |
| 关联 | QUEU-SYST-0002 |

## 问题描述

旧架构为每个队列任务创建/销毁 Foreground Service：

```
任务1: startForegroundService(BgGenService) → 生成 → stopSelf → wait stop
任务2: startForegroundService(BgGenService) → 生成 → stopSelf → wait stop
```

每次 Service 创建/销毁延迟 ~500ms+，任务间切换开销大。

## 涉及文件

- `service/BackgroundGenerationService.kt` — 旧实现
- `service/queue/QueueProcessingService.kt` — 新实现

## 修复方案

`QueueProcessingService` 改为持久 Foreground Service，`processLoop()` 顺序处理多个任务，无切换开销：

```kotlin
class QueueProcessingService : Service() {
    private fun processLoop() {
        while (queueRepository.hasNextPending()) {
            val task = queueRepository.nextPending()
            // process task...
        }
    }
}
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | QueueProcessingService 持久 Service 实现并验证 → ✅ Fixed |
