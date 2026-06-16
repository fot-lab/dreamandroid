# MODU-IFCE-0001: 核心模块接口标准

| 属性 | 值 |
|------|-----|
| 分类 | Module Interface Standards |
| 对应章节 | §11 |
| 依赖 | ARCH-TARG-0001 |

## 11.1 BackendManager — 后端进程管理器

```kotlin
class BackendManager(private val context: Context) {
    enum class Mode { Diffusion, Upscaler }

    sealed class State {
        object Idle : State()
        data class Starting(val mode: Mode, val modelId: String) : State()
        data class Running(val mode: Mode, val modelId: String) : State()
        data class Error(val message: String) : State()
    }

    val state: StateFlow<State>
    val httpClient: OkHttpClient

    // 生命周期管理
    suspend fun startDiffusion(modelId: String, width: Int, height: Int, useOpenCL: Boolean): Result<Unit>
    suspend fun startUpscaler(upscalerId: String): Result<Unit>
    suspend fun stop()

    // 健康检查
    suspend fun healthCheck(): Boolean
    suspend fun healthCheckWithRetry(maxRetries: Int = 4, intervalSeconds: Long = 20): Boolean

    // 业务端点
    fun generate(params: GenerateParams): Flow<SseEvent>
    suspend fun tokenize(prompt: String): TokenizeResult
    suspend fun upscale(rgbBytes: ByteArray, width: Int, height: Int, upscalerPath: String): ByteArray
}
```

**接口约束：**
- `state` 必须在 IO 线程更新，Main 线程收集
- `startXxx()` 返回值 `Result<Unit>` 统一错误处理，不抛异常
- `stop()` 必须等待进程真正退出后才返回（防止僵尸进程）
- `httpClient` 全局唯一，在 `DreamAndroidApplication.onCreate()` 中初始化
- **唯一进程管理者**: 仅 BackendManager 有权调用 `ProcessBuilder.start()` / `Process.destroy()`

## 11.1.1 BackendService — HTTP 中间件（UI 层边界）

```kotlin
class BackendService(private val backendManager: BackendManager) {
    val state: StateFlow<BackendManager.State>

    suspend fun startDiffusion(modelId: String, width: Int, height: Int, useOpenCL: Boolean): Result<Unit>
    suspend fun startUpscaler(upscalerId: String): Result<Unit>
    suspend fun stop()
    suspend fun healthCheck(): Boolean
    suspend fun healthCheckWithRetry(maxRetries: Int = 4, intervalSeconds: Long = 20): Boolean
    fun generate(params: GenerateParams): Flow<BackendManager.SseEvent>
    suspend fun tokenize(prompt: String): BackendManager.TokenizeResult
    suspend fun upscale(rgbBytes: ByteArray, width: Int, height: Int, upscalerPath: String): ByteArray
}
```

**接口约束：**
- UI 层 (Screen/ViewModel) **只允许**引用 `BackendService`，禁止直接引用 `BackendManager`
- `BackendService` 当前为薄代理，未来可在此层统一添加 logging/metrics/caching/retry policies
- 实例通过 `DreamAndroidApplication.backendService` 获取

## 11.2 QueueProcessingService — 队列处理服务

```kotlin
class QueueProcessingService : Service() {
    private val backendManager: BackendManager
    private val queueRepository: QueueRepository
    private val historyManager: HistoryManager

    val isProcessing: StateFlow<Boolean>
    val currentProgress: StateFlow<Float>

    private suspend fun processLoop() {
        while (true) {
            val task = queueRepository.getNextPending() ?: break
            queueRepository.markProcessing(task.id)

            // 1. 健康检查
            if (!backendManager.healthCheckWithRetry()) {
                queueRepository.markError(task.id, AppError.Backend("Health check failed"))
                continue
            }

            // 2. 发起生成请求
            try {
                backendManager.generate(task.toParams()).collect { event ->
                    when (event) {
                        is SseEvent.Progress -> queueRepository.updateProgress(task.id, event.step.toFloat() / event.total)
                        is SseEvent.Complete -> { /* save + markComplete */ }
                        is SseEvent.Error -> queueRepository.markError(task.id, AppError.Backend(event.message))
                    }
                }
            } catch (e: Exception) {
                queueRepository.markError(task.id, AppError.from(e))
            }
        }
        stopSelf()
    }
}
```

## 11.3 SseStreamParser — SSE 流解析器

```kotlin
class SseStreamParser(private val inputStream: InputStream) {
    sealed class SseEvent {
        data class Progress(val step: Int, val totalSteps: Int, val imageBase64: String) : SseEvent()
        data class Complete(val imageBase64: String, val seed: Long, val width: Int, val height: Int) : SseEvent()
        data class Error(val message: String) : SseEvent()
    }

    fun events(): Flow<SseEvent> = flow {
        val reader = BufferedReader(InputStreamReader(inputStream))
        reader.use {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (l.startsWith("data: ")) {
                    val json = l.removePrefix("data: ")
                    if (json == "[DONE]") break
                    emit(parseEvent(json))
                }
            }
        }
    }
}
```

## 11.4 数据层接口

```kotlin
interface QueueRepository {
    val tasks: StateFlow<List<GenerationTask>>
    val batchGroups: StateFlow<List<BatchGroupDisplay>>
    suspend fun addBatch(tasks: List<GenerationTask>)
    suspend fun getNextPending(): GenerationTask?
    suspend fun markProcessing(taskId: String)
    suspend fun markComplete(taskId: String, bitmap: Bitmap)
    suspend fun markError(taskId: String, error: AppError)
    suspend fun updateProgress(taskId: String, progress: Float)
    suspend fun removeTask(taskId: String)
    suspend fun cancelAllPending()
}

interface HistoryManager {
    fun getHistory(modelId: String? = null): Flow<List<HistoryRecord>>
    suspend fun save(record: HistoryRecord)
    suspend fun delete(recordId: Long)
    suspend fun deleteMultiple(ids: List<Long>)
    suspend fun clearForModel(modelId: String)
}

interface ModelRepository {
    fun observeModels(): Flow<List<ModelEntity>>
    suspend fun deleteModel(modelId: String)
    suspend fun importModel(source: Uri): ModelEntity
    suspend fun renameModel(modelId: String, newName: String)
}

interface PreferencesManager {
    val prompt: Flow<String>
    val negativePrompt: Flow<String>
    val batchCount: Flow<Int>
    suspend fun setPrompt(value: String)
}
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §11 内容，创建独立文件 |
| 2026-06-16 | §11.1 BackendManager 从 interface 修正为 class (实际实现)；新增 §11.1.1 BackendService (HTTP 中间件) 接口规范；BackendManager 添加"唯一进程管理者"约束 |
