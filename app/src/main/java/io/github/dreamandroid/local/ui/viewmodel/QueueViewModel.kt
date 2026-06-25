package io.github.dreamandroid.local.ui.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dreamandroid.local.DreamAndroidApplication
import io.github.dreamandroid.local.data.BatchGroupDisplay
import io.github.dreamandroid.local.data.GenerationTask
import io.github.dreamandroid.local.data.RecordRepository
import io.github.dreamandroid.local.data.RecordSource
import io.github.dreamandroid.local.data.GenerateParameterRecord
import io.github.dreamandroid.local.service.QueueRepository
import io.github.dreamandroid.local.service.queue.QueueController
import kotlinx.coroutines.launch

/**
 * Queue ViewModel extracted from AppContent God Object (UILA-COMP-0001).
 *
 * Manages:
 * - Queue state observation (read-only from QueueRepository)
 * - Auto-start processing when tasks are added
 * - WorkManager state logging
 */
class QueueViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as DreamAndroidApplication

    // ── Queue repository (process-wide singleton, shared with WorkManager Worker) ──
    val queueRepository: QueueRepository = app.queueRepository

    init {
        Log.d("QueueViewModelDbg", "QueueViewModel init: tasks.size=${queueRepository.tasks.value.size}")

        // Observe WorkManager state for logging
        viewModelScope.launch {
            QueueController.observeState(application).collect { info ->
                Log.d("QueueViewModel", "WorkInfo state: ${QueueController.stateLabel(info)}")
            }
        }

        // Auto-start queue processing when tasks are added (if not already running and not paused)
        viewModelScope.launch {
            queueRepository.tasks.collect { tasks ->
                Log.d("QueueViewModelDbg", "tasks.collect: size=${tasks.size} pending=${queueRepository.hasPendingTasks()} active=${queueRepository.processingActive.value} paused=${queueRepository.queuePaused.value}")
                if (queueRepository.hasPendingTasks()
                    && !queueRepository.processingActive.value
                    && !queueRepository.queuePaused.value) {
                    Log.d("QueueViewModelDbg", "Auto-starting QueueController")
                    QueueController.start(application)
                }
            }
        }
    }

    fun start(context: Context) {
        QueueController.start(context)
        queueRepository.setQueuePaused(false)
    }

    fun stop(context: Context) {
        viewModelScope.launch {
            try {
                QueueController.stop(context)
            } catch (e: Exception) {
                Log.e("QueueViewModel", "Error stopping queue", e)
                // Safety net: ensure paused state is set even on error
                queueRepository.setProcessingActive(false)
                queueRepository.setQueuePaused(true)
                queueRepository.resetProcessingToPending()
            }
        }
    }

    fun resume(context: Context) {
        QueueController.start(context)
    }

    fun removeTask(taskId: String) = queueRepository.removeTask(taskId)

    fun removeBatch(batchId: String) = queueRepository.removeBatch(batchId)

    // ── Queue Selection Mode ────────────────────────────────────

    var queueIsSelectionMode by mutableStateOf(false)
    val queueSelectedBatchIds = mutableStateListOf<String>()
    val queueSelectedTaskIds = mutableStateListOf<String>()
    var queueShowBatchDeleteDialog by mutableStateOf(false)
    var queueShowBatchSaveInfoDialog by mutableStateOf(false)

    /** Long-press on a batch group → enter selection mode & select that batch. */
    fun queueStartSelection(batchId: String) {
        if (!queueIsSelectionMode) {
            queueIsSelectionMode = true
            queueSelectedBatchIds.add(batchId)
        }
    }

    /** Exit selection mode, clear all selections. */
    fun queueExitSelection() {
        queueIsSelectionMode = false
        queueSelectedBatchIds.clear()
        queueSelectedTaskIds.clear()
    }

    /** Toggle batch group selection. Auto-exit selection mode when nothing is selected. */
    fun queueToggleBatchSelection(batchId: String) {
        if (batchId in queueSelectedBatchIds) {
            queueSelectedBatchIds.remove(batchId)
        } else {
            queueSelectedBatchIds.add(batchId)
        }
        if (queueSelectedBatchIds.isEmpty() && queueSelectedTaskIds.isEmpty()) {
            queueIsSelectionMode = false
        }
    }

    /** Toggle individual task selection. Auto-exit selection mode when nothing is selected. */
    fun queueToggleTaskSelection(taskId: String) {
        if (taskId in queueSelectedTaskIds) {
            queueSelectedTaskIds.remove(taskId)
        } else {
            queueSelectedTaskIds.add(taskId)
        }
        if (queueSelectedBatchIds.isEmpty() && queueSelectedTaskIds.isEmpty()) {
            queueIsSelectionMode = false
        }
    }

    /**
     * Compute the effective set of selected task IDs:
     * union of (all tasks from selected batches) + (individually selected tasks).
     */
    fun queueGetSelectedTaskIds(batchGroups: List<BatchGroupDisplay>): Set<String> {
        val fromBatches = batchGroups
            .filter { it.batchGroupId in queueSelectedBatchIds }
            .flatMap { it.tasks.map { t -> t.id } }
            .toSet()
        return fromBatches + queueSelectedTaskIds.filter { it !in fromBatches }
    }

    /** Compute how many unique tasks are currently selected. */
    fun queueComputeSelectedCount(batchGroups: List<BatchGroupDisplay>): Int =
        queueGetSelectedTaskIds(batchGroups).size

    /** Execute batch delete for all selected tasks. */
    fun queueBatchDelete(batchGroups: List<BatchGroupDisplay>) {
        val taskIds = queueGetSelectedTaskIds(batchGroups)
        taskIds.forEach { removeTask(it) }
        queueExitSelection()
    }

    /** Execute batch save-info (save parameters) for all selected tasks. */
    fun queueBatchSaveInfo(
        batchGroups: List<BatchGroupDisplay>,
        tasks: List<GenerationTask>,
        recordRepository: RecordRepository,
    ) {
        val taskIds = queueGetSelectedTaskIds(batchGroups)
        viewModelScope.launch {
            tasks.filter { it.id in taskIds }.forEach { task ->
                val record = GenerateParameterRecord(
                    prompt = task.prompt,
                    negativePrompt = task.negativePrompt,
                    modelId = task.modelId,
                    steps = task.steps,
                    cfg = task.cfg,
                    seed = task.seed,
                    width = task.width,
                    height = task.height,
                    sampler = task.sampler,
                    timestamp = task.timestamp,
                    source = RecordSource.QUEUE,
                )
                recordRepository.addRecord(record)
            }
        }
        queueExitSelection()
    }

    // ── 3-dot dropdown actions ─────────────────────────────────

    /** Select all batch groups, enter selection mode. */
    fun queueSelectAll(batchGroups: List<BatchGroupDisplay>) {
        queueIsSelectionMode = true
        queueSelectedBatchIds.clear()
        queueSelectedTaskIds.clear()
        queueSelectedBatchIds.addAll(batchGroups.map { it.batchGroupId })
    }

    /**
     * Invert batch-level selection. If not yet in selection mode,
     * selects all batches and enters selection mode.
     */
    fun queueInvertSelection(batchGroups: List<BatchGroupDisplay>) {
        if (!queueIsSelectionMode) {
            queueSelectAll(batchGroups)
        } else {
            val allBatchIds = batchGroups.map { it.batchGroupId }.toSet()
            val selectedBatchIds = queueSelectedBatchIds.toSet()
            val inverted = allBatchIds - selectedBatchIds
            queueSelectedBatchIds.clear()
            queueSelectedTaskIds.clear()
            queueSelectedBatchIds.addAll(inverted)
            if (queueSelectedBatchIds.isEmpty()) {
                queueIsSelectionMode = false
            }
        }
    }

    /** Deselect all and exit selection mode. */
    fun queueDeselectAll() {
        queueExitSelection()
    }
}
