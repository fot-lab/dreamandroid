package io.github.dreamandroid.local.service.backend

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.core.error.AppError
import io.github.dreamandroid.local.core.functional.healthCheck
import io.github.dreamandroid.local.core.functional.retryOrFalse
import io.github.dreamandroid.local.core.model.DreamHubConstants
import io.github.dreamandroid.local.core.model.GenerateParams
import io.github.dreamandroid.local.data.Model
import io.github.dreamandroid.local.data.ModelRepository
import io.github.dreamandroid.local.service.http.HttpClientProvider
import io.github.dreamandroid.local.service.queue.SseStreamParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Unified backend process manager.
 * Guarantees only one C++ process runs on port 8081 at any time.
 * Replaces: BackendService + UpscaleBackendManager
 *
 * IMPORTANT: [context] MUST be ApplicationContext to prevent Activity leaks.
 */
class BackendManager(context: Context) {

    /** Always use ApplicationContext to prevent Activity leaks. */
    private val context: Context = context.applicationContext

    companion object {
        private const val TAG = "BackendManager"
        private const val NOTIFICATION_CHANNEL_ID = "backend_process_channel"
        private const val NOTIFICATION_ID = 6
        private const val MEMORY_MONITOR_INTERVAL_MS = 5_000L

        /**
         * Interval for verifying that a Running backend is still alive.
         * Longer than Worker's poll (3s) so it doesn't race; this is a
         * safety net — the Worker's health check is the primary detector.
         */
        private const val HEALTH_CHECK_MONITOR_INTERVAL_MS = 10_000L
    }

    enum class Mode { Diffusion, Upscaler }

    sealed class State {
        data object Idle : State()
        data class Starting(val mode: Mode, val modelId: String) : State()
        data class Running(val mode: Mode, val modelId: String) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Single shared OkHttpClient for all backend HTTP calls */
    val httpClient: OkHttpClient = HttpClientProvider.create()

    // ── Thread-safe process management ──

    @Volatile
    private var process: Process? = null

    @Volatile
    private var monitorThread: Thread? = null

    @Volatile
    private var backendPid: Int = -1

    private var memoryMonitorJob: Job? = null

    private var healthCheckJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * True while a [generate] flow is actively consuming the backend (SSE stream open).
     * Callers that would [stopProcess] (e.g. model switch, cleanup) MUST check this
     * flag first to avoid killing the C++ process mid-generation.
     */
    @Volatile
    var isGenerating: Boolean = false
        private set

    // ── Public API ──

    suspend fun startDiffusion(
        modelId: String,
        width: Int,
        height: Int,
        useOpenCL: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Refuse to kill the process while a generation is in-flight.
            // Otherwise the Worker's SSE stream breaks → PENDING flicker loop.
            if (isGenerating) {
                return@withContext Result.failure(
                    AppError.BackendBusy(
                        "Cannot restart backend — a generation is currently in progress",
                        0L,
                    ),
                )
            }

            stopProcess()
            _state.value = State.Starting(Mode.Diffusion, modelId)

            val modelRepository = ModelRepository(context)
            val model = modelRepository.models.find { it.id == modelId }
                ?: return@withContext Result.failure(AppError.Backend("Model not found: $modelId"))
            val modelsDir = File(Model.getModelsDir(context), modelId)

            val nativeDir = context.applicationInfo.nativeLibraryDir
            val executableFile = File(nativeDir, DreamHubConstants.EXECUTABLE_NAME)

            if (!executableFile.exists()) {
                val msg = "Executable not found: ${executableFile.absolutePath}"
                _state.value = State.Error(msg)
                return@withContext Result.failure(AppError.Backend(msg))
            }

            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val useImg2img = prefs.getBoolean("use_img2img", true)
            val listenOnAll = prefs.getBoolean("listen_on_all_addresses", false)

            val command = mutableListOf(
                executableFile.absolutePath,
                "--type", model.backendType,
                "--model_dir", modelsDir.absolutePath,
                "--port", DreamHubConstants.BACKEND_PORT.toString()
            )

            // QNN models require --lib_dir for QNN backend stubs
            if (!model.runOnCpu) {
                val runtimeDir = RuntimeDirPreparer.prepare(context)
                command += listOf(
                    "--lib_dir", runtimeDir.absolutePath,
                )
            }

            if (!useImg2img) {
                command += "--no_img2img"
            }

            // SD1.5 NPU non-512x512 patch
            if (model.backendType == "sd15npu" && (width != 512 || height != 512)) {
                val patchFile = if (width == height) {
                    val squarePatch = File(modelsDir, "$width.patch")
                    if (squarePatch.exists()) squarePatch
                    else File(modelsDir, "${width}x$height.patch")
                } else {
                    File(modelsDir, "${width}x$height.patch")
                }
                if (patchFile.exists()) command += listOf("--patch", patchFile.absolutePath)
            }

            if (File(modelsDir, "V_PRED").exists()) command += "--use_v_pred"
            if (model.isSdxl && prefs.getBoolean("sdxl_lowram", true)) command += "--lowram"
            if (listenOnAll) command += "--listen_all"

            val env = buildLibraryPathEnv()

            Log.d(TAG, "COMMAND: ${command.joinToString(" ")}")

            val processBuilder = ProcessBuilder(command).apply {
                directory(File(nativeDir))
                redirectErrorStream(true)
                environment().putAll(env)
            }

            process = processBuilder.start()
            backendPid = getProcessPid(process!!)
            startProcessMonitor()
            startMemoryMonitor()

            _state.value = State.Running(Mode.Diffusion, modelId)
            startHealthCheckMonitor()
            showBackendNotification("Diffusion: $modelId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start diffusion backend", e)
            _state.value = State.Error("Failed to start: ${e.message}")
            cancelBackendNotification()
            Result.failure(AppError.from(e))
        }
    }

    suspend fun startUpscaler(upscalerId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (isGenerating) {
                return@withContext Result.failure(
                    AppError.BackendBusy("Cannot restart backend — a generation is currently in progress", 0L),
                )
            }

            stopProcess()
            _state.value = State.Starting(Mode.Upscaler, upscalerId)

            val nativeDir = context.applicationInfo.nativeLibraryDir
            val executableFile = File(nativeDir, DreamHubConstants.EXECUTABLE_NAME)

            if (!executableFile.exists()) {
                val msg = "Executable not found"
                _state.value = State.Error(msg)
                return@withContext Result.failure(AppError.Backend(msg))
            }

            val listenOnAll = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .getBoolean("listen_on_all_addresses", false)

            val runtimeDir = RuntimeDirPreparer.prepare(context)
            var command = listOf(
                executableFile.absolutePath,
                "--upscaler_mode",
                "--lib_dir", runtimeDir.absolutePath,
                "--port", DreamHubConstants.BACKEND_PORT.toString()
            )
            if (listenOnAll) command = command + "--listen_all"

            val env = buildLibraryPathEnv()

            Log.d(TAG, "COMMAND: ${command.joinToString(" ")}")

            val processBuilder = ProcessBuilder(command).apply {
                directory(File(nativeDir))
                redirectErrorStream(true)
                environment().putAll(env)
            }

            process = processBuilder.start()
            backendPid = getProcessPid(process!!)
            startProcessMonitor()
            startMemoryMonitor()

            _state.value = State.Running(Mode.Upscaler, upscalerId)
            startHealthCheckMonitor()
            showBackendNotification("Upscaler: $upscalerId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start upscaler backend", e)
            _state.value = State.Error("Failed to start upscaler: ${e.message}")
            cancelBackendNotification()
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Graceful shutdown: SIGTERM → waitFor(5s) → destroyForcibly() → waitFor()
     * Process must fully exit before returning (prevents zombie processes).
     *
     * IMPORTANT: Does NOT cancel [scope] — the scope must survive for future
     * restart (memory monitor uses scope.launch). The memory monitor job is
     * cancelled separately by [stopProcess] → [stopMemoryMonitor].
     */
    suspend fun stop() {
        withContext(Dispatchers.IO) { stopProcess() }
    }

    // ── Health Check ──

    suspend fun healthCheck(): Boolean = withContext(Dispatchers.IO) {
        healthCheck(
            client = httpClient,
            baseUrl = DreamHubConstants.BASE_URL,
            connectTimeoutMs = DreamHubConstants.HEALTH_CHECK_TIMEOUT_S * 1000,
            readTimeoutMs = DreamHubConstants.HEALTH_CHECK_TIMEOUT_S * 1000,
        )
    }

    suspend fun healthCheckWithRetry(
        maxRetries: Int = DreamHubConstants.DEFAULT_HEALTH_CHECK_MAX_FAILURES,
        intervalSeconds: Long = DreamHubConstants.DEFAULT_HEALTH_CHECK_RETRY_INTERVAL_S
    ): Boolean = retryOrFalse(maxRetries, intervalSeconds * 1000) { healthCheck() }

    // ── Business Endpoints ──

    fun generate(params: GenerateParams): Flow<SseStreamParser.SseEvent> = flow {
        if (isGenerating) {
            // The C++ server rejects concurrent /generate requests (503).
            // Defensive: if somehow a second caller enters, let the server reject it
            // rather than silently hanging.
            throw AppError.BackendBusy("A generation is already in progress", 0L)
        }
        isGenerating = true
        try {
            val jsonBody = JSONObject().apply {
                put("prompt", params.prompt)
                put("negative_prompt", params.negativePrompt)
                put("steps", params.steps)
                put("samples", 1)  // batch generation: only 1 supported
                put("cfg_scale", params.cfgScale.toDouble())
                put("use_cfg", params.useCfg)
                put("width", params.width)
                put("height", params.height)
                put("denoising_strength", params.denoisingStrength.toDouble())
                put("use_opencl", params.useOpenCL)
                put("sampler", params.sampler)
                put("scheduler", params.denoiseCurve)
                put("show_diffusion_process", params.showDiffusionProcess)
                put("show_diffusion_stride", params.showDiffusionStride)
                put("aspect_ratio", params.aspectRatio)
                params.seed?.let { put("seed", it) }
                params.imageBase64?.let { put("image", it) }
                params.maskBase64?.let { put("mask", it) }
            }

            val requestBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${DreamHubConstants.BASE_URL}/v1/generate")
                .post(requestBody)
                .build()

            val response = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }

            // BKND-PROC-0008: Handle backend busy (503 Service Unavailable) —
            // the C++ server rejects concurrent /generate requests following
            // Stability AI / Ollama conventions with a standard Retry-After header.
            if (response.code == 503) {
                val retryAfterSec = response.header("Retry-After")?.toIntOrNull() ?: 3
                throw AppError.BackendBusy(
                    "Server is currently processing another request",
                    retryAfterSec * 1000L,
                )
            }

            if (!response.isSuccessful) {
                throw AppError.Backend("Generate request failed: ${response.code}")
            }

            val body = response.body ?: throw AppError.Backend("Empty response body")
            val parser = SseStreamParser(body.byteStream())
            parser.events().collect { emit(it) }
        } finally {
            isGenerating = false
        }
    }

    /**
     * Query the C++ backend for the current generation progress.
     * Returns null if the backend is not reachable, OR if the backend reports
     * "busy" but has no per-step progress (e.g. upscale in progress).
     * Otherwise returns a pair of (currentStep, totalSteps).
     *
     * Important: the C++ /v1/progress endpoint includes a "status" field
     * ("busy"/"idle") in addition to step counts.  When status is "busy"
     * but step counts are 0/0, the backend is doing a non-step-tracked
     * operation (upscale).  Returning null in that case prevents callers
     * from incorrectly assuming the backend is idle.
     */
    suspend fun queryProgress(): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${DreamHubConstants.BASE_URL}/v1/progress")
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            val status = json.optString("status", "idle")
            val current = json.optInt("current_step", 0)
            val total = json.optInt("total_steps", 0)
            // Backend reports busy but has no step progress — it's doing
            // a non-step-tracked operation (e.g. upscale).  Report as
            // unreachable so callers keep waiting.
            if (status == "busy" && current == 0 && total == 0) return@withContext null
            Pair(current, total)
        } catch (_: Exception) {
            null
        }
    }

    data class TokenizeResult(
        val count: Int,
        val maxLength: Int,
        val overflowOffset: Int,
    )

    suspend fun tokenize(prompt: String): TokenizeResult = withContext(Dispatchers.IO) {
        val jsonBody = JSONObject().apply { put("prompt", prompt) }
        val requestBody = jsonBody.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${DreamHubConstants.BASE_URL}/v1/tokenize")
            .post(requestBody)
            .build()

        val client = httpClient.newBuilder()
            .readTimeout(DreamHubConstants.TOKENIZE_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw AppError.Backend("Tokenize failed: ${response.code}")
        val body = response.body?.string() ?: throw AppError.Backend("Empty tokenize response")
        val json = JSONObject(body)
        TokenizeResult(
            count = json.optInt("count", 0),
            maxLength = json.optInt("max_length", 77),
            overflowOffset = json.optInt("overflow_offset", -1),
        )
    }

    suspend fun upscale(
        rgbBytes: ByteArray,
        width: Int,
        height: Int,
        upscalerPath: String
    ): ByteArray = withContext(Dispatchers.IO) {
        val requestBody = rgbBytes.toRequestBody("application/octet-stream".toMediaType())

        val request = Request.Builder()
            .url("${DreamHubConstants.BASE_URL}/v1/upscale")
            .header("X-Image-Width", width.toString())
            .header("X-Image-Height", height.toString())
            .header("X-Upscaler-Path", upscalerPath)
            .post(requestBody)
            .build()

        val client = httpClient.newBuilder()
            .readTimeout(DreamHubConstants.UPSCALE_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw AppError.Backend("Upscale failed: ${response.code}")
        response.body?.bytes() ?: throw AppError.Backend("Empty upscale response")
    }

    // ── Private Helpers ──

    private fun stopProcess() {
        monitorThread?.interrupt()
        stopMemoryMonitor()
        stopHealthCheckMonitor()
        process?.let { proc ->
            try {
                proc.destroy()
                if (!proc.waitFor(DreamHubConstants.PROCESS_STOP_TIMEOUT_S, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    proc.waitFor()
                }
                Log.i(TAG, "Process stopped, exit code: ${proc.exitValue()}")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping process", e)
            } finally {
                process = null
                monitorThread = null
                backendPid = -1
            }
        }
        // Reset state to Idle so UI correctly reflects that no model is loaded.
        // This is safe even when called from startDiffusion() / startUpscaler(),
        // because those methods immediately set Starting → Running afterward.
        _state.value = State.Idle
        cancelBackendNotification()
    }

    /**
     * Immediate forced kill for shutdown hooks — no waitFor().
     * Safe to call from any thread (ShutdownHook, UncaughtExceptionHandler).
     */
    fun stopProcessImmediate() {
        monitorThread?.interrupt()
        stopMemoryMonitor()
        stopHealthCheckMonitor()
        process?.let { proc ->
            try {
                proc.destroyForcibly()
                Log.i(TAG, "Process forcibly terminated (immediate)")
            } catch (e: Exception) {
                Log.e(TAG, "Error in immediate process stop", e)
            } finally {
                process = null
                monitorThread = null
                backendPid = -1
            }
        }
        _state.value = State.Idle
    }

    private fun buildLibraryPathEnv(): Map<String, String> {
        val runtimeDir = RuntimeDirPreparer.prepare(context)

        val systemLibPaths = mutableListOf(
            runtimeDir.absolutePath,
            "/system/lib64",
            "/vendor/lib64",
            "/vendor/lib64/egl",
        )

        try {
            val maliSymlink = File("/system/vendor/lib64/egl/libGLES_mali.so")
            if (maliSymlink.exists()) {
                val realPath = maliSymlink.canonicalPath
                val soc = realPath.split("/").getOrNull(realPath.split("/").size - 2)
                if (soc != null) {
                    listOf("/vendor/lib64/$soc", "/vendor/lib64/egl/$soc").forEach { path ->
                        if (path !in systemLibPaths) systemLibPaths.add(path)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve Mali paths: ${e.message}")
        }

        return mapOf(
            "LD_LIBRARY_PATH" to systemLibPaths.joinToString(":"),
            "DSP_LIBRARY_PATH" to runtimeDir.absolutePath
        )
    }

    private fun startProcessMonitor() {
        // Interrupt any previous monitor thread before starting a new one
        monitorThread?.interrupt()
        monitorThread = Thread {
            try {
                process?.let { proc ->
                    proc.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            Log.i(TAG, "Backend: $line")
                        }
                    }
                    val exitCode = proc.waitFor()
                    Log.i(TAG, "Backend process exited with code: $exitCode")
                    if (_state.value is State.Running) {
                        _state.value = State.Error("Process exited with code: $exitCode")
                    }
                    // Clean up: process is dead, release references and stop monitors
                    process = null
                    backendPid = -1
                    stopMemoryMonitor()
                    cancelBackendNotification()
                }
            } catch (e: InterruptedException) {
                Log.i(TAG, "Backend process monitor interrupted (expected during stop)")
            } catch (e: Exception) {
                Log.e(TAG, "Process monitor error", e)
            }
        }.apply {
            isDaemon = true
            name = "BackendProcessMonitor"
            start()
        }
    }

    // ── Memory Monitoring ──

    /**
     * Extract the native PID from a Java [Process] via reflection.
     * Required because Process.pid() is only available on API 26+.
     */
    private fun getProcessPid(process: Process): Int {
        return try {
            val pidField = process.javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getInt(process)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get PID via reflection", e)
            -1
        }
    }

    /** Read VmRSS and VmSize (in KB) from /proc/{pid}/status. Returns null on failure. */
    private fun readProcessMemory(pid: Int): Pair<Long, Long>? {
        if (pid <= 0) return null
        return try {
            var vmRss = 0L
            var vmSize = 0L
            File("/proc/$pid/status").forEachLine { line ->
                when {
                    line.startsWith("VmRSS:") -> vmRss = line.replace(Regex("[^0-9]"), "").toLong()
                    line.startsWith("VmSize:") -> vmSize = line.replace(Regex("[^0-9]"), "").toLong()
                }
            }
            Pair(vmRss, vmSize)
        } catch (e: Exception) {
            null
        }
    }

    private fun formatMemoryKB(kb: Long): String = when {
        kb >= 1_000_000 -> String.format("%.1f GB", kb / 1_000_000.0)
        kb >= 1_000 -> String.format("%.1f MB", kb / 1_000.0)
        else -> "${kb} KB"
    }

    private fun startMemoryMonitor() {
        stopMemoryMonitor()
        memoryMonitorJob = scope.launch {
            while (isActive && backendPid > 0) {
                delay(MEMORY_MONITOR_INTERVAL_MS)
                val (rss, _) = readProcessMemory(backendPid) ?: continue
                updateNotificationMemory(rss)
            }
        }
    }

    private fun stopMemoryMonitor() {
        memoryMonitorJob?.cancel()
        memoryMonitorJob = null
    }

    // ── Health Check Monitor ──

    /**
     * Periodically verifies that a Running backend is still reachable via HTTP.
     *
     * The process monitor thread detects native crashes via [Process.waitFor()],
     * but that can lag behind the actual crash (blocked on pipe I/O).  This monitor
     * provides a second, independent detection path using HTTP health checks, so
     * the UI state is corrected promptly even if the process monitor hasn't noticed
     * the crash yet.
     *
     * When a health check fails while [_state] is Running, the state is degraded
     * to Error and all backend resources (process reference, PID, monitors) are
     * cleaned up — matching what [stopProcess] does.
     */
    private fun startHealthCheckMonitor() {
        stopHealthCheckMonitor()
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_MONITOR_INTERVAL_MS)
                if (_state.value !is State.Running) continue
                try {
                    if (!healthCheck()) {
                        Log.w(TAG, "Health check failed — backend unresponsive, marking as Error")
                        _state.value = State.Error("Backend process unresponsive")
                        process = null
                        backendPid = -1
                        stopMemoryMonitor()
                        cancelBackendNotification()
                    }
                } catch (_: Exception) {
                    // healthCheck() itself may throw on network error
                    Log.w(TAG, "Health check threw — backend unreachable, marking as Error")
                    if (_state.value is State.Running) {
                        _state.value = State.Error("Backend process unreachable")
                        process = null
                        backendPid = -1
                        stopMemoryMonitor()
                        cancelBackendNotification()
                    }
                }
            }
        }
    }

    private fun stopHealthCheckMonitor() {
        healthCheckJob?.cancel()
        healthCheckJob = null
    }

    // ── Notification ──

    private var lastNotifiedMemoryKb: Long = -1L

    private fun showBackendNotification(title: String) {
        ensureNotificationChannel()
        lastNotifiedMemoryKb = -1L
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Backend Running")
            .setContentText(title)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationMemory(rssKb: Long) {
        ensureNotificationChannel()
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val currentState = _state.value
        val modelName = when (currentState) {
            is State.Running -> currentState.modelId
            else -> return
        }
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Backend Running")
            .setContentText("$modelName  ·  RSS ${formatMemoryKB(rssKb)}")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true) // Memory updates should not chime
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun cancelBackendNotification() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    private fun ensureNotificationChannel() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Backend Process",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "DreamHub backend process status" }
            nm.createNotificationChannel(channel)
        }
    }
}
