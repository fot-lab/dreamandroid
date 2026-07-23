package io.github.dreamandroid.local.ui.orchestrator

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.GenerateParameterRecord
import io.github.dreamandroid.local.data.GenerationTask
import io.github.dreamandroid.local.data.RecordRepository
import io.github.dreamandroid.local.data.RecordSource
import io.github.dreamandroid.local.ui.frontend.QueueSettingsDrawerContent
import io.github.dreamandroid.local.ui.frontend.QueueTopBar
import io.github.dreamandroid.local.data.BatchGroupDisplay
import io.github.dreamandroid.local.ui.queue.TabQueueScreen
import io.github.dreamandroid.local.ui.viewmodel.QueueViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

private const val TAG_QUEUE = "QueueTabDbg"

/**
 * Queue tab: drawer (Generation + Health settings) + Scaffold + QueueTopBar + TabQueueScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContentTabQueue(
    drawerState: DrawerState,
    snackbarHostState: SnackbarHostState,
    queueViewModel: QueueViewModel,
    tasks: List<GenerationTask>,
    batchGroups: List<BatchGroupDisplay>,
    processingActive: Boolean,
    queuePaused: Boolean,
    hasPendingTasks: Boolean,
    cooldownRemainingS: Int = 0,
    cooldownTotalS: Int = 0,
    recordRepository: RecordRepository,
) {
    SideEffect {
        Log.d(TAG_QUEUE, "AppContentTabQueue() tasks.size=${tasks.size} batchGroups.size=${batchGroups.size} processing=$processingActive paused=$queuePaused selectionMode=${queueViewModel.queueIsSelectionMode}")
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val queueSelectedCount = queueViewModel.queueComputeSelectedCount(batchGroups)

    // ── Batch Delete Confirmation Dialog ──
    if (queueViewModel.queueShowBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { queueViewModel.queueShowBatchDeleteDialog = false },
            title = { Text(stringResource(R.string.batch_delete)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.batch_delete_confirm,
                        queueSelectedCount,
                        queueSelectedCount,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        queueViewModel.queueBatchDelete(batchGroups)
                        queueViewModel.queueShowBatchDeleteDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { queueViewModel.queueShowBatchDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── Import error dialog ──
    var showImportErrorDialog by remember { mutableStateOf(false) }
    var importErrorDetails by remember { mutableStateOf("") }
    if (showImportErrorDialog) {
        AlertDialog(
            onDismissRequest = { showImportErrorDialog = false },
            title = { Text(stringResource(R.string.record_import)) },
            text = { Text(importErrorDetails) },
            confirmButton = {
                TextButton(onClick = { showImportErrorDialog = false }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }

    // ── Export launcher: save selected queue tasks as JSON file ──
    val dateFormat = remember { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val selectedTaskIds = queueViewModel.queueGetSelectedTaskIds(batchGroups)
                val selectedTasks = tasks.filter { it.id in selectedTaskIds }
                val records = selectedTasks.map { task ->
                    GenerateParameterRecord(
                        id = task.id,
                        prompt = task.prompt,
                        negativePrompt = task.negativePrompt,
                        modelId = task.modelId,
                        steps = task.steps,
                        cfg = task.cfg,
                        seed = task.seed,
                        width = task.width,
                        height = task.height,
                        sampler = task.sampler,
                        scheduler = task.scheduler,
                        timestamp = task.timestamp,
                        source = RecordSource.QUEUE,
                    )
                }
                val jsonArray = GenerateParameterRecord.listToJsonArray(records)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(jsonArray.toString(2).toByteArray(Charsets.UTF_8))
                    }
                }
                queueViewModel.queueExitSelection()
                snackbarHostState.showSnackbar(
                    context.getString(R.string.export_success, records.size)
                )
            } catch (e: Exception) {
                Log.e(TAG_QUEUE, "Export failed", e)
                snackbarHostState.showSnackbar(
                    context.getString(R.string.export_failed, e.message ?: "")
                )
            }
        }
    }

    // ── Import launcher: pick JSON file and import records after validation ──
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val jsonString = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                        ?: throw Exception("Cannot read file")
                }
                val jsonArray = JSONArray(jsonString)
                // Validate schema
                val errors = validateRecordSchema(jsonArray)
                if (errors.isNotEmpty()) {
                    importErrorDetails = errors.joinToString("\n")
                    showImportErrorDialog = true
                    return@launch
                }
                // Convert and import
                val records = GenerateParameterRecord.listFromJsonArray(jsonArray)
                withContext(Dispatchers.IO) {
                    records.forEach { recordRepository.addRecord(it) }
                }
                snackbarHostState.showSnackbar(
                    context.getString(R.string.import_success, records.size)
                )
            } catch (e: Exception) {
                Log.e(TAG_QUEUE, "Import failed", e)
                snackbarHostState.showSnackbar(
                    context.getString(R.string.import_failed, e.message ?: "")
                )
            }
        }
    }

    // ── Batch Save Info Confirmation Dialog ──
    if (queueViewModel.queueShowBatchSaveInfoDialog) {
        AlertDialog(
            onDismissRequest = { queueViewModel.queueShowBatchSaveInfoDialog = false },
            title = { Text(stringResource(R.string.batch_save_params)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.batch_save_params_confirm,
                        queueSelectedCount,
                        queueSelectedCount,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    queueViewModel.queueBatchSaveInfo(batchGroups, tasks, recordRepository)
                    queueViewModel.queueShowBatchSaveInfoDialog = false
                    scope.launch {
                        snackbarHostState.showSnackbar("Parameters saved (${queueSelectedCount} tasks)")
                    }
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { queueViewModel.queueShowBatchSaveInfoDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── BackHandler: close drawer on system back press ──
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.8f)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 16.dp, end = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { scope.launch { drawerState.close() } }) {
                        Icon(Icons.Default.Close, stringResource(R.string.close))
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                QueueSettingsDrawerContent(
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                QueueTopBar(
                    drawerState = drawerState,
                    processingActive = processingActive,
                    queuePaused = queuePaused,
                    hasPendingTasks = hasPendingTasks,
                    cooldownRemainingS = cooldownRemainingS,
                    onPause = { queueViewModel.stop(context) },
                    onResume = { queueViewModel.resume(context) },
                    // Queue selection mode
                    queueIsSelectionMode = queueViewModel.queueIsSelectionMode,
                    queueSelectedCount = queueSelectedCount,
                    queueOnExitSelection = { queueViewModel.queueExitSelection() },
                    queueOnBatchSaveInfo = {
                        queueViewModel.queueShowBatchSaveInfoDialog = true
                    },
                    queueOnBatchDelete = {
                        queueViewModel.queueShowBatchDeleteDialog = true
                    },
                    queueOnSelectAll = { queueViewModel.queueSelectAll(batchGroups) },
                    queueOnInvertSelection = { queueViewModel.queueInvertSelection(batchGroups) },
                    queueOnDeselectAll = { queueViewModel.queueDeselectAll() },
                    queueOnExport = {
                        exportLauncher.launch(
                            "dreamhub_queue_${dateFormat.format(java.util.Date())}.json"
                        )
                    },
                    queueOnImport = { importLauncher.launch(arrayOf("application/json")) },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                TabQueueScreen(
                    tasks = tasks,
                    batchGroups = batchGroups,
                    processingActive = processingActive,
                    cooldownRemainingS = cooldownRemainingS,
                    cooldownTotalS = cooldownTotalS,
                    onRemoveTask = { queueViewModel.removeTask(it) },
                    onRemoveBatch = { queueViewModel.removeBatch(it) },
                    recordRepository = recordRepository,
                    // Queue selection
                    queueIsSelectionMode = queueViewModel.queueIsSelectionMode,
                    queueSelectedBatchIds = queueViewModel.queueSelectedBatchIds.toSet(),
                    queueSelectedTaskIds = queueViewModel.queueSelectedTaskIds.toSet(),
                    queueOnLongPressBatch = { queueViewModel.queueStartSelection(it) },
                    queueOnToggleBatch = { queueViewModel.queueToggleBatchSelection(it) },
                    queueOnToggleTask = { queueViewModel.queueToggleTaskSelection(it) },
                )
            }
        }
    }
}

/** Required fields for [GenerateParameterRecord] with their expected JSON types. */
private val RECORD_REQUIRED_FIELDS = listOf(
    "id" to "string",
    "prompt" to "string",
    "modelId" to "string",
    "steps" to "number",
    "cfg" to "number",
    "width" to "number",
    "height" to "number",
    "sampler" to "string",
    "timestamp" to "number",
    "source" to "string",
)

/**
 * Validates that every object in [jsonArray] matches the [GenerateParameterRecord] schema.
 * Returns a list of human-readable error messages (empty = valid).
 */
private fun validateRecordSchema(jsonArray: JSONArray): List<String> {
    val errors = mutableListOf<String>()

    for (i in 0 until jsonArray.length()) {
        val obj = jsonArray.getJSONObject(i)
        val recordIndex = i + 1

        for ((field, expectedType) in RECORD_REQUIRED_FIELDS) {
            if (!obj.has(field)) {
                errors.add("Record $recordIndex: missing required field '$field'")
                continue
            }
            val value = obj.get(field)
            val actualType = when {
                value is String -> "string"
                value is Number -> "number"
                value is Boolean -> "boolean"
                else -> value.javaClass.simpleName.lowercase()
            }
            if (actualType != expectedType) {
                if (field == "seed" && value == null) continue
                errors.add("Record $recordIndex: field '$field' expected $expectedType but got $actualType")
            }
        }

        listOf("negativePrompt" to "string", "seed" to "number").forEach { (field, expectedType) ->
            if (obj.has(field) && !obj.isNull(field)) {
                val value = obj.get(field)
                val actualType = when {
                    value is String -> "string"
                    value is Number -> "number"
                    value is Boolean -> "boolean"
                    else -> value.javaClass.simpleName.lowercase()
                }
                if (actualType != expectedType) {
                    errors.add("Record $recordIndex: field '$field' expected $expectedType but got $actualType")
                }
            }
        }

        if (obj.has("source")) {
            val sourceValue = obj.optString("source", "")
            if (sourceValue.isNotEmpty()) {
                try {
                    RecordSource.valueOf(sourceValue)
                } catch (_: IllegalArgumentException) {
                    errors.add("Record $recordIndex: 'source' value '$sourceValue' is invalid (expected QUEUE or GALLERY)")
                }
            }
        }
    }

    return errors
}
