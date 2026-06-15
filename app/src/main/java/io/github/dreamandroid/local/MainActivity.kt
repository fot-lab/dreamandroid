package io.github.dreamandroid.local

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.io.File
import io.github.dreamandroid.local.data.*
import io.github.dreamandroid.local.navigation.BottomTab
import io.github.dreamandroid.local.service.ModelDownloadService
import io.github.dreamandroid.local.service.QueueRepository
import io.github.dreamandroid.local.service.backend.BackendManager
import io.github.dreamandroid.local.service.queue.QueueController
import io.github.dreamandroid.local.ui.screens.*
import io.github.dreamandroid.local.ui.theme.DreamHubTheme
import io.github.dreamandroid.local.ui.theme.LocalThemeController
import io.github.dreamandroid.local.ui.theme.rememberThemeController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(
                this,
                "Storage permission is required for saving generated images",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(
                this,
                "Notification permission is required for background image generation",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) == PackageManager.PERMISSION_GRANTED -> return
                shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE) -> {
                    Toast.makeText(this, "Storage permission is needed", Toast.LENGTH_LONG).show()
                    requestStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                else -> requestStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED -> return
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkStoragePermission()
        checkNotificationPermission()

        val app = application as DreamAndroidApplication

        setContent {
            val themeController = rememberThemeController()
            CompositionLocalProvider(LocalThemeController provides themeController) {
                DreamHubTheme(themeController.state) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        val migrationState by app.migrationState.collectAsState()
                        when (migrationState) {
                            is MigrationState.Done, is MigrationState.NotNeeded -> AppContent()
                            is MigrationState.Idle, is MigrationState.InProgress, is MigrationState.Failed ->
                                MigrationScreen(
                                    state = migrationState,
                                    onRetry = { app.retryMigration() },
                                    onSkip = { app.skipMigration() },
                                )
                        }
                    }
                }
            }
        }
    }
}

// =========== Importing model state ===========

data class ImportingModelState(
    val modelId: String,
    val modelName: String,
    val isNpu: Boolean,
    val progressText: String,
    val byteProgress: ExtractByteProgress?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppContent() {
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
    val isModelLoaded = backendState is BackendManager.State.Running
        && (backendState as BackendManager.State.Running).mode == BackendManager.Mode.Diffusion
    val isModelLoading = backendState is BackendManager.State.Starting
        && (backendState as BackendManager.State.Starting).mode == BackendManager.Mode.Diffusion
    val isUpscaleModelLoaded = backendState is BackendManager.State.Running
        && (backendState as BackendManager.State.Running).mode == BackendManager.Mode.Upscaler
    val modelRepository = remember { ModelRepository(context) }
    var modelRefreshVersion by remember { mutableIntStateOf(0) }

    // ---- Generation parameters (shared between top bar and screen) ----
    val model = remember(selectedModelId) { modelRepository.models.find { it.id == selectedModelId } }
    // prompt / negative prompt / batchCounts: screen-level state, NOT reset on model switch
    var genPrompt by remember { mutableStateOf("") }
    var genNegativePrompt by remember { mutableStateOf("") }
    var genSteps by remember { mutableFloatStateOf(20f) }
    var genCfg by remember { mutableFloatStateOf(7f) }
    var genSeed by remember { mutableStateOf("") }
    var genBatchCounts by remember { mutableIntStateOf(1) }
    var genScheduler by remember { mutableStateOf("dpm") }
    var genDenoiseStrength by remember { mutableFloatStateOf(0.6f) }
    var genUseOpenCL by remember { mutableStateOf(false) }
    // Aspect ratio is screen-level — persists across model switches.
    // Width/Height are also screen-level, set directly by user.
    var genWidth by remember { mutableIntStateOf(512) }
    var genHeight by remember { mutableIntStateOf(512) }

    // ---- Queue repository (process-wide singleton, shared with WorkManager Worker) ----
    val queueRepository = remember { QueueRepository.getInstance(context) }
    val recordRepository = remember { RecordRepository(context) }
    val queueTasks by queueRepository.tasks.collectAsState()
    val queueProcessing by queueRepository.processingActive.collectAsState()
    val queueBatchGroups = remember(queueTasks) { queueRepository.getBatchGroups() }

    // ---- WorkManager queue control ----
    // Observe WorkInfo for diagnostics only.
    // processingActive is the single source of truth, managed exclusively
    // by GenerationWorker (see §16.B.3 in PrdReqDoc.md).
    LaunchedEffect(Unit) {
        QueueController.observeState(context).collect { info ->
            Log.d("MainActivity", "WorkInfo state: ${QueueController.stateLabel(info)}")
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

    // ---- Upscale model state (§17.4: derived from unified backendManager.state) ----
    val selectedUpscalerId = (backendState as? BackendManager.State.Running)
        ?.takeIf { it.mode == BackendManager.Mode.Upscaler }?.modelId
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
                // Screen-level (global) values take priority;
                // fall back to model-specific saved values when global is empty/default.
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

                            // Copy the .bin file
                            val inputStream = context.contentResolver.openInputStream(fileUri)
                                ?: throw Exception("Cannot open file")
                            val binFile = File(modelDir, "$modelId.bin")
                            inputStream.use { input ->
                                binFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }

                            // Create marker file
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
        val model = remember(selectedModelId) {
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
                        if (newName.isNotEmpty() && model != null) {
                            val success = model.renameModel(context, newName)
                            if (success) {
                                modelRepository.refreshAllModels()
                                modelRefreshVersion++
                                // Update selectedModelId to the new ID
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
                        val model = modelRepository.models.find { it.id == selectedModelId }
                        if (model != null) {
                            // Unload if this model is currently loaded
                            if (isModelLoaded && selectedModelId == model.id) {
                                unloadModel()
                            }
                            scope.launch {
                                val success = model.deleteModel(context)
                                if (success) {
                                    if (selectedModelId == model.id) selectedModelId = null
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
                            val model = modelRepository.models.find { it.id == selectedModelId }
                            renameText = model?.name ?: selectedModelId ?: ""
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

// =========== Top App Bars ===========

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelsTopBar(
    drawerState: DrawerState,
    selectedModelId: String?,
    isModelLoaded: Boolean,
    isModelLoading: Boolean,
    onLoadModel: (String) -> Unit,
    onUnloadModel: () -> Unit,
    onImportModel: () -> Unit = {},
    onImportNpuModel: () -> Unit = {},
    onImportUpscaleModel: () -> Unit = {},
    onRenameModel: () -> Unit = {},
    onDeleteModel: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var showImportMenu by remember { mutableStateOf(false) }
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                Icon(Icons.Default.Menu, stringResource(R.string.settings))
            }
        },
        actions = {
            // Stop model service: always visible when a model is loaded,
            // regardless of whether a model card is selected.
            if (isModelLoaded) {
                if (isModelLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .size(24.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    TextButton(onClick = onUnloadModel) {
                        Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.unload_model))
                    }
                }
            }

            // Start model service: requires a selected model
            if (selectedModelId != null && !isModelLoaded && !isModelLoading) {
                TextButton(onClick = { onLoadModel(selectedModelId) }) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.load_model))
                }
            }

            // Rename (pencil) and Delete (trash) — require a selected model
            if (selectedModelId != null) {
                IconButton(onClick = onDeleteModel) {
                    Icon(Icons.Default.Delete, stringResource(R.string.delete_model))
                }
                IconButton(onClick = onRenameModel) {
                    Icon(Icons.Default.Edit, stringResource(R.string.rename_model))
                }
            }

            Box {
                IconButton(onClick = { showImportMenu = true }) {
                    Icon(Icons.Default.Add, stringResource(R.string.import_model))
                }
                DropdownMenu(
                    expanded = showImportMenu,
                    onDismissRequest = { showImportMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.import_model)) },
                        onClick = {
                            showImportMenu = false
                            onImportModel()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Memory, contentDescription = null)
                        },
                    )
                    if (Model.isQualcommDevice()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.import_npu_model)) },
                            onClick = {
                                showImportMenu = false
                                onImportNpuModel()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Speed, contentDescription = null)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.import_upscale_model)) },
                        onClick = {
                            showImportMenu = false
                            onImportUpscaleModel()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Hd, contentDescription = null)
                        },
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueTopBar(
    drawerState: DrawerState,
    processingActive: Boolean = false,
    onStop: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    TopAppBar(
        title = {
            Text(
                text = "Queue",
                maxLines = 1,
            )
        },
        navigationIcon = {
            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                Icon(Icons.Default.Menu, stringResource(R.string.settings))
            }
        },
        actions = {
            if (processingActive) {
                IconButton(onClick = onStop) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop queue",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenerateTopBar(
    drawerState: DrawerState,
    modelId: String?,
    isModelLoaded: Boolean,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val modelRepository = remember { ModelRepository(context) }
    val model = remember(modelId) { modelRepository.models.find { it.id == modelId } }

    TopAppBar(
        title = {
            if (isModelLoaded && model != null) {
                Text(
                    text = model.name,
                    maxLines = 1,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Report,
                        contentDescription = stringResource(R.string.generate_model_not_loaded),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.generate_model_not_loaded),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                Icon(Icons.Default.Menu, stringResource(R.string.settings))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseTopBar(drawerState: DrawerState) {
    val scope = rememberCoroutineScope()
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                Icon(Icons.Default.Menu, stringResource(R.string.settings))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpscaleTopBar(
    drawerState: DrawerState,
    isUpscaleModelLoaded: Boolean,
    upscalerId: String?,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val upscalerRepository = remember { UpscalerRepository(context) }
    val upscalerName = remember(upscalerId, upscalerRepository.upscalers) {
        upscalerId?.let { id -> upscalerRepository.upscalers.find { it.id == id }?.name }
    }

    TopAppBar(
        title = {
            if (isUpscaleModelLoaded && upscalerName != null) {
                Text(
                    text = upscalerName,
                    maxLines = 1,
                )
            } else if (!isUpscaleModelLoaded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Report,
                        contentDescription = stringResource(R.string.upscale_model_not_loaded),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.upscale_model_not_loaded),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                Icon(Icons.Default.Menu, stringResource(R.string.settings))
            }
        },
    )
}

// =========== Models Tab ===========

@Composable
private fun ModelListTab(
    selectedModelId: String?,
    isModelLoaded: Boolean,
    onSelectModel: (String) -> Unit,
    onLoadModel: (String) -> Unit,
    modelRepository: ModelRepository,
    refreshVersion: Int,
    importingModels: List<ImportingModelState> = emptyList(),
    // Upscale model support
    isUpscaleModelLoaded: Boolean = false,
    onLoadUpscaleModel: (String) -> Unit = {},
    onUnloadUpscaleModel: () -> Unit = {},
    persistedUpscalerId: String? = null,
) {
    val context = LocalContext.current
    val upscalerRepository = remember(refreshVersion) { UpscalerRepository(context) }
    val downloadState by ModelDownloadService.downloadState.collectAsState()

    // Only show custom (imported) models
    val customModels = remember(modelRepository.models, refreshVersion) {
        modelRepository.models.filter { it.isCustom }
    }

    val downloadedUpscalers = remember(upscalerRepository.upscalers) {
        upscalerRepository.upscalers.filter { it.isDownloaded }
    }

    val hasAnyContent = customModels.isNotEmpty() || importingModels.isNotEmpty() ||
        downloadedUpscalers.isNotEmpty()

    if (!hasAnyContent) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Default.Memory,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                Text(
                    stringResource(R.string.select_model_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            importingModels.forEach { imp ->
                item(key = "importing_${imp.modelId}") {
                    ImportingModelCard(state = imp)
                }
            }

            items(
                items = customModels,
                key = { "${it.id}_$refreshVersion" },
            ) { model ->
                ModelSelectCard(
                    model = model,
                    isSelected = selectedModelId == model.id,
                    isActive = isModelLoaded && selectedModelId == model.id,
                    onSelect = { onSelectModel(model.id) },
                )
            }

            // ---- Upscale Models Section (downloaded/imported only) ----
            if (downloadedUpscalers.isNotEmpty()) {
                item(key = "upscale_header") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        stringResource(R.string.upscale_models_section),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                items(downloadedUpscalers, key = { "upscaler_${it.id}" }) { upscaler ->
                    val isThisUpscalerLoaded = isUpscaleModelLoaded &&
                        selectedUpscalerId == upscaler.id

                    UpscaleModelCardInline(
                        upscaler = upscaler,
                        isLoaded = isThisUpscalerLoaded,
                        isSelected = persistedUpscalerId == upscaler.id && !isThisUpscalerLoaded,
                        onSelect = {
                            context.getSharedPreferences("upscaler_prefs", Context.MODE_PRIVATE).edit {
                                putString("upscaler_standalone_selected_upscaler", upscaler.id)
                            }
                        },
                        onLoad = { onLoadUpscaleModel(upscaler.id) },
                        onUnload = { onUnloadUpscaleModel() },
                    )
                }
            }
        }
    }
}

@Composable
private fun UpscaleModelCardInline(
    upscaler: UpscalerModel,
    isLoaded: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isLoaded -> MaterialTheme.colorScheme.primaryContainer
                isSelected -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surface
            },
        ),
        border = when {
            isLoaded -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            isSelected -> BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
            else -> null
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = upscaler.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = upscaler.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isLoaded) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.model_loaded),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (isLoaded) {
                Button(onClick = onUnload) {
                    Text(stringResource(R.string.unload_upscale_model))
                }
            } else {
                Button(onClick = onLoad) {
                    Text(stringResource(R.string.load_upscale_model))
                }
            }
        }
    }
}

@Composable
private fun ImportingModelCard(state: ImportingModelState) {
    val fraction = state.byteProgress?.fraction ?: 0f
    val bytesProgress = state.byteProgress

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Badge(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.model_type_generation),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.modelName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.progressText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (bytesProgress != null) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${(fraction * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.5.dp,
            )
        }
    }
}

@Composable
private fun ModelSelectCard(
    model: Model,
    isSelected: Boolean,
    isActive: Boolean,
    onSelect: () -> Unit,
) {
    val targetContainer = when {
        isActive -> MaterialTheme.colorScheme.primaryContainer
        isSelected -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val backgroundColor by animateColorAsState(
        targetValue = targetContainer,
        animationSpec = tween(300),
        label = "CardBg",
    )

    ElevatedCard(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = backgroundColor),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Badge(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.model_type_generation),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onSelect() },
            )
        }
    }
}

// =========== Generate Tab (parameterized wrapper) ===========

@Composable
private fun TabGenerateScreen(
    modelId: String?,
    prompt: String,
    onPromptChange: (String) -> Unit,
    negativePrompt: String,
    onNegativePromptChange: (String) -> Unit,
    steps: Float,
    onStepsChange: (Float) -> Unit,
    cfg: Float,
    onCfgChange: (Float) -> Unit,
    seed: String,
    onSeedChange: (String) -> Unit,
    batchCounts: Int,
    onBatchCountsChange: (Int) -> Unit,
    scheduler: String,
    onSchedulerChange: (String) -> Unit,
    denoiseStrength: Float,
    onDenoiseStrengthChange: (Float) -> Unit,
    useOpenCL: Boolean,
    onUseOpenCLChange: (Boolean) -> Unit,
    width: Int,
    onWidthChange: (Int) -> Unit,
    height: Int,
    onHeightChange: (Int) -> Unit,
    onAddToQueue: (Int) -> Unit = {},
    recordRepository: RecordRepository? = null,
) {
    GenerateScreen(
        modelId = modelId,
        prompt = prompt,
        onPromptChange = onPromptChange,
        negativePrompt = negativePrompt,
        onNegativePromptChange = onNegativePromptChange,
        steps = steps,
        onStepsChange = onStepsChange,
        cfg = cfg,
        onCfgChange = onCfgChange,
        seed = seed,
        onSeedChange = onSeedChange,
        batchCounts = batchCounts,
        onBatchCountsChange = onBatchCountsChange,
        scheduler = scheduler,
        onSchedulerChange = onSchedulerChange,
        denoiseStrength = denoiseStrength,
        onDenoiseStrengthChange = onDenoiseStrengthChange,
        useOpenCL = useOpenCL,
        onUseOpenCLChange = onUseOpenCLChange,
        width = width,
        onWidthChange = onWidthChange,
        height = height,
        onHeightChange = onHeightChange,
        onAddToQueue = onAddToQueue,
        recordRepository = recordRepository,
    )
}

// =========== Queue Tab ===========

@Composable
private fun TabQueueScreen(
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
                scheduler = task.scheduler,
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

// =========== Settings Drawer ===========

@Composable
private fun ColumnScope.AppSettingsDrawerContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val themeController = LocalThemeController.current
    val appPrefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val genPrefs = remember { GenerationPreferences(context) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ────── Appearance ──────
        SectionHeader(stringResource(R.string.appearance))

        // Dynamic Color
        var dynamicColor by remember { mutableStateOf(themeController.state.dynamicColor) }
        SwitchSetting(
            title = stringResource(R.string.dynamic_color),
            hint = stringResource(R.string.dynamic_color_hint),
            checked = dynamicColor,
            onCheckedChange = { checked ->
                dynamicColor = checked
                themeController.update { it.copy(dynamicColor = checked) }
            },
        )

        // Dark Mode
        var darkMode by remember { mutableStateOf(themeController.state.darkMode) }
        ChipSetting(
            title = stringResource(R.string.dark_mode),
            options = listOf(
                DarkModePreference.SYSTEM to stringResource(R.string.dark_mode_system),
                DarkModePreference.LIGHT to stringResource(R.string.dark_mode_light),
                DarkModePreference.DARK to stringResource(R.string.dark_mode_dark),
            ),
            selected = darkMode,
            onSelect = { mode ->
                darkMode = mode
                themeController.update { it.copy(darkMode = mode) }
            },
        )

        // OLED Pure Black (only visible when not forced light)
        if (darkMode != DarkModePreference.LIGHT) {
            var oledBlack by remember { mutableStateOf(themeController.state.oledBlack) }
            SwitchSetting(
                title = stringResource(R.string.oled_black),
                hint = stringResource(R.string.oled_black_hint),
                checked = oledBlack,
                onCheckedChange = { checked ->
                    oledBlack = checked
                    themeController.update { it.copy(oledBlack = checked) }
                },
            )
        }

        // Theme Preset
        var themePreset by remember { mutableStateOf(themeController.state.preset) }
        ChipSetting(
            title = stringResource(R.string.theme_preset),
            hint = stringResource(R.string.theme_preset_hint),
            options = ThemePreset.entries.map { it to stringResource(it.nameRes) },
            selected = themePreset,
            onSelect = { preset ->
                themePreset = preset
                themeController.update { it.copy(preset = preset) }
            },
        )

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()

        // ────── Backend ──────
        SectionHeader(stringResource(R.string.backend_settings))

        var listenAll by remember { mutableStateOf(appPrefs.getBoolean("listen_on_all_addresses", false)) }
        SwitchSetting(
            title = stringResource(R.string.listen_on_all_addresses),
            hint = stringResource(R.string.listen_on_all_addresses_hint),
            checked = listenAll,
            onCheckedChange = { checked ->
                listenAll = checked
                appPrefs.edit { putBoolean("listen_on_all_addresses", checked) }
            },
        )

        var sdxlLowram by remember { mutableStateOf(appPrefs.getBoolean("sdxl_lowram", true)) }
        SwitchSetting(
            title = stringResource(R.string.sdxl_lowram),
            hint = stringResource(R.string.sdxl_lowram_hint),
            checked = sdxlLowram,
            onCheckedChange = { checked ->
                sdxlLowram = checked
                appPrefs.edit { putBoolean("sdxl_lowram", checked) }
            },
        )

        var enableLogCapture by remember { mutableStateOf(appPrefs.getBoolean("enable_log_capture", false)) }
        SwitchSetting(
            title = stringResource(R.string.capture_logs),
            hint = stringResource(R.string.capture_logs_hint),
            checked = enableLogCapture,
            onCheckedChange = { checked ->
                enableLogCapture = checked
                appPrefs.edit { putBoolean("enable_log_capture", checked) }
            },
        )

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()

        // ────── Generation ──────
        SectionHeader(stringResource(R.string.generation_settings))

        var showDiffusion by remember { mutableStateOf(appPrefs.getBoolean("show_diffusion_process", false)) }
        SwitchSetting(
            title = stringResource(R.string.show_process),
            hint = stringResource(R.string.show_process_hint),
            checked = showDiffusion,
            onCheckedChange = { checked ->
                showDiffusion = checked
                appPrefs.edit { putBoolean("show_diffusion_process", checked) }
            },
        )

        if (showDiffusion) {
            var diffusionStride by remember {
                mutableIntStateOf(appPrefs.getInt("show_diffusion_stride", 1).coerceIn(1, 10))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.preview_stride))
                    Text(
                        stringResource(R.plurals.preview_stride_hint, diffusionStride, diffusionStride),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = diffusionStride.toFloat(),
                    onValueChange = { diffusionStride = it.roundToInt().coerceIn(1, 10) },
                    onValueChangeFinished = {
                        appPrefs.edit { putInt("show_diffusion_stride", diffusionStride) }
                    },
                    valueRange = 1f..10f,
                    steps = 8,
                    modifier = Modifier.width(120.dp),
                )
                Text(
                    "$diffusionStride",
                    modifier = Modifier.width(28.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        var genTimeout by remember {
            mutableIntStateOf(appPrefs.getInt("generation_timeout_s", 60).coerceIn(15, 600))
        }
        SliderSetting(
            title = stringResource(R.string.generation_timeout),
            hint = stringResource(R.string.generation_timeout_hint, genTimeout),
            value = genTimeout,
            valueRange = 15..600,
            steps = 38, // ~15 sec increments
            suffix = "s",
            onValueChangeFinished = {
                appPrefs.edit { putInt("generation_timeout_s", genTimeout) }
            },
        ) { genTimeout = it }

        var bitmapTimeout by remember {
            mutableIntStateOf(appPrefs.getInt("bitmap_consumed_timeout_s", 30).coerceIn(5, 120))
        }
        SliderSetting(
            title = stringResource(R.string.bitmap_consumed_timeout),
            hint = stringResource(R.string.bitmap_consumed_timeout_hint, bitmapTimeout),
            value = bitmapTimeout,
            valueRange = 5..120,
            steps = 22, // ~5 sec increments
            suffix = "s",
            onValueChangeFinished = {
                appPrefs.edit { putInt("bitmap_consumed_timeout_s", bitmapTimeout) }
            },
        ) { bitmapTimeout = it }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()

        // ────── Health Check ──────
        SectionHeader(stringResource(R.string.health_check_settings))

        var healthCheckInterval by remember {
            mutableIntStateOf(appPrefs.getInt("health_check_retry_interval_s", 20).coerceIn(5, 120))
        }
        SliderSetting(
            title = stringResource(R.string.health_check_retry_interval),
            hint = stringResource(R.string.health_check_retry_interval_hint, healthCheckInterval),
            value = healthCheckInterval,
            valueRange = 5..120,
            steps = 22, // ~5 sec increments
            suffix = "s",
            onValueChangeFinished = {
                appPrefs.edit { putInt("health_check_retry_interval_s", healthCheckInterval) }
            },
        ) { healthCheckInterval = it }

        var healthCheckMaxFails by remember {
            mutableIntStateOf(appPrefs.getInt("health_check_max_failures", 4).coerceIn(1, 20))
        }
        SliderSetting(
            title = stringResource(R.string.health_check_max_failures),
            hint = stringResource(R.string.health_check_max_failures_hint, healthCheckMaxFails),
            value = healthCheckMaxFails,
            valueRange = 1..20,
            steps = 18,
            suffix = "",
            onValueChangeFinished = {
                appPrefs.edit { putInt("health_check_max_failures", healthCheckMaxFails) }
            },
        ) { healthCheckMaxFails = it }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()

        // ────── Downloads ──────
        SectionHeader(stringResource(R.string.download_settings_section))

        var selectedSource by remember { mutableStateOf("huggingface") }
        var customUrl by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            selectedSource = genPrefs.getSelectedSource()
            customUrl = if (selectedSource == "custom") genPrefs.getBaseUrl() else ""
        }

        ChipSetting(
            title = stringResource(R.string.download_from),
            options = listOf(
                "huggingface" to stringResource(R.string.source_huggingface),
                "hf-mirror" to stringResource(R.string.source_hf_mirror),
                "custom" to stringResource(R.string.source_custom),
            ),
            selected = selectedSource,
            onSelect = { src ->
                selectedSource = src
                scope.launch {
                    genPrefs.saveSelectedSource(src)
                    when (src) {
                        "huggingface" -> genPrefs.saveBaseUrl("https://huggingface.co/")
                        "hf-mirror" -> genPrefs.saveBaseUrl("https://hf-mirror.com/")
                        "custom" -> { /* keep existing custom URL */ }
                    }
                }
            },
        )

        if (selectedSource == "custom") {
            OutlinedTextField(
                value = customUrl,
                onValueChange = { customUrl = it },
                label = { Text("URL") },
                placeholder = { Text("https://hf-mirror.com/") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = {
                scope.launch {
                    genPrefs.saveBaseUrl(customUrl.trim().ifBlank { "https://hf-mirror.com/" })
                }
            }) {
                Text(stringResource(R.string.save))
            }
        }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()

        // ────── About ──────
        SectionHeader(stringResource(R.string.about_app))
        Text(
            stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.must_read),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(16.dp))
}

// ────── Reusable drawer setting components ──────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SwitchSetting(
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun <T> ChipSetting(
    title: String,
    hint: String? = null,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(title)
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun SliderSetting(
    title: String,
    hint: String,
    value: Int,
    valueRange: IntRange,
    steps: Int,
    suffix: String,
    onValueChangeFinished: () -> Unit,
    onValueChange: (Int) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title)
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "$value$suffix",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(valueRange.first, valueRange.last)) },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = steps,
        )
    }
}
