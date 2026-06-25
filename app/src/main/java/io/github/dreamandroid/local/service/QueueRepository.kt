package io.github.dreamandroid.local.service

import android.content.Context
import android.util.Log
import io.github.dreamandroid.local.core.error.AppError
import io.github.dreamandroid.local.data.BatchGroupDisplay
import io.github.dreamandroid.local.data.GenerationTask
import io.github.dreamandroid.local.data.TaskStatus
import io.github.dreamandroid.local.data.db.AppDatabase
import io.github.dreamandroid.local.data.db.TaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random

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

    private val _generationTimedOut = MutableStateFlow(false)
    val generationTimedOut: StateFlow<Boolean> = _generationTimedOut

    /** Whether the queue was paused by user action (vs. naturally idle). */
    private val _queuePaused = MutableStateFlow(false)
    val queuePaused: StateFlow<Boolean> = _queuePaused

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Init: restore from Room ──

    /**
     * Restores queue tasks from Room on process start.
     *
     * PROCESSING tasks are reset to PENDING because the worker that owned
     * them died with the previous process. Without this reset they would be
     * permanently invisible to [getNextPending] and stuck forever.
     */
    private fun restoreFromDb() {
        scope.launch {
            val entities = db.taskDao().getRestorableQueueTasks()
            val staleProcessing = entities.filter { it.status == TaskStatus.PROCESSING.name }
            if (staleProcessing.isNotEmpty()) {
                // Reset stale PROCESSING → PENDING (worker died with the process)
                val reset = staleProcessing.map {
                    it.copy(status = TaskStatus.PENDING.name, progress = 0f)
                }
                db.taskDao().insertAll(reset)
                val resetIds = reset.map { it.id }.toSet()
                _tasks.value = entities.map { entity ->
                    if (entity.id in resetIds) {
                        reset.first { it.id == entity.id }.toDomain()
                    } else {
                        entity.toDomain()
                    }
                }
            } else {
                _tasks.value = entities.map { it.toDomain() }
            }
        }
    }

    // ── Processing flag ──

    fun setProcessingActive(active: Boolean) {
        _processingActive.value = active
    }

    fun setGenerationTimedOut(timedOut: Boolean) {
        _generationTimedOut.value = timedOut
    }

    fun setQueuePaused(paused: Boolean) {
        _queuePaused.value = paused
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
        sampler: String,
        aspectRatio: String,
        count: Int,
    ): String {
        val batchGroupId = UUID.randomUUID().toString()
        val userSeed = seed.toLongOrNull()
        val now = System.currentTimeMillis()
        val newTasks = (0 until count).map { i ->
            val seedLong = userSeed ?: Random.nextLong()
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
                sampler = sampler,
                aspectRatio = aspectRatio,
                timestamp = now,
            )
        }
        // Optimistic in-memory update
        _tasks.value = _tasks.value + newTasks
        // Persist async
        val entities = newTasks.map { it.toEntity() }
        scope.launch { db.taskDao().insertAll(entities) }
        // Reset timeout flag on new batch
        _generationTimedOut.value = false
        return batchGroupId
    }

    fun removeTask(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id }
        // PROCESSING tasks are owned by the worker and cannot be removed
        if (task == null || task.status == TaskStatus.PROCESSING) return
        task.resultBitmapPath?.let { path ->
            try { java.io.File(path).delete() } catch (_: Exception) {}
        }
        _tasks.update { it.filterNot { t -> t.id == id } }
        scope.launch { db.taskDao().deleteQueueById(id) }
    }

    fun removeBatch(batchGroupId: String) {
        val batchTasks = _tasks.value.filter { it.batchGroupId == batchGroupId }
        if (batchTasks.isEmpty() && batchGroupId.isNotEmpty()) {
            // Synthetic group key for an orphan task (batchGroupId was empty, keyed by task id)
            removeTask(batchGroupId)
            return
        }
        // Only remove non-PROCESSING tasks; worker owns PROCESSING tasks
        val removable = batchTasks.filter { it.status != TaskStatus.PROCESSING }
        if (removable.isEmpty()) return
        removable.forEach { task ->
            task.resultBitmapPath?.let { path ->
                try { java.io.File(path).delete() } catch (_: Exception) {}
            }
        }
        val removableIds = removable.map { it.id }.toSet()
        _tasks.update { it.filterNot { t -> t.id in removableIds } }
        removable.forEach { task ->
            scope.launch { db.taskDao().deleteQueueById(task.id) }
        }
    }

    fun updateTask(id: String, transform: (GenerationTask) -> GenerationTask) {
        var updated: GenerationTask? = null
        _tasks.update { tasks ->
            tasks.map { t -> if (t.id == id) { val u = transform(t); updated = u; u } else t }
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

    fun markTaskComplete(id: String, bitmapPath: String?, seed: Long?) {
        updateTask(id) {
            it.copy(
                status = TaskStatus.COMPLETED,
                resultBitmapPath = bitmapPath,
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

    /**
     * Pause the queue: reset any PROCESSING task back to PENDING.
     * PENDING tasks remain PENDING (not cancelled).
     * Used when the user pauses the queue.
     */
    fun resetProcessingToPending() {
        _tasks.update { tasks ->
            tasks.map { task ->
                if (task.status == TaskStatus.PROCESSING) {
                    task.copy(status = TaskStatus.PENDING, progress = 0f)
                } else task
            }
        }
        scope.launch {
            _tasks.value.filter { it.status == TaskStatus.PENDING }
                .forEach { db.taskDao().insert(it.toEntity()) }
        }
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
        val tasks = _tasks.value
        Log.d("QueueRepoDbg", "getBatchGroups() tasks.size=${tasks.size}")
        // Tasks without batchGroupId (orphans from pre-batch era) get their own group keyed by task id
        val groups = tasks.groupBy { it.batchGroupId.ifEmpty { it.id } }
            .map { (groupId, groupedTasks) ->
                val sorted = groupedTasks.sortedBy { it.batchIndex }
                BatchGroupDisplay(
                    batchGroupId = groupId,
                    tasks = sorted,
                    prompt = sorted.firstOrNull()?.prompt ?: "",
                    count = groupedTasks.size,
                )
            }
        Log.d("QueueRepoDbg", "getBatchGroups() returned ${groups.size} groups: ${groups.map { "id=${it.batchGroupId.take(8)} count=${it.count}" }}")
        return groups
    }

    fun clearCompleted() {
        _tasks.value.filter { it.status == TaskStatus.COMPLETED || it.status == TaskStatus.ERROR || it.status == TaskStatus.CANCELLED }
            .forEach { task ->
                task.resultBitmapPath?.let { path ->
                    try { java.io.File(path).delete() } catch (_: Exception) {}
                }
            }
        _tasks.update { it.filterNot { t -> t.status == TaskStatus.COMPLETED || t.status == TaskStatus.ERROR || t.status == TaskStatus.CANCELLED } }
        scope.launch { db.taskDao().clearQueueCompleted() }
    }

    /**
     * Recycle result images for completed tasks (release disk space).
     * Called on memory pressure; completed tasks remain in the queue.
     */
    fun recycleCompletedBitmaps() {
        _tasks.value.filter { it.resultBitmapPath != null && it.status == TaskStatus.COMPLETED }
            .forEach { task ->
                task.resultBitmapPath?.let { path ->
                    try { java.io.File(path).delete() } catch (_: Exception) {}
                }
                updateTask(task.id) { it.copy(resultBitmapPath = null) }
            }
    }

    /**
     * Cancel the internal CoroutineScope. Only needed for testing or app termination.
     * In production, the singleton lives for the process lifetime.
     */
    fun cancelScope() {
        scope.coroutineContext.cancel()
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
        sampler = sampler,
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
        sampler = sampler,
        aspectRatio = aspectRatio ?: "",
        status = try { TaskStatus.valueOf(status ?: "PENDING") } catch (_: Exception) { TaskStatus.PENDING },
        timestamp = timestamp,
        resultBitmapPath = null, // not persisted
        resultSeed = resultSeed,
        errorMessage = errorMessage,
        progress = progress ?: 0f,
    )
}
