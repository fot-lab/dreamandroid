package io.github.dreamandroid.local.ui.orchestrator

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dreamandroid.local.DreamAndroidApplication
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.*
import io.github.dreamandroid.local.navigation.BottomTab
import io.github.dreamandroid.local.service.QueueRepository
import io.github.dreamandroid.local.ui.backend.*
import io.github.dreamandroid.local.ui.frontend.*
import io.github.dreamandroid.local.ui.queue.TabQueueScreen
import io.github.dreamandroid.local.ui.screens.*
import io.github.dreamandroid.local.ui.viewmodel.GenerateViewModel
import io.github.dreamandroid.local.ui.viewmodel.MainViewModel
import io.github.dreamandroid.local.ui.viewmodel.ModelsViewModel
import io.github.dreamandroid.local.ui.viewmodel.QueueViewModel
import kotlinx.coroutines.launch

/**
 * Thin orchestrator Composable — state and business logic has been extracted to ViewModels
 * (Phase D: UILA-COMP-0001 God Object refactoring).
 *
 * AppContent now only handles:
 * - ViewModel instantiation
 * - UI feedback (snackbar) orchestration
 * - Drawer + Scaffold structure
 * - Top/Navigation bar wiring
 * - Content routing to screen Composables
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }

    // ── ViewModels (Activity-scoped, shared across tabs) ──
    val mainViewModel: MainViewModel = viewModel()
    val modelsViewModel: ModelsViewModel = viewModel()
    val generateViewModel: GenerateViewModel = viewModel()
    val queueViewModel: QueueViewModel = viewModel()

    // ── Application-level dependencies ──
    val app = context.applicationContext as DreamAndroidApplication
    val backendState by modelsViewModel.backendManager.state.collectAsState()
    val isModelLoaded = backendState.isDiffusionLoaded()
    val isModelLoading = backendState.isDiffusionLoading()
    val isUpscaleModelLoaded = backendState.isUpscalerLoaded()
    val selectedUpscalerId = backendState.activeUpscalerId()
    val generationPreferences = remember { GenerationPreferences(context) }

    // ── Queue state (from QueueViewModel) ──
    val queueTasks by queueViewModel.queueRepository.tasks.collectAsState()
    val queueProcessing by queueViewModel.queueRepository.processingActive.collectAsState()
    val queueBatchGroups = remember(queueTasks) { queueViewModel.queueRepository.getBatchGroups() }

    // ── Upscaler preferences ──
    val persistedUpscalerId = remember {
        modelsViewModel.upscalerPreferences?.getString("upscaler_standalone_selected_upscaler", null)
    }
    LaunchedEffect(Unit) {
        modelsViewModel.upscalerPreferences = context.getSharedPreferences("upscaler_prefs", Context.MODE_PRIVATE)
    }

    // ── Load global preferences once on startup ──
    LaunchedEffect(Unit) {
        generateViewModel.loadGlobalPrefs(generationPreferences)
    }

    // ── Load model-specific preferences when model changes ──
    LaunchedEffect(modelsViewModel.selectedModelId) {
        modelsViewModel.selectedModelId?.let { modelId ->
            generateViewModel.loadModelPrefs(modelId, generationPreferences)
        }
    }

    // ── String resources ──
    val msgNpuModelAddedSuccess = stringResource(R.string.npu_model_added_success)
    val msgNpuModelAddFailed = stringResource(R.string.npu_model_add_failed)
    val msgModelConversionSuccess = stringResource(R.string.model_conversion_success)
    val msgModelConversionFailed = stringResource(R.string.model_conversion_failed)

    // ── Model load/unload callbacks (ViewModel + UI feedback) ──
    fun loadModel(mId: String) {
        scope.launch {
            val result = modelsViewModel.loadModel(
                mId, generateViewModel.genWidth, generateViewModel.genHeight, generateViewModel.genUseOpenCL,
            )
            result.onSuccess {
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
            modelsViewModel.unloadModel()
            snackbarHostState.showSnackbar(context.getString(R.string.model_unloaded))
        }
    }

    fun loadUpscaleModel(upscalerId: String) {
        scope.launch {
            modelsViewModel.loadUpscaleModel(upscalerId).onFailure { error ->
                snackbarHostState.showSnackbar(
                    context.getString(R.string.model_load_failed, error.message ?: "unknown")
                )
            }
        }
    }

    fun unloadUpscaleModel() {
        scope.launch { modelsViewModel.unloadUpscaleModel() }
    }

    // ── Dialogs ──

    // No model warning
    if (mainViewModel.showNoModelWarning) {
        AlertDialog(
            onDismissRequest = { mainViewModel.showNoModelWarning = false },
            title = { Text(stringResource(R.string.no_model_loaded)) },
            text = { Text(stringResource(R.string.no_model_loaded_hint)) },
            confirmButton = {
                TextButton(onClick = { mainViewModel.showNoModelWarning = false }) {
                    Text(stringResource(R.string.got_it))
                }
            },
        )
    }

    // Custom model import dialog
    if (modelsViewModel.showCustomModelDialog) {
        CustomModelDialog(
            context,
            onDismiss = { modelsViewModel.showCustomModelDialog = false },
            onModelAdded = { modelName, fileUri, clipSkip, loraFiles ->
                modelsViewModel.showCustomModelDialog = false
                scope.launch {
                    modelsViewModel.importCustomModel(context, modelName, fileUri, clipSkip, loraFiles)
                    snackbarHostState.showSnackbar(msgModelConversionSuccess)
                }
            },
        )
    }

    // Custom NPU model import dialog
    if (modelsViewModel.showCustomNpuModelDialog) {
        CustomNpuModelDialog(
            context,
            onDismiss = { modelsViewModel.showCustomNpuModelDialog = false },
            onModelAdded = { modelName, zipUri ->
                modelsViewModel.showCustomNpuModelDialog = false
                scope.launch {
                    modelsViewModel.importNpuModel(context, modelName, zipUri)
                    snackbarHostState.showSnackbar(msgNpuModelAddedSuccess)
                }
            },
        )
    }

    // Custom upscale model import dialog
    if (modelsViewModel.showCustomUpscaleModelDialog) {
        CustomUpscaleModelDialog(
            context = context,
            onDismiss = { modelsViewModel.showCustomUpscaleModelDialog = false },
            onModelAdded = { modelName, fileUri ->
                modelsViewModel.showCustomUpscaleModelDialog = false
                scope.launch {
                    val success = modelsViewModel.importUpscaleModel(context, modelName, fileUri)
                    snackbarHostState.showSnackbar(
                        if (success) context.getString(R.string.upscale_file_selected)
                        else context.getString(R.string.error_download_failed, "")
                    )
                }
            },
        )
    }

    // Rename model dialog
    if (modelsViewModel.showRenameDialog) {
        val renameModel = remember(modelsViewModel.selectedModelId) {
            modelsViewModel.modelRepository.models.find { it.id == modelsViewModel.selectedModelId }
        }
        AlertDialog(
            onDismissRequest = { modelsViewModel.showRenameDialog = false },
            title = { Text(stringResource(R.string.rename_model)) },
            text = {
                OutlinedTextField(
                    value = modelsViewModel.renameText,
                    onValueChange = { modelsViewModel.renameText = it },
                    label = { Text(stringResource(R.string.custom_model_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val newName = modelsViewModel.renameText.trim()
                            val success = modelsViewModel.renameModel(context, newName)
                            snackbarHostState.showSnackbar(
                                if (success) context.getString(R.string.rename_success)
                                else context.getString(R.string.rename_failed, "directory error")
                            )
                        }
                    },
                    enabled = modelsViewModel.renameText.trim().isNotEmpty(),
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { modelsViewModel.showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Delete model confirmation dialog
    if (modelsViewModel.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { modelsViewModel.showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_model)) },
            text = { Text(stringResource(R.string.delete_model_confirm_single)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val success = modelsViewModel.deleteModel(context, isModelLoaded)
                            snackbarHostState.showSnackbar(
                                if (success) context.getString(R.string.delete_success)
                                else context.getString(R.string.delete_failed)
                            )
                        }
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { modelsViewModel.showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── Drawer + Scaffold ──
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
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
                AppSettingsDrawerContent(
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                when (mainViewModel.selectedTab) {
                    BottomTab.Models -> ModelsTopBar(
                        drawerState = drawerState,
                        selectedModelId = modelsViewModel.selectedModelId,
                        isModelLoaded = isModelLoaded,
                        isModelLoading = isModelLoading,
                        onLoadModel = { loadModel(it) },
                        onUnloadModel = { unloadModel() },
                        onImportModel = { modelsViewModel.showCustomModelDialog = true },
                        onImportNpuModel = { modelsViewModel.showCustomNpuModelDialog = true },
                        onImportUpscaleModel = { modelsViewModel.showCustomUpscaleModelDialog = true },
                        onRenameModel = { modelsViewModel.prepareRename() },
                        onDeleteModel = { modelsViewModel.showDeleteConfirm = true },
                    )
                    BottomTab.Queue -> QueueTopBar(
                        drawerState = drawerState,
                        processingActive = queueProcessing,
                        onStop = { queueViewModel.stop(context) },
                    )
                    BottomTab.Generate -> GenerateTopBar(
                        drawerState = drawerState,
                        modelId = modelsViewModel.selectedModelId,
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
                            selected = mainViewModel.selectedTab == tab,
                            onClick = { mainViewModel.selectedTab = tab },
                            icon = { Icon(tab.icon, stringResource(tab.labelResId)) },
                            label = { Text(stringResource(tab.labelResId)) },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
            ) {
                when (mainViewModel.selectedTab) {
                    BottomTab.Models -> ModelListTab(
                        selectedModelId = modelsViewModel.selectedModelId,
                        isModelLoaded = isModelLoaded,
                        onSelectModel = { modelsViewModel.selectedModelId = it },
                        onLoadModel = { loadModel(it) },
                        modelRepository = modelsViewModel.modelRepository,
                        refreshVersion = modelsViewModel.modelRefreshVersion,
                        importingModels = modelsViewModel.importingModels,
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
                        onRemoveTask = { queueViewModel.removeTask(it) },
                        onRemoveBatch = { queueViewModel.removeBatch(it) },
                        recordRepository = queueViewModel.recordRepository,
                    )
                    BottomTab.Generate -> TabGenerateScreen(
                        modelId = if (isModelLoaded) modelsViewModel.selectedModelId else null,
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
                        scheduler = generateViewModel.genScheduler,
                        onSchedulerChange = { generateViewModel.genScheduler = it },
                        denoiseStrength = generateViewModel.genDenoiseStrength,
                        onDenoiseStrengthChange = { generateViewModel.genDenoiseStrength = it },
                        useOpenCL = generateViewModel.genUseOpenCL,
                        onUseOpenCLChange = { generateViewModel.genUseOpenCL = it },
                        width = generateViewModel.genWidth,
                        onWidthChange = { generateViewModel.genWidth = it },
                        height = generateViewModel.genHeight,
                        onHeightChange = { generateViewModel.genHeight = it },
                        recordRepository = queueViewModel.recordRepository,
                        onAddToQueue = { count ->
                            val modelId = modelsViewModel.selectedModelId ?: return@TabGenerateScreen
                            generateViewModel.addToQueue(modelId, count, queueViewModel.queueRepository)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.added_to_queue, count)
                                )
                            }
                        },
                    )
                    BottomTab.Upscale -> UpscaleScreen()
                    BottomTab.Browse -> BrowseScreen(recordRepository = queueViewModel.recordRepository)
                }
            }
        }
    }
}
