package io.github.dreamandroid.local.ui.orchestrator

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.dreamandroid.local.DreamAndroidApplication
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.*
import io.github.dreamandroid.local.navigation.BottomTab
import io.github.dreamandroid.local.service.QueueRepository
import io.github.dreamandroid.local.service.backend.BackendManager
import io.github.dreamandroid.local.service.queue.QueueController
import io.github.dreamandroid.local.ui.backend.*
import io.github.dreamandroid.local.ui.frontend.*
import io.github.dreamandroid.local.ui.queue.TabQueueScreen
import io.github.dreamandroid.local.ui.screens.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }

    // ---- Shared state ----
    var selectedTab by remember { mutableStateOf(BottomTab.Models) }
    var selectedModelId by remember { mutableStateOf<String?>(null) }

    // §17.3: Unified backend lifecycle via BackendManager (single source of truth)
    val app = context.applicationContext as DreamAndroidApplication
    val backendManager = app.backendManager
    val backendState by backendManager.state.collectAsState()
    val isModelLoaded = backendState.isDiffusionLoaded()
    val isModelLoading = backendState.isDiffusionLoading()
    val isUpscaleModelLoaded = backendState.isUpscalerLoaded()
    val selectedUpscalerId = backendState.activeUpscalerId()
    val modelRepository = remember { ModelRepository(context) }
    var modelRefreshVersion by remember { mutableIntStateOf(0) }

    // ---- Generation parameters (shared between top bar and screen) ----
    val model = remember(selectedModelId) { modelRepository.models.find { it.id == selectedModelId } }
    var genPrompt by remember { mutableStateOf("") }
    var genNegativePrompt by remember { mutableStateOf("") }
    var genSteps by remember { mutableFloatStateOf(20f) }
    var genCfg by remember { mutableFloatStateOf(7f) }
    var genSeed by remember { mutableStateOf("") }
    var genBatchCounts by remember { mutableIntStateOf(1) }
    var genScheduler by remember { mutableStateOf("dpm") }
    var genDenoiseStrength by remember { mutableFloatStateOf(0.6f) }
    var genUseOpenCL by remember { mutableStateOf(false) }
    var genWidth by remember { mutableIntStateOf(512) }
    var genHeight by remember { mutableIntStateOf(512) }

    // ---- Queue repository (process-wide singleton, shared with WorkManager Worker) ----
    val queueRepository = remember { QueueRepository.getInstance(context) }
    val recordRepository = remember { RecordRepository(context) }
    val queueTasks by queueRepository.tasks.collectAsState()
    val queueProcessing by queueRepository.processingActive.collectAsState()
    val queueBatchGroups = remember(queueTasks) { queueRepository.getBatchGroups() }

    // ---- WorkManager queue control ----
    LaunchedEffect(Unit) {
        QueueController.observeState(context).collect { info ->
            Log.d("AppContent", "WorkInfo state: ${QueueController.stateLabel(info)}")
        }
    }

    // Auto-start queue processing when tasks are added (if not already running)
    LaunchedEffect(queueTasks) {
        if (queueRepository.hasPendingTasks() && !queueRepository.processingActive.value) {
            QueueController.start(context)
        }
    }

    // ---- Import dialog state ----
    var showCustomModelDialog by remember { mutableStateOf(false) }
    var showCustomNpuModelDialog by remember { mutableStateOf(false) }
    var showCustomUpscaleModelDialog by remember { mutableStateOf(false) }
    var importingModels by remember { mutableStateOf<List<ImportingModelState>>(emptyList()) }

    // ---- Rename / Delete dialog state ----
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    // ---- Upscale model state ----
    var upscalerPreferences by remember {
        mutableStateOf(context.getSharedPreferences("upscaler_prefs", Context.MODE_PRIVATE))
    }
    val persistedUpscalerId = remember {
        upscalerPreferences.getString("upscaler_standalone_selected_upscaler", null)
    }

    fun addImportingModel(state: ImportingModelState) {
        importingModels = importingModels + state
    }

    fun updateImportingModel(modelId: String, update: (ImportingModelState) -> ImportingModelState) {
        importingModels = importingModels.map { existing ->
            if (existing.modelId == modelId) update(existing) else existing
        }
    }

    fun removeImportingModel(modelId: String) {
        importingModels = importingModels.filterNot { it.modelId == modelId }
    }

    val msgNpuModelAddedSuccess = stringResource(R.string.npu_model_added_success)
    val msgNpuModelAddFailed = stringResource(R.string.npu_model_add_failed)
    val msgModelConversionSuccess = stringResource(R.string.model_conversion_success)
    val msgModelConversionFailed = stringResource(R.string.model_conversion_failed)

    // ---- Model load/unload (§17.4: unified via BackendManager) ----
    fun loadModel(mId: String) {
        scope.launch {
            val result = backendManager.startDiffusion(mId, genWidth, genHeight, genUseOpenCL)
            result.onSuccess {
                selectedModelId = mId
                snackbarHostState.showSnackbar(context.getString(R.string.loading_model_label))
            }.onFailure { error ->
                snackbarHostState.showSnackbar(
                    context.getString(R.string.model_load_failed, error.message ?: "unknown")
                )
            }
        }
    }

    fun unloadModel() {
        scope.launch {
            backendManager.stop()
            selectedModelId = null
            snackbarHostState.showSnackbar(context.getString(R.string.model_unloaded))
        }
    }

    fun loadUpscaleModel(upscalerId: String) {
        scope.launch {
            val result = backendManager.startUpscaler(upscalerId)
            result.onSuccess {
                upscalerPreferences.edit {
                    putString("upscaler_standalone_selected_upscaler", upscalerId)
                }
            }.onFailure { error ->
                snackbarHostState.showSnackbar(
                    context.getString(R.string.model_load_failed, error.message ?: "unknown")
                )
            }
        }
    }

    fun unloadUpscaleModel() {
        scope.launch {
            backendManager.stop()
        }
    }

    var showNoModelWarning by remember { mutableStateOf(false) }
    val generationPreferences = remember { GenerationPreferences(context) }

    // Load screen-level (global) preferences once on startup
    LaunchedEffect(Unit) {
        genPrompt = generationPreferences.getGlobalPrompt()
        genNegativePrompt = generationPreferences.getGlobalNegativePrompt()
        genBatchCounts = generationPreferences.getGlobalBatchCounts().coerceAtLeast(1)
        genWidth = generationPreferences.getGlobalWidth().coerceIn(64, 4096)
        genHeight = generationPreferences.getGlobalHeight().coerceIn(64, 4096)
    }

    // Load preferences when model changes
    LaunchedEffect(selectedModelId) {
        if (selectedModelId != null) {
            val prefs = generationPreferences.getPreferences(selectedModelId!!)
            prefs.first().let { p ->
                if (genPrompt.isEmpty() && p.prompt.isNotEmpty()) genPrompt = p.prompt
                if (genNegativePrompt.isEmpty() && p.negativePrompt.isNotEmpty()) genNegativePrompt = p.negativePrompt
                if (genBatchCounts == 1 && p.batchCounts > 1) genBatchCounts = p.batchCounts
                if (p.steps > 0) genSteps = p.steps
                if (p.cfg > 0) genCfg = p.cfg
                if (p.seed.isNotEmpty()) genSeed = p.seed
                genScheduler = p.scheduler
                genDenoiseStrength = p.denoiseStrength
                genUseOpenCL = p.useOpenCL
            }
        }
    }

    // Dialog: no model warning
    if (showNoModelWarning) {
        AlertDialog(
            onDismissRequest = { showNoModelWarning = false },
            title = { Text(stringResource(R.string.no_model_loaded)) },
            text = { Text(stringResource(R.string.no_model_loaded_hint)) },
            confirmButton = {
                TextButton(onClick = { showNoModelWarning = false }) {
                    Text(stringResource(R.string.got_it))
                }
            },
        )
    }

    // Dialog: custom model import
    if (showCustomModelDialog) {
        CustomModelDialog(
            context,
            onDismiss = { showCustomModelDialog = false },
            onModelAdded = { modelName, fileUri, clipSkip, loraFiles ->
                showCustomModelDialog = false
                val modelId = modelName.replace(" ", "")
                addImportingModel(
                    ImportingModelState(
                        modelId = modelId,
                        modelName = modelName,
                        isNpu = false,
                        progressText = context.getString(R.string.preparing_model),
                        byteProgress = null,
                    ),
                )
                scope.launch {
                    convertCustomModel(
                        context = context,
                        modelName = modelName,
                        fileUri = fileUri,
                        clipSkip = clipSkip,
                        loraFiles = loraFiles,
                        onProgress = { progress ->
                            updateImportingModel(modelId) { existing ->
                                existing.copy(progressText = progress)
                            }
                        },
                        onStart = {},
                        onSuccess = {
                            removeImportingModel(modelId)
                            modelRepository.refreshAllModels()
                            modelRefreshVersion++
                            scope.launch {
                                snackbarHostState.showSnackbar(msgModelConversionSuccess)
                            }
                        },
                        onError = { error ->
                            removeImportingModel(modelId)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    msgModelConversionFailed.format(error)
                                )
                            }
                        },
                    )
                }
            },
        )
    }

    // Dialog: custom NPU model import
    if (showCustomNpuModelDialog) {
        CustomNpuModelDialog(
            context,
            onDismiss = { showCustomNpuModelDialog = false },
            onModelAdded = { modelName, zipUri ->
                showCustomNpuModelDialog = false
                val modelId = modelName.replace(" ", "")
                addImportingModel(
                    ImportingModelState(
                        modelId = modelId,
                        modelName = modelName,
                        isNpu = true,
                        progressText = context.getString(R.string.preparing_model),
                        byteProgress = null,
                    ),
                )
                scope.launch {
                    extractNpuModel(
                        context = context,
                        modelName = modelName,
                        zipUri = zipUri,
                        onProgress = { progress ->
                            updateImportingModel(modelId) { existing ->
                                existing.copy(progressText = progress)
                            }
                        },
                        onByteProgress = { extracted, total, fraction ->
                            updateImportingModel(modelId) { existing ->
                                existing.copy(byteProgress = ExtractByteProgress(extracted, total, fraction))
                            }
                        },
                        onStart = {},
                        onSuccess = {
                            removeImportingModel(modelId)
                            modelRepository.refreshAllModels()
                            modelRefreshVersion++
                            scope.launch {
                                snackbarHostState.showSnackbar(msgNpuModelAddedSuccess)
                            }
                        },
                        onError = { error ->
                            removeImportingModel(modelId)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    msgNpuModelAddFailed.format(error)
                                )
                            }
                        },
                    )
                }
            },
        )
    }

    // Dialog: custom upscale model import
    if (showCustomUpscaleModelDialog) {
        CustomUpscaleModelDialog(
            context = context,
            onDismiss = { showCustomUpscaleModelDialog = false },
            onModelAdded = { modelName, fileUri ->
                showCustomUpscaleModelDialog = false
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val modelId = modelName.replace(" ", "")
                            val modelDir = File(Model.getModelsDir(context), modelId)
                            if (modelDir.exists()) {
                                modelDir.deleteRecursively()
                            }
                            modelDir.mkdirs()

                            val inputStream = context.contentResolver.openInputStream(fileUri)
                                ?: throw Exception("Cannot open file")
                            val binFile = File(modelDir, "$modelId.bin")
                            inputStream.use { input ->
                                binFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }

                            File(modelDir, "upscaler_custom").createNewFile()
                        }
                        modelRefreshVersion++
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.upscale_file_selected)
                            )
                        }
                    } catch (e: Exception) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.error_download_failed, e.message ?: "")
                            )
                        }
                    }
                }
            },
        )
    }

    // Dialog: rename model
    if (showRenameDialog) {
        val renameModel = remember(selectedModelId) {
            modelRepository.models.find { it.id == selectedModelId }
        }
        val title = stringResource(R.string.rename_model)
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.custom_model_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newName = renameText.trim()
                        if (newName.isNotEmpty() && renameModel != null) {
                            val success = renameModel.renameModel(context, newName)
                            if (success) {
                                modelRepository.refreshAllModels()
                                modelRefreshVersion++
                                selectedModelId = newName.replace(" ", "")
                                val renameSuccessMsg = context.getString(R.string.rename_success)
                                scope.launch {
                                    snackbarHostState.showSnackbar(renameSuccessMsg)
                                }
                            } else {
                                val renameFailedMsg = context.getString(R.string.rename_failed, "directory error")
                                scope.launch {
                                    snackbarHostState.showSnackbar(renameFailedMsg)
                                }
                            }
                        }
                        showRenameDialog = false
                    },
                    enabled = renameText.trim().isNotEmpty(),
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Dialog: delete model confirmation
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_model)) },
            text = { Text(stringResource(R.string.delete_model_confirm_single)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        val delModel = modelRepository.models.find { it.id == selectedModelId }
                        if (delModel != null) {
                            if (isModelLoaded && selectedModelId == delModel.id) {
                                unloadModel()
                            }
                            scope.launch {
                                val success = delModel.deleteModel(context)
                                if (success) {
                                    if (selectedModelId == delModel.id) selectedModelId = null
                                    modelRepository.refreshAllModels()
                                    modelRefreshVersion++
                                    val deleteSuccessMsg = context.getString(R.string.delete_success)
                                    snackbarHostState.showSnackbar(deleteSuccessMsg)
                                } else {
                                    val deleteFailedMsg = context.getString(R.string.delete_failed)
                                    snackbarHostState.showSnackbar(deleteFailedMsg)
                                }
                            }
                        }
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ---- Drawer content ----
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 28.dp, top = 16.dp, end = 4.dp),
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
                AppSettingsDrawerContent(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                when (selectedTab) {
                    BottomTab.Models -> ModelsTopBar(
                        drawerState = drawerState,
                        selectedModelId = selectedModelId,
                        isModelLoaded = isModelLoaded,
                        isModelLoading = isModelLoading,
                        onLoadModel = { loadModel(it) },
                        onUnloadModel = { unloadModel() },
                        onImportModel = { showCustomModelDialog = true },
                        onImportNpuModel = { showCustomNpuModelDialog = true },
                        onImportUpscaleModel = { showCustomUpscaleModelDialog = true },
                        onRenameModel = {
                            val m = modelRepository.models.find { it.id == selectedModelId }
                            renameText = m?.name ?: selectedModelId ?: ""
                            showRenameDialog = true
                        },
                        onDeleteModel = { showDeleteConfirm = true },
                    )
                    BottomTab.Queue -> QueueTopBar(
                        drawerState = drawerState,
                        processingActive = queueProcessing,
                        onStop = { scope.launch { QueueController.stop(context) } },
                    )
                    BottomTab.Generate -> GenerateTopBar(
                        drawerState = drawerState,
                        modelId = selectedModelId,
                        isModelLoaded = isModelLoaded,
                    )
                    BottomTab.Upscale -> UpscaleTopBar(
                        drawerState = drawerState,
                        isUpscaleModelLoaded = isUpscaleModelLoaded,
                        upscalerId = selectedUpscalerId,
                    )
                    BottomTab.Browse -> BrowseTopBar(drawerState = drawerState)
                }
            },
            bottomBar = {
                NavigationBar {
                    BottomTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Icon(tab.icon, stringResource(tab.labelResId)) },
                            label = { Text(stringResource(tab.labelResId)) },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                when (selectedTab) {
                    BottomTab.Models -> ModelListTab(
                        selectedModelId = selectedModelId,
                        isModelLoaded = isModelLoaded,
                        onSelectModel = { selectedModelId = it },
                        onLoadModel = { loadModel(it) },
                        modelRepository = modelRepository,
                        refreshVersion = modelRefreshVersion,
                        importingModels = importingModels,
                        isUpscaleModelLoaded = isUpscaleModelLoaded,
                        onLoadUpscaleModel = { loadUpscaleModel(it) },
                        onUnloadUpscaleModel = { unloadUpscaleModel() },
                        persistedUpscalerId = persistedUpscalerId,
                        selectedUpscalerId = selectedUpscalerId,
                    )
                    BottomTab.Queue -> TabQueueScreen(
                        tasks = queueTasks,
                        batchGroups = queueBatchGroups,
                        processingActive = queueProcessing,
                        onRemoveTask = { queueRepository.removeTask(it) },
                        onRemoveBatch = { queueRepository.removeBatch(it) },
                        recordRepository = recordRepository,
                    )
                    BottomTab.Generate -> TabGenerateScreen(
                        modelId = if (isModelLoaded) selectedModelId else null,
                        prompt = genPrompt,
                        onPromptChange = { genPrompt = it },
                        negativePrompt = genNegativePrompt,
                        onNegativePromptChange = { genNegativePrompt = it },
                        steps = genSteps,
                        onStepsChange = { genSteps = it },
                        cfg = genCfg,
                        onCfgChange = { genCfg = it },
                        seed = genSeed,
                        onSeedChange = { genSeed = it },
                        batchCounts = genBatchCounts,
                        onBatchCountsChange = { genBatchCounts = it },
                        scheduler = genScheduler,
                        onSchedulerChange = { genScheduler = it },
                        denoiseStrength = genDenoiseStrength,
                        onDenoiseStrengthChange = { genDenoiseStrength = it },
                        useOpenCL = genUseOpenCL,
                        onUseOpenCLChange = { genUseOpenCL = it },
                        width = genWidth,
                        onWidthChange = { genWidth = it },
                        height = genHeight,
                        onHeightChange = { genHeight = it },
                        recordRepository = recordRepository,
                        onAddToQueue = { count ->
                            val modelId = selectedModelId ?: return@TabGenerateScreen
                            queueRepository.addBatch(
                                modelId = modelId,
                                prompt = genPrompt,
                                negativePrompt = genNegativePrompt,
                                steps = genSteps.roundToInt(),
                                cfg = genCfg,
                                seed = genSeed,
                                width = genWidth,
                                height = genHeight,
                                effectiveWidth = genWidth,
                                effectiveHeight = genHeight,
                                denoiseStrength = genDenoiseStrength,
                                useOpenCL = genUseOpenCL,
                                scheduler = genScheduler,
                                aspectRatio = inferAspectRatioString(genWidth, genHeight),
                                count = count.coerceAtLeast(1),
                            )
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.added_to_queue, count)
                                )
                            }
                        },
                    )
                    BottomTab.Upscale -> UpscaleScreen()
                    BottomTab.Browse -> BrowseScreen(recordRepository = recordRepository)
                }
            }
        }
    }
}
