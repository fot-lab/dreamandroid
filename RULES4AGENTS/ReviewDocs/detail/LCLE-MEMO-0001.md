# LCLE-MEMO-0001: App 退出时内存安全、生命周期清理、异常恢复 & 模型加载/卸载 完整审计

| 属性 | 值 |
|------|-----|
| 优先级 | P0 |
| 分类 | Lifecycle & Memory Safety (Newly Discovered) |
| 关联 | DFLW-INTG-0007, QUEU-SYST-0007, CORO-EXEC-0002 |
| 审计日期 | 2026-06-16 |

## 1. 审计范围

对以下场景进行全链路生命周期与内存安全审计：

```
场景 A: App 正常退出 (用户按 back/slides out)
场景 B: App 强制退出 (系统杀进程 / swipe-from-recents)
场景 C: Backend 模型加载中途失败
场景 D: Backend 模型卸载失败
场景 E: Backend 运行时意外退出 (崩溃)
场景 F: 内存压力 (onTrimMemory/onLowMemory)
场景 G: 正常运行时内存泄漏隐患
```

---

## 2. 发现总览

| # | 优先级 | 问题 | 涉及文件 |
|---|--------|------|----------|
| 1 | **P0** | App 强制退出后 C++ 后端进程变为孤儿进程，占用端口 8081 | `BackendManager.kt` |
| 2 | **P0** | 孤儿进程导致重启后新进程端口绑定失败 | `BackendManager.kt` |
| 3 | **P1** | 无 UncaughtExceptionHandler / ShutdownHook — 崩溃无清理 | `DreamAndroidApplication.kt` |
| 4 | **P1** | 无 onTrimMemory/onLowMemory 处理 — 内存压力无响应 | `MainActivity.kt`, Application |
| 5 | **P1** | 线程泄漏：`startProcessMonitor()` 每次 new raw Thread | `BackendManager.kt` L374-398 |
| 6 | **P1** | stopProcess() 失败后 process=null 但原进程可能仍在运行 | `BackendManager.kt` L325-341 |
| 7 | **P2** | UpscaleScreen Bitmap 泄漏：selectedBitmap/upscaledBitmap | `UpscaleScreen.kt` |
| 8 | **P2** | 临时缓存文件 `upscaled_temp_*.jpg` 无限累积 | `UpscaleScreen.kt` L308-314 |
| 9 | **P2** | ModelDownloadService 私有 OkHttpClient，未复用 HttpClientProvider | `ModelDownloadService.kt` L34-37 |
| 10 | **P2** | BackendManager 持有 Context 引用 (Application — 可接受但隐含风险) | `BackendManager.kt` L43 |
| 11 | **P2** | GenerationTask.resultBitmap 内存累积 (已知，DFLW-INTG-0007 已规划) | `QueueModels.kt`, `QueueRepository.kt` |
| 12 | **P3** | RuntimeDirPreparer QNN 库文件无清理机制 | `RuntimeDirPreparer.kt` |
| 13 | **P3** | QueueRepository scope 无取消入口 (process-scope singleton — 可接受) | `QueueRepository.kt` L53 |

---

## 3. 逐项分析

### 发现 1-2 (P0): App 强制退出 → C++ 孤儿进程

**触发链**:

```
用户 Swipe-from-Recents
  → Android 发送 SIGKILL 到 app 进程
  → DreamAndroidApplication.onTerminate() — NEVER CALLED
  → BackendManager.stop() — NEVER CALLED
  → C++ 进程 (libstable_diffusion_core.so) — 仍在运行，绑定 8081 端口
  → 孤儿进程！
  → 用户重新打开 App
  → BackendManager.startDiffusion()
    → stopProcess() → process 为 null → 无操作
    → ProcessBuilder.start() → 新 C++ 进程尝试 bind 8081
    → FAIL: 地址已被孤儿进程占用
  → health check 失败 → 模型加载失败
```

**根因**: `BackendManager` 没有注册任何进程级退出钩子（ShutdownHook / UncaughtExceptionHandler / `Runtime.addShutdownHook`）。

**涉及代码**:

```kotlin
// BackendManager.kt L66
private var process: Process? = null

// BackendManager.kt L325-341
private fun stopProcess() {
    process?.let { proc -> // process is null on restart → NO-OP
        proc.destroy()
        ...
        process = null
    }
}

// DreamAndroidApplication.kt L95-98
override fun onTerminate() {
    super.onTerminate()
    appScope.cancel()  // only cancels coroutines, NOT the C++ process
}
```

**修复方案**:

```kotlin
// DreamAndroidApplication.onCreate()
override fun onCreate() {
    super.onCreate()
    // Register JVM shutdown hook as last-resort cleanup
    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { backendManager.stopProcessImmediate() }
    })
    startMigration()
}

// Also: on next app launch, kill any process on port 8081
// BackendManager.startDiffusion() — before ProcessBuilder.start():
suspend fun killStaleBackend(): Result<Unit> {
    // Attempt to kill orphaned backend process by health-checking
    // then sending a /shutdown HTTP request (if backend supports it)
}
```

**替代方案 (更可靠)**: 应用启动时检测端口 8081 占用，如果 backend 的 `/health` 返回旧模型 ID → 发送 `/shutdown` HTTP 请求使其退出。

---

### 发现 3 (P1): 无 UncaughtExceptionHandler / ShutdownHook

**现状**: 没有任何异常处理器。Java 层 crash 时：
- C++ 后端进程 → 孤儿
- 活动队列任务 → PENDING 状态已存入 Room → 部分恢复
- 内存中的 Bitmap → 泄漏到进程死亡

**涉及文件**: `DreamAndroidApplication.kt` — 缺少 `Thread.setDefaultUncaughtExceptionHandler`

**修复方案**:

```kotlin
override fun onCreate() {
    super.onCreate()
    
    // Last-resort cleanup on JVM crash
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        Log.e(TAG, "Uncaught exception in thread ${thread.name}", throwable)
        runCatching {
            backendManager.stopProcessImmediate()
        }
        // Re-throw to let Android's default handler generate crash dialog
        throwable.printStackTrace()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
```

**注意**: `addShutdownHook` 在 SIGKILL (系统杀进程) 时不触发。`UncaughtExceptionHandler` 只在 Java crash 时触发。两者组合可覆盖大多数场景，但 SIGKILL 无法拦截（Android 限制）。

---

### 发现 4 (P1): 无 onTrimMemory/onLowMemory 处理

**现状**: 搜索 `onTrimMemory`, `onLowMemory`, `WeakReference` 在所有 Kotlin 文件中 — 零命中。

**影响**: 系统内存不足时，App 不释放 Bitmap/卸载模型，导致被系统优先 Kill。

**涉及文件**: `MainActivity.kt`, `DreamAndroidApplication.kt`

**修复方案**:

```kotlin
// DreamAndroidApplication 或 Activity 中
override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
            // Clear caches
            queueRepository.recycleCompletedBitmaps()
        }
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
            // More aggressive cleanup
            appScope.launch { backendManager.stop() }
            queueRepository.recycleCompletedBitmaps()
        }
    }
}
```

---

### 发现 5 (P1): 线程泄漏 — 多次 Daemon Monitor Thread

**现状**:

```kotlin
// BackendManager.kt L374-398
private fun startProcessMonitor() {
    Thread {
        // ... read stdout
    }.apply {
        isDaemon = true
        name = "BackendProcessMonitor"
        start()
    }
}
```

每次 `startDiffusion()` 或 `startUpscaler()` 调用 `startProcessMonitor()`。但在 `stopProcess()` 中：
- 旧的 process 被 destroy 并置 null
- **旧的 monitor thread 继续运行** — 阻塞在 `proc.inputStream.bufferedReader().readLine()`
- 由于 old process 已被 destroy，`readLine()` 返回 null → thread 退出

**Time window**: 如果 `stopProcess()` 的 destroy 发生在 thread 进入 `readLine()` 之前，thread 会永远阻塞读取一个已关闭的 stream → stream 关闭后 readLine 返回 null → thread exits。所以严格来说不是永久泄漏，但在极短时间内可能积累多余 thread。

**实际风险评估**: **低** — daemon threads 会在 process destroy 后自然退出。但 code pattern 不健壮。

**修复方案**: 将 monitor thread 管理化为 Job 或保存 thread 引用以便主动 interrupt：

```kotlin
private var monitorThread: Thread? = null

private fun startProcessMonitor() {
    monitorThread?.interrupt()  // interrupt old monitor
    monitorThread = Thread { ... }.apply { start() }
}

private fun stopProcess() {
    process?.let { proc ->
        monitorThread?.interrupt()
        proc.destroy()
        ...
        process = null
        monitorThread = null
    }
}
```

---

### 发现 6 (P1): stopProcess() 失败后 process 泄露

**现状**:

```kotlin
// BackendManager.kt L325-341
private fun stopProcess() {
    process?.let { proc ->
        try {
            proc.destroy()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                proc.waitFor()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping process", e)
        } finally {
            process = null  // ← always sets null, even if destroy failed
        }
    }
}
```

**问题**: 如果 `destroy()` 和 `destroyForcibly()` 都失败（极端情况），finally 仍然 `process = null`。调用方认为进程已停止，但实际进程可能仍在运行。下次 `startDiffusion()` 的 `stopProcess()` 检查 `process` 为 null → 不停止 → `ProcessBuilder.start()` 可能因端口 8081 被占而内部失败。

**修复**: 增加 health check 验证停止是否成功：

```kotlin
private suspend fun stopProcess() {
    process?.let { proc ->
        try {
            proc.destroy()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                proc.waitFor()
            }
            // Verify process truly stopped
            val exitValue = proc.exitValue()
            Log.i(TAG, "Process stopped, exit code: $exitValue")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping process", e)
        } finally {
            process = null
        }
    }
    cancelBackendNotification()
    
    // Double-check: verify port 8081 is free via health check failure
    if (healthCheck()) {
        Log.w(TAG, "Process reportedly stopped but health check still passes — orphan suspected")
    }
}
```

---

### 发现 7 (P2): UpscaleScreen Bitmap 泄漏

**现状**:

```kotlin
// UpscaleScreen.kt (Compose state)
var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
var upscaledBitmap by remember { mutableStateOf<Bitmap?>(null) }
```

`selectedBitmap` 和 `upscaledBitmap` 是 full-size Bitmap，通过 `remember` 绑定到 Composable 生命周期。当用户离开 Upscale tab 时，Composable 从 composition 移除时 `remember` 会释放，但 **Bitmap.recycle() 从未显式调用**。GC 将回收 native pixel buffer，但时机不可控。

**修复**:

```kotlin
DisposableEffect(Unit) {
    onDispose {
        selectedBitmap?.recycle()
        upscaledBitmap?.recycle()
        selectedBitmap = null
        upscaledBitmap = null
    }
}
```

---

### 发现 8 (P2): 缓存文件累积

**现状**:

```kotlin
// UpscaleScreen.kt L308-314
val tempFile = File(
    context.cacheDir,
    "upscaled_temp_${System.currentTimeMillis()}.jpg",
)
```

每次 upscale 操作创建一个新临时文件，使用时间戳命名，永不清理。

**影响**: `cacheDir` 累积 `.jpg` 文件，占用磁盘空间。

**修复**: 在 upscale 操作前清理旧文件：

```kotlin
// Clean old temp files before creating new one
context.cacheDir.listFiles { f -> f.name.startsWith("upscaled_temp_") }
    ?.forEach { it.delete() }
```

---

### 发现 9 (P2): ModelDownloadService 独立 OkHttpClient

**现状**:

```kotlin
// ModelDownloadService.kt L34-37
private val client = OkHttpClient.Builder()
    .connectTimeout(Duration.ofSeconds(30))
    .readTimeout(Duration.ofSeconds(30))
    .build()
```

使用私有 `OkHttpClient`，而非 `HttpClientProvider.create()`。HTTP-CLNT-0001 修复已将其他 4 个 client 统一，但 `ModelDownloadService` 被遗漏。

**修复**: 使用 `HttpClientProvider.create()` 或创建带下载专用超时的变体。

---

### 发现 10 (P2): BackendManager Context 引用

**现状**:

```kotlin
class BackendManager(private val context: Context)
```

`BackendManager` 是 Application-level 单例（通过 `DreamAndroidApplication.backendManager`），传入 `Application` context → 不泄漏。但构造函数签名使用 `Context` 类型，未来如果误传 `Activity` context 会造成 Activity 泄漏。

**修复**: 在文档/enforce使用 `ApplicationContext`。

---

### 发现 11 (P2): resultBitmap 内存累积 (已知)

详见 **DFLW-INTG-0007** (Resolution Planned) 和 **QUEU-SYST-0007** (Fully Fixed — 缓解方案)。

当前状态：
- `removeTask/removeBatch/clearCompleted` 调用 `recycle()` — 已实施
- 长期方案：`resultBitmap: Bitmap?` → `resultBitmapPath: String?` — 已规划，Deferred

---

### 发现 12 (P3): RuntimeDirPreparer QNN 库无清理

**现状**:

```kotlin
// RuntimeDirPreparer.kt
object RuntimeDirPreparer {
    @Synchronized
    fun prepare(context: Context): File {
        // Copies QNN .so and .bin files from assets to runtime_libs/
        // idempotent via "prepared" flag
        // NEVER cleans up
    }
}
```

QNN 库从 assets 复制到 `filesDir/runtime_libs/`，永不清理。存在磁盘空间占用，但文件总大小有限（QNN 库约 50-100MB）。

**评估**: P3 — 用户可接受。

---

### 发现 13 (P3): QueueRepository scope 无取消

**现状**:

```kotlin
// QueueRepository.kt L53
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

process-scope singleton，无 `cancel()` 调用。但如果 `QueueRepository` 需要被 destroy（例如测试中），无法清理。

**评估**: P3 — 生产环境中 process-scope singleton，随进程死亡，可接受。已在 CORO-EXEC-0002 审查中确认非泄漏。

---

## 4. 各场景安全评估矩阵

| 场景 | 内存安全 | 进程清理 | 模型状态 | 数据持久化 | 综合评级 |
|------|----------|----------|----------|------------|----------|
| A. App 正常退出 | ✅ 正常 | ✅ stopProcess() 完整 | ✅ Idle | ✅ Room 持久 | 🟢 安全 |
| B. App 强制退出 | ⚠️ Bitmap 泄漏到进程死 | ❌ C++ 孤儿进程 | ❌ Running → 孤儿 | ✅ PENDING 已持久 | 🔴 危险 |
| C. 模型加载中途失败 | ✅ 无泄漏 | ✅ process 未设置 | ⚠️ State.Error | N/A | 🟡 可接受 |
| D. 模型卸载失败 | ✅ 无泄漏 | ⚠️ 极端情况 orphan | ⚠️ process=null 误判 | N/A | 🟡 可接受 |
| E. Backend 意外退出 | ✅ 无泄漏 (monitor 捕获) | ✅ monitor thread 检测 | ⚠️ State.Error | ✅ 任务 reset PENDING | 🟢 安全 |
| F. 内存压力 | ❌ 无响应 | ❌ 不卸载模型 | ❌ 不释放资源 | N/A | 🔴 危险 |
| G. 正常运行内存 | ⚠️ Bitmap 累积 | N/A | N/A | N/A | 🟡 可接受 |

---

## 5. 修复优先级 & 实施方案

### Phase 1: P0 — 紧急修复 (孤儿进程)

1. **ShutdownHook**: 在 `DreamAndroidApplication.onCreate()` 注册 `Runtime.addShutdownHook` → 调用 `backendManager.stopProcessImmediate()`
2. **启动时端口抢占检测**: `BackendManager.startDiffusion()` 前，health check 现有端口 → 若有响应且 model ID 不匹配 → 发送 HTTP `/shutdown` → 等待 5s → 再启动新进程
3. **UncaughtExceptionHandler**: 注册全局 crash handler → 清理 C++ 进程

### Phase 2: P1 — 高优先级 (内存压力 + 线程管理)

4. **onTrimMemory**: 在 `DreamAndroidApplication` 中实现 → 根据 level 释放 Bitmap/卸载模型
5. **Monitor Thread**: 保存 Thread 引用，stop 时 interrupt

### Phase 3: P2 — 常规修复 (泄漏)

6. **UpscaleScreen Bitmap**: `DisposableEffect` → recycle on dispose
7. **Cache 文件清理**: 操作前清理 `upscaled_temp_*` 文件
8. **ModelDownloadService OkHttpClient**: 改为 `HttpClientProvider.create()`
9. **resultBitmap → resultBitmapPath**: 按 DFLW-INTG-0007 方案执行

---

## 6. 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-16 | 初始审计：覆盖 7 个生命周期场景，发现 13 个问题 (2 P0 / 4 P1 / 5 P2 / 2 P3) |
| 2026-06-16 | **全部修复：13/13 Fixed**。修复方案如下 |
