package io.github.dreamandroid.local.ui.queue

import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import io.github.dreamandroid.local.data.*
import io.github.dreamandroid.local.ui.screens.QueueScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG_QUEUE = "QueueSectionDbg"

// =========== Queue Tab ===========

@Composable
fun TabQueueScreen(
    tasks: List<GenerationTask>,
    batchGroups: List<BatchGroupDisplay>,
    processingActive: Boolean,
    onRemoveTask: (String) -> Unit,
    onRemoveBatch: (String) -> Unit,
    recordRepository: RecordRepository? = null,
    // ── Queue selection mode ──────────────────────────────────
    queueIsSelectionMode: Boolean = false,
    queueSelectedBatchIds: Set<String> = emptySet(),
    queueSelectedTaskIds: Set<String> = emptySet(),
    queueOnLongPressBatch: (String) -> Unit = {},
    queueOnToggleBatch: (String) -> Unit = {},
    queueOnToggleTask: (String) -> Unit = {},
) {
    Log.d(TAG_QUEUE, "TabQueueScreen() tasks.size=${tasks.size} batchGroups.size=${batchGroups.size} selection=$queueIsSelectionMode")
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val onSaveInfo: (GenerationTask) -> Unit = { task ->
        scope.launch {
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
            recordRepository?.addRecord(record)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Parameters saved", Toast.LENGTH_SHORT).show()
            }
        }
    }

    QueueScreen(
        tasks = tasks,
        batchGroups = batchGroups,
        processingActive = processingActive,
        onRemoveTask = onRemoveTask,
        onRemoveBatch = onRemoveBatch,
        onSaveInfo = onSaveInfo,
        // Queue selection
        queueIsSelectionMode = queueIsSelectionMode,
        queueSelectedBatchIds = queueSelectedBatchIds,
        queueSelectedTaskIds = queueSelectedTaskIds,
        queueOnLongPressBatch = queueOnLongPressBatch,
        queueOnToggleBatch = queueOnToggleBatch,
        queueOnToggleTask = queueOnToggleTask,
    )
}
