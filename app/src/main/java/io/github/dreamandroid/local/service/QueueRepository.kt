package io.github.dreamandroid.local.service

import android.content.Context
import android.graphics.Bitmap
import io.github.dreamandroid.local.core.error.AppError
import io.github.dreamandroid.local.data.BatchGroupDisplay
import io.github.dreamandroid.local.data.GenerationTask
import io.github.dreamandroid.local.data.TaskStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * In-memory queue state management, shared between UI and WorkManager Worker.
 *
 * Accessed via [QueueRepository.getInstance] to ensure a single instance
 * per process (Application-scoped singleton).
 *
 * WorkManager's GenerationWorker polls [getNextPending] to dequeue tasks,
 * while the UI observes [tasks] and [processingActive] via collectAsState().
 */
class QueueRepository private constructor() {

    companion object {
        @Volatile
        private var INSTANCE: QueueRepository? = null

        /** Returns the process-wide singleton. Safe to call from any thread. */
        fun getInstance(context: Context): QueueRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: QueueRepository().also { INSTANCE = it }
            }
        }
    }

    private val _tasks = MutableStateFlow<List<GenerationTask>>(emptyList())
    val tasks: StateFlow<List<GenerationTask>> = _tasks

    private val _processingActive = MutableStateFlow(false)
    val processingActive: StateFlow<Boolean> = _processingActive

    fun setProcessingActive(active: Boolean) {
        _processingActive.value = active
    }

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
            )
        }
        _tasks.update { it + newTasks }
        return batchGroupId
    }

    fun removeTask(id: String) {
        _tasks.update { it.filterNot { t -> t.id == id } }
    }

    fun removeBatch(batchGroupId: String) {
        _tasks.update { it.filterNot { t -> t.batchGroupId == batchGroupId } }
    }

    fun updateTask(id: String, update: (GenerationTask) -> GenerationTask) {
        _tasks.update { it.map { t -> if (t.id == id) update(t) else t } }
    }

    fun markTaskProcessing(id: String) {
        updateTask(id) { it.copy(status = TaskStatus.PROCESSING) }
    }

    /**
     * Reset a PROCESSING task back to PENDING.
     * Used when the backend becomes unavailable mid-generation —
     * the task is not failed, only deferred until the backend returns.
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
    }

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
        _tasks.update { it.filterNot { t -> t.status == TaskStatus.COMPLETED } }
    }
}
