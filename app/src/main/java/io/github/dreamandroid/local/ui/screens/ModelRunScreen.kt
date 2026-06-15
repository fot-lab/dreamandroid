package io.github.dreamandroid.local.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect as AndroidRect
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import io.github.dreamandroid.local.DreamAndroidApplication
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.*
import io.github.dreamandroid.local.service.BackendService
import io.github.dreamandroid.local.service.BackendService.BackendState
import io.github.dreamandroid.local.service.BackgroundGenerationService
import io.github.dreamandroid.local.service.BackgroundGenerationService.GenerationState
import io.github.dreamandroid.local.service.ModelDownloadService
import io.github.dreamandroid.local.ui.components.*
import io.github.dreamandroid.local.ui.screens.run.*
import io.github.dreamandroid.local.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val HISTORY_COALESCE_MS = 1000L
private const val HISTORY_LIMIT = 50

@SuppressLint("DefaultLocale")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ModelRunScreen(modelId: String, navController: NavController, modifier: Modifier = Modifier) {
    val serviceState by BackgroundGenerationService.generationState.collectAsState()
    val backendState by BackendService.backendState.collectAsState()
    val context = LocalContext.current
    val resources = context.resources
    val scope = rememberCoroutineScope()
    val generationPreferences = remember { GenerationPreferences(context) }
    val backendManager = remember { (context.applicationContext as DreamAndroidApplication).backendManager }
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val modelRepository = remember { ModelRepository(context) }

    // ── String resources ──────────────────────────────────────
    val msgMediaPermissionHint = stringResource(R.string.media_permission_hint)
    val msgBackendFailed = stringResource(R.string.backend_failed)
    val msgImageSaved = stringResource(R.string.image_saved)
    val msgDownloadDone = stringResource(R.string.download_done)
    val msgErrorDownloadFailed = stringResource(R.string.error_download_failed)
    val msgDownloadModelFirst = stringResource(R.string.download_model_first)
    val msgDeleted = stringResource(R.string.deleted)
    val msgDeleteFailedMessage = stringResource(R.string.delete_failed_message)
    val msgShareCopied = stringResource(R.string.share_copied)
    val msgImportApplied = stringResource(R.string.import_applied)
    val msgUpscaleFailed = stringResource(R.string.upscale_failed)
    val msgSavedCountWithFailed = stringResource(R.string.saved_count_with_failed)
    val msgDeletedCountWithFailed = stringResource(R.string.deleted_count_with_failed)

    val model = remember { modelRepository.models.find { it.id == modelId } }
    val historyManager = remember { HistoryManager(context) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val focusManager = LocalFocusManager.current

    val view = LocalView.current
    DisposableEffect(view) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }

    // ── State ─────────────────────────────────────────────────
    val state = remember { ModelRunState() }

    // History flow
    val historyFlow = remember(state.historyFilter) { historyManager.observe(state.historyFilter) }
    val historyItems by historyFlow.collectAsState(initial = emptyList())
    val knownModelIds by remember { historyManager.observeKnownModelIds() }.collectAsState(initial = emptyList())
    val knownSchedulers by remember { historyManager.observeKnownSchedulers() }.collectAsState(initial = emptyList())
    val knownSizes by remember { historyManager.observeKnownSizes() }.collectAsState(initial = emptyList())

    // Share preferences
    val shareUseBase64 by remember { generationPreferences.observeShareUseBase64() }.collectAsState(initial = true)
    val shareClearClipboardOnImport by remember { generationPreferences.observeShareClearClipboardOnImport() }.collectAsState(initial = true)

    // Preferences
    val preferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    val useImg2img = preferences.getBoolean("use_img2img", true)
    val enableTagAutocomplete = preferences.getBoolean("enable_tag_autocomplete", true)
    val tagSuggestionCount = 128
    val tagAutocompleteRepository = remember { TagAutocompleteRepository.getInstance(context) }
    val tagDictState by tagAutocompleteRepository.state.collectAsState()
    val tagAutocompleteAvailable = enableTagAutocomplete && tagDictState.mainImported

    // Upscaler
    val upscalerRepository = remember { UpscalerRepository(context) }
    val upscalerPreferences = remember { context.getSharedPreferences("upscaler_prefs", Context.MODE_PRIVATE) }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })

    // ── LaunchedEffects ───────────────────────────────────────

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != 0) focusManager.clearFocus()
    }

    LaunchedEffect(tagAutocompleteAvailable) {
        if (tagAutocompleteAvailable) tagAutocompleteRepository.warmUp()
    }

    LaunchedEffect(state.isPromptFocused, state.isNegativePromptFocused) {
        if (!state.isPromptFocused && !state.isNegativePromptFocused) return@LaunchedEffect
        val names = withContext(Dispatchers.IO) {
            File(context.filesDir, "embeddings").takeIf { it.isDirectory }?.listFiles()
                ?.asSequence()?.filter { it.isFile && it.extension.equals("safetensors", ignoreCase = true) }
                ?.map { it.nameWithoutExtension }?.sortedBy { it.lowercase() }?.toList().orEmpty()
        }
        state.embeddingNames = names
    }

    LaunchedEffect(state.prompt, state.isCheckingBackend) {
        if (state.isCheckingBackend) return@LaunchedEffect
        delay(400)
        try {
            val result = withContext(Dispatchers.IO) { backendManager.tokenize(state.prompt) }
            state.promptTokenCount = result.count; state.promptTokenMax = result.maxLength; state.promptOverflowOffset = result.overflowOffset
        } catch (_: Exception) { }
    }

    LaunchedEffect(state.negativePrompt, state.isCheckingBackend) {
        if (state.isCheckingBackend) return@LaunchedEffect
        delay(400)
        try {
            val result = withContext(Dispatchers.IO) { backendManager.tokenize(state.negativePrompt) }
            state.negativePromptTokenCount = result.count; state.negativePromptTokenMax = result.maxLength; state.negativePromptOverflowOffset = result.overflowOffset
        } catch (_: Exception) { }
    }

    LaunchedEffect(modelId, model?.runOnCpu) {
        if (model?.runOnCpu == false && model.isSdxl == false) {
            val baseResolution = Resolution(512, 512)
            val patchResolutions = PatchScanner.scanAvailableResolutions(context, modelId)
            state.availableResolutions = (listOf(baseResolution) + patchResolutions).distinctBy { "${it.width}x${it.height}" }
        }
    }

    LaunchedEffect(modelId) {
        if (!state.hasInitialized) {
            val prefs = generationPreferences.getPreferences(modelId).first()
            if (prefs.prompt.isEmpty() && prefs.negativePrompt.isEmpty()) {
                model?.let { m ->
                    if (m.defaultPrompt.isNotEmpty()) {
                        state.prompt = m.defaultPrompt; state.promptFieldValue = TextFieldValue(m.defaultPrompt, TextRange(m.defaultPrompt.length))
                    }
                    if (m.defaultNegativePrompt.isNotEmpty()) {
                        state.negativePrompt = m.defaultNegativePrompt; state.negativePromptFieldValue = TextFieldValue(m.defaultNegativePrompt, TextRange(m.defaultNegativePrompt.length))
                    }
                    saveAllFields(state, scope, generationPreferences, modelId)
                }
            } else {
                state.prompt = prefs.prompt; state.negativePrompt = prefs.negativePrompt
                state.promptFieldValue = TextFieldValue(prefs.prompt, TextRange(prefs.prompt.length))
                state.negativePromptFieldValue = TextFieldValue(prefs.negativePrompt, TextRange(prefs.negativePrompt.length))
            }
            state.steps = prefs.steps; state.cfg = prefs.cfg; state.seed = prefs.seed
            state.denoiseStrength = prefs.denoiseStrength; state.useOpenCL = prefs.useOpenCL
            state.batchCounts = prefs.batchCounts; state.scheduler = prefs.scheduler
            state.aspectRatio = if (useImg2img) prefs.aspectRatio else "1:1"
            state.currentWidth = if (model?.isSdxl == true) 1024 else if (prefs.width == -1) (if (model?.runOnCpu == true) 256 else 512) else prefs.width
            state.currentHeight = if (model?.isSdxl == true) 1024 else if (prefs.height == -1) (if (model?.runOnCpu == true) 256 else 512) else prefs.height
            state.hasInitialized = true
        }
    }

    LaunchedEffect(state.hasInitialized) {
        if (state.hasInitialized && backendState !is BackendState.Running) {
            val intent = Intent(context, BackendService::class.java).apply {
                putExtra("modelId", model?.id); putExtra("width", state.currentWidth)
                putExtra("height", state.currentHeight); putExtra("use_opencl", state.useOpenCL)
            }
            context.startForegroundService(intent)
        }
    }

    LaunchedEffect(Unit) {
        val healthy = withContext(Dispatchers.IO) { backendManager.healthCheckWithRetry() }
        state.isCheckingBackend = false
        if (!healthy) state.errorMessage = msgBackendFailed
    }

    LaunchedEffect(state.backendRestartTrigger) {
        if (state.backendRestartTrigger > 0) {
            delay(500)
            val healthy = withContext(Dispatchers.IO) { backendManager.healthCheckWithRetry() }
            state.isCheckingBackend = false
            if (!healthy) state.errorMessage = msgBackendFailed
        }
    }

    LaunchedEffect(serviceState) {
        handleServiceState(serviceState, state, model, modelId, historyManager, coroutineScope, pagerState)
    }

    LaunchedEffect(backendState, state.hasInitialized) {
        if (!state.clipboardImportChecked && state.hasInitialized && backendState is BackendState.Running) {
            state.clipboardImportChecked = true
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val raw = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            ParamShare.tryDecode(raw)?.let { state.pendingImport = it }
        }
    }

    // ── DisposableEffects ─────────────────────────────────────

    DisposableEffect(Unit) { onDispose { cleanupModelRun(state, context, coroutineScope, pagerState) } }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) cleanupModelRun(state, context, coroutineScope, pagerState)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); cleanupModelRun(state, context, coroutineScope, pagerState) }
    }

    DisposableEffect(modelId) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val captureEnabled = prefs.getBoolean("enable_log_capture", false)
        if (captureEnabled) LogCapture.start()
        onDispose {
            if (captureEnabled) LogCapture.stopAndPublish()
            BackgroundGenerationService.clearCompleteState()
        }
    }

    // ── Effective size ────────────────────────────────────────

    val effectiveSize = remember(model?.isSdxl, state.aspectRatio, state.currentWidth, state.currentHeight) {
        computeAspectTargetSize(model?.isSdxl == true, state.aspectRatio) ?: Pair(state.currentWidth, state.currentHeight)
    }

    // ── Prompt helpers ────────────────────────────────────────

    fun embeddingSuggestionsFor(query: String, limit: Int = 5): List<TagSuggestion> {
        if (state.embeddingNames.isEmpty()) return emptyList()
        val q = query.trim().lowercase().replace(' ', '_').replace('-', '_')
        if (q.isEmpty()) return emptyList()
        val prefix = mutableListOf<TagSuggestion>()
        val contains = mutableListOf<TagSuggestion>()
        for (name in state.embeddingNames) {
            val normalized = name.lowercase().replace(' ', '_').replace('-', '_')
            val idx = normalized.indexOf(q)
            if (idx < 0) continue
            val s = TagSuggestion(replacementTag = name, primaryText = name, secondaryText = null, matchType = TagMatchType.Embedding, category = 0, postCount = 0, score = 0)
            if (idx == 0) prefix += s else contains += s
        }
        return (prefix + contains).take(limit)
    }

    fun pushPromptHistory(snapshot: String, coalesce: Boolean) {
        val now = System.currentTimeMillis()
        val skip = coalesce && state.promptUndoStack.isNotEmpty() && now - state.promptHistoryAt < HISTORY_COALESCE_MS
        if (!skip) state.promptUndoStack = (state.promptUndoStack + snapshot).takeLast(HISTORY_LIMIT)
        state.promptRedoStack = emptyList(); state.promptHistoryAt = if (coalesce) now else 0L
    }

    fun pushNegativePromptHistory(snapshot: String, coalesce: Boolean) {
        val now = System.currentTimeMillis()
        val skip = coalesce && state.negativePromptUndoStack.isNotEmpty() && now - state.negativePromptHistoryAt < HISTORY_COALESCE_MS
        if (!skip) state.negativePromptUndoStack = (state.negativePromptUndoStack + snapshot).takeLast(HISTORY_LIMIT)
        state.negativePromptRedoStack = emptyList(); state.negativePromptHistoryAt = if (coalesce) now else 0L
    }

    fun updatePromptField(value: TextFieldValue, recordHistory: Boolean = true) {
        val previousText = state.promptFieldValue.text
        val textChanged = value.text != previousText
        val selectionChanged = value.selection != state.promptFieldValue.selection
        if (textChanged && recordHistory) pushPromptHistory(previousText, coalesce = true)
        if (textChanged || selectionChanged) state.promptPopupDismissed = false
        state.promptFieldValue = value
        if (textChanged) { state.prompt = value.text; saveAllFields(state, scope, generationPreferences, modelId) }
        if (!tagAutocompleteAvailable || !state.isPromptFocused) { state.promptSuggestJob?.cancel(); state.promptSuggestions = emptyList(); state.promptActiveQuery = null; return }
        if (!textChanged && !selectionChanged) return
        val activeTag = TagAutocompleteRepository.extractActiveTag(value.text, value.selection.start)
        if (activeTag == null) { state.promptSuggestJob?.cancel(); state.promptSuggestions = emptyList(); state.promptActiveQuery = null; return }
        state.promptActiveQuery = activeTag.token
        state.promptSuggestJob?.cancel()
        state.promptSuggestJob = scope.launch {
            delay(200)
            state.promptSuggestions = embeddingSuggestionsFor(activeTag.token) + tagAutocompleteRepository.suggest(activeTag.token, tagSuggestionCount)
        }
    }

    fun updateNegativePromptField(value: TextFieldValue, recordHistory: Boolean = true) {
        val previousText = state.negativePromptFieldValue.text
        val textChanged = value.text != previousText
        val selectionChanged = value.selection != state.negativePromptFieldValue.selection
        if (textChanged && recordHistory) pushNegativePromptHistory(previousText, coalesce = true)
        if (textChanged || selectionChanged) state.negativePromptPopupDismissed = false
        state.negativePromptFieldValue = value
        if (textChanged) { state.negativePrompt = value.text; saveAllFields(state, scope, generationPreferences, modelId) }
        if (!tagAutocompleteAvailable || !state.isNegativePromptFocused) { state.negativePromptSuggestJob?.cancel(); state.negativePromptSuggestions = emptyList(); state.negativePromptActiveQuery = null; return }
        if (!textChanged && !selectionChanged) return
        val activeTag = TagAutocompleteRepository.extractActiveTag(value.text, value.selection.start)
        if (activeTag == null) { state.negativePromptSuggestJob?.cancel(); state.negativePromptSuggestions = emptyList(); state.negativePromptActiveQuery = null; return }
        state.negativePromptActiveQuery = activeTag.token
        state.negativePromptSuggestJob?.cancel()
        state.negativePromptSuggestJob = scope.launch {
            delay(200)
            state.negativePromptSuggestions = embeddingSuggestionsFor(activeTag.token) + tagAutocompleteRepository.suggest(activeTag.token, tagSuggestionCount)
        }
    }

    fun applyPromptSuggestion(suggestion: TagSuggestion) {
        pushPromptHistory(state.promptFieldValue.text, coalesce = false)
        val (updatedText, updatedSelection) = TagAutocompleteRepository.applySuggestion(state.promptFieldValue.text, state.promptFieldValue.selection.start, suggestion)
        state.prompt = updatedText; state.promptFieldValue = TextFieldValue(updatedText, TextRange(updatedSelection))
        state.promptSuggestions = emptyList(); state.promptActiveQuery = null; state.promptPopupDismissed = false
        saveAllFields(state, scope, generationPreferences, modelId)
    }

    fun applyNegativePromptSuggestion(suggestion: TagSuggestion) {
        pushNegativePromptHistory(state.negativePromptFieldValue.text, coalesce = false)
        val (updatedText, updatedSelection) = TagAutocompleteRepository.applySuggestion(state.negativePromptFieldValue.text, state.negativePromptFieldValue.selection.start, suggestion)
        state.negativePrompt = updatedText; state.negativePromptFieldValue = TextFieldValue(updatedText, TextRange(updatedSelection))
        state.negativePromptSuggestions = emptyList(); state.negativePromptActiveQuery = null; state.negativePromptPopupDismissed = false
        saveAllFields(state, scope, generationPreferences, modelId)
    }

    fun runPromptTagAction(action: (String, Int) -> Pair<String, Int>?) {
        val (updatedText, updatedSelection) = action(state.promptFieldValue.text, state.promptFieldValue.selection.start) ?: return
        pushPromptHistory(state.promptFieldValue.text, coalesce = false)
        updatePromptField(TextFieldValue(updatedText, TextRange(updatedSelection)), recordHistory = false)
    }

    fun runNegativePromptTagAction(action: (String, Int) -> Pair<String, Int>?) {
        val (updatedText, updatedSelection) = action(state.negativePromptFieldValue.text, state.negativePromptFieldValue.selection.start) ?: return
        pushNegativePromptHistory(state.negativePromptFieldValue.text, coalesce = false)
        updateNegativePromptField(TextFieldValue(updatedText, TextRange(updatedSelection)), recordHistory = false)
    }

    fun undoPrompt() {
        if (state.promptUndoStack.isEmpty()) return
        val previous = state.promptUndoStack.last(); state.promptUndoStack = state.promptUndoStack.dropLast(1)
        state.promptRedoStack = (state.promptRedoStack + state.promptFieldValue.text).takeLast(HISTORY_LIMIT); state.promptHistoryAt = 0L
        updatePromptField(TextFieldValue(previous, TextRange(previous.length)), recordHistory = false)
    }

    fun redoPrompt() {
        if (state.promptRedoStack.isEmpty()) return
        val next = state.promptRedoStack.last(); state.promptRedoStack = state.promptRedoStack.dropLast(1)
        state.promptUndoStack = (state.promptUndoStack + state.promptFieldValue.text).takeLast(HISTORY_LIMIT); state.promptHistoryAt = 0L
        updatePromptField(TextFieldValue(next, TextRange(next.length)), recordHistory = false)
    }

    fun undoNegativePrompt() {
        if (state.negativePromptUndoStack.isEmpty()) return
        val previous = state.negativePromptUndoStack.last(); state.negativePromptUndoStack = state.negativePromptUndoStack.dropLast(1)
        state.negativePromptRedoStack = (state.negativePromptRedoStack + state.negativePromptFieldValue.text).takeLast(HISTORY_LIMIT); state.negativePromptHistoryAt = 0L
        updateNegativePromptField(TextFieldValue(previous, TextRange(previous.length)), recordHistory = false)
    }

    fun redoNegativePrompt() {
        if (state.negativePromptRedoStack.isEmpty()) return
        val next = state.negativePromptRedoStack.last(); state.negativePromptRedoStack = state.negativePromptRedoStack.dropLast(1)
        state.negativePromptUndoStack = (state.negativePromptUndoStack + state.negativePromptFieldValue.text).takeLast(HISTORY_LIMIT); state.negativePromptHistoryAt = 0L
        updateNegativePromptField(TextFieldValue(next, TextRange(next.length)), recordHistory = false)
    }

    // ── Image handling helpers (orchestration) ────────────────

    val photoPickerLauncher = rememberLauncherForActivityResult(PickVisualMedia()) { uri -> uri?.let { processSelectedImage(state, it) } }
    val contentPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { processSelectedImage(state, it) } }
    val requestStoragePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) contentPickerLauncher.launch("image/*") else Toast.makeText(context, msgMediaPermissionHint, Toast.LENGTH_SHORT).show()
    }

    fun handleSelectImageClick() { onSelectImageClick(state, context, msgMediaPermissionHint, { photoPickerLauncher.launch(it) }, { contentPickerLauncher.launch("image/*") }, { requestStoragePermissionLauncher.launch(it) }) }

    fun handleExit() { cleanupModelRun(state, context, coroutineScope, pagerState); BackgroundGenerationService.clearCompleteState(); navController.navigateUp() }

    // ── BackHandler ───────────────────────────────────────────

    if (state.isRunning) BackHandler { state.showExitDialog = true }

    // ── Scaffold ──────────────────────────────────────────────

    Box(modifier = modifier.fillMaxSize()
        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { focusManager.clearFocus() }
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    title = {
                        if (scrollBehavior.state.collapsedFraction < 0.5f) {
                            Column {
                                Text(text = model?.name ?: "Running Model", fontWeight = FontWeight.Normal, maxLines = 1, softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()))
                                Text(text = model?.description ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()))
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { if (state.isRunning) state.showExitDialog = true else handleExit() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, scrolledContainerColor = MaterialTheme.colorScheme.surface),
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            if (model != null) {
                Column(modifier = Modifier.fillMaxSize().padding(paddingValues).consumeWindowInsets(paddingValues)) {
                    TabRow(selectedTabIndex = pagerState.currentPage) {
                        Tab(selected = pagerState.currentPage == 0, onClick = { coroutineScope.launch { focusManager.clearFocus(); pagerState.animateScrollToPage(0) } }, text = { Text(stringResource(R.string.prompt_tab)) })
                        Tab(selected = pagerState.currentPage == 1, onClick = { coroutineScope.launch { focusManager.clearFocus(); pagerState.animateScrollToPage(1) } }, text = { Text(stringResource(R.string.result_tab)) })
                        Tab(selected = pagerState.currentPage == 2, onClick = { coroutineScope.launch { focusManager.clearFocus(); pagerState.animateScrollToPage(2) } }, text = { Text(stringResource(R.string.history_tab)) })
                    }
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        when (page) {
                            0 -> ModelRunPromptPage(
                                state = state, model = model, context = context, scope = scope, coroutineScope = coroutineScope,
                                generationPreferences = generationPreferences, tagAutocompleteRepository = tagAutocompleteRepository,
                                tagAutocompleteAvailable = tagAutocompleteAvailable, tagSuggestionCount = tagSuggestionCount,
                                useImg2img = useImg2img, pagerState = pagerState, serviceState = serviceState,
                                onPromptFieldChanged = { v, r -> updatePromptField(v, r) },
                                onNegativePromptFieldChanged = { v, r -> updateNegativePromptField(v, r) },
                                onApplyPromptSuggestion = { applyPromptSuggestion(it) },
                                onApplyNegativePromptSuggestion = { applyNegativePromptSuggestion(it) },
                                onRunPromptTagAction = { runPromptTagAction(it) },
                                onRunNegativePromptTagAction = { runNegativePromptTagAction(it) },
                                onUndoPrompt = { undoPrompt() }, onRedoPrompt = { redoPrompt() },
                                onUndoNegativePrompt = { undoNegativePrompt() }, onRedoNegativePrompt = { redoNegativePrompt() },
                                onSelectImageClick = { handleSelectImageClick() },
                                onSaveAllFields = { saveAllFields(state, scope, generationPreferences, modelId) },
                                onClearImg2imgState = { state.clearImg2imgState() },
                                onStepsChange = { onStepsChange(state, it) { saveAllFields(state, scope, generationPreferences, modelId) } },
                                onCfgChange = { onCfgChange(state, it) { saveAllFields(state, scope, generationPreferences, modelId) } },
                                onSizeChange = { onSizeChange(state, it) { saveAllFields(state, scope, generationPreferences, modelId) } },
                                onDenoiseStrengthChange = { onDenoiseStrengthChange(state, it) { saveAllFields(state, scope, generationPreferences, modelId) } },
                                onSeedChange = { onSeedChange(state, it) { saveAllFields(state, scope, generationPreferences, modelId) } },
                                onBatchCountsChange = { onBatchCountsChange(state, it) { saveAllFields(state, scope, generationPreferences, modelId) } },
                                onGenerateClick = {
                                    startBatchGeneration(state, context, model, coroutineScope, effectiveSize.first, effectiveSize.second)
                                },
                                onResetAll = {
                                    state.steps = 20f; state.cfg = 7f; state.seed = ""; state.batchCounts = 1; state.scheduler = "dpm"; state.aspectRatio = "1:1"
                                    state.prompt = model.defaultPrompt; state.negativePrompt = model.defaultNegativePrompt
                                    state.promptFieldValue = TextFieldValue(state.prompt, TextRange(state.prompt.length))
                                    state.negativePromptFieldValue = TextFieldValue(state.negativePrompt, TextRange(state.negativePrompt.length))
                                    state.promptSuggestions = emptyList(); state.negativePromptSuggestions = emptyList(); state.denoiseStrength = 0.6f
                                    scope.launch(Dispatchers.IO) {
                                        generationPreferences.saveAllFields(modelId = modelId, prompt = model.defaultPrompt, negativePrompt = model.defaultNegativePrompt, steps = 20f, cfg = 7f, seed = "", width = if (model.isSdxl) 1024 else if (model.runOnCpu) 256 else 512, height = if (model.isSdxl) 1024 else if (model.runOnCpu) 256 else 512, denoiseStrength = 0.6f, useOpenCL = state.useOpenCL, batchCounts = 1, scheduler = "dpm", aspectRatio = "1:1")
                                    }
                                },
                                onShareCurrent = {
                                    val tmp = GenerationParameters(steps = state.steps.roundToInt(), cfg = state.cfg, seed = state.seed.toLongOrNull(), prompt = state.prompt, negativePrompt = state.negativePrompt, generationTime = "", width = state.currentWidth, height = state.currentHeight, runOnCpu = model.runOnCpu, denoiseStrength = state.denoiseStrength, useOpenCL = state.useOpenCL, scheduler = state.scheduler, mode = GenerationMode.TXT2IMG)
                                    state.shareSourceParams = tmp; state.shareSourceModelId = modelId
                                },
                                onPasteClipboard = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val raw = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                                    ParamShare.tryDecode(raw)?.let { state.pendingImport = it }
                                },
                            )
                            1 -> ModelRunResultPage(
                                state = state, model = model, context = context, scope = scope, coroutineScope = coroutineScope,
                                useImg2img = useImg2img, generationParamsModelId = state.generationParamsModelId,
                                pagerState = pagerState, msgImageSaved = msgImageSaved, historyItems = historyItems,
                                onSaveImage = { ctx, bmp, onOk, onErr -> handleSaveImage(state, ctx, coroutineScope, bmp, onOk, onErr) },
                                onSendToImg2img = { sendBitmapToImg2img(state, context, model, scope, it, pagerState) },
                                onPreview = { state.isPreviewMode = true },
                                onHistoryThumbnailClick = { state.selectedHistoryItem = it; state.showHistoryDetailDialog = true },
                            )
                            2 -> ModelRunHistoryPage(
                                state = state, historyItems = historyItems, knownModelIds = knownModelIds,
                                knownSchedulers = knownSchedulers, knownSizes = knownSizes, scope = scope,
                                modelId = modelId, msgImageSaved = msgImageSaved, msgDeleted = msgDeleted,
                                msgDeleteFailedMessage = msgDeleteFailedMessage, resources = resources,
                                msgSavedCountWithFailed = msgSavedCountWithFailed, msgDeletedCountWithFailed = msgDeletedCountWithFailed,
                                historyManager = historyManager,
                            )
                        }
                    }
                }
            }
        }

        // ── Crop / Inpaint / Preview overlays ──────────────────

        if (state.showCropScreen && state.imageUriForCrop != null) {
            val aspectTarget = computeAspectTargetSize(model?.isSdxl == true, state.aspectRatio)
            CropImageScreen(
                imageUri = state.imageUriForCrop!!,
                width = aspectTarget?.first ?: state.currentWidth, height = aspectTarget?.second ?: state.currentHeight,
                onCropComplete = { bs, bmp, rect -> handleCropComplete(state, context, model, scope, bs, bmp, rect) },
                onCancel = { state.showCropScreen = false; state.imageUriForCrop = null; state.selectedImageUri = null; state.hasOriginalImageForStitch = false },
            )
        }

        if (state.showInpaintScreen && state.croppedBitmap != null) {
            InpaintScreen(
                originalBitmap = state.croppedBitmap!!,
                existingMaskBitmap = if (state.isInpaintMode) state.maskBitmap else null,
                existingPathHistory = state.savedPathHistory,
                onInpaintComplete = { mbs, _, mb, ph -> handleInpaintComplete(state, context, scope, mbs, mb, ph) },
                onCancel = { state.showInpaintScreen = false },
            )
        }

        if (state.isPreviewMode && state.currentBitmap != null) {
            ZoomableImageOverlay(
                bitmap = state.currentBitmap,
                onDismiss = { state.isPreviewMode = false },
                showScaleIndicator = true,
                topEndContent = { OverlayIconButton(icon = Icons.Default.Close, contentDescription = "close preview", onClick = { state.isPreviewMode = false }) },
            )
        }
    }

    // ── Dialog invocations ────────────────────────────────────

    ModelRunExitDialog(state, onExit = { handleExit() })
    ModelRunOpenCLWarningDialog(state) { saveAllFields(state, scope, generationPreferences, modelId) }
    ModelRunResolutionChangeDialog(state, model, modelId, context, scope, generationPreferences) { resolution ->
        model?.let { m ->
            val serviceIntent = Intent(context, BackendService::class.java).apply { action = BackendService.ACTION_RESTART; putExtra("modelId", modelId); putExtra("width", resolution.width); putExtra("height", resolution.height) }
            context.startForegroundService(serviceIntent)
        }
        state.isCheckingBackend = true; state.backendRestartTrigger++
    }
    ModelRunCustomAspectRatioDialog(state,
        onClearImg2img = { state.clearImg2imgState() },
        onSaveAll = { saveAllFields(state, scope, generationPreferences, modelId) },
    )
    ModelRunHistoryDetailDialog(state, context, scope, msgImageSaved)
    ModelRunHistoryParametersDialog(state, context, useImg2img) { bmp -> sendBitmapToImg2img(state, context, model, scope, bmp, pagerState) }
    ModelRunReproduceParamsDialog(state, model, scope, pagerState, useImg2img,
        onSaveAll = { saveAllFields(state, scope, generationPreferences, modelId) },
        onClearImg2img = { state.clearImg2imgState() },
    )
    ModelRunDeleteHistoryDialog(state, historyManager, scope, msgDeleted, msgDeleteFailedMessage)
    ModelRunBatchSaveDialog(state, context, scope, msgSavedCountWithFailed, resources)
    ModelRunBatchSaveProgressDialog(state)
    ModelRunBatchDeleteDialog(state, historyManager, scope, msgDeletedCountWithFailed, resources)
    ModelRunShareParamsDialog(state, context, scope, generationPreferences, shareUseBase64, msgShareCopied)
    ModelRunImportParamsDialog(state, context, scope, generationPreferences, shareClearClipboardOnImport, msgImportApplied) {
        saveAllFields(state, scope, generationPreferences, modelId)
    }
    ModelRunBlockingOverlays(state)
    ModelRunUpscalerDialog(state, modelId, upscalerRepository, upscalerPreferences, context, historyManager, scope, msgDownloadDone, msgErrorDownloadFailed, msgDownloadModelFirst, msgUpscaleFailed)
}
