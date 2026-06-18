package io.github.dreamandroid.local.service.queue

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.IBinder
import android.util.Base64
import android.util.Log
import io.github.dreamandroid.local.DreamAndroidApplication
import io.github.dreamandroid.local.core.error.AppError
import io.github.dreamandroid.local.core.model.GenerateParams
import io.github.dreamandroid.local.data.GenerationMode
import io.github.dreamandroid.local.data.HistoryManager
import io.github.dreamandroid.local.service.QueueRepository
import io.github.dreamandroid.local.service.queue.SseStreamParser
import io.github.dreamandroid.local.ui.screens.run.GenerationParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Persistent Foreground Service that sequentially processes the generation queue.
 *
 * Replaces the per-task BackgroundGenerationService pattern.
 * Lifecycle: runs while PENDING tasks exist, stopSelf() when queue is empty.
 *
 * Architecture: Service → BackendManager → SseStreamParser → QueueRepository/HistoryManager
 * Communication: StateFlow fields observed by QueueViewModel → QueueScreen
 */
class QueueProcessingService : Service() {

    companion object {
        private const val TAG = "QueueProcService"
        const val ACTION_STOP = "io.github.dreamandroid.local.STOP_QUEUE"
    }

    // ── Dependencies (via Application) ──

    private val backendManager get() = (application as DreamAndroidApplication).backendManager
    private val historyManager by lazy { HistoryManager(applicationContext) }

    // Use process-wide singleton shared with WorkManager Worker and UI
    private val queueRepository get() = QueueRepository.getInstance(applicationContext)

    // ── State ──

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _currentProgress = MutableStateFlow(0f)
    val currentProgress: StateFlow<Float> = _currentProgress.asStateFlow()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processingJob: Job? = null

    // ── Service Lifecycle ──

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stop requested by user")
                processingJob?.cancel()
                _isProcessing.value = false
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(
            QueueNotificationHelper.NOTIFICATION_ID,
            QueueNotificationHelper.createNotification(this, "Idle", 0),
        )

        // Start processing loop if not already running
        if (!_isProcessing.value && queueRepository.hasPendingTasks()) {
            startProcessing()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        processingJob?.cancel()
        serviceScope.cancel()
        _isProcessing.value = false
        super.onDestroy()
    }

    // ── Processing Loop ──

    private fun startProcessing() {
        if (_isProcessing.value) return
        _isProcessing.value = true

        processingJob = serviceScope.launch {
            processLoop()
        }
    }

    /**
     * Main processing loop. Strategy aligned with [GenerationWorker]:
     * - Health check failure → pause-and-poll (not permanent error)
     * - Backend crash → resetTaskToPending (not permanent error)
     * - Cancellation → resetTaskToPending + re-throw
     */
    private suspend fun processLoop() {
        while (coroutineContext.isActive) {
            val task = queueRepository.getNextPending()
            if (task == null) {
                Log.d(TAG, "No more pending tasks, stopping")
                _isProcessing.value = false
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
                return
            }

            Log.d(TAG, "Processing task: ${task.id} (${task.prompt.take(50)}...)")
            queueRepository.markTaskProcessing(task.id)
            updateNotification("Processing: ${task.prompt.take(30)}...", 0)

            // 1. Health Check — pause and poll until backend online
            // Aligned with GenerationWorker.waitForBackend() strategy (§16.C.1)
            if (!waitForBackend()) {
                // Service was cancelled while waiting
                return
            }

            // 2. Build GenerateParams from task
            val params = GenerateParams(
                prompt = task.prompt,
                negativePrompt = task.negativePrompt,
                steps = task.steps,
                cfgScale = task.cfg,
                width = task.width,
                height = task.height,
                denoisingStrength = task.denoiseStrength,
                useOpenCL = task.useOpenCL,
                sampler = task.sampler,
                aspectRatio = task.aspectRatio,
                seed = task.seed,
            )

            // 3. Execute generation via BackendManager
            try {
                backendManager.generate(params).collect { event ->
                    when (event) {
                        is SseStreamParser.SseEvent.Progress -> {
                            val progress = event.step.toFloat() / event.totalSteps
                            _currentProgress.value = progress
                            queueRepository.updateTaskProgress(task.id, progress)
                            updateNotification(
                                "Generating: ${task.prompt.take(30)}...",
                                (progress * 100).toInt(),
                            )
                        }
                        is SseStreamParser.SseEvent.Complete -> {
                            val bitmap = base64ToBitmap(
                                event.imageBase64,
                                event.width,
                                event.height,
                            )
                            if (bitmap != null) {
                                val genParams = GenerationParameters(
                                    steps = task.steps,
                                    cfgScale = task.cfg,
                                    seed = event.seed,
                                    prompt = task.prompt,
                                    negativePrompt = task.negativePrompt,
                                    generationTime = System.currentTimeMillis().toString(),
                                    width = event.width,
                                    height = event.height,
                                    runOnCpu = false,
                                    denoisingStrength = task.denoiseStrength,
                                    useOpenCL = task.useOpenCL,
                                    sampler = task.sampler,
                                    mode = GenerationMode.TXT2IMG,
                                )
                                // Check save result — do NOT mark COMPLETED if save failed
                                val historyItem = historyManager.saveGeneratedImage(
                                    modelId = task.modelId,
                                    bitmap = bitmap,
                                    params = genParams,
                                    mode = GenerationMode.TXT2IMG,
                                )
                                if (historyItem != null) {
                                    // Save bitmap to queue cache file (not memory)
                                    var cachePath: String? = null
                                    try {
                                        val cacheFile = java.io.File(
                                            cacheDir,
                                            "queue_result_${task.id}.jpg",
                                        )
                                        cacheFile.outputStream().use { out ->
                                            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                                        }
                                        cachePath = cacheFile.absolutePath
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to cache result bitmap", e)
                                    }
                                    bitmap.recycle()
                                    queueRepository.markTaskComplete(task.id, cachePath, event.seed)
                                    updateNotification(
                                        "Complete: ${task.prompt.take(30)}...",
                                        100,
                                    )
                                } else {
                                    queueRepository.markTaskError(
                                        task.id,
                                        AppError.Storage("Failed to save generated image"),
                                    )
                                    bitmap.recycle()
                                }
                            } else {
                                queueRepository.markTaskError(
                                    task.id,
                                    AppError.Parse("Failed to decode result bitmap"),
                                )
                            }
                        }
                        is SseStreamParser.SseEvent.Error -> {
                            queueRepository.markTaskError(
                                task.id,
                                AppError.Backend(event.message),
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                // User cancelled via ACTION_STOP → reset for retry
                Log.d(TAG, "Service cancelled during generation — resetting task ${task.id}")
                queueRepository.resetTaskToPending(task.id)
                throw e
            } catch (e: Exception) {
                // Backend may have crashed — reset to PENDING for retry
                // Aligned with GenerationWorker strategy (§16.C.2)
                Log.e(TAG, "Generation interrupted for task ${task.id} (backend may be down)", e)
                queueRepository.resetTaskToPending(task.id)
                // Loop will re-enter waitForBackend() at the top of the next iteration
            }
        }
    }

    /**
     * Blocks until the backend HTTP server responds to /health, or the service is cancelled.
     * Aligned with [GenerationWorker.waitForBackend()] strategy (§16.C.1).
     *
     * @return true when backend is available; false if the service was cancelled
     */
    private suspend fun waitForBackend(): Boolean {
        if (backendManager.healthCheck()) return true

        Log.d(TAG, "Backend not available — pausing queue until backend comes online")
        updateNotification("Waiting for backend...", 0)

        while (coroutineContext.isActive) {
            delay(3000L)
            if (backendManager.healthCheck()) {
                Log.d(TAG, "Backend is now available — resuming queue processing")
                return true
            }
            Log.d(TAG, "Still waiting for backend...")
        }

        return false // Service cancelled
    }

    // ── Helpers ──

    private fun base64ToBitmap(base64: String, width: Int, height: Int): Bitmap? {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)
            for (i in 0 until width * height) {
                val idx = i * 3
                if (idx + 2 < bytes.size) {
                    val r = bytes[idx].toInt() and 0xFF
                    val g = bytes[idx + 1].toInt() and 0xFF
                    val b = bytes[idx + 2].toInt() and 0xFF
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode base64 bitmap", e)
            null
        }
    }

    // ── Notification ──

    private fun updateNotification(text: String, progress: Int) {
        val stopIntent = Intent(this, QueueProcessingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = QueueNotificationHelper.createNotification(
            context = this,
            title = text,
            progress = progress,
            stopPendingIntent = stopPendingIntent,
        )
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(QueueNotificationHelper.NOTIFICATION_ID, notification)
    }
}
