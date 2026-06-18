package io.github.dreamandroid.local.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/**
 * Unified data access for [TaskEntity] (queue tasks + history items + records).
 *
 * All write operations are suspend; reactive queries return [Flow].
 * [taskType] discriminator filters queue vs. history vs. record operations.
 */
@Dao
interface TaskDao {

    // ── Generic mutations ──

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<TaskEntity>)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    // ── Queue operations ──

    @Query("SELECT * FROM tasks WHERE task_type = 'QUEUE' ORDER BY timestamp ASC")
    fun observeQueueAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE task_type = 'QUEUE' ORDER BY timestamp ASC")
    suspend fun getQueueAll(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE task_type = 'QUEUE' AND status = 'PENDING' ORDER BY timestamp ASC LIMIT 1")
    suspend fun getQueueNextPending(): TaskEntity?

    @Query("SELECT COUNT(*) FROM tasks WHERE task_type = 'QUEUE' AND status = 'PENDING'")
    suspend fun countQueuePending(): Int

    @Query("UPDATE tasks SET status = :status WHERE id = :id AND task_type = 'QUEUE'")
    suspend fun updateQueueStatus(id: String, status: String)

    @Query("UPDATE tasks SET progress = :progress WHERE id = :id AND task_type = 'QUEUE'")
    suspend fun updateQueueProgress(id: String, progress: Float)

    @Query("UPDATE tasks SET status = :status, result_seed = :seed WHERE id = :id AND task_type = 'QUEUE'")
    suspend fun updateQueueComplete(id: String, status: String, seed: Long?)

    @Query("UPDATE tasks SET status = :status, error_message = :message WHERE id = :id AND task_type = 'QUEUE'")
    suspend fun updateQueueError(id: String, status: String, message: String?)

    @Query("DELETE FROM tasks WHERE id = :id AND task_type = 'QUEUE'")
    suspend fun deleteQueueById(id: String)

    @Query("DELETE FROM tasks WHERE batch_group_id = :batchGroupId AND task_type = 'QUEUE'")
    suspend fun deleteQueueByBatch(batchGroupId: String)

    @Query("DELETE FROM tasks WHERE task_type = 'QUEUE' AND (status = 'CANCELLED' OR status = 'COMPLETED' OR status = 'ERROR')")
    suspend fun clearQueueCompleted()

    @Query("DELETE FROM tasks WHERE task_type = 'QUEUE'")
    suspend fun deleteAllQueue()

    @Query("SELECT * FROM tasks WHERE task_type = 'QUEUE' AND (status = 'PENDING' OR status = 'PROCESSING')")
    suspend fun getRestorableQueueTasks(): List<TaskEntity>

    // ── History operations ──

    @Query("SELECT * FROM tasks WHERE task_type = 'HISTORY' AND id = :id")
    suspend fun getHistoryById(id: String): TaskEntity?

    @Query("DELETE FROM tasks WHERE task_type = 'HISTORY' AND model_id = :modelId")
    suspend fun deleteHistoryForModel(modelId: String)

    @Query("SELECT COUNT(*) FROM tasks WHERE task_type = 'HISTORY' AND model_id = :modelId AND timestamp = :timestamp")
    suspend fun countHistoryByKey(modelId: String, timestamp: Long): Int

    @RawQuery(observedEntities = [TaskEntity::class])
    fun query(q: SupportSQLiteQuery): Flow<List<TaskEntity>>

    @RawQuery
    suspend fun queryOnce(q: SupportSQLiteQuery): List<TaskEntity>

    @Query("SELECT DISTINCT model_id FROM tasks WHERE task_type = 'HISTORY' ORDER BY model_id")
    fun observeHistoryKnownModelIds(): Flow<List<String>>

    @Query("SELECT DISTINCT scheduler FROM tasks WHERE task_type = 'HISTORY' ORDER BY scheduler")
    fun observeHistoryKnownSamplers(): Flow<List<String>>

    @Query("SELECT DISTINCT (width || 'x' || height) FROM tasks WHERE task_type = 'HISTORY' ORDER BY width * height DESC")
    fun observeHistoryKnownSizes(): Flow<List<String>>

    // ── Record operations ──

    @Query("SELECT * FROM tasks WHERE task_type = 'RECORD' ORDER BY timestamp DESC")
    fun observeRecords(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE task_type = 'RECORD' ORDER BY timestamp DESC")
    suspend fun getAllRecords(): List<TaskEntity>

    @Query("DELETE FROM tasks WHERE id = :id AND task_type = 'RECORD'")
    suspend fun deleteRecordById(id: String)

    @Query("DELETE FROM tasks WHERE task_type = 'RECORD'")
    suspend fun deleteAllRecords()
}
