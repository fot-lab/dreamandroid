package io.github.dreamandroid.local.data.db.download

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: DownloadTaskEntity)

    @Query("UPDATE download_tasks SET status = :status, downloaded_bytes = :downloadedBytes, total_bytes = :totalBytes, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateProgress(
        id: String,
        status: String,
        downloadedBytes: Long,
        totalBytes: Long,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE download_tasks SET status = :status, error_message = :errorMessage, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStatus(
        id: String,
        status: String,
        errorMessage: String? = null,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE download_tasks SET local_path = :localPath, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateLocalPath(id: String, localPath: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM download_tasks WHERE status = :status")
    suspend fun deleteByStatus(status: String)

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    suspend fun getById(id: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks ORDER BY created_at DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE status = :status ORDER BY created_at ASC")
    suspend fun getByStatus(status: String): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE model_id = :modelId AND status != :excludeStatus ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestByModelId(modelId: String, excludeStatus: String = DownloadTaskEntity.STATUS_CANCELLED): DownloadTaskEntity?

    @Query("SELECT COUNT(*) FROM download_tasks WHERE status = :status")
    suspend fun countByStatus(status: String): Int
}
