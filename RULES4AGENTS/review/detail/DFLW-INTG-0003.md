# DFLW-INTG-0003: flow{} 内 withContext 反模式

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Data Flow Integrity |
| 关联 | DFLW-INTG-0004, QUEU-SYST-0006 |

## 问题描述

`SseStreamParser` 旧实现在 `flow {}` builder 内使用 `withContext(Dispatchers.IO)`，这违反 Kotlin Flow 的上下文保持规则：

```kotlin
// Before (反模式):
flow {
    withContext(Dispatchers.IO) {
        // 阻塞 IO 操作
    }
}.flowOn(Dispatchers.Default) // 无效 — flow {} 内 withContext 覆盖了 flowOn
```

## 涉及文件

- `service/queue/SseStreamParser.kt`

## 修复方案

迁移为 `channelFlow {}` + `flowOn(Dispatchers.IO)`：

```kotlin
channelFlow {
    // IO 操作在当前线程
    send(SseEvent.Progress(progress))
}.flowOn(Dispatchers.IO)
```

JSON 解析在 `Dispatchers.Default` 上执行。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 迁移至 channelFlow + flowOn → ✅ Fixed |
