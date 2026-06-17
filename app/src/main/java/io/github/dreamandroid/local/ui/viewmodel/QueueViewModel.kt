package io.github.dreamandroid.local.ui.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dreamandroid.local.DreamAndroidApplication
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
        // Observe WorkManager state for logging
        viewModelScope.launch {
            QueueController.observeState(application).collect { info ->
                Log.d("QueueViewModel", "WorkInfo state: ${QueueController.stateLabel(info)}")
            }
        }

        // Auto-start queue processing when tasks are added (if not already running)
        viewModelScope.launch {
            queueRepository.tasks.collect {
                if (queueRepository.hasPendingTasks() && !queueRepository.processingActive.value) {
                    QueueController.start(application)
                }
            }
        }
    }

    fun start(context: Context) = QueueController.start(context)

    fun stop(context: Context) {
        viewModelScope.launch { QueueController.stop(context) }
    }

    fun removeTask(taskId: String) = queueRepository.removeTask(taskId)

    fun removeBatch(batchId: String) = queueRepository.removeBatch(batchId)
}
