# CORO-EXEC-0003: ModelRepository.runBlocking 在 by lazy 中阻塞调用线程

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Coroutine Execution |
| 关联 | DATA-STOR-0003, BKND-PROC-0007 |

## 问题描述

`ModelRepository` 构造函数中使用了 `runBlocking(Dispatchers.IO)` 包裹在 `by lazy` 委托中，当 ViewModel 构造期间首次访问 `baseUrl` 属性时，阻塞主线程：

```
ModelsViewModel(application)  // ← 主线程
  → ModelRepository(application)
    → initializeModels()  // ← 构造函数中立即调用
      → createSDXLBaseModel()  → 访问 baseUrl 属性
        → _baseUrl by lazy { runBlocking(Dispatchers.IO) { ... } }
          // ↑ 主线程被阻塞，等待 IO 线程读取 SharedPreferences
```

**关键链路**:
```kotlin
// ModelRepository.kt:376-381
private val _baseUrl: MutableState<String> by lazy {
    val url = runBlocking(Dispatchers.IO) {   // ← 阻塞调用线程
        generationPreferences.getBaseUrl()    // ← SharedPreferences 读取
    }
    mutableStateOf(url)
}
```

```kotlin
// ModelRepository.kt:388-389
var models by mutableStateOf(initializeModels())  // ← 构造函数中立即执行
```

`initializeModels()` → `createSDXLBaseModel()` → `Model(baseUrl = baseUrl, ...)` → 触发 `_baseUrl` lazy → `runBlocking` 阻塞主线程。

`runBlocking` 设计用于桥接阻塞代码和协程，但在 `by lazy` 中使用会：
1. **阻塞首次调用线程** — 这里是主线程
2. **违背 `Dispatchers.IO` 意图** — `runBlocking` 不切换线程，`Dispatchers.IO` 参数无效
3. **增加 App 启动延迟** — SharedPreferences 读取通常很快（~1-5ms），但极端情况（闪存繁忙）可能达 50ms+

## 涉及文件

- `data/Model.kt:373-389` — `ModelRepository` 构造函数 + `_baseUrl` lazy
- `data/Model.kt:388` — `initializeModels()` 在构造函数中立即调用
- `data/Preferences.kt` — `GenerationPreferences.getBaseUrl()`

## 初始化未就绪处理检查

| 依赖 | 处理方式 | 安全性 |
|------|----------|--------|
| SharedPreferences 不存在 | `getBaseUrl()` 返回默认值 | ✅ 安全 |
| `_baseUrl` 未初始化 | `by lazy` 确保首次访问时初始化 | ✅ 安全 |
| 首次访问阻塞主线程 | `runBlocking` 阻塞调用线程 | ❌ 不应在主线程调用 |
| modelsDir 目录不存在 | `if (modelsDir.exists())` 判空 | ✅ 安全 |
| `GenerationPreferences` 构造 | 无异常风险（仅访问 SharedPreferences） | ✅ 安全 |

## 修复方案

**方案 A (推荐) — 使用 `runBlocking` + `Dispatchers.Main.immediate` 或无 Dispatcher**:
```kotlin
private val _baseUrl: MutableState<String> by lazy {
    val url = runBlocking { generationPreferences.getBaseUrl() }
    mutableStateOf(url)
}
```
`getBaseUrl()` 是对 `SharedPreferences` 的同步读取，本身不挂起，`runBlocking` 是多余的。

**方案 B — 移除 runBlocking**:
```kotlin
private val _baseUrl: MutableState<String> by lazy {
    val url = runBlocking { generationPreferences.getBaseUrl() }
    mutableStateOf(url)
}
// 如果 getBaseUrl() 是同步的:
private val baseUrl: String by lazy { generationPreferences.getBaseUrl() }
// baseUrl 属性 getter 不变
```

**方案 C — 延迟模型初始化**:
将 `initializeModels()` 从构造函数移到显式的 `init()` 或 `LaunchedEffect` 中，避免 ViewModel 构造时阻塞主线程。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-16 | 初始发现 |
