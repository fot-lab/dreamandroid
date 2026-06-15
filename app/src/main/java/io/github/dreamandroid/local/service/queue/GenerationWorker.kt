package io.github.dreamandroid.local.service.queue

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import io.github.dreamandroid.local.DreamAndroidApplication
import io.github.dreamandroid.local.core.error.AppError
import io.github.dreamandroid.local.core.model.GenerateParams
import io.github.dreamandroid.local.data.GenerationMode
import io.github.dreamandroid.local.data.GenerationParameters
import io.github.dreamandroid.local.data.HistoryManager
import io.github.dreamandroid.local.service.QueueRepository

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
        while (isActive) {
            val task = queueRepository.getNextPending()
            if (task == null) {
                Log.d(TAG, "No pending tasks, worker complete")
                queueRepository.setProcessingActive(false)
                return Result.success()
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
                cfg = task.cfg,
                width = task.width,
                height = task.height,
                denoiseStrength = task.denoiseStrength,
                useOpenCL = task.useOpenCL,
                scheduler = task.scheduler,
                aspectRatio = task.aspectRatio,
                seed = task.seed,
            )

            // ── 3. Execute generation via BackendManager (SSE Flow) ──
            try {
                backendManager.generate(params).collect { event ->
                    if (!isActive) throw CancellationException("Worker cancelled")

                    when (event) {
                        is SseStreamParser.SseEvent.Progress -> {
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
                            val bitmap = base64ToBitmap(
                                event.imageBase64,
                                event.width,
                                event.height,
                            )
                            if (bitmap != null) {
                                // Save to history via HistoryManager
                                val genParams = GenerationParameters(
                                    steps = task.steps,
                                    cfg = task.cfg,
                                    seed = event.seed,
                                    prompt = task.prompt,
                                    negativePrompt = task.negativePrompt,
                                    generationTime = System.currentTimeMillis().toString(),
                                    width = event.width,
                                    height = event.height,
                                    runOnCpu = false,
                                    denoiseStrength = task.denoiseStrength,
                                    useOpenCL = task.useOpenCL,
                                    scheduler = task.scheduler,
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
                                    queueRepository.markTaskComplete(task.id, bitmap, event.seed)
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
                            queueRepository.markTaskError(
                                task.id,
                                AppError.Backend(event.message),
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Worker cancelled during generation")
                // Reset task to PENDING so it can be retried when the queue resumes
                queueRepository.resetTaskToPending(task.id)
                throw e
            } catch (e: Exception) {
                // Backend may have crashed mid-generation — reset to PENDING for retry.
                // The queue does NOT mark this as a permanent error because the
                // task itself is valid; only the infrastructure is temporarily unavailable.
                Log.e(TAG, "Generation interrupted for task ${task.id} (backend may be down)", e)
                queueRepository.resetTaskToPending(task.id)
                // Loop will re-enter waitForBackend() at the top of the next iteration
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

        while (isActive) {
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
