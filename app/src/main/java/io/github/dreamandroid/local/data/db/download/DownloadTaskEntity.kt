package io.github.dreamandroid.local.data.db.download

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Download task entity for the independent download database.
 *
 * Tracks model download lifecycle: pending → downloading → completed / failed / cancelled.
 * Supports resume via [downloadedBytes] (HTTP Range header).
 */
@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey
    val id: String,                          // UUID

    @ColumnInfo(name = "model_id")
    val modelId: String,                     // e.g. "anythingv5"

    @ColumnInfo(name = "model_name")
    val modelName: String,                   // e.g. "Anything V5.0"

    @ColumnInfo(name = "model_type")
    val modelType: String,                   // "sd", "sdxl", "upscaler"

    @ColumnInfo(name = "file_url")
    val fileUrl: String,                     // Full download URL

    @ColumnInfo(name = "local_path")
    val localPath: String = "",              // Local temp file path during download

    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long = -1,              // Total file size (-1 = unknown)

    @ColumnInfo(name = "downloaded_bytes")
    val downloadedBytes: Long = 0,          // Downloaded bytes (for resume)

    @ColumnInfo(name = "status")
    val status: String = STATUS_PENDING,     // One of STATUS_* constants

    @ColumnInfo(name = "is_zip")
    val isZip: Boolean = false,

    @ColumnInfo(name = "is_npu")
    val isNpu: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_DOWNLOADING = "DOWNLOADING"
        const val STATUS_EXTRACTING = "EXTRACTING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"
    }
}
