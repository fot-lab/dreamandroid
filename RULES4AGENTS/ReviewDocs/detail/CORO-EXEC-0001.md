# CORO-EXEC-0001: runBlocking 主线程阻塞

| 属性 | 值 |
|------|-----|
| 优先级 | P0 |
| 分类 | Coroutine & Lifecycle |
| 关联 | CORO-EXEC-0002 |

## 问题描述

`Model.kt` 中存在两处 `runBlocking(Dispatchers.IO)` 用于 lazy 属性初始化 `_baseUrl`；`AppContentState.kt` 中存在一处 `runBlocking` 用于加载模型参数。

| 位置 | 模式 | 风险评估 |
|------|------|----------|
| `UpscalerRepository._baseUrl` (L267) | `by lazy { runBlocking(Dispatchers.IO) { ... } }` | 低：lazy 一次性初始化，DataStore `.first()` 极快 |
| `ModelRepository._baseUrl` (L374) | `by lazy { runBlocking(Dispatchers.IO) { ... } }` | 低：同上，同步属性必需 |
| `AppContentState.loadModelPrefs()` (L96) | `runBlocking(Dispatchers.IO)` 每次模型切换调用 | 高：可阻塞主线程，且调用方已在协程中 |

## 涉及文件

- `data/Model.kt` (UpscalerRepository L267, ModelRepository L374)
- `ui/orchestrator/state/AppContentState.kt`

## 修复方案

### Site 1 & 2 (Model.kt lazy 属性)

`UpscalerRepository._baseUrl` 和 `ModelRepository._baseUrl` 使用 `by lazy { runBlocking(Dispatchers.IO) { ... } }` 初始化同步属性。这是一种正确的模式：lazy 确保仅运行一次，`Dispatchers.IO` 确保 IO 工作在线程池执行。`GenerationPreferences.getBaseUrl()` 通过 DataStore `.first()` 获取值，操作极快（~10ms），首次访问时的阻塞是低风险的。

**结论**: 保持现状。改为真正的 async 需要大幅重构属性访问链（`_baseUrl` 在构造函数链中被同步使用）。

### Site 3 (AppContentState.loadModelPrefs) — 已修复

`loadModelPrefs()` 从 `AppContent.kt` 的 `LaunchedEffect` 中调用，调用方已在协程中。修复：将方法签名改为 `suspend fun`，移除 `runBlocking` 包裹。

```kotlin
// Before
fun loadModelPrefs(modelId: String, prefs: GenerationPreferences) {
    val p = runBlocking(Dispatchers.IO) { prefs.getPreferences(modelId).first() }
    ...
}

// After
suspend fun loadModelPrefs(modelId: String, prefs: GenerationPreferences) {
    val p = prefs.getPreferences(modelId).first()
    ...
}
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-13 | 初始发现 |
| 2026-06-15 | 修复方案设计完成，待实施 |
| 2026-06-16 | Phase B1 完成：`AppContentState.loadModelPrefs()` → `suspend fun`，移除主线程阻塞；Model.kt lazy 属性评估为低风险保持现状 → ✅ Fully Fixed |
