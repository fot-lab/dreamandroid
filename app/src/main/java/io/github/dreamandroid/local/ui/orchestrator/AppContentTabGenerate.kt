package io.github.dreamandroid.local.ui.orchestrator

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.GenerateParameterRecord
import io.github.dreamandroid.local.data.GenerationPreferences
import io.github.dreamandroid.local.data.ModelRepository
import io.github.dreamandroid.local.data.RecordRepository
import io.github.dreamandroid.local.data.RecordSource
import io.github.dreamandroid.local.service.QueueRepository
import io.github.dreamandroid.local.service.backend.BackendManager
import io.github.dreamandroid.local.ui.frontend.GenerateTopBar
import io.github.dreamandroid.local.ui.frontend.TabGenerateScreen
import io.github.dreamandroid.local.ui.screens.run.inferAspectRatioString
import io.github.dreamandroid.local.ui.viewmodel.GenerateViewModel
import io.github.dreamandroid.local.ui.viewmodel.ModelsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generate tab: ModalNavigationDrawer (empty) + Scaffold + GenerateTopBar + TabGenerateScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContentTabGenerate(
    drawerState: DrawerState,
    snackbarHostState: SnackbarHostState,
    modelsViewModel: ModelsViewModel,
    generateViewModel: GenerateViewModel,
    queueRepository: QueueRepository,
    loadedModelId: String?,
    loadedModelType: BackendManager.Mode?,
    recordRepository: RecordRepository,
    // Queue animation
    onQueueAnimationRequest: () -> Unit = {},
    queueAnimEnabled: Boolean = true,
    onQueueAnimEnabledChange: (Boolean) -> Unit = {},
    onGenParamAddQueuePositioned: (Offset) -> Unit = {},
) {
    // Derive whether the currently loaded model is a generator (Diffusion mode)
    val isModelLoaded = loadedModelType == BackendManager.Mode.Diffusion && loadedModelId != null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val generationPreferences = remember { GenerationPreferences(context) }

    // ── Records tab selection state ──
    var selectedGenerateTab by remember { mutableIntStateOf(0) }
    var selectedRecordIds by remember { mutableStateOf(setOf<String>()) }
    var recordsList by remember { mutableStateOf(emptyList<GenerateParameterRecord>()) }
    val selectedRecordCount = selectedRecordIds.size
    var showDeleteRecordsDialog by remember { mutableStateOf(false) }
    var showImportErrorDialog by remember { mutableStateOf(false) }
    var importErrorDetails by remember { mutableStateOf("") }

    // ── Export launcher: save selected records as JSON file ──
    val dateFormat = remember { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val selectedRecords = recordsList.filter { it.id in selectedRecordIds }
                val jsonArray = GenerateParameterRecord.listToJsonArray(selectedRecords)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(jsonArray.toString(2).toByteArray(Charsets.UTF_8))
                    }
                }
                selectedRecordIds = emptySet()
                scope.launch {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.export_success, selectedRecords.size)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                scope.launch {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.export_failed, e.message ?: "")
                    )
                }
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
                scope.launch {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.import_success, records.size)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
                scope.launch {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.import_failed, e.message ?: "")
                    )
                }
            }
        }
    }

    // ── Reset all generation params to defaults ──
    val onGenTaskParamReset: () -> Unit = {
        generateViewModel.genSteps = 20f
        generateViewModel.genCfg = 7f
        generateViewModel.genSeed = ""
        generateViewModel.genBatchCounts = 1
        generateViewModel.genSampler = "dpm"
        generateViewModel.genDenoiseCurve = "scaled_linear"
        generateViewModel.genDenoiseStrength = 0.6f
        val repo = ModelRepository(context)
        val m = loadedModelId?.let { id -> repo.models.find { it.id == id } }
        generateViewModel.genPrompt = m?.defaultPrompt ?: ""
        generateViewModel.genNegativePrompt = m?.defaultNegativePrompt ?: ""
        kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
            generationPreferences.saveGlobalFields(
                prompt = generateViewModel.genPrompt,
                negativePrompt = generateViewModel.genNegativePrompt,
                batchCounts = generateViewModel.genBatchCounts,
                width = generateViewModel.genWidth,
                height = generateViewModel.genHeight,
            )
            loadedModelId?.let { modelId ->
                generationPreferences.saveAllFields(
                    modelId = modelId,
                    prompt = generateViewModel.genPrompt,
                    negativePrompt = generateViewModel.genNegativePrompt,
                    steps = generateViewModel.genSteps,
                    cfgScale = generateViewModel.genCfg,
                    seed = generateViewModel.genSeed,
                    width = generateViewModel.genWidth,
                    height = generateViewModel.genHeight,
                    denoisingStrength = generateViewModel.genDenoiseStrength,
                    useOpenCL = generateViewModel.genUseOpenCL,
                    batchCounts = generateViewModel.genBatchCounts,
                    sampler = generateViewModel.genSampler,
                    aspectRatio = inferAspectRatioString(generateViewModel.genWidth, generateViewModel.genHeight),
                )
            }
        }
    }

    // ── Add to queue ──
    val onAddToQueue: (Int) -> Unit = { count ->
        loadedModelId?.let { modelId ->
            generateViewModel.addToQueue(modelId, count, queueRepository)
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.added_to_queue, count))
            }
        }
    }

    val onGenTaskAddToQueue: () -> Unit = {
        loadedModelId?.let { mid ->
            val count = if (generateViewModel.genSeed.isNotBlank()) 1
            else generateViewModel.genBatchCounts.coerceAtLeast(1)
            generateViewModel.addToQueue(mid, count, queueRepository)
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.added_to_queue, count))
            }
            onQueueAnimationRequest()
        }
    }

    // ── Records tab selection operations ──
    val onRecordsSelectAll: () -> Unit = { selectedRecordIds = recordsList.map { it.id }.toSet() }
    val onRecordsInvertSelection: () -> Unit = {
        selectedRecordIds = recordsList.map { it.id }.toSet() - selectedRecordIds
    }
    val onRecordsDeselectAll: () -> Unit = { selectedRecordIds = emptySet() }
    val onLoadSelectedRecord: () -> Unit = {
        val record = recordsList.find { it.id in selectedRecordIds }
        if (record != null) {
            generateViewModel.genPrompt = record.prompt
            generateViewModel.genNegativePrompt = record.negativePrompt
            generateViewModel.genSteps = record.steps.toFloat()
            generateViewModel.genCfg = record.cfg
            generateViewModel.genSeed = record.seed?.toString() ?: ""
            generateViewModel.genWidth = record.width
            generateViewModel.genHeight = record.height
            generateViewModel.genSampler = record.sampler
            selectedGenerateTab = 0
        }
    }
    val onDeleteSelectedRecords: () -> Unit = { showDeleteRecordsDialog = true }
    val onRecordsSave: () -> Unit = { exportLauncher.launch("dreamandroid_records_${dateFormat.format(Date())}.json") }
    val onRecordsExport: () -> Unit = onRecordsSave
    val onRecordsImport: () -> Unit = { importLauncher.launch(arrayOf("application/json")) }

    // ── Delete records confirmation dialog ──
    if (showDeleteRecordsDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteRecordsDialog = false },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_record_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteRecordsDialog = false
                        scope.launch {
                            selectedRecordIds.forEach { id ->
                                recordRepository.deleteRecord(id)
                            }
                            selectedRecordIds = emptySet()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteRecordsDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── Import schema mismatch dialog ──
    if (showImportErrorDialog) {
        AlertDialog(
            onDismissRequest = { showImportErrorDialog = false },
            title = { Text(stringResource(R.string.import_schema_mismatch)) },
            text = { Text(importErrorDetails) },
            confirmButton = {
                TextButton(onClick = { showImportErrorDialog = false }) {
                    Text(stringResource(R.string.ok))
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
                    verticalAlignment = Alignment.CenterVertically,
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
                // Queue animation toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.queue_anim_toggle),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = queueAnimEnabled,
                        onCheckedChange = onQueueAnimEnabledChange,
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                GenerateTopBar(
                    drawerState = drawerState,
                    loadedModelId = loadedModelId,
                    loadedModelType = loadedModelType,
                    onGenTaskParamReset = onGenTaskParamReset,
                    onGenTaskAddToQueue = onGenTaskAddToQueue,
                    selectedGenerateTab = selectedGenerateTab,
                    selectedRecordCount = selectedRecordCount,
                    onRecordsSelectAll = onRecordsSelectAll,
                    onRecordsInvertSelection = onRecordsInvertSelection,
                    onRecordsDeselectAll = onRecordsDeselectAll,
                    onLoadSelectedRecord = onLoadSelectedRecord,
                    onDeleteSelectedRecords = onDeleteSelectedRecords,
                    onRecordsSave = onRecordsSave,
                    onRecordsExport = onRecordsExport,
                    onRecordsImport = onRecordsImport,
                    onPlayButtonPositioned = onGenParamAddQueuePositioned,
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                TabGenerateScreen(
                    modelId = if (isModelLoaded) loadedModelId else null,
                    prompt = generateViewModel.genPrompt,
                    onPromptChange = { generateViewModel.genPrompt = it },
                    negativePrompt = generateViewModel.genNegativePrompt,
                    onNegativePromptChange = { generateViewModel.genNegativePrompt = it },
                    steps = generateViewModel.genSteps,
                    onStepsChange = { generateViewModel.genSteps = it },
                    cfg = generateViewModel.genCfg,
                    onCfgChange = { generateViewModel.genCfg = it },
                    seed = generateViewModel.genSeed,
                    onSeedChange = { generateViewModel.genSeed = it },
                    batchCounts = generateViewModel.genBatchCounts,
                    onBatchCountsChange = { generateViewModel.genBatchCounts = it },
                    sampler = generateViewModel.genSampler,
                    onSamplerChange = { generateViewModel.genSampler = it },
                    denoiseCurve = generateViewModel.genDenoiseCurve,
                    onDenoiseCurveChange = { generateViewModel.genDenoiseCurve = it },
                    denoiseStrength = generateViewModel.genDenoiseStrength,
                    onDenoiseStrengthChange = { generateViewModel.genDenoiseStrength = it },
                    useOpenCL = generateViewModel.genUseOpenCL,
                    onUseOpenCLChange = { generateViewModel.genUseOpenCL = it },
                    width = generateViewModel.genWidth,
                    onWidthChange = { generateViewModel.genWidth = it },
                    height = generateViewModel.genHeight,
                    onHeightChange = { generateViewModel.genHeight = it },
                    onAddToQueue = onAddToQueue,
                    recordRepository = recordRepository,
                    // Records tab selection (lifted for top bar)
                    selectedGenerateTab = selectedGenerateTab,
                    onSelectedGenerateTabChange = { selectedGenerateTab = it },
                    selectedRecordIds = selectedRecordIds,
                    onSelectedRecordIdsChange = { selectedRecordIds = it },
                    onRecordsListChange = { recordsList = it },
                    onTokenizePrompt = { prompt -> generateViewModel.tokenizePrompt(prompt) },
                    onTokenizeNegativePrompt = { nPrompt -> generateViewModel.tokenizeNegativePrompt(nPrompt) },
                    promptTokenCount = generateViewModel.promptTokenCount,
                    promptTokenMax = generateViewModel.promptTokenMax,
                    promptOverflowOffset = generateViewModel.promptOverflowOffset,
                    negativePromptTokenCount = generateViewModel.negativePromptTokenCount,
                    negativePromptTokenMax = generateViewModel.negativePromptTokenMax,
                    negativePromptOverflowOffset = generateViewModel.negativePromptOverflowOffset,
                )
            }
        }
    }
}

private const val TAG = "AppContentTabGen"

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

        // Check required fields exist with correct type
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
                // Allow nullable seed to be null
                if (field == "seed" && value == null) continue
                errors.add("Record $recordIndex: field '$field' expected $expectedType but got $actualType")
            }
        }

        // Validate optional fields types if present
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

        // Validate "source" enum value
        if (obj.has("source")) {
            val sourceValue = obj.optString("source", "")
            if (!sourceValue.isNullOrEmpty()) {
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
