package io.github.dreamandroid.local.ui.queue

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import io.github.dreamandroid.local.data.*
import io.github.dreamandroid.local.ui.screens.QueueScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// =========== Queue Tab ===========

@Composable
fun TabQueueScreen(
    tasks: List<GenerationTask>,
    batchGroups: List<BatchGroupDisplay>,
    processingActive: Boolean,
    onRemoveTask: (String) -> Unit,
    onRemoveBatch: (String) -> Unit,
    recordRepository: RecordRepository? = null,
) {
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
    )
}
