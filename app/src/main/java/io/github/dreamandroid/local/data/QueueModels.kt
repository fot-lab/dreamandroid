package io.github.dreamandroid.local.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory

enum class TaskStatus { PENDING, PROCESSING, COMPLETED, ERROR, CANCELLED }

data class GenerationTask(
    val id: String,
    val batchGroupId: String,
    val batchIndex: Int,
    val modelId: String,
    val prompt: String,
    val negativePrompt: String,
    val steps: Int,
    val cfg: Float,
    val seed: Long?,
    val width: Int,
    val height: Int,
    val effectiveWidth: Int,
    val effectiveHeight: Int,
    val denoiseStrength: Float,
    val useOpenCL: Boolean,
    val sampler: String,
    val aspectRatio: String,
    val status: TaskStatus = TaskStatus.PENDING,
    val timestamp: Long = System.currentTimeMillis(),
    /** On-disk path to the result image (cacheDir), loaded on demand. */
    val resultBitmapPath: String? = null,
    val resultSeed: Long? = null,
    val errorMessage: String? = null,
    val progress: Float = 0f,
) {
    /** Display-friendly status label with progress percentage when processing */
    val statusLabel: String
        get() = when (status) {
            TaskStatus.PENDING -> "PENDING"
            TaskStatus.PROCESSING -> if (progress > 0f) {
                "PROCESSING: ${(progress * 100).toInt()}%"
            } else {
                "PROCESSING"
            }
            TaskStatus.COMPLETED -> "COMPLETED"
            TaskStatus.ERROR -> "ERROR"
            TaskStatus.CANCELLED -> "CANCELLED"
        }

    /**
     * Load the result bitmap from disk on demand.
     * Caller is responsible for recycling when done.
     */
    fun loadResultBitmap(): Bitmap? {
        return resultBitmapPath?.let { path ->
            try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
        }
    }
}

/** Collapsed view of a batch group */
data class BatchGroupDisplay(
    val batchGroupId: String,
    val tasks: List<GenerationTask>,
    val prompt: String,
    val count: Int,
    val isExpanded: Boolean = false,
)
