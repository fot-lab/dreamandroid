package io.github.dreamandroid.local.service.queue

import android.content.Context
import android.graphics.Bitmap
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
import io.github.dreamandroid.local.core.functional.rgbBytesToBitmap
import io.github.dreamandroid.local.core.model.GenerateParams
import io.github.dreamandroid.local.data.GenerationMode
import io.github.dreamandroid.local.ui.screens.run.GenerationParameters
import io.github.dreamandroid.local.data.HistoryManager
import io.github.dreamandroid.local.service.QueueRepository
import java.io.File
import java.util.Base64

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
                val livenessPollerJob = CoroutineScope(coroutineContext + Job()).launch {
                    while (isActive) {
                        delay(PROGRESS_POLL_INTERVAL_MS)
                        try {
                            val p = backendManager.queryProgress()
                            // Backend is reporting step progress → it is alive
                            if (p != null && p.second > 0 && p.first > 0) {
                                resetStepTimeout()
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
                                // SSE progress is the authoritative source (matching
                                // old BackgroundGenerationService).  Fall back to
                                // task.steps if the backend omits totalSteps (lenient).
                                val effectiveTotalSteps =
                                    if (event.totalSteps > 0) event.totalSteps else task.steps
                                val progress = event.step.toFloat() / effectiveTotalSteps
                                resetStepTimeout()
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
                                // ── Diagnostic: verify Bitmap pixels (gated by debug_model pref) ──
                                val debugModel = prefs.getBoolean("debug_model", false)
                                if (debugModel && bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
                                    val sampleW = bitmap.width
                                    val sampleH = bitmap.height
                                    val pixTL = bitmap.getPixel(0, 0)
                                    val pixCC = bitmap.getPixel(sampleW / 2, sampleH / 2)
                                    val pixBR = bitmap.getPixel(sampleW - 1, sampleH - 1)
                                    Log.d(TAG, "[DIAG] Bitmap top-left ARGB=${
                                        pixTL.toUInt().toString(16)} R=${(pixTL shr 16) and 0xFF} G=${(pixTL shr 8) and 0xFF} B=${pixTL and 0xFF}")
                                    Log.d(TAG, "[DIAG] Bitmap center ARGB=${
                                        pixCC.toUInt().toString(16)} R=${(pixCC shr 16) and 0xFF} G=${(pixCC shr 8) and 0xFF} B=${pixCC and 0xFF}")
                                    Log.d(TAG, "[DIAG] Bitmap bottom-right ARGB=${
                                        pixBR.toUInt().toString(16)} R=${(pixBR shr 16) and 0xFF} G=${(pixBR shr 8) and 0xFF} B=${pixBR and 0xFF}")
                                }
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
                                    // Save to history (best-effort, consistent with old QueueProcessingService
                                    // which never blocked task completion on history save failure)
                                    val historyItem = historyManager.saveGeneratedImage(
                                        modelId = task.modelId,
                                        bitmap = bitmap,
                                        params = genParams,
                                        mode = GenerationMode.TXT2IMG,
                                    )
                                    if (historyItem == null) {
                                        Log.w(TAG, "Failed to save to history, continuing with cache only")
                                    }

                                    // Save bitmap to queue cache file (not memory)
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
                                        Log.e(TAG, "Failed to cache result bitmap", e)
                                    }
                                    // Recycle in-memory bitmap immediately (stored on disk)
                                    bitmap.recycle()

                                    if (cachePath != null) {
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
                                            AppError.Storage("Failed to cache generated image to disk"),
                                        )
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
                    livenessPollerJob.cancel()
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

    private fun base64ToBitmap(base64: String, width: Int, height: Int): Bitmap? {
        return try {
            val imageBytes = Base64.getDecoder().decode(base64)
            val expected = width * height * 3
            // ── Diagnostic: verify decoded raw bytes match C++ output (gated by debug_model pref) ──
            val debugModel = applicationContext
                .getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .getBoolean("debug_model", false)
            if (debugModel) {
                Log.d(TAG, "[DIAG] base64 decoded: ${imageBytes.size} bytes " +
                        "(expect $expected for ${width}x${height}x3)")
                if (imageBytes.size >= 9) {
                    fun samplePixel(y: Int, x: Int, label: String) {
                        val off = (y * width + x) * 3
                        if (off + 2 < imageBytes.size) {
                            val r = imageBytes[off].toInt() and 0xFF
                            val g = imageBytes[off + 1].toInt() and 0xFF
                            val b = imageBytes[off + 2].toInt() and 0xFF
                            Log.d(TAG, "[DIAG] decoded $label ($x,$y): R=$r G=$g B=$b")
                        }
                    }
                    samplePixel(0, 0, "top-left")
                    samplePixel(height / 2, width / 2, "center")
                    samplePixel(height - 1, width - 1, "bottom-right")
                }
            }
            if (imageBytes.size < expected) {
                Log.w(TAG, "Decoded base64 smaller than expected: ${imageBytes.size} < $expected, continuing with partial data")
            }
            rgbBytesToBitmap(imageBytes, width, height)
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
