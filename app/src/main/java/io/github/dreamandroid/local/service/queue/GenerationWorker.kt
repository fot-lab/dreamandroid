package io.github.dreamandroid.local.service.queue

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.github.dreamandroid.local.DreamAndroidApplication
import io.github.dreamandroid.local.core.error.AppError
import io.github.dreamandroid.local.core.model.GenerateParams
import io.github.dreamandroid.local.data.GenerationMode
import io.github.dreamandroid.local.ui.screens.run.GenerationParameters
import io.github.dreamandroid.local.data.HistoryManager
import io.github.dreamandroid.local.service.QueueRepository
import java.io.File

/**
 * WorkManager [CoroutineWorker] that processes the generation queue sequentially.
 *
 * Replaces the custom polling loop in MainActivity and the legacy
 * BackgroundGenerationService.  WorkManager provides:
 * - Lifecycle management (survives process death)
 * - Built-in cancellation via [WorkManager.cancelAllWorkByTag]
 * - Progress observation via [setProgress] + [getWorkInfoByIdLiveData]
 * - Backoff/retry via [setBackoffCriteria]
 * - Foreground service notification via [setForeground]
 *
 * Tag: "generation-queue" — used for cancellation and observation.
 */
class GenerationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "GenerationWorker"
        const val WORK_TAG = "generation-queue"
        const val KEY_PROGRESS = "progress"
        const val KEY_TASK_ID = "taskId"
        const val KEY_PROMPT = "prompt"

        /** Interval between health-check polls while waiting for backend. */
        private const val BACKEND_POLL_INTERVAL_MS = 3000L

        /**
         * Interval for parallel progress polling via GET /v1/progress.
         * Runs alongside the SSE stream to provide non-SSE progress updates
         * and liveness detection. Faster than BACKEND_POLL_INTERVAL_MS
         * because progress changes rapidly during UNET denoising.
         */
        private const val PROGRESS_POLL_INTERVAL_MS = 2000L

        /**
         * Max consecutive retries for the same task before marking it ERROR.
         * Prevents the PROCESSING → PENDING flicker death-loop when the
         * backend is temporarily unavailable (e.g. model switch kills process).
         */
        private const val MAX_TASK_RETRIES = 3

        /** Backoff delay between retry attempts for the same task. */
        private const val TASK_RETRY_DELAY_MS = 5000L
    }

    private val app: DreamAndroidApplication
        get() = applicationContext as DreamAndroidApplication

    private val backendManager
        get() = app.backendManager

    private val queueRepository: QueueRepository
        get() = QueueRepository.getInstance(applicationContext)

    private val historyManager by lazy { HistoryManager(applicationContext) }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Worker started")
        setForeground(createForegroundInfo("Starting...", 0))

        return try {
            processLoop()
        } catch (e: CancellationException) {
            // User-initiated stop — do not retry
            Log.d(TAG, "Worker cancelled by user")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Worker failed with exception", e)
            // Retry with backoff if we had tasks remaining
            if (queueRepository.hasPendingTasks()) Result.retry() else Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo("Processing queue...", 0)
    }

    private suspend fun processLoop(): Result {
        // Track retries per task to prevent PROCESSING → PENDING flicker death-loop
        var taskRetryCount = 0
        var lastTaskId: String? = null

        while (!isStopped) {
            val task = queueRepository.getNextPending()
            if (task == null) {
                Log.d(TAG, "No pending tasks, worker complete")
                queueRepository.setProcessingActive(false)
                return Result.success()
            }

            // Reset retry counter when a genuinely new task is picked up
            if (task.id != lastTaskId) {
                taskRetryCount = 0
                lastTaskId = task.id
            }

            // ── 1. Ensure backend is available before processing ──
            // The queue does NOT start or restart the backend.
            // Backend lifecycle is managed by the Model Screen via BackendManager.
            // If the backend is not running, pause and poll until it comes online.
            if (!waitForBackend()) {
                // Worker was cancelled while waiting
                return Result.success()
            }

            Log.d(TAG, "Processing task: ${task.id} (${task.prompt.take(50)}...)")
            queueRepository.markTaskProcessing(task.id)
            queueRepository.setProcessingActive(true)

            setProgress(workDataOf(
                KEY_PROGRESS to 0,
                KEY_TASK_ID to task.id,
                KEY_PROMPT to task.prompt.take(30),
            ))
            setForeground(createForegroundInfo("Generating: ${task.prompt.take(30)}...", 0))

            // ── 2. Build GenerateParams from task ──
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

            // ── 3. Execute generation via BackendManager (dual-path: SSE + polling) ──
            // Read user-configured per-step SSE timeout (accessible in catch blocks too)
            val prefs = applicationContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val timeoutSeconds = prefs.getInt("generation_timeout_s", 60).coerceAtLeast(10)
            val timeoutMs = timeoutSeconds * 1000L
            try {
                // ── Per-step timeout: restarts on each SSE message ──
                // Fires only when a single step exceeds the timeout window.
                var stepTimeoutJob: Job? = null

                fun resetStepTimeout() {
                    stepTimeoutJob?.cancel()
                    if (queueRepository.generationTimedOut.value) {
                        queueRepository.setGenerationTimedOut(false)
                    }
                    stepTimeoutJob = CoroutineScope(coroutineContext + Job()).launch {
                        delay(timeoutMs)
                        if (isActive) {
                            Log.w(TAG, "SSE step timed out after ${timeoutSeconds}s for task ${task.id}")
                            queueRepository.setGenerationTimedOut(true)
                        }
                    }
                }

                // ── Parallel progress poller (non-SSE side-channel) ──
                // GET /v1/progress provides backend-reported progress independently
                // of the SSE stream.  This serves two purposes:
                //   1. UI progress updates even if SSE is slow/laggy
                //   2. Liveness detection: if SSE breaks but poller saw progress,
                //      we know the backend is still working.
                val lastPolledStep = java.util.concurrent.atomic.AtomicInteger(0)

                val progressPollerJob = CoroutineScope(coroutineContext + Job()).launch {
                    while (isActive) {
                        delay(PROGRESS_POLL_INTERVAL_MS)
                        try {
                            val p = backendManager.queryProgress()
                            if (p != null && p.second > 0 && p.first > 0) {
                                val pollProgress = p.first.toFloat() / p.second.toFloat()
                                // Only update if polled progress is ahead of last known step
                                val lastStep = lastPolledStep.get()
                                if (p.first > lastStep && lastPolledStep.compareAndSet(lastStep, p.first)) {
                                    queueRepository.updateTaskProgress(task.id, pollProgress)
                                    setProgress(workDataOf(
                                        KEY_PROGRESS to (pollProgress * 100).toInt(),
                                        KEY_TASK_ID to task.id,
                                        KEY_PROMPT to task.prompt.take(30),
                                    ))
                                    setForeground(createForegroundInfo(
                                        "Generating: ${task.prompt.take(30)}...",
                                        (pollProgress * 100).toInt(),
                                    ))
                                    // Progress via polling → backend is alive, reset step timeout
                                    resetStepTimeout()
                                }
                            }
                        } catch (_: Exception) {
                            // Poll may fail transiently; keep polling
                        }
                    }
                }

                try {
                    resetStepTimeout() // start initial timer
                    backendManager.generate(params).collect { event ->
                        if (isStopped) throw CancellationException("Worker cancelled")

                        when (event) {
                            is SseStreamParser.SseEvent.Progress -> {
                                resetStepTimeout()
                                val progress = event.step.toFloat() / event.totalSteps
                                queueRepository.updateTaskProgress(task.id, progress)
                                setProgress(workDataOf(
                                    KEY_PROGRESS to (progress * 100).toInt(),
                                    KEY_TASK_ID to task.id,
                                    KEY_PROMPT to task.prompt.take(30),
                                ))
                                setForeground(createForegroundInfo(
                                    "Generating: ${task.prompt.take(30)}...",
                                    (progress * 100).toInt(),
                                ))
                            }

                            is SseStreamParser.SseEvent.Complete -> {
                                stepTimeoutJob?.cancel()
                                // Clear timeout state on successful completion
                                queueRepository.setGenerationTimedOut(false)
                                val bitmap = base64ToBitmap(
                                    event.imageBase64,
                                    event.width,
                                    event.height,
                                )
                                if (bitmap != null) {
                                    // Save to history via HistoryManager
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
                                        // HistoryManager already saved a copy to history storage
                                        var cachePath: String? = null
                                        try {
                                            val cacheFile = File(
                                                applicationContext.cacheDir,
                                                "queue_result_${task.id}.jpg",
                                            )
                                            cacheFile.outputStream().use { out ->
                                                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                                            }
                                            cachePath = cacheFile.absolutePath
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Failed to cache result bitmap", e)
                                        }
                                        // Recycle in-memory bitmap immediately (stored on disk)
                                        bitmap.recycle()

                                        queueRepository.markTaskComplete(task.id, cachePath, event.seed)
                                        setProgress(workDataOf(
                                            KEY_PROGRESS to 100,
                                            KEY_TASK_ID to task.id,
                                            KEY_PROMPT to task.prompt.take(30),
                                        ))
                                        setForeground(createForegroundInfo(
                                            "Complete: ${task.prompt.take(30)}...",
                                            100,
                                        ))
                                    } else {
                                        queueRepository.markTaskError(
                                            task.id,
                                            AppError.Storage("Failed to save generated image to history"),
                                        )
                                        // Recycle bitmap since save failed and it won't be displayed
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
                                // Clear timeout state on backend error
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
                    progressPollerJob.cancel()
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Worker cancelled during generation")
                queueRepository.setGenerationTimedOut(false)
                // Reset task to PENDING so it can be retried when the queue resumes
                queueRepository.resetTaskToPending(task.id)
                throw e
            } catch (e: Exception) {
                // ── Generation interrupted ──
                // Lenient handling matching the pre-migration GenerationWorker:
                // on any exception, simply reset and retry with a max-retry guard.
                // The SseStreamParser now silently skips unparseable events, so
                // an exception here means a genuine transport error (SSE disconnect,
                // backend crash, etc.) rather than a parsing glitch.
                Log.e(TAG, "Generation interrupted for task ${task.id}: ${e.message}", e)
                queueRepository.setGenerationTimedOut(false)

                // ── BackendBusy (503): backend IS processing, do NOT consume retry ──
                if (e is AppError.BackendBusy) {
                    Log.d(TAG, "Backend busy — waiting then retrying (no retry consumed)")
                    queueRepository.resetTaskToPending(task.id)
                    if (!isStopped) delay(TASK_RETRY_DELAY_MS)
                    continue  // jump to next while-loop iteration
                }

                // ── All other errors: retry with count guard ──
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
                    if (!isStopped) delay(TASK_RETRY_DELAY_MS)
                }
            }
        }

        return Result.success()
    }

    /**
     * Blocks until the backend HTTP server responds to /health, or the worker is cancelled.
     *
     * The queue does NOT start/restart the backend — that is the Model Screen's
     * responsibility.  While waiting, [processingActive] is set to false so the
     * UI shows the queue as paused rather than failed.
     *
     * @return true when backend is available; false if the worker was cancelled
     */
    private suspend fun waitForBackend(): Boolean {
        // Fast path: backend is already running
        if (backendManager.healthCheck()) return true

        Log.d(TAG, "Backend not available — pausing queue until backend comes online")
        queueRepository.setProcessingActive(false)
        setForeground(createForegroundInfo("Waiting for backend...", 0))
        setProgress(workDataOf(
            KEY_PROGRESS to 0,
            KEY_TASK_ID to "waiting",
            KEY_PROMPT to "Backend unavailable",
        ))

        while (!isStopped) {
            delay(BACKEND_POLL_INTERVAL_MS)
            if (backendManager.healthCheck()) {
                Log.d(TAG, "Backend is now available — resuming queue processing")
                setForeground(createForegroundInfo("Backend online — resuming...", 0))
                return true
            }
            Log.d(TAG, "Still waiting for backend...")
        }

        return false // Worker cancelled
    }

    // ── Helpers ──

    private fun base64ToBitmap(base64: String, width: Int, height: Int): android.graphics.Bitmap? {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bitmap = android.graphics.Bitmap.createBitmap(
                width, height, android.graphics.Bitmap.Config.ARGB_8888,
            )
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

    private fun createForegroundInfo(title: String, progress: Int): ForegroundInfo {
        return QueueNotificationHelper.createForegroundInfo(
            context = applicationContext,
            title = title,
            progress = progress,
        )
    }
}
