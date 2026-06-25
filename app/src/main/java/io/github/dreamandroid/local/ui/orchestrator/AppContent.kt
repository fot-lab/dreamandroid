package io.github.dreamandroid.local.ui.orchestrator

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import io.github.dreamandroid.local.ui.screens.model.CustomModelDialog
import io.github.dreamandroid.local.ui.screens.model.CustomNpuModelDialog
import io.github.dreamandroid.local.ui.screens.model.CustomUpscaleModelDialog
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
    val recordRepository = remember { RecordRepository(context) }

    // ── ViewModels (Activity-scoped, shared across tabs) ──
    val mainViewModel: MainViewModel = viewModel()
    val modelsViewModel: ModelsViewModel = viewModel()
    val generateViewModel: GenerateViewModel = viewModel()
    val queueViewModel: QueueViewModel = viewModel()

    // ── Application-level dependencies ──
    val app = context.applicationContext as DreamAndroidApplication
    val backendState by modelsViewModel.backendService.state.collectAsState()
    val isModelLoaded = backendState.isDiffusionLoaded()
    val isModelLoading = backendState.isDiffusionLoading()
    val isUpscaleModelLoaded = backendState.isUpscalerLoaded()
    val selectedUpscalerId = backendState.activeUpscalerId()
    val generationPreferences = remember { GenerationPreferences(context) }

    // ── Queue state (from QueueViewModel) ──
    val queueTasks by queueViewModel.queueRepository.tasks.collectAsState()
    val queueProcessing by queueViewModel.queueRepository.processingActive.collectAsState()
    val queueBatchGroups = remember(queueTasks) { queueViewModel.queueRepository.getBatchGroups() }
    val generationTimedOut by queueViewModel.queueRepository.generationTimedOut.collectAsState()
    val queuePaused by queueViewModel.queueRepository.queuePaused.collectAsState()
    val queueHasPending = remember(queueTasks) {
        queueTasks.any { it.status == TaskStatus.PENDING }
    }

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
                        queuePaused = queuePaused,
                        hasPendingTasks = queueHasPending,
                        onStop = { queueViewModel.stop(context) },
                        onResume = { queueViewModel.resume(context) },
                    )
                    BottomTab.Generate -> GenerateTopBar(
                        drawerState = drawerState,
                        modelId = modelsViewModel.selectedModelId,
                        isModelLoaded = isModelLoaded,
                        onGenTaskParamReset = {
                            generateViewModel.genSteps = 20f
                            generateViewModel.genCfg = 7f
                            generateViewModel.genSeed = ""
                            generateViewModel.genBatchCounts = 1
                            generateViewModel.genSampler = "dpm"
                            generateViewModel.genDenoiseCurve = "scaled_linear"
                            generateViewModel.genDenoiseStrength = 0.6f
                            val repo = ModelRepository(context)
                            val m = modelsViewModel.selectedModelId?.let { id -> repo.models.find { it.id == id } }
                            generateViewModel.genPrompt = m?.defaultPrompt ?: ""
                            generateViewModel.genNegativePrompt = m?.defaultNegativePrompt ?: ""
                            // Persist via GenerationPreferences
                            val prefs = GenerationPreferences(context)
                            kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
                                prefs.saveGlobalFields(
                                    prompt = generateViewModel.genPrompt,
                                    negativePrompt = generateViewModel.genNegativePrompt,
                                    batchCounts = generateViewModel.genBatchCounts,
                                    width = generateViewModel.genWidth,
                                    height = generateViewModel.genHeight,
                                )
                                modelsViewModel.selectedModelId?.let { modelId ->
                                    prefs.saveAllFields(
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
                                        aspectRatio = io.github.dreamandroid.local.ui.screens.run.inferAspectRatioString(
                                            generateViewModel.genWidth,
                                            generateViewModel.genHeight,
                                        ),
                                    )
                                }
                            }
                        },
                        onGenTaskAddToQueue = {
                            val mid = modelsViewModel.selectedModelId ?: return@GenerateTopBar
                            val count = if (generateViewModel.genSeed.isNotBlank()) 1
                            else generateViewModel.genBatchCounts.coerceAtLeast(1)
                            generateViewModel.addToQueue(mid, count, queueViewModel.queueRepository)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.added_to_queue, count)
                                )
                            }
                        },
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
                // Red flash animation for Models tab when generation timed out
                val flashAlpha by rememberInfiniteTransition(label = "flash").animateFloat(
                    initialValue = 1f,
                    targetValue = 0.3f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(500, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "flashAlpha",
                )
                NavigationBar {
                    BottomTab.entries.forEach { tab ->
                        val isModelsTab = tab == BottomTab.Models
                        val shouldFlash = isModelsTab && generationTimedOut
                        NavigationBarItem(
                            selected = mainViewModel.selectedTab == tab,
                            onClick = { mainViewModel.selectedTab = tab },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = stringResource(tab.labelResId),
                                    tint = if (shouldFlash)
                                        MaterialTheme.colorScheme.error.copy(alpha = flashAlpha)
                                    else
                                        LocalContentColor.current,
                                )
                            },
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
                        recordRepository = recordRepository,
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
                        recordRepository = recordRepository,
                        // Tokenize via ViewModel → BackendService (HTTP middleware, no direct BackendManager)
                        onTokenizePrompt = { prompt ->
                            generateViewModel.tokenizePrompt(prompt)
                        },
                        onTokenizeNegativePrompt = { negativePrompt ->
                            generateViewModel.tokenizeNegativePrompt(negativePrompt)
                        },
                        promptTokenCount = generateViewModel.promptTokenCount,
                        promptTokenMax = generateViewModel.promptTokenMax,
                        promptOverflowOffset = generateViewModel.promptOverflowOffset,
                        negativePromptTokenCount = generateViewModel.negativePromptTokenCount,
                        negativePromptTokenMax = generateViewModel.negativePromptTokenMax,
                        negativePromptOverflowOffset = generateViewModel.negativePromptOverflowOffset,
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
                    BottomTab.Browse -> BrowseScreen(recordRepository = recordRepository)
                }
            }
        }
    }
}
