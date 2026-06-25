package io.github.dreamandroid.local.service.queue

/*
 * NOTE: This file is no longer in use.
 *
 * QueueProcessingService was the foreground service for queue processing. It has been
 * replaced by GenerationWorker (WorkManager). The service is registered in
 * AndroidManifest.xml with android:enabled="false" and will stopSelf() immediately
 * if ever started. Kept solely as historical reference.
 */

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.IBinder
import android.util.Log
import io.github.dreamandroid.local.DreamAndroidApplication
import io.github.dreamandroid.local.core.error.AppError
import io.github.dreamandroid.local.core.functional.rgbBytesToBitmap
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import java.util.Base64

/**
 * Persistent Foreground Service that sequentially processes the generation queue.
 *
 * **LEGACY / FALLBACK** — the primary path is [GenerationWorker] (WorkManager).
 * This service is retained as a backup implementation with the same protections
 * (per-step timeout, retry limit, retry backoff) as [GenerationWorker].
 * Currently NOT wired into [QueueController]; exists registered in manifest only.
 *
 * Replaces the per-task BackgroundGenerationService pattern.
 * Lifecycle: runs while PENDING tasks exist, stopSelf() when queue is empty.
 *
 * Architecture: Service → BackendManager → SseStreamParser → QueueRepository/HistoryManager
 * Communication: shared [QueueRepository] StateFlows observed by QueueViewModel → QueueScreen
 */
class QueueProcessingService : Service() {

    companion object {
        private const val TAG = "QueueProcService"
        const val ACTION_STOP = "io.github.dreamandroid.local.STOP_QUEUE"

        /** Interval between health-check polls while waiting for backend. */
        private const val BACKEND_POLL_INTERVAL_MS = 3000L

        /**
         * Interval for parallel progress polling via GET /v1/progress.
         * Aligned with [GenerationWorker.PROGRESS_POLL_INTERVAL_MS].
         */
        private const val PROGRESS_POLL_INTERVAL_MS = 2000L

        /**
         * Max consecutive retries for the same task before marking it ERROR.
         * Prevents the PROCESSING → PENDING flicker death-loop when the
         * backend is temporarily unavailable (e.g. model switch kills process).
         * Aligned with [GenerationWorker.MAX_TASK_RETRIES].
         */
        private const val MAX_TASK_RETRIES = 3

        /** Backoff delay between retry attempts for the same task. */
        private const val TASK_RETRY_DELAY_MS = 5000L
    }

    // ── Dependencies (via Application) ──

    private val backendManager get() = (application as DreamAndroidApplication).backendManager
    private val historyManager by lazy { HistoryManager(applicationContext) }

    // Use process-wide singleton shared with WorkManager Worker and UI
    private val queueRepository get() = QueueRepository.getInstance(applicationContext)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processingJob: Job? = null

    // ── Service Lifecycle ──

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
    }

    /**
     * NO-OP: This service is disabled in the manifest (enabled=false) and
     * intentionally never started. The primary queue processing path is
     * [GenerationWorker] (WorkManager). This onStartCommand returns
     * immediately to prevent any accidental competition or dual-processing.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.w(TAG, "QueueProcessingService is DISABLED — onStartCommand ignored. " +
            "Use GenerationWorker (WorkManager) for queue processing.")
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        processingJob?.cancel()
        serviceScope.cancel()
        stopProcessing()
        super.onDestroy()
    }

    private fun stopProcessing() {
        queueRepository.setProcessingActive(false)
    }

    // ── Processing Loop ──

    private fun startProcessing() {
        processingJob = serviceScope.launch {
            processLoop()
        }
    }

    /**
     * Main processing loop. Fully aligned with [GenerationWorker.processLoop]:
     * - Shared [QueueRepository] state (processingActive, generationTimedOut)
     * - Per-step SSE timeout watchdog
     * - Retry limit (MAX_TASK_RETRIES) + backoff (TASK_RETRY_DELAY_MS)
     * - Health check failure → pause-and-poll (not permanent error)
     * - Non-cancellation exception → retry with backoff; after 3 → ERROR
     */
    private suspend fun processLoop() {
        // Track retries per task to prevent PROCESSING → PENDING flicker death-loop
        var taskRetryCount = 0
        var lastTaskId: String? = null

        while (coroutineContext.isActive) {
            val task = queueRepository.getNextPending()
            if (task == null) {
                Log.d(TAG, "No more pending tasks, stopping")
                stopProcessing()
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
                return
            }

            // Reset retry counter when a genuinely new task is picked up
            if (task.id != lastTaskId) {
                taskRetryCount = 0
                lastTaskId = task.id
            }

            Log.d(TAG, "Processing task: ${task.id} (${task.prompt.take(50)}...)")
            queueRepository.markTaskProcessing(task.id)
            queueRepository.setProcessingActive(true)
            updateNotification("Processing: ${task.prompt.take(30)}...", 0)

            // 1. Health Check — pause and poll until backend online
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

            // 3. Execute generation via BackendManager (dual-path: SSE + polling)
            try {
                // Read user-configured per-step SSE timeout
                val prefs = applicationContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val timeoutSeconds = prefs.getInt("generation_timeout_s", 60).coerceAtLeast(10)
                val timeoutMs = timeoutSeconds * 1000L

                // Per-step timeout: restarts on each SSE message.
                var stepTimeoutJob: Job? = null
                // Capture coroutineContext before the local non-suspend function
                val ctx = coroutineContext

                fun resetStepTimeout() {
                    stepTimeoutJob?.cancel()
                    if (queueRepository.generationTimedOut.value) {
                        queueRepository.setGenerationTimedOut(false)
                    }
                    stepTimeoutJob = CoroutineScope(ctx + Job()).launch {
                        delay(timeoutMs)
                        if (isActive) {
                            Log.w(TAG, "SSE step timed out after ${timeoutSeconds}s for task ${task.id}")
                            queueRepository.setGenerationTimedOut(true)
                        }
                    }
                }

                // ── Parallel liveness poller (non-SSE side-channel) ──
                // GET /v1/progress provides backend-reported progress independently
                // of the SSE stream.  Matching the pre-migration
                // BackgroundGenerationService pattern, the poller serves purely as
                // a liveness signal: if the backend reports any step progress (> 0),
                // it is alive and we reset the per-step timeout.
                //
                // Progress percentage is authoritatively provided by the SSE stream
                // (event.step / event.totalSteps), exactly as in the old BGS.
                // The poller does NOT override SSE progress — it only ensures the
                // timeout watchdog does not fire while the backend is actively working.
                val livenessPollerJob = CoroutineScope(ctx + Job()).launch {
                    while (isActive) {
                        delay(PROGRESS_POLL_INTERVAL_MS)
                        try {
                            val p = backendManager.queryProgress()
                            if (p != null && p.second > 0 && p.first > 0) {
                                resetStepTimeout()
                            }
                        } catch (_: Exception) { }
                    }
                }

                try {
                    resetStepTimeout() // start initial timer
                    backendManager.generate(params).collect { event ->
                        if (!coroutineContext.isActive) throw CancellationException("Service cancelled")

                        when (event) {
                            is SseStreamParser.SseEvent.Progress -> {
                                // SSE progress is the authoritative source.
                                // Fall back to task.steps if backend omits totalSteps.
                                val effectiveTotalSteps =
                                    if (event.totalSteps > 0) event.totalSteps else task.steps
                                val progress = event.step.toFloat() / effectiveTotalSteps
                                resetStepTimeout()
                                queueRepository.updateTaskProgress(task.id, progress)
                                updateNotification(
                                    "Generating: ${task.prompt.take(30)}...",
                                    (progress * 100).toInt(),
                                )
                            }
                            is SseStreamParser.SseEvent.Complete -> {
                                stepTimeoutJob?.cancel()
                                queueRepository.setGenerationTimedOut(false)
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
                                    val historyItem = historyManager.saveGeneratedImage(
                                        modelId = task.modelId,
                                        bitmap = bitmap,
                                        params = genParams,
                                        mode = GenerationMode.TXT2IMG,
                                    )
                                    if (historyItem != null) {
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
                                stepTimeoutJob?.cancel()
                                queueRepository.setGenerationTimedOut(false)
                                queueRepository.markTaskError(
                                    task.id,
                                    AppError.Backend(event.message),
                                )
                            }
                        }
                    }
                } finally {
                    stepTimeoutJob?.cancel()
                    livenessPollerJob.cancel()
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Service cancelled during generation — resetting task ${task.id}")
                queueRepository.setGenerationTimedOut(false)
                queueRepository.resetTaskToPending(task.id)
                throw e
            } catch (e: Exception) {
                // Lenient handling: on any exception, simply reset and retry.
                // SseStreamParser now skips unparseable events silently, so
                // exceptions here are genuine transport errors.
                Log.e(TAG, "Generation interrupted for task ${task.id}: ${e.message}", e)
                queueRepository.setGenerationTimedOut(false)

                taskRetryCount++
                Log.e(TAG, "Generation retry $taskRetryCount/$MAX_TASK_RETRIES for task ${task.id}")

                if (taskRetryCount >= MAX_TASK_RETRIES) {
                    Log.e(TAG, "Task ${task.id} exceeded max retries ($MAX_TASK_RETRIES), marking ERROR")
                    queueRepository.markTaskError(
                        task.id,
                        AppError.Backend(
                            "Generation failed after $MAX_TASK_RETRIES attempts: ${e.message}",
                        ),
                    )
                    taskRetryCount = 0
                    lastTaskId = null
                } else {
                    queueRepository.resetTaskToPending(task.id)
                    if (coroutineContext.isActive) {
                        delay(TASK_RETRY_DELAY_MS)
                    }
                }
            }
        }
    }

    /**
     * Blocks until the backend HTTP server responds to /health, or the service is cancelled.
     * Fully aligned with [GenerationWorker.waitForBackend()].
     *
     * While waiting, [queueRepository.processingActive] is set to false so the UI
     * shows the queue as paused rather than running/failed.
     *
     * @return true when backend is available; false if the service was cancelled
     */
    private suspend fun waitForBackend(): Boolean {
        if (backendManager.healthCheck()) return true

        Log.d(TAG, "Backend not available — pausing queue until backend comes online")
        queueRepository.setProcessingActive(false)
        updateNotification("Waiting for backend...", 0)

        while (coroutineContext.isActive) {
            delay(BACKEND_POLL_INTERVAL_MS)
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
            val imageBytes = Base64.getDecoder().decode(base64)
            if (imageBytes.size < width * height * 3) {
                Log.e(TAG, "Decoded base64 too small: ${imageBytes.size} < ${width * height * 3}")
                return null
            }
            rgbBytesToBitmap(imageBytes, width, height)
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
