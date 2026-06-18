package io.github.dreamandroid.local.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Unified Room entity for queue tasks, generation history, and parameter records.
 *
 * Discrimination: [taskType] = "QUEUE" | "HISTORY" | "RECORD"
 * Queue-unique fields (batchGroupId, status, progress, etc.) are null for history/record.
 * History-unique fields (imagePath, mode, generationTime, etc.) are null for queue/record.
 * Record stores [RecordSource] in [tags] JSON bag.
 * [tags] is a JSON extensibility bag for future fields.
 */
@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["task_type"]),
        Index(value = ["task_type", "model_id", "timestamp"]),
        Index(value = ["batch_group_id"]),
        Index(value = ["timestamp"]),
        Index(value = ["status"]),
    ],
)
data class TaskEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "task_type")
    val taskType: String,

    // ── Common generation params (non-null for both types) ──
    @ColumnInfo(name = "model_id")
    val modelId: String,

    val prompt: String,

    @ColumnInfo(name = "negative_prompt")
    val negativePrompt: String,

    val steps: Int,
    val cfg: Float,
    val seed: Long?,
    val width: Int,
    val height: Int,

    @ColumnInfo(name = "denoise_strength")
    val denoiseStrength: Float?,  // nullable: TXT2IMG history sets null

    @ColumnInfo(name = "use_opencl")
    val useOpenCL: Boolean,

    @ColumnInfo(name = "scheduler")
    val sampler: String,
    val timestamp: Long,

    // ── Queue-specific fields ──
    @ColumnInfo(name = "batch_group_id")
    val batchGroupId: String? = null,

    @ColumnInfo(name = "batch_index")
    val batchIndex: Int? = null,

    @ColumnInfo(name = "effective_width")
    val effectiveWidth: Int? = null,

    @ColumnInfo(name = "effective_height")
    val effectiveHeight: Int? = null,

    @ColumnInfo(name = "aspect_ratio")
    val aspectRatio: String? = null,

    val status: String? = null,       // PENDING | PROCESSING | COMPLETED | ERROR | CANCELLED

    @ColumnInfo(name = "result_seed")
    val resultSeed: Long? = null,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,

    val progress: Float? = null,

    // ── History-specific fields ──
    @ColumnInfo(name = "image_path")
    val imagePath: String? = null,

    val mode: String? = null,         // GenerationMode.name

    @ColumnInfo(name = "upscaler_id")
    val upscalerId: String? = null,

    @ColumnInfo(name = "generation_time")
    val generationTime: String? = null,

    @ColumnInfo(name = "run_on_cpu")
    val runOnCpu: Boolean? = null,

    // ── JSON extensibility bag (future-proof) ──
    val tags: String? = null,
) {

    companion object {
        const val TYPE_QUEUE = "QUEUE"
        const val TYPE_HISTORY = "HISTORY"
        const val TYPE_RECORD = "RECORD"
    }

    val isQueueTask: Boolean get() = taskType == TYPE_QUEUE
    val isHistoryItem: Boolean get() = taskType == TYPE_HISTORY
    val isRecord: Boolean get() = taskType == TYPE_RECORD
}
