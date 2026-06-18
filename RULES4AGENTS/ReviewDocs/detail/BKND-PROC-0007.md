# BKND-PROC-0007: ViewModel 构造函数触发 BackendManager 重量级初始化链

| 属性 | 值 |
|------|-----|
| 优先级 | P0 |
| 分类 | Backend Process Management |
| 关联 | DATA-STOR-0003, CORO-EXEC-0003, UILA-COMP-0001 |

## 问题描述

`ModelsViewModel` 和 `GenerateViewModel` 构造函数通过 `app.backendService` 触发 `BackendManager` 的 `by lazy` 初始化链，在主线程执行以下重量级操作：

```
ModelsViewModel(application)  // ← 主线程
  → app.backendService        // ← by lazy 首次触发
    → BackendService(backendManager)  // ← backendManager by lazy 首次触发
      → BackendManager(context)
        ├── HttpClientProvider.create()      // OkHttpClient 构建
        ├── CoroutineScope(SupervisorJob() + IO)  // 协程作用域创建
        └── RuntimeDirPreparer.prepare()     // ← appScope.launch 异步预准备
                                             //    但从 assets 复制 QNN libs (IO)
```

关键链路：
1. `app.backendService` 自身是 `by lazy` → 线程安全一次初始化
2. 但它引用 `backendManager`，后者也是 `by lazy` → 级联触发
3. `BackendManager` 构造函数创建 `OkHttpClient`（轻量）和 `CoroutineScope`（轻量）
4. `RuntimeDirPreparer.prepare()` 在 `appScope.launch` 中异步执行，**不阻塞主线程**
5. **但是** `appScope.launch {}` 的块内部调用了 `context.assets.list("qnnlibs")` 和文件复制，这些 IO 操作在 `Dispatchers.IO` 上执行，实际不阻塞主线程

**主要风险**: `BackendManager` 构造函数虽然本身同步操作较轻，但：
- `ModelsViewModel` 和 `GenerateViewModel` 都在 `AppContent()` 首次组装时被创建
- 两个 ViewModel 同时触发同一个 `by lazy` 的 `backendService`，线程安全但后触发的 ViewModel 会等待
- 如果 `HttpClientProvider.create()` 抛出异常（DNS 解析、SSL 证书问题等），则 `BackendService` 初始化失败，所有依赖它的 ViewModel 都会崩溃

## 涉及文件

- `DreamAndroidApplication.kt:37-55` — `backendManager` 和 `backendService` 的 `by lazy`
- `ui/viewmodel/ModelsViewModel.kt:39` — `val backendService: BackendService = app.backendService`
- `ui/viewmodel/GenerateViewModel.kt:36` — `private val backendService: BackendService = app.backendService`
- `service/backend/BackendManager.kt:46-80` — BackendManager 构造函数
- `service/backend/RuntimeDirPreparer.kt:15-81` — QNN 库复制

## 初始化未就绪处理检查

| 依赖 | 处理方式 | 安全性 |
|------|----------|--------|
| `BackendManager` 未初始化 | 首次访问 `backendService` 时 `by lazy` 自动创建 | ✅ 安全 |
| `RuntimeDirPreparer.prepare()` 失败 | `appScope.launch` 内 try-catch → 下次 prepare 重试 | ✅ 安全 |
| `backendManager` 构造失败 | `by lazy` 无 try-catch → 异常穿透到调用方 | ❌ 致命 |
| 两个 ViewModel 竞争触发 | `by lazy` 默认 `synchronized` | ✅ 安全 |
| QNN 库 assets 缺失 | `assets.list("qnnlibs")` 返回 null → forEach 跳过 | ✅ 安全 |
| 可执行文件不存在 | 在 `startDiffusion/startUpscaler` 调用时才检查 | ✅ 延迟检查 |
| `HttpClientProvider.create()` 失败 | 无 try-catch → 异常穿透 | ❌ 致命 |

## 修复方案

**方案 A (推荐) — Application 层面预初始化**:
在 `DreamAndroidApplication.onCreate()` 中提前初始化 `backendService`（现在是惰性），在启动阶段显式处理异常：
```kotlin
override fun onCreate() {
    super.onCreate()
    appScope.launch {
        try { backendService } catch (e: Exception) { /* log + degrade gracefully */ }
    }
}
```

**方案 B — ViewModel 延迟获取**:
将 ViewModel 中的 `val backendService = app.backendService` 改为 `viewModelScope.launch` 中 try-catch 获取。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-16 | 初始发现 |
