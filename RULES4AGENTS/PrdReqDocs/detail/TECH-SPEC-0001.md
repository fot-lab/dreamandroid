# TECH-SPEC-0001: 技术规范

| 属性 | 值 |
|------|-----|
| 分类 | Technical Specifications |
| 对应章节 | §12 |
| 依赖 | ARCH-TARG-0001 |

## 12.1 统一错误模型

```kotlin
sealed class AppError(message: String, open val cause: Throwable? = null) : Exception(message) {
    data class Network(override val message: String, val code: Int? = null, override val cause: Throwable? = null) : AppError(message, cause)
    data class Backend(override val message: String) : AppError(message)
    data class Parse(override val message: String, override val cause: Throwable? = null) : AppError(message, cause)
    data class Storage(override val message: String, override val cause: Throwable? = null) : AppError(message, cause)

    companion object {
        fun from(e: Throwable): AppError = when (e) {
            is AppError -> e
            is IOException -> Network(e.message ?: "IO Error", cause = e)
            is JSONException -> Parse(e.message ?: "Parse Error", cause = e)
            else -> Backend(e.message ?: "Unknown Error")
        }
    }
}
```

**错误传播约定：**

| 来源 | 转换规则 | 终端处理 |
|------|---------|---------|
| OkHttp IOException | → `AppError.Network` | ViewModel 转为 UI 状态 (Snackbar / 错误卡片) |
| HTTP 4xx/5xx + JSON message | → `AppError.Backend` | 同上 |
| SSE parse 失败 (JSONException) | → `AppError.Parse` | 同上 |
| Room / File I/O 异常 | → `AppError.Storage` | 同上 |
| Health check 失败 | 静默重试 (≤ maxRetries) | 通知用户后端不可用 |

## 12.2 OkHttpClient 配置标准

```kotlin
fun createHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(3600, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .callTimeout(0, TimeUnit.SECONDS)
    .connectionPool(ConnectionPool(5, 1, TimeUnit.MINUTES))
    .retryOnConnectionFailure(true)
    .addInterceptor(HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) Level.BODY else Level.NONE
    })
    .build()
```

**端点超时覆盖：**

| 端点 | readTimeout 覆盖 |
|------|-----------------|
| `GET /health` | 3s |
| `POST /tokenize` | 5s |
| `POST /generate` | 3600s (默认) |
| `POST /upscale` | 300s |

## 12.3 协程管理规范

```kotlin
// ✅ 正确：Service 层使用明确的 Job + 生命周期取消
class QueueProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

// ✅ 正确：ViewModel 使用 viewModelScope
class GenerateViewModel : ViewModel() {
    fun load() { viewModelScope.launch(Dispatchers.IO) { /* ... */ } }
}

// ❌ 禁止：无 Job 的 fire-and-forget scope
// ❌ 禁止：主线程调用 runBlocking
```

## 12.4 Bitmap 生命周期规范

```
规则：
1. 每个 Bitmap 只有一个生产者，一个消费者
2. QueueProcessingService 产生的 Bitmap：UI 渲染后由 ViewModel 负责 recycle()
3. Upscale 产生的 Bitmap：渲染后由 UpscaleViewModel 负责 recycle()
4. 所有中间 Bitmap（SSE progress preview）立即 recycle()
5. LruCache 最大缓存 3 个全分辨率 Bitmap (3 × 64MB = 192MB)
```

## 12.5 原生进程生命周期规范

```
进程启动流程：
  1. prepareRuntimeDir()                     [一次性，Application.onCreate]
  2. ProcessBuilder.start()                  [IO 线程]
  3. 等待 GET /health 返回 200               [轮询，最多 30s]
  4. state = Running(mode, modelId)          [发出 StateFlow]

进程停止流程（优雅关闭）：
  1. process.destroy()                       [SIGTERM]
  2. process.waitFor(5, TimeUnit.SECONDS)    [等待 5s]
  3. if 超时: process.destroyForcibly()      [SIGKILL]
  4. process.waitFor()                       [确认退出]
  5. process = null; state = Idle            [发出 StateFlow]
```

## 12.6 Preferences → DataStore 迁移规范

```
当前: 3 个 SharedPreferences 文件 (app_prefs, upscaler_prefs, default)
目标: 1 个 Preferences DataStore

迁移策略：
  1. Phase 1: 新建 PreferencesManager，平行写入旧 SP + 新 DataStore
  2. Phase 2: 读全部切换为 DataStore
  3. Phase 3: 去除旧 SP 写入
  4. Phase 4: 清理旧 SP 文件
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §12 内容，创建独立文件 |
