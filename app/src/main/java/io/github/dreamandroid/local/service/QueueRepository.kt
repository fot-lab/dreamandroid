package io.github.dreamandroid.local.service

import android.content.Context
import android.graphics.Bitmap
import io.github.dreamandroid.local.core.error.AppError
import io.github.dreamandroid.local.data.BatchGroupDisplay
import io.github.dreamandroid.local.data.GenerationTask
import io.github.dreamandroid.local.data.TaskStatus
import io.github.dreamandroid.local.data.db.AppDatabase
import io.github.dreamandroid.local.data.db.TaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Queue state management with Room persistence, shared between UI and Worker.
 *
 * All writes are persisted to Room asynchronously; the in-memory [StateFlow] is
 * updated immediately for responsive UI. On process restart, pending and
 * processing tasks are restored from the database.
 *
 * Accessed via [QueueRepository.getInstance] — process-wide singleton.
 */
class QueueRepository private constructor(private val db: AppDatabase) {

    companion object {
        @Volatile
        private var INSTANCE: QueueRepository? = null

        /** Returns the process-wide singleton, restoring persisted tasks on first call. */
        fun getInstance(context: Context): QueueRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: QueueRepository(
                    AppDatabase.get(context.applicationContext),
                ).also { repo ->
                    repo.restoreFromDb()
                    INSTANCE = repo
                }
            }
        }
    }

    private val _tasks = MutableStateFlow<List<GenerationTask>>(emptyList())
    val tasks: StateFlow<List<GenerationTask>> = _tasks

    private val _processingActive = MutableStateFlow(false)
    val processingActive: StateFlow<Boolean> = _processingActive

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Init: restore from Room ──

    private fun restoreFromDb() {
        scope.launch {
            val entities = db.taskDao().getRestorableQueueTasks()
            _tasks.value = entities.map { it.toDomain() }
        }
    }

    // ── Processing flag ──

    fun setProcessingActive(active: Boolean) {
        _processingActive.value = active
    }

    // ── Batch / Task mutations ──

    fun addBatch(
        modelId: String,
        prompt: String,
        negativePrompt: String,
        steps: Int,
        cfg: Float,
        seed: String,
        width: Int,
        height: Int,
        effectiveWidth: Int,
        effectiveHeight: Int,
        denoiseStrength: Float,
        useOpenCL: Boolean,
        scheduler: String,
        aspectRatio: String,
        count: Int,
    ): String {
        val batchGroupId = UUID.randomUUID().toString()
        val seedLong = seed.toLongOrNull()
        val now = System.currentTimeMillis()
        val newTasks = (0 until count).map { i ->
            GenerationTask(
                id = UUID.randomUUID().toString(),
                batchGroupId = batchGroupId,
                batchIndex = i,
                modelId = modelId,
                prompt = prompt,
                negativePrompt = negativePrompt,
                steps = steps,
                cfg = cfg,
                seed = seedLong,
                width = width,
                height = height,
                effectiveWidth = effectiveWidth,
                effectiveHeight = effectiveHeight,
                denoiseStrength = denoiseStrength,
                useOpenCL = useOpenCL,
                scheduler = scheduler,
                aspectRatio = aspectRatio,
                timestamp = now,
            )
        }
        // Optimistic in-memory update
        _tasks.update { it + newTasks }
        // Persist async
        val entities = newTasks.map { it.toEntity() }
        scope.launch { db.taskDao().insertAll(entities) }
        return batchGroupId
    }

    fun removeTask(id: String) {
        _tasks.value.firstOrNull { it.id == id }?.resultBitmap?.recycle()
        _tasks.update { it.filterNot { t -> t.id == id } }
        scope.launch { db.taskDao().deleteQueueById(id) }
    }

    fun removeBatch(batchGroupId: String) {
        _tasks.value.filter { it.batchGroupId == batchGroupId }.forEach { it.resultBitmap?.recycle() }
        _tasks.update { it.filterNot { t -> t.batchGroupId == batchGroupId } }
        scope.launch { db.taskDao().deleteQueueByBatch(batchGroupId) }
    }

    fun updateTask(id: String, update: (GenerationTask) -> GenerationTask) {
        var updated: GenerationTask? = null
        _tasks.update { tasks ->
            tasks.map { t -> if (t.id == id) { val u = update(t); updated = u; u } else t }
        }
        updated?.let { scope.launch { db.taskDao().insert(it.toEntity()) } }
    }

    fun markTaskProcessing(id: String) {
        updateTask(id) { it.copy(status = TaskStatus.PROCESSING) }
    }

    /**
     * Reset a PROCESSING task back to PENDING.
     * Used when the backend becomes unavailable mid-generation.
     */
    fun resetTaskToPending(id: String) {
        updateTask(id) { it.copy(status = TaskStatus.PENDING, progress = 0f) }
    }

    fun markTaskComplete(id: String, bitmap: Bitmap?, seed: Long?) {
        updateTask(id) {
            it.copy(
                status = TaskStatus.COMPLETED,
                resultBitmap = bitmap,
                resultSeed = seed,
            )
        }
    }

    fun markTaskError(id: String, message: String) {
        updateTask(id) {
            it.copy(
                status = TaskStatus.ERROR,
                errorMessage = message,
            )
        }
    }

    /** Mark task as ERROR with an AppError */
    fun markTaskError(id: String, error: AppError) {
        updateTask(id) {
            it.copy(
                status = TaskStatus.ERROR,
                errorMessage = error.message,
            )
        }
    }

    fun updateTaskProgress(id: String, progress: Float) {
        updateTask(id) { it.copy(progress = progress) }
    }

    fun cancelAllPending() {
        _tasks.update { tasks ->
            tasks.map { task ->
                if (task.status == TaskStatus.PENDING) {
                    task.copy(status = TaskStatus.CANCELLED)
                } else task
            }
        }
        scope.launch {
            _tasks.value.filter { it.status == TaskStatus.CANCELLED }
                .forEach { db.taskDao().insert(it.toEntity()) }
        }
    }

    // ── Queries ──

    fun getNextPending(): GenerationTask? {
        return _tasks.value.firstOrNull { it.status == TaskStatus.PENDING }
    }

    fun hasPendingTasks(): Boolean {
        return _tasks.value.any { it.status == TaskStatus.PENDING }
    }

    /** Build collapsed batch groups for display */
    fun getBatchGroups(): List<BatchGroupDisplay> {
        val grouped = _tasks.value.groupBy { it.batchGroupId }
        return grouped.map { (groupId, tasks) ->
            val sorted = tasks.sortedBy { it.batchIndex }
            BatchGroupDisplay(
                batchGroupId = groupId,
                tasks = sorted,
                prompt = sorted.firstOrNull()?.prompt ?: "",
                count = tasks.size,
            )
        }
    }

    fun clearCompleted() {
        _tasks.value.filter { it.status == TaskStatus.COMPLETED || it.status == TaskStatus.ERROR || it.status == TaskStatus.CANCELLED }
            .forEach { it.resultBitmap?.recycle() }
        _tasks.update { it.filterNot { t -> t.status == TaskStatus.COMPLETED || t.status == TaskStatus.ERROR || t.status == TaskStatus.CANCELLED } }
        scope.launch { db.taskDao().clearQueueCompleted() }
    }

    // ── Mapping helpers ──

    private fun GenerationTask.toEntity() = TaskEntity(
        id = id,
        taskType = TaskEntity.TYPE_QUEUE,
        modelId = modelId,
        prompt = prompt,
        negativePrompt = negativePrompt,
        steps = steps,
        cfg = cfg,
        seed = seed,
        width = width,
        height = height,
        denoiseStrength = denoiseStrength,
        useOpenCL = useOpenCL,
        scheduler = scheduler,
        timestamp = timestamp,
        batchGroupId = batchGroupId,
        batchIndex = batchIndex,
        effectiveWidth = effectiveWidth,
        effectiveHeight = effectiveHeight,
        aspectRatio = aspectRatio,
        status = status.name,
        resultSeed = resultSeed,
        errorMessage = errorMessage,
        progress = progress,
    )

    private fun TaskEntity.toDomain() = GenerationTask(
        id = id,
        batchGroupId = batchGroupId ?: "",
        batchIndex = batchIndex ?: 0,
        modelId = modelId,
        prompt = prompt,
        negativePrompt = negativePrompt,
        steps = steps,
        cfg = cfg,
        seed = seed,
        width = width,
        height = height,
        effectiveWidth = effectiveWidth ?: width,
        effectiveHeight = effectiveHeight ?: height,
        denoiseStrength = denoiseStrength ?: 0.6f,
        useOpenCL = useOpenCL,
        scheduler = scheduler,
        aspectRatio = aspectRatio ?: "",
        status = try { TaskStatus.valueOf(status ?: "PENDING") } catch (_: Exception) { TaskStatus.PENDING },
        timestamp = timestamp,
        resultBitmap = null, // not persisted
        resultSeed = resultSeed,
        errorMessage = errorMessage,
        progress = progress ?: 0f,
    )
}
