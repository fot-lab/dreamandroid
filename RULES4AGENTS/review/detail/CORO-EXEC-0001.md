# CORO-EXEC-0001: runBlocking 主线程阻塞

| 属性 | 值 |
|------|-----|
| 优先级 | P0 |
| 分类 | Coroutine & Lifecycle |
| 关联 | CORO-EXEC-0002 |

## 问题描述

`Model.kt` 中存在两处 `runBlocking(Dispatchers.IO)` 在 `init {}` 中调用 suspend 函数：

- Line 277: `generationPreferences.getBaseUrl()` — `init{}` 内 `runBlocking`
- Line 386: 另一处 suspend 调用

`runBlocking` 在调用线程上阻塞直到协程完成，在 `init {}` 中可能导致主线程阻塞（取决于初始化时机）。

## 涉及文件

- `data/Model.kt`

## 修复方案


```kotlin
// Before: 主线程阻塞
fun deleteModel() { runBlocking { historyManager.clearHistoryForModel(id) } }

// After: suspend + 协程调用
suspend fun deleteModel() { historyManager.clearHistoryForModel(id) }

// UI 层:
scope.launch { model.deleteModel() }
```

将 `init {}` 中的 `runBlocking` 改为 lazy 属性或由调用方传入 CoroutineScope。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-13 | 初始发现 |
| 2026-06-15 | 修复方案设计完成，待实施 |
