package io.github.dreamandroid.local.ui.orchestrator

import android.content.Context
import android.util.Log
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dreamandroid.local.DreamAndroidApplication
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.*
import io.github.dreamandroid.local.navigation.BottomTab
import io.github.dreamandroid.local.service.QueueRepository
import io.github.dreamandroid.local.ui.backend.*
import io.github.dreamandroid.local.ui.viewmodel.BrowseViewModel
import io.github.dreamandroid.local.ui.viewmodel.GenerateViewModel
import io.github.dreamandroid.local.ui.viewmodel.MainViewModel
import io.github.dreamandroid.local.ui.viewmodel.ModelsViewModel
import io.github.dreamandroid.local.ui.viewmodel.QueueViewModel
import io.github.dreamandroid.local.ui.screens.BrowseLayoutMode
import kotlinx.coroutines.launch

/**
 * Thin orchestrator Composable — state and business logic extracted to ViewModels;
 * per-tab drawer + scaffold + topBar moved to AppContentTab* composables.
 *
 * AppContent now only handles:
 * - ViewModel instantiation
 * - Application-level state & dependency wiring
 * - Cross-cutting dialogs (import, rename, delete)
 * - Bottom NavigationBar
 * - Content routing to per-tab Composable
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val recordRepository = remember { RecordRepository(context) }
    var browseLayoutMode by remember { mutableStateOf(BrowseLayoutMode.SINGLE_COLUMN) }

    // ── ViewModels (Activity-scoped, shared across tabs) ──
    val mainViewModel: MainViewModel = viewModel()
    val modelsViewModel: ModelsViewModel = viewModel()
    val generateViewModel: GenerateViewModel = viewModel()
    val queueViewModel: QueueViewModel = viewModel()
    val browseViewModel: BrowseViewModel = viewModel()

    // ── Application-level dependencies ──
    val app = context.applicationContext as DreamAndroidApplication
    val backendState by modelsViewModel.backendService.state.collectAsState()
    val loadedModelId = backendState.loadedModelId()
    val loadedModelType = backendState.loadedModelType()
    val isModelLoaded = backendState.isDiffusionLoaded()
    val isModelLoading = backendState.isDiffusionLoading()
    val isUpscaleModelLoaded = backendState.isUpscalerLoaded()
    val generationPreferences = remember { GenerationPreferences(context) }

    // ── Queue state (from QueueViewModel) ──
    val queueTasks by queueViewModel.queueRepository.tasks.collectAsState()
    val queueProcessing by queueViewModel.queueRepository.processingActive.collectAsState()
    val queueBatchGroups = remember(queueTasks) { queueViewModel.queueRepository.getBatchGroups() }
    val generationTimedOut by queueViewModel.queueRepository.generationTimedOut.collectAsState()
    val queuePaused by queueViewModel.queueRepository.queuePaused.collectAsState()
    val queueHasPending = remember(queueTasks) {
        queueViewModel.queueRepository.hasPendingTasks()
    }

    // Debug: log queue state changes
    Log.d("AppContentDbg", "Queue state: tasks.size=${queueTasks.size} batchGroups.size=${queueBatchGroups.size} " +
        "processing=$queueProcessing paused=$queuePaused pending=$queueHasPending " +
        "selectionMode=${queueViewModel.queueIsSelectionMode}")

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

    // ── Load model-specific preferences when loaded model changes ──
    LaunchedEffect(loadedModelId) {
        loadedModelId?.let { modelId ->
            generateViewModel.loadModelPrefs(modelId, generationPreferences)
        }
    }

    // ── Dialogs ──

    // Shared alert/confirm dialogs (no-model warning, delete confirm)
    AppContentDialogModelsAlert(
        mainViewModel = mainViewModel,
        modelsViewModel = modelsViewModel,
        snackbarHostState = snackbarHostState,
    )

    // Model import dialogs
    AppContentDialogModelsImport(
        modelsViewModel = modelsViewModel,
        snackbarHostState = snackbarHostState,
        scope = scope,
    )

    // Rename model dialog
    if (modelsViewModel.showRenameDialog) {
        AppContentDialogModelsRename(
            modelsViewModel = modelsViewModel,
            context = context,
            snackbarHostState = snackbarHostState,
        )
    }

    // ── Scaffold with bottom bar ──
    // Each tab manages its own drawer + topBar + content via AppContentTab* composables.
    // contentWindowInsets = WindowInsets(0) lets status bar insets pass through
    // to the inner tab Scaffolds, so their TopAppBar can extend behind the system
    // status bar (edge-to-edge). The bottom NavigationBar internally handles
    // navigation bar insets.
    Scaffold(
        contentWindowInsets = WindowInsets(0),
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
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when (mainViewModel.selectedTab) {
                BottomTab.Models -> AppContentTabModels(
                    drawerState = drawerState,
                    snackbarHostState = snackbarHostState,
                    modelsViewModel = modelsViewModel,
                    loadedModelId = loadedModelId,
                    loadedModelType = loadedModelType,
                    isModelLoaded = isModelLoaded,
                    isModelLoading = isModelLoading,
                    isUpscaleModelLoaded = isUpscaleModelLoaded,
                    persistedUpscalerId = persistedUpscalerId,
                )
                BottomTab.Queue -> AppContentTabQueue(
                    drawerState = drawerState,
                    snackbarHostState = snackbarHostState,
                    queueViewModel = queueViewModel,
                    tasks = queueTasks,
                    batchGroups = queueBatchGroups,
                    processingActive = queueProcessing,
                    queuePaused = queuePaused,
                    hasPendingTasks = queueHasPending,
                    recordRepository = recordRepository,
                )
                BottomTab.Generate -> AppContentTabGenerate(
                    drawerState = drawerState,
                    snackbarHostState = snackbarHostState,
                    modelsViewModel = modelsViewModel,
                    generateViewModel = generateViewModel,
                    queueRepository = queueViewModel.queueRepository,
                    loadedModelId = loadedModelId,
                    loadedModelType = loadedModelType,
                    recordRepository = recordRepository,
                )
                BottomTab.Upscale -> AppContentTabUpscale(
                    drawerState = drawerState,
                    snackbarHostState = snackbarHostState,
                    loadedModelId = loadedModelId,
                    loadedModelType = loadedModelType,
                )
                BottomTab.Browse -> AppContentTabBrowse(
                    drawerState = drawerState,
                    snackbarHostState = snackbarHostState,
                    browseViewModel = browseViewModel,
                    recordRepository = recordRepository,
                    browseLayoutMode = browseLayoutMode,
                    onToggleLayout = { browseLayoutMode = browseLayoutMode.next() },
                )
            }
        }
    }
}
