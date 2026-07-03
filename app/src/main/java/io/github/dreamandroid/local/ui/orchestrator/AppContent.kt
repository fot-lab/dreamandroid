package io.github.dreamandroid.local.ui.orchestrator

import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.abs
import kotlin.math.min
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
import io.github.dreamandroid.local.ui.screens.download.DownloadManagerScreen
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
    var browseLayoutMode by remember { mutableStateOf(BrowseLayoutMode.THREE_COLUMNS) }

    // ── Queue-fly animation state ──
    //     Storing Offset (value equality) instead of LayoutCoordinates (reference
    //     equality) — prevents infinite recomposition loop that occurs when
    //     onGloballyPositioned writes new LayoutCoordinates every layout pass.
    var queueAnimEnabled by remember { mutableStateOf(true) }
    var genParamAddQueueBtnPos by remember { mutableStateOf(Offset.Zero) }
    var queueIconPosInRoot by remember { mutableStateOf(Offset.Zero) }
    var animTrigger by remember { mutableIntStateOf(0) }
    var queueBumpTrigger by remember { mutableIntStateOf(0) }
    val queueBumpScale = remember { Animatable(1f) }

    // ── Expandable bottom bar state ──
    var isBottomBarExpanded by remember { mutableStateOf(false) }
    var navBarHeightPx by remember { mutableFloatStateOf(0f) }

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

    // Load persisted browse layout mode
    LaunchedEffect(Unit) {
        generationPreferences.observeBrowseLayoutMode().collect { persisted ->
            try {
                browseLayoutMode = BrowseLayoutMode.valueOf(persisted)
            } catch (_: IllegalArgumentException) {
                // fallback to default THREE_COLUMNS
            }
        }
    }

    // Load persisted queue animation preference
    LaunchedEffect(Unit) {
        generationPreferences.observeQueueAnimEnabled().collect { enabled ->
            queueAnimEnabled = enabled
        }
    }

    // ── Queue state (from QueueViewModel) ──
    val queueTasks by queueViewModel.queueRepository.tasks.collectAsState()
    val queueProcessing by queueViewModel.queueRepository.processingActive.collectAsState()
    val queueBatchGroups = remember(queueTasks) { queueViewModel.queueRepository.getBatchGroups() }
    val generationTimedOut by queueViewModel.queueRepository.generationTimedOut.collectAsState()
    val queuePaused by queueViewModel.queueRepository.queuePaused.collectAsState()
    val queueHasPending = remember(queueTasks) {
        queueViewModel.queueRepository.hasPendingTasks()
    }

    // Debug: log queue state changes (SideEffect runs after successful recomposition only)
    SideEffect {
        Log.d("AppContentDbg", "Queue state: tasks.size=${queueTasks.size} batchGroups.size=${queueBatchGroups.size} " +
            "processing=$queueProcessing paused=$queuePaused pending=$queueHasPending " +
            "selectionMode=${queueViewModel.queueIsSelectionMode}")
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


    // ── Queue-fly animation callbacks ──
    val onQueueAnimationRequest: () -> Unit = remember(queueAnimEnabled) {
        { if (queueAnimEnabled) animTrigger++ }
    }

    // Stable lambdas passed to child composables — remembered to avoid
    // new lambda identity on every recomposition (would force child re-render).
    val onQueueAnimEnabledChange: (Boolean) -> Unit = remember {
        { enabled: Boolean ->
            queueAnimEnabled = enabled
            scope.launch { generationPreferences.setQueueAnimEnabled(enabled) }
        }
    }
    val onGenParamAddQueuePositioned: (Offset) -> Unit = remember {
        { offset: Offset -> genParamAddQueueBtnPos = offset }
    }
    val onToggleLayout: () -> Unit = remember {
        {
            val next = browseLayoutMode.next()
            browseLayoutMode = next
            scope.launch { generationPreferences.setBrowseLayoutMode(next.name) }
        }
    }

    // Queue icon bump animation
    LaunchedEffect(queueBumpTrigger) {
        if (queueBumpTrigger == 0) return@LaunchedEffect
        queueBumpScale.snapTo(1f)
        queueBumpScale.animateTo(1.5f, spring())
        queueBumpScale.animateTo(1f, spring())
    }

    // ── Red flash animation (Models tab when generation times out)
    //     Animatable + LaunchedEffect keyed on generationTimedOut:
    //     animation frames only run when the flash is actually needed.
    //     When timed-out condition clears → coroutine cancels → snap to 1f.
    val flashAlpha = remember { Animatable(1f) }
    LaunchedEffect(generationTimedOut) {
        if (generationTimedOut) {
            while (true) {
                flashAlpha.animateTo(
                    targetValue = 0.3f,
                    animationSpec = tween(800, easing = FastOutSlowInEasing),
                )
                flashAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(800, easing = FastOutSlowInEasing),
                )
            }
        } else {
            flashAlpha.snapTo(1f)
        }
    }

    // ── Scaffold with bottom bar ──
    // Each tab manages its own drawer + topBar + content via AppContentTab* composables.
    // contentWindowInsets = WindowInsets(0) lets status bar insets pass through
    // to the inner tab Scaffolds, so their TopAppBar can extend behind the system
    // status bar (edge-to-edge). The bottom NavigationBar internally handles
    // navigation bar insets.
    // Wrapped in a Box so the queue-fly star animation can overlay everything
    // including the bottom NavigationBar.
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                val density = LocalDensity.current

                // ── Expandable bottom bar (5 rows × 5 icons when expanded) ──
                // Swipe up/down on the hint bar to expand/collapse.
                // All sizes derived from measured navBarHeightPx — no hardcoded dp.
                Column(modifier = Modifier.fillMaxWidth()) {

                    // ── navigation expand hint bar (expanded): at top of expanded area, ↓ swipe to collapse ──
                    // Only visible when fully expanded; no animation to avoid
                    // participating in expand/shrink transitions.
                    if (isBottomBarExpanded) {
                        val hintHeightDp = with(density) { (navBarHeightPx * 0.20f).toDp() }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(hintHeightDp)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .pointerInput(Unit) {
                                    var totalDrag = 0f
                                    detectVerticalDragGestures(
                                        onVerticalDrag = { _, dragAmount ->
                                            totalDrag += dragAmount
                                        },
                                        onDragEnd = {
                                            if (totalDrag > navBarHeightPx * 0.6f) {
                                                isBottomBarExpanded = false
                                            }
                                            totalDrag = 0f
                                        },
                                        onDragCancel = { totalDrag = 0f },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Row 5 (user-customizable, empty for now)
                    AnimatedVisibility(
                        visible = isBottomBarExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        ExpandableIconRow(
                            rowHeightPx = navBarHeightPx,
                            slots = listOf(
                                ExpandableSlot(null, null),
                                ExpandableSlot(null, null),
                                ExpandableSlot(null, null),
                                ExpandableSlot(null, null),
                                ExpandableSlot(null, null),
                            ),
                        )
                    }

                    // Row 4 (user-customizable, empty for now)
                    AnimatedVisibility(
                        visible = isBottomBarExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        ExpandableIconRow(
                            rowHeightPx = navBarHeightPx,
                            slots = listOf(
                                ExpandableSlot(null, null),
                                ExpandableSlot(null, null),
                                ExpandableSlot(null, null),
                                ExpandableSlot(null, null),
                                ExpandableSlot(null, null),
                            ),
                        )
                    }

                    // Row 3 (user-customizable, empty for now)
                    AnimatedVisibility(
                        visible = isBottomBarExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        ExpandableIconRow(
                            rowHeightPx = navBarHeightPx,
                            slots = listOf(
                                ExpandableSlot(null, null),
                                ExpandableSlot(null, null),
                                ExpandableSlot(null, null),
                                ExpandableSlot(null, null),
                                ExpandableSlot(null, null),
                            ),
                        )
                    }

                    // Row 2 — predefined system icons (immutable)
                    // Info | RecycleBin | User | Download | Settings
                    AnimatedVisibility(
                        visible = isBottomBarExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        ExpandableIconRow(
                            rowHeightPx = navBarHeightPx,
                            slots = listOf(
                                ExpandableSlot(Icons.Default.Info, stringResource(R.string.nav_expand_info)),
                                ExpandableSlot(Icons.Default.Delete, stringResource(R.string.nav_expand_recycle)),
                                ExpandableSlot(Icons.Default.Person, stringResource(R.string.nav_expand_person)),
                                ExpandableSlot(Icons.Default.ArrowDownward, stringResource(R.string.nav_expand_download),
                                    onClick = {
                                        modelsViewModel.showDownloadManager = true
                                        isBottomBarExpanded = false
                                    },
                                ),
                                ExpandableSlot(Icons.Default.Settings, stringResource(R.string.nav_expand_settings)),
                            ),
                        )
                    }

                    // ── navigation expand hint bar (collapsed): below rows, above NavBar, ↑ swipe to expand ──
                    // Only visible when collapsed; no animation.
                    if (!isBottomBarExpanded) {
                        val hintHeightDp = with(density) { (navBarHeightPx * 0.20f).toDp() }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(hintHeightDp)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .pointerInput(Unit) {
                                    var totalDrag = 0f
                                    detectVerticalDragGestures(
                                        onVerticalDrag = { _, dragAmount ->
                                            totalDrag += dragAmount
                                        },
                                        onDragEnd = {
                                            if (totalDrag < -navBarHeightPx * 0.6f) {
                                                isBottomBarExpanded = true
                                            }
                                            totalDrag = 0f
                                        },
                                        onDragCancel = { totalDrag = 0f },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Row 1 — NavigationBar (always visible)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                navBarHeightPx = coords.size.height.toFloat()
                            },
                    ) {
                        NavigationBar {
                            BottomTab.entries.forEach { tab ->
                                val isModelsTab = tab == BottomTab.Models
                                val shouldFlash = isModelsTab && generationTimedOut
                                val isQueueTab = tab == BottomTab.Queue
                                NavigationBarItem(
                                    modifier = if (isQueueTab) {
                                        Modifier
                                            .onGloballyPositioned { coords ->
                                                queueIconPosInRoot = coords.positionInRoot()
                                            }
                                            .graphicsLayer {
                                                scaleX = queueBumpScale.value
                                                scaleY = queueBumpScale.value
                                            }
                                    } else {
                                        Modifier
                                    },
                                    selected = mainViewModel.selectedTab == tab,
                                    onClick = { mainViewModel.selectedTab = tab },
                                    icon = {
                                        Icon(
                                            imageVector = tab.icon,
                                            contentDescription = stringResource(tab.labelResId),
                                            tint = if (shouldFlash)
                                                MaterialTheme.colorScheme.error.copy(alpha = flashAlpha.value)
                                            else
                                                LocalContentColor.current,
                                        )
                                    },
                                    label = { Text(stringResource(tab.labelResId)) },
                                )
                            }
                        }
                    }
                }
            },
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                // ── Download Manager — content-level peer module ──
                // Appears in the same content slot as tab screens (Queue / Models
                // / Generate / Browse). Not shown in the main NavigationBar;
                // reachable via + menu in Models tab or expanded bottom bar.
                if (modelsViewModel.showDownloadManager) {
                    DownloadManagerScreen(
                        onClose = { modelsViewModel.showDownloadManager = false },
                        onModelStateChanged = { modelId ->
                            modelsViewModel.modelRepository.refreshModelState(modelId)
                            modelsViewModel.modelRefreshVersion++
                        },
                    )
                } else {
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
                            onQueueAnimationRequest = onQueueAnimationRequest,
                            queueAnimEnabled = queueAnimEnabled,
                            onQueueAnimEnabledChange = onQueueAnimEnabledChange,
                            onGenParamAddQueuePositioned = onGenParamAddQueuePositioned,
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
                            onToggleLayout = onToggleLayout,
                        )
                    }
                }
            }
        }

        // ── Queue-fly star animation overlay ──
        // Drawn on top of everything (above the Scaffold including bottom bar)
        if (animTrigger > 0) {
            val startPos = genParamAddQueueBtnPos
            val endPos = queueIconPosInRoot
            if (startPos != Offset.Zero && endPos != Offset.Zero) {
                key(animTrigger) {
                    QueueStarAnimation(
                        startOffset = startPos,
                        endOffset = endPos,
                        onArrived = { queueBumpTrigger++ },
                    )
                }
            }
        }
    }
}

/**
 * A work icon that flies from [startOffset] to [endOffset] along a parabola.
 * Icon renders at default Material size (24.dp).
 */
@Composable
private fun QueueStarAnimation(
    startOffset: Offset,
    endOffset: Offset,
    onArrived: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // Phase 1: appear
        alpha.animateTo(1f, tween(120))
        scale.animateTo(1f, tween(150))

        // Phase 2: fly — X linear, Y has parabolic arc added
        progress.animateTo(1f, tween(500, easing = LinearEasing))

        // Phase 3: vanish
        alpha.animateTo(0f, tween(100))
        scale.animateTo(0f, tween(100))

        onArrived()
    }

    val p = progress.value
    val dx = endOffset.x - startOffset.x
    val dy = endOffset.y - startOffset.y
    // Relative parabola: height = 25% of vertical distance, peaks at midpoint
    val arcHeight = abs(dy) * 0.25f
    val cx = startOffset.x + dx * p
    val cy = startOffset.y + dy * p - 4f * arcHeight * p * (1f - p)

    Icon(
        imageVector = Icons.Default.Work,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier
            .offset { IntOffset(cx.toInt(), cy.toInt()) }
            .graphicsLayer {
                this.alpha = alpha.value
                this.scaleX = scale.value
                this.scaleY = scale.value
            },
    )
}

/**
 * A slot in an expandable bottom bar row.
 *
 * @param icon    icon to display (null = empty slot)
 * @param label   label text below the icon (null = no label)
 * @param onClick click callback (null = non-interactive placeholder)
 */
private data class ExpandableSlot(
    val icon: ImageVector?,
    val label: String?,
    val onClick: (() -> Unit)? = null,
)

/**
 * A single row of the expandable bottom bar with [slots] equally distributed.
 *
 * Row height = [rowHeightPx] (measured NavigationBar height, relative).
 * Columns use [Modifier.weight] for automatic equal-width distribution.
 * Each column contains icon + label vertically centered — no hardcoded dp.
 *
 * @param rowHeightPx  measured pixel height of the NavigationBar row
 * @param slots        list of up to 5 slots; null icon = empty placeholder
 */
@Composable
private fun ExpandableIconRow(
    rowHeightPx: Float,
    slots: List<ExpandableSlot>,
) {
    val density = LocalDensity.current
    val rowHeightDp = with(density) { rowHeightPx.toDp() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeightDp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        slots.take(5).forEach { slot ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(if (slot.onClick != null) {
                        Modifier.clickable { slot.onClick.invoke() }
                    } else {
                        Modifier
                    }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (slot.icon != null) {
                    Icon(
                        imageVector = slot.icon,
                        contentDescription = slot.label,
                        tint = if (slot.onClick != null)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else
                            MaterialTheme.colorScheme.outline,
                    )
                }
                if (slot.label != null) {
                    Text(
                        text = slot.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (slot.onClick != null)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else
                            MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
