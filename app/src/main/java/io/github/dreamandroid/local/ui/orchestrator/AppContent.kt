package io.github.dreamandroid.local.ui.orchestrator

import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
    var queueAnimEnabled by remember { mutableStateOf(true) }
    var genParamAddQueueBtnCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var queueIconCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
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

    // ── Queue-fly animation callbacks ──
    val onQueueAnimationRequest: () -> Unit = remember(queueAnimEnabled) {
        { if (queueAnimEnabled) animTrigger++ }
    }

    // Queue icon bump animation
    LaunchedEffect(queueBumpTrigger) {
        if (queueBumpTrigger == 0) return@LaunchedEffect
        queueBumpScale.snapTo(1f)
        queueBumpScale.animateTo(1.5f, spring())
        queueBumpScale.animateTo(1f, spring())
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

                // ── Expandable bottom bar (5 rows × 5 icons when expanded) ──
                // Swipe up on the NavigationBar to reveal rows 2–5.
                // All sizes derived from measured navBarHeightPx — no hardcoded dp.
                Column(modifier = Modifier.fillMaxWidth()) {

                    // Row 5 (user-customizable, empty for now)
                    AnimatedVisibility(
                        visible = isBottomBarExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        ExpandableIconRow(
                            rowHeightPx = navBarHeightPx,
                            icons = listOf(null, null, null, null, null),
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
                            icons = listOf(null, null, null, null, null),
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
                            icons = listOf(null, null, null, null, null),
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
                            icons = listOf(
                                Icons.Default.Info to null,
                                Icons.Default.Delete to null,
                                Icons.Default.Person to null,
                                Icons.Default.ArrowDownward to null,
                                Icons.Default.Settings to null,
                            ),
                        )
                    }

                    // Row 1 — NavigationBar (always visible, swipe to expand)
                    // Gesture: swipe up ≥ 60% of nav bar height → expand
                    //          swipe down ≥ 60% of nav bar height → collapse
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                navBarHeightPx = coords.size.height.toFloat()
                            }
                            .pointerInput(isBottomBarExpanded) {
                                var totalDrag = 0f
                                detectVerticalDragGestures(
                                    onVerticalDrag = { _, dragAmount ->
                                        totalDrag += dragAmount
                                    },
                                    onDragEnd = {
                                        val threshold = navBarHeightPx * 0.6f
                                        if (!isBottomBarExpanded && totalDrag < -threshold) {
                                            isBottomBarExpanded = true
                                        } else if (isBottomBarExpanded && totalDrag > threshold) {
                                            isBottomBarExpanded = false
                                        }
                                        totalDrag = 0f
                                    },
                                    onDragCancel = { totalDrag = 0f },
                                )
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
                                            .onGloballyPositioned { queueIconCoords = it }
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
                                                MaterialTheme.colorScheme.error.copy(alpha = flashAlpha)
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
                        onQueueAnimEnabledChange = { enabled ->
                            queueAnimEnabled = enabled
                            scope.launch { generationPreferences.setQueueAnimEnabled(enabled) }
                        },
                        onGenParamAddQueuePositioned = { genParamAddQueueBtnCoords = it },
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
                        onToggleLayout = {
                            val next = browseLayoutMode.next()
                            browseLayoutMode = next
                            scope.launch { generationPreferences.setBrowseLayoutMode(next.name) }
                        },
                    )
                }
            }
        }

        // ── Queue-fly star animation overlay ──
        // Drawn on top of everything (above the Scaffold including bottom bar)
        if (animTrigger > 0) {
            val genParamPos = genParamAddQueueBtnCoords
            val queuePos = queueIconCoords
            if (genParamPos != null && queuePos != null) {
                val genParamRoot = genParamPos.positionInRoot()
                val queueRoot = queuePos.positionInRoot()
                // Star size = actual destination icon size (adapts to any device)
                val starPxSize = queuePos.size
                key(animTrigger) {
                    QueueStarAnimation(
                        startOffset = genParamRoot,
                        endOffset = queueRoot,
                        starSizePx = starPxSize,
                        onArrived = { queueBumpTrigger++ },
                    )
                }
            }
        }
    }
}

/**
 * A four-pointed star that flies from [startOffset] to [endOffset].
 *
 * All positioning/sizing is relative and driven by actual layout measurements:
 * - Path = parabola with arc height = 25% of vertical distance
 * - Star size = [starSizePx] (the destination icon's measured pixel size)
 * - Star is centered on the path point (not top-left aligned)
 *
 * No hardcoded dp values used for any size or position calculation.
 */
@Composable
private fun QueueStarAnimation(
    startOffset: Offset,
    endOffset: Offset,
    starSizePx: IntSize,
    onArrived: () -> Unit,
) {
    val density = LocalDensity.current
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

    // Convert measured icon pixel size to dp, then center the star on the path point
    val starWidthDp = with(density) { starSizePx.width.toDp() }
    val starHeightDp = with(density) { starSizePx.height.toDp() }
    val halfW = starSizePx.width / 2f
    val halfH = starSizePx.height / 2f

    Canvas(
        modifier = Modifier
            .offset { IntOffset((cx - halfW).toInt(), (cy - halfH).toInt()) }
            .size(starWidthDp, starHeightDp)
            .graphicsLayer {
                this.alpha = alpha.value
                this.scaleX = scale.value
                this.scaleY = scale.value
            },
    ) {
        val w = size.width
        val h = size.height
        val cxStar = w / 2f
        val cyStar = h / 2f
        val outerR = min(w, h) / 2f
        val innerR = outerR * 0.35f
        val path = Path().apply {
            moveTo(cxStar, cyStar - outerR)                // top
            lineTo(cxStar + innerR, cyStar - innerR)       // inner top-right
            lineTo(cxStar + outerR, cyStar)                // right
            lineTo(cxStar + innerR, cyStar + innerR)       // inner bottom-right
            lineTo(cxStar, cyStar + outerR)                // bottom
            lineTo(cxStar - innerR, cyStar + innerR)       // inner bottom-left
            lineTo(cxStar - outerR, cyStar)                // left
            lineTo(cxStar - innerR, cyStar - innerR)       // inner top-left
            close()
        }
        drawPath(path, Color(0xFFFFD700)) // gold 4-pointed star
    }
}

/**
 * A single row of the expandable bottom bar with [icons] equally spaced.
 *
 * All sizes are derived from [rowHeightPx] (the measured NavigationBar height)
 * so the row adapts to any device / theme / accessibility setting.
 *
 * @param rowHeightPx  measured pixel height of the NavigationBar row
 * @param icons        list of up to 5 icons; null = empty placeholder slot
 */
@Composable
private fun ExpandableIconRow(
    rowHeightPx: Float,
    icons: List<ImageVector?>,
) {
    val density = LocalDensity.current
    val rowHeightDp = with(density) { rowHeightPx.toDp() }
    // Icon slot = 45% of row height, square
    val slotSizeDp = rowHeightDp * 0.45f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeightDp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icons.take(5).forEach { icon ->
            Box(
                modifier = Modifier
                    .size(slotSizeDp),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(0.7f),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
