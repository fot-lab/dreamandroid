# QUEU-SYST-0004: busy-wait 在 UI 层

| 属性 | 值 |
|------|-----|
| 优先级 | P2 |
| 分类 | Queue Processing |
| 关联 | QUEU-SYST-0002 |

## 问题描述

旧架构在 MainActivity 中通过 busy-wait 轮询等待 Service 停止：

```kotlin
// MainActivity
while (isServiceRunning) {
    delay(100) // busy-wait poll
}
```

这浪费 UI 线程资源且脆弱。

## 涉及文件

- `MainActivity.kt` — 旧代码已移除

## 修复方案

队列处理已不在 MainActivity 中轮询。`QueueProcessingService` 在独立 Service 中运行 `processLoop()`。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 队列处理移至 QueueProcessingService，移除 UI 层轮询 → ✅ Fixed |
