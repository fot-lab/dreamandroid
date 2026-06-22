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
import java.io.IOException
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

            // ── 3. Execute generation via BackendManager (SSE Flow) ──
            try {
                // Read user-configured per-step SSE timeout
                val prefs = applicationContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val timeoutSeconds = prefs.getInt("generation_timeout_s", 60).coerceAtLeast(10)
                val timeoutMs = timeoutSeconds * 1000L

                // Per-step timeout: restarts on each SSE message.
                // Fires only when a single step exceeds the timeout window.
                var stepTimeoutJob: Job? = null

                fun resetStepTimeout() {
                    stepTimeoutJob?.cancel()
                    // Auto-dismiss any lingering timeout warning when a new message arrives
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
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Worker cancelled during generation")
                queueRepository.setGenerationTimedOut(false)
                // Reset task to PENDING so it can be retried when the queue resumes
                queueRepository.resetTaskToPending(task.id)
                throw e
            } catch (e: Exception) {
                // ── Generation interrupted ──
                // The SSE stream may have disconnected (IOException), the backend
                // may have returned 503 (BackendBusy), or the backend process may
                // have crashed.  BEFORE counting this as a retry, check whether the
                // backend is still alive and processing the original request.
                // Blind retries on a busy backend waste retryCount and can
                // permanently ERROR a task whose generation is proceeding normally.
                Log.e(TAG, "Generation interrupted for task ${task.id}: ${e.message}", e)
                queueRepository.setGenerationTimedOut(false)

                // ── Case 1: BackendBusy (503) — backend IS processing, do NOT consume retry ──
                if (e is AppError.BackendBusy) {
                    Log.d(TAG, "Backend busy — original generation still running server-side, waiting for idle")
                    if (!isStopped) delay(e.retryAfterMs)
                    waitForBackendIdle(timeoutSeconds)
                    // Backend now idle (or wait timed out). Reset for while-loop retry.
                    queueRepository.resetTaskToPending(task.id)
                    if (!isStopped) delay(TASK_RETRY_DELAY_MS)
                    // retryCount NOT incremented — this was not a real failure
                    continue  // jump to next while-loop iteration
                }

                // ── Case 2: IOException / Network — SSE stream broke.
                // If backend is still alive, it may be finishing the original
                // generation.  Wait for it to become idle before retrying.
                if ((e is IOException || e is AppError.Network) && backendManager.healthCheck()) {
                    Log.d(TAG, "SSE disconnected but backend alive — waiting for idle before retry")
                    waitForBackendIdle(timeoutSeconds)
                    // Fall through to normal retry (backend idle or wait timed out)
                }

                // ── Case 3: Normal retry path (backend crashed, or idle-wait exhausted) ──
                taskRetryCount++
                Log.e(TAG, "Generation retry $taskRetryCount/$MAX_TASK_RETRIES for task ${task.id}")

                if (taskRetryCount >= MAX_TASK_RETRIES) {
                    // Max retries exhausted — mark as permanent error to break the loop
                    Log.e(TAG, "Task ${task.id} exceeded max retries ($MAX_TASK_RETRIES), marking ERROR")
                    queueRepository.markTaskError(
                        task.id,
                        AppError.Backend(
                            "Generation failed after $MAX_TASK_RETRIES attempts: ${e.message}",
                        ),
                    )
                    // Reset counter so the next task gets a fresh start
                    taskRetryCount = 0
                    lastTaskId = null
                } else {
                    // Reset to PENDING with backoff delay to prevent tight flicker loop
                    queueRepository.resetTaskToPending(task.id)
                    if (!isStopped) {
                        delay(TASK_RETRY_DELAY_MS)
                    }
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

    /**
     * Waits for the backend to complete any in-progress generation.
     *
     * Uses [BackendManager.queryProgress] to detect an active generation on
     * the server side.  Polls every 3 seconds until the backend reports idle
     * (progress == null or step 0/0 or step >= total), the worker is stopped,
     * or the max wait time is exceeded.
     *
     * Max wait is capped at [maxWaitSeconds] * 5 (up to 10 minutes).
     * Requires two consecutive idle polls to avoid transient 0/0 states.
     *
     * @param maxWaitSeconds per-step timeout from user prefs, used to derive max wait cap
     * @return true if backend is confirmed idle; false if worker stopped or backend crashed
     */
    private suspend fun waitForBackendIdle(maxWaitSeconds: Int): Boolean {
        val pollIntervalMs = 3000L
        // Cap waiting at 5x per-step timeout, max 10 minutes (600s)
        val maxWaitSec = (maxWaitSeconds * 5).coerceAtMost(600)
        val maxWaitMs = maxWaitSec * 1000L
        val deadline = System.currentTimeMillis() + maxWaitMs

        Log.d(TAG, "Waiting for backend idle (max ${maxWaitMs / 1000}s)...")

        var consecutiveIdle = 0
        while (!isStopped && System.currentTimeMillis() < deadline) {
            delay(pollIntervalMs)

            if (!backendManager.healthCheck()) {
                Log.d(TAG, "Backend went down while waiting for idle")
                return false
            }

            val progress = backendManager.queryProgress()
            // queryProgress() returns null when backend is busy with a
            // non-step-tracked operation (e.g. upscale) — keep waiting.
            val isIdle = progress != null && (
                (progress.first == 0 && progress.second == 0) ||
                progress.first >= progress.second
            )

            if (isIdle) {
                consecutiveIdle++
                if (consecutiveIdle >= 2) {
                    Log.d(TAG, "Backend confirmed idle — safe to retry")
                    return true
                }
            } else {
                consecutiveIdle = 0
                Log.d(TAG, "Backend still processing (step ${progress?.first}/${progress?.second})")
            }
        }

        Log.w(TAG, "Backend idle wait ended (stopped=${isStopped}, timeout=${System.currentTimeMillis() >= deadline})")
        return !isStopped // true = timeout (still alive), false = worker stopped
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
