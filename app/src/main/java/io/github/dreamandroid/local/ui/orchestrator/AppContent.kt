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
import io.github.dreamandroid.local.ui.orchestrator.state.AppContentState
import io.github.dreamandroid.local.ui.queue.TabQueueScreen
import io.github.dreamandroid.local.ui.screens.*
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }

    // ---- State holder (all mutable UI state) ----
    val state = remember { AppContentState() }

    // §17.3: Unified backend lifecycle via BackendManager (single source of truth)
    val app = context.applicationContext as DreamAndroidApplication
    val backendManager = app.backendManager
    val backendState by backendManager.state.collectAsState()
    val isModelLoaded = backendState.isDiffusionLoaded()
    val isModelLoading = backendState.isDiffusionLoading()
    val isUpscaleModelLoaded = backendState.isUpscalerLoaded()
    val selectedUpscalerId = backendState.activeUpscalerId()
    val modelRepository = remember { ModelRepository(context) }

    val model = remember(state.selectedModelId) { modelRepository.models.find { it.id == state.selectedModelId } }

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

    // ---- Upscale model state ----
    state.upscalerPreferences = context.getSharedPreferences("upscaler_prefs", Context.MODE_PRIVATE)
    val persistedUpscalerId = remember {
        state.upscalerPreferences?.getString("upscaler_standalone_selected_upscaler", null)
    }

    val msgNpuModelAddedSuccess = stringResource(R.string.npu_model_added_success)
    val msgNpuModelAddFailed = stringResource(R.string.npu_model_add_failed)
    val msgModelConversionSuccess = stringResource(R.string.model_conversion_success)
    val msgModelConversionFailed = stringResource(R.string.model_conversion_failed)

    // ---- Model load/unload (§17.4: unified via BackendManager) ----
    fun loadModel(mId: String) {
        scope.launch {
            val result = backendManager.startDiffusion(mId, state.genWidth, state.genHeight, state.genUseOpenCL)
            result.onSuccess {
                state.selectedModelId = mId
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
            state.selectedModelId = null
            snackbarHostState.showSnackbar(context.getString(R.string.model_unloaded))
        }
    }

    fun loadUpscaleModel(upscalerId: String) {
        scope.launch {
            val result = backendManager.startUpscaler(upscalerId)
            result.onSuccess {
                state.upscalerPreferences?.edit {
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

    val generationPreferences = remember { GenerationPreferences(context) }

    // Load screen-level (global) preferences once on startup
    LaunchedEffect(Unit) {
        state.loadGlobalPrefs(generationPreferences)
    }

    // Load preferences when model changes
    LaunchedEffect(state.selectedModelId) {
        if (state.selectedModelId != null) {
            state.loadModelPrefs(state.selectedModelId!!, generationPreferences)
        }
    }

    // Dialog: no model warning
    if (state.showNoModelWarning) {
        AlertDialog(
            onDismissRequest = { state.showNoModelWarning = false },
            title = { Text(stringResource(R.string.no_model_loaded)) },
            text = { Text(stringResource(R.string.no_model_loaded_hint)) },
            confirmButton = {
                TextButton(onClick = { state.showNoModelWarning = false }) {
                    Text(stringResource(R.string.got_it))
                }
            },
        )
    }

    // Dialog: custom model import
    if (state.showCustomModelDialog) {
        CustomModelDialog(
            context,
            onDismiss = { state.showCustomModelDialog = false },
            onModelAdded = { modelName, fileUri, clipSkip, loraFiles ->
                state.showCustomModelDialog = false
                val modelId = modelName.replace(" ", "")
                state.addImportingModel(
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
                            state.updateImportingModel(modelId) { existing ->
                                existing.copy(progressText = progress)
                            }
                        },
                        onStart = {},
                        onSuccess = {
                            state.removeImportingModel(modelId)
                            modelRepository.refreshAllModels()
                            state.modelRefreshVersion++
                            scope.launch {
                                snackbarHostState.showSnackbar(msgModelConversionSuccess)
                            }
                        },
                        onError = { error ->
                            state.removeImportingModel(modelId)
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
    if (state.showCustomNpuModelDialog) {
        CustomNpuModelDialog(
            context,
            onDismiss = { state.showCustomNpuModelDialog = false },
            onModelAdded = { modelName, zipUri ->
                state.showCustomNpuModelDialog = false
                val modelId = modelName.replace(" ", "")
                state.addImportingModel(
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
                            state.updateImportingModel(modelId) { existing ->
                                existing.copy(progressText = progress)
                            }
                        },
                        onByteProgress = { extracted, total, fraction ->
                            state.updateImportingModel(modelId) { existing ->
                                existing.copy(byteProgress = ExtractByteProgress(extracted, total, fraction))
                            }
                        },
                        onStart = {},
                        onSuccess = {
                            state.removeImportingModel(modelId)
                            modelRepository.refreshAllModels()
                            state.modelRefreshVersion++
                            scope.launch {
                                snackbarHostState.showSnackbar(msgNpuModelAddedSuccess)
                            }
                        },
                        onError = { error ->
                            state.removeImportingModel(modelId)
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
    if (state.showCustomUpscaleModelDialog) {
        CustomUpscaleModelDialog(
            context = context,
            onDismiss = { state.showCustomUpscaleModelDialog = false },
            onModelAdded = { modelName, fileUri ->
                state.showCustomUpscaleModelDialog = false
                scope.launch {
                    try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
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
                        state.modelRefreshVersion++
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
    if (state.showRenameDialog) {
        val renameModel = remember(state.selectedModelId) {
            modelRepository.models.find { it.id == state.selectedModelId }
        }
        val title = stringResource(R.string.rename_model)
        AlertDialog(
            onDismissRequest = { state.showRenameDialog = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = state.renameText,
                    onValueChange = { state.renameText = it },
                    label = { Text(stringResource(R.string.custom_model_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newName = state.renameText.trim()
                        if (newName.isNotEmpty() && renameModel != null) {
                            val success = renameModel.renameModel(context, newName)
                            if (success) {
                                modelRepository.refreshAllModels()
                                state.modelRefreshVersion++
                                state.selectedModelId = newName.replace(" ", "")
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
                        state.showRenameDialog = false
                    },
                    enabled = state.renameText.trim().isNotEmpty(),
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { state.showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Dialog: delete model confirmation
    if (state.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { state.showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_model)) },
            text = { Text(stringResource(R.string.delete_model_confirm_single)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.showDeleteConfirm = false
                        val delModel = modelRepository.models.find { it.id == state.selectedModelId }
                        if (delModel != null) {
                            if (isModelLoaded && state.selectedModelId == delModel.id) {
                                unloadModel()
                            }
                            scope.launch {
                                val success = delModel.deleteModel(context)
                                if (success) {
                                    if (state.selectedModelId == delModel.id) state.selectedModelId = null
                                    modelRepository.refreshAllModels()
                                    state.modelRefreshVersion++
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
                TextButton(onClick = { state.showDeleteConfirm = false }) {
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
                when (state.selectedTab) {
                    BottomTab.Models -> ModelsTopBar(
                        drawerState = drawerState,
                        selectedModelId = state.selectedModelId,
                        isModelLoaded = isModelLoaded,
                        isModelLoading = isModelLoading,
                        onLoadModel = { loadModel(it) },
                        onUnloadModel = { unloadModel() },
                        onImportModel = { state.showCustomModelDialog = true },
                        onImportNpuModel = { state.showCustomNpuModelDialog = true },
                        onImportUpscaleModel = { state.showCustomUpscaleModelDialog = true },
                        onRenameModel = {
                            val m = modelRepository.models.find { it.id == state.selectedModelId }
                            state.renameText = m?.name ?: (state.selectedModelId ?: "")
                            state.showRenameDialog = true
                        },
                        onDeleteModel = { state.showDeleteConfirm = true },
                    )
                    BottomTab.Queue -> QueueTopBar(
                        drawerState = drawerState,
                        processingActive = queueProcessing,
                        onStop = { scope.launch { QueueController.stop(context) } },
                    )
                    BottomTab.Generate -> GenerateTopBar(
                        drawerState = drawerState,
                        modelId = state.selectedModelId,
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
                            selected = state.selectedTab == tab,
                            onClick = { state.selectedTab = tab },
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
                when (state.selectedTab) {
                    BottomTab.Models -> ModelListTab(
                        selectedModelId = state.selectedModelId,
                        isModelLoaded = isModelLoaded,
                        onSelectModel = { state.selectedModelId = it },
                        onLoadModel = { loadModel(it) },
                        modelRepository = modelRepository,
                        refreshVersion = state.modelRefreshVersion,
                        importingModels = state.importingModels,
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
                        modelId = if (isModelLoaded) state.selectedModelId else null,
                        prompt = state.genPrompt,
                        onPromptChange = { state.genPrompt = it },
                        negativePrompt = state.genNegativePrompt,
                        onNegativePromptChange = { state.genNegativePrompt = it },
                        steps = state.genSteps,
                        onStepsChange = { state.genSteps = it },
                        cfg = state.genCfg,
                        onCfgChange = { state.genCfg = it },
                        seed = state.genSeed,
                        onSeedChange = { state.genSeed = it },
                        batchCounts = state.genBatchCounts,
                        onBatchCountsChange = { state.genBatchCounts = it },
                        scheduler = state.genScheduler,
                        onSchedulerChange = { state.genScheduler = it },
                        denoiseStrength = state.genDenoiseStrength,
                        onDenoiseStrengthChange = { state.genDenoiseStrength = it },
                        useOpenCL = state.genUseOpenCL,
                        onUseOpenCLChange = { state.genUseOpenCL = it },
                        width = state.genWidth,
                        onWidthChange = { state.genWidth = it },
                        height = state.genHeight,
                        onHeightChange = { state.genHeight = it },
                        recordRepository = recordRepository,
                        onAddToQueue = { count ->
                            val modelId = state.selectedModelId ?: return@TabGenerateScreen
                            queueRepository.addBatch(
                                modelId = modelId,
                                prompt = state.genPrompt,
                                negativePrompt = state.genNegativePrompt,
                                steps = state.genSteps.roundToInt(),
                                cfg = state.genCfg,
                                seed = state.genSeed,
                                width = state.genWidth,
                                height = state.genHeight,
                                effectiveWidth = state.genWidth,
                                effectiveHeight = state.genHeight,
                                denoiseStrength = state.genDenoiseStrength,
                                useOpenCL = state.genUseOpenCL,
                                scheduler = state.genScheduler,
                                aspectRatio = inferAspectRatioString(state.genWidth, state.genHeight),
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
