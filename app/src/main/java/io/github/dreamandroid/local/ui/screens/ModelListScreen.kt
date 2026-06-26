package io.github.dreamandroid.local.ui.screens

/*
 * NOTE: This file is no longer in use.
 *
 * ModelListScreen was the original model list / management screen. It has been
 * replaced by the tab-based UI (BottomTab Models → AppContentTabModels).
 * This file and its companion files are kept solely as reference.
 *
 * Do NOT add new navigation routes pointing to ModelListScreen.
 */

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.core.content.edit
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.*
import io.github.dreamandroid.local.navigation.Screen
import io.github.dreamandroid.local.service.ModelDownloadService
import io.github.dreamandroid.local.ui.components.BlockingProgressOverlay
import io.github.dreamandroid.local.ui.components.SmoothCircularWavyProgressIndicator
import io.github.dreamandroid.local.ui.components.SmoothLinearWavyProgressIndicator
import io.github.dreamandroid.local.ui.screens.model.*
import io.github.dreamandroid.local.ui.theme.Motion
import io.github.dreamandroid.local.utils.LogCapture
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
)
@Composable
fun ModelListScreen(navController: NavController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = context.resources
    val scope = rememberCoroutineScope()

    // String resources hoisted to composable scope (lint: LocalContextGetResourceValueCall).
    val msgDownloadDone = stringResource(R.string.download_done)
    val msgFileDeleted = stringResource(R.string.file_deleted)
    val msgEmbeddingDeleted = stringResource(R.string.embedding_deleted)
    val msgEmbeddingImported = stringResource(R.string.embedding_imported)
    val msgLogSaved = stringResource(R.string.log_saved)
    val msgLogSaveFailed = stringResource(R.string.log_save_failed)
    val msgModelConversionSuccess = stringResource(R.string.model_conversion_success)
    val msgModelConversionFailed = stringResource(R.string.model_conversion_failed)
    val msgNpuModelAddedSuccess = stringResource(R.string.npu_model_added_success)
    val msgNpuModelAddFailed = stringResource(R.string.npu_model_add_failed)
    val msgDeleteSuccess = stringResource(R.string.delete_success)
    val msgDeleteFailed = stringResource(R.string.delete_failed)
    val msgUnsupportNpu = stringResource(R.string.unsupport_npu)
    val msgTagImportFailed = stringResource(R.string.tag_import_failed)

    var downloadingModel by remember { mutableStateOf<Model?>(null) }
    var currentProgress by remember { mutableStateOf<DownloadProgress?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var showDownloadConfirm by remember { mutableStateOf<Model?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showUpgradeConfirm by remember { mutableStateOf<Model?>(null) }

    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedModels by remember { mutableStateOf(setOf<Model>()) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showFileManagerDialog by remember { mutableStateOf(false) }
    var showEmbeddingManagerDialog by remember { mutableStateOf(false) }
    var showCustomModelDialog by remember { mutableStateOf(false) }
    var showCustomNpuModelDialog by remember { mutableStateOf(false) }
    var isConverting by remember { mutableStateOf(false) }
    var conversionProgress by remember { mutableStateOf("") }
    var extractByteProgress by remember { mutableStateOf<ExtractByteProgress?>(null) }
    var tempBaseUrl by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf("huggingface") }
    val generationPreferences = remember { GenerationPreferences(context) }
    var currentBaseUrl by remember { mutableStateOf("https://huggingface.co/") }

    var version by remember { mutableIntStateOf(0) }
    val modelRepository = remember(version) { ModelRepository(context) }

    var showHelpDialog by remember { mutableStateOf(false) }

    val isFirstLaunch = remember {
        val preferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val isFirst = preferences.getBoolean("is_first_launch", true)
        if (isFirst) {
            preferences.edit { putBoolean("is_first_launch", false) }
        }
        isFirst
    }

    val downloadState by ModelDownloadService.downloadState.collectAsState()

    LaunchedEffect(downloadState) {
        when (val state = downloadState) {
            is ModelDownloadService.DownloadState.Downloading -> {
                val model = modelRepository.models.find { it.id == state.modelId }
                if (model != null) {
                    downloadingModel = model
                    currentProgress = DownloadProgress(
                        progress = state.progress,
                        downloadedBytes = state.downloadedBytes,
                        totalBytes = state.totalBytes,
                    )
                }
            }

            is ModelDownloadService.DownloadState.Extracting -> {
                val model = modelRepository.models.find { it.id == state.modelId }
                if (model != null) {
                    downloadingModel = model
                    currentProgress = null
                }
            }

            is ModelDownloadService.DownloadState.Success -> {
                modelRepository.refreshModelState(state.modelId)
                downloadingModel = null
                currentProgress = null
                snackbarHostState.showSnackbar(msgDownloadDone)
            }

            is ModelDownloadService.DownloadState.Error -> {
                downloadingModel = null
                currentProgress = null
                downloadError = state.message
            }

            is ModelDownloadService.DownloadState.Idle -> {
                if (downloadingModel != null) {
                    downloadingModel = null
                    currentProgress = null
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (isFirstLaunch) {
            showHelpDialog = true
        }
        scope.launch {
            currentBaseUrl = generationPreferences.getBaseUrl()
            selectedSource = generationPreferences.getSelectedSource()
        }
    }

    val cpuModels = remember(modelRepository.models) {
        modelRepository.models.filter { it.runOnCpu }
    }
    val npuModels = remember(modelRepository.models) {
        modelRepository.models.filter { !it.runOnCpu }
    }

    val lastViewedPage = remember {
        val preferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        preferences.getInt("last_viewed_page", 0)
    }

    val pagerState = rememberPagerState(
        initialPage = lastViewedPage,
        pageCount = { 2 },
    )

    LaunchedEffect(pagerState.currentPage) {
        val preferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        preferences.edit { putInt("last_viewed_page", pagerState.currentPage) }
    }

    val tabTitles = listOf(
        stringResource(R.string.cpu_models),
        stringResource(R.string.npu_models),
    )

    if (isSelectionMode) {
        BackHandler {
            isSelectionMode = false
            selectedModels = emptySet()
        }
    }
    LaunchedEffect(downloadError) {
        downloadError?.let {
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = it,
                    duration = SnackbarDuration.Short,
                )
                downloadError = null
            }
        }
    }
    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.about_app)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                ) {
                    val mustReadText = stringResource(R.string.must_read)
                    val githubUrl = "https://github.com/xororz/local-dream"
                    val linkColor = MaterialTheme.colorScheme.primary

                    val annotatedString = buildAnnotatedString {
                        val startIndex = mustReadText.indexOf(githubUrl)
                        if (startIndex >= 0) {
                            append(mustReadText.substring(0, startIndex))
                            withLink(
                                LinkAnnotation.Url(
                                    url = githubUrl,
                                    styles = TextLinkStyles(
                                        style = SpanStyle(
                                            color = linkColor,
                                            textDecoration = TextDecoration.Underline,
                                        ),
                                    ),
                                ),
                            ) {
                                append(githubUrl)
                            }
                            append(mustReadText.substring(startIndex + githubUrl.length))
                        } else {
                            append(mustReadText)
                        }
                    }

                    Text(
                        text = annotatedString,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text(stringResource(R.string.got_it))
                }
            },
        )
    }

    LaunchedEffect(showSettingsDialog) {
        if (showSettingsDialog) {
            tempBaseUrl = currentBaseUrl
        }
    }

    if (showFileManagerDialog) {
        FileManagerDialog(
            context = context,
            onDismiss = { showFileManagerDialog = false },
            onFileDeleted = {
                modelRepository.refreshAllModels()
                scope.launch {
                    snackbarHostState.showSnackbar(msgFileDeleted)
                }
            },
        )
    }

    if (showEmbeddingManagerDialog) {
        EmbeddingManagerDialog(
            context = context,
            onDismiss = { showEmbeddingManagerDialog = false },
            onEmbeddingDeleted = {
                scope.launch {
                    snackbarHostState.showSnackbar(msgEmbeddingDeleted)
                }
            },
            onEmbeddingImported = {
                scope.launch {
                    snackbarHostState.showSnackbar(msgEmbeddingImported)
                }
            },
        )
    }

    val capturedLogs = LogCapture.lastCapturedLogs.value
    if (capturedLogs != null) {
        AlertDialog(
            onDismissRequest = { LogCapture.consume() },
            title = { Text(stringResource(R.string.captured_logs_title)) },
            text = {
                if (capturedLogs.isBlank()) {
                    Text(stringResource(R.string.no_logs_captured))
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                MaterialTheme.shapes.extraSmall,
                            )
                            .padding(8.dp),
                    ) {
                        Text(
                            text = capturedLogs,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                        .format(Date())
                    val filename = "DreamHub_log_$timestamp.log"
                    scope.launch(Dispatchers.IO) {
                        val savedPath = try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val values = ContentValues().apply {
                                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                                    put(
                                        MediaStore.Downloads.RELATIVE_PATH,
                                        Environment.DIRECTORY_DOWNLOADS + "/dreamhub",
                                    )
                                }
                                val resolver = context.contentResolver
                                val uri = resolver.insert(
                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                    values,
                                ) ?: throw java.io.IOException("MediaStore insert failed")
                                resolver.openOutputStream(uri)?.use { out ->
                                    out.write(capturedLogs.toByteArray(Charsets.UTF_8))
                                } ?: throw java.io.IOException("openOutputStream failed")
                                "Downloads/dreamhub/$filename"
                            } else {
                                val dir = File(
                                    Environment.getExternalStoragePublicDirectory(
                                        Environment.DIRECTORY_DOWNLOADS,
                                    ),
                                    "dreamhub",
                                )
                                if (!dir.exists()) dir.mkdirs()
                                val file = File(dir, filename)
                                file.outputStream().use { out ->
                                    out.write(capturedLogs.toByteArray(Charsets.UTF_8))
                                }
                                file.absolutePath
                            }
                        } catch (e: Exception) {
                            Log.e("LogCapture", "save failed", e)
                            null
                        }
                        withContext(Dispatchers.Main) {
                            val msg = if (savedPath != null) {
                                msgLogSaved.format(savedPath)
                            } else {
                                msgLogSaveFailed
                            }
                            snackbarHostState.showSnackbar(msg)
                            LogCapture.consume()
                        }
                    }
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { LogCapture.consume() }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    if (showCustomModelDialog) {
        CustomModelDialog(
            context,
            onDismiss = { showCustomModelDialog = false },
            onModelAdded = { modelName, fileUri, clipSkip, loraFiles ->
                showCustomModelDialog = false
                scope.launch {
                    convertCustomModel(
                        context = context,
                        modelName = modelName,
                        fileUri = fileUri,
                        clipSkip = clipSkip,
                        loraFiles = loraFiles,
                        onProgress = { progress ->
                            conversionProgress = progress
                        },
                        onStart = {
                            isConverting = true
                        },
                        onSuccess = {
                            isConverting = false
                            modelRepository.refreshAllModels()
                            scope.launch {
                                snackbarHostState.showSnackbar(msgModelConversionSuccess)
                            }
                        },
                        onError = { error ->
                            isConverting = false
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    msgModelConversionFailed.format(error),
                                )
                            }
                        },
                    )
                }
            },
        )
    }

    if (showCustomNpuModelDialog) {
        CustomNpuModelDialog(
            context,
            onDismiss = { showCustomNpuModelDialog = false },
            onModelAdded = { modelName, zipUri ->
                showCustomNpuModelDialog = false
                scope.launch {
                    extractNpuModel(
                        context = context,
                        modelName = modelName,
                        zipUri = zipUri,
                        onProgress = { progress ->
                            conversionProgress = progress
                        },
                        onByteProgress = { extracted, total, fraction ->
                            extractByteProgress = ExtractByteProgress(extracted, total, fraction)
                        },
                        onStart = {
                            extractByteProgress = null
                            isConverting = true
                        },
                        onSuccess = {
                            isConverting = false
                            extractByteProgress = null
                            modelRepository.refreshAllModels()
                            scope.launch {
                                snackbarHostState.showSnackbar(msgNpuModelAddedSuccess)
                            }
                        },
                        onError = { error ->
                            isConverting = false
                            extractByteProgress = null
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    msgNpuModelAddFailed.format(error),
                                )
                            }
                        },
                    )
                }
            },
        )
    }

    if (showDeleteConfirm && selectedModels.isNotEmpty()) {
        DeleteConfirmDialog(
            selectedCount = selectedModels.size,
            onConfirm = {
                showDeleteConfirm = false
                isSelectionMode = false

                scope.launch {
                    var successCount = 0
                    selectedModels.forEach { model ->
                        if (model.deleteModel(context)) {
                            successCount++
                        }
                    }

                    modelRepository.refreshAllModels()

                    snackbarHostState.showSnackbar(
                        if (successCount == selectedModels.size) {
                            msgDeleteSuccess
                        } else {
                            msgDeleteFailed
                        },
                    )

                    selectedModels = emptySet()
                }
            },
            onDismiss = {
                showDeleteConfirm = false
            },
        )
    }

    showDownloadConfirm?.let { model ->
        if (downloadingModel != null) {
            AlertDialog(
                onDismissRequest = { showDownloadConfirm = null },
                title = { Text(stringResource(R.string.cannot_download)) },
                text = { Text(stringResource(R.string.cannot_download_hint)) },
                confirmButton = {
                    TextButton(onClick = { showDownloadConfirm = null }) {
                        Text(stringResource(R.string.confirm))
                    }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { showDownloadConfirm = null },
                title = { Text(stringResource(R.string.download_model)) },
                text = {
                    Text(stringResource(R.string.download_model_hint, model.name))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDownloadConfirm = null
                            downloadingModel = model
                            currentProgress = null
                            model.startDownload(context)
                        },
                    ) {
                        Text(stringResource(R.string.confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDownloadConfirm = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }

    showUpgradeConfirm?.let { model ->
        AlertDialog(
            onDismissRequest = { showUpgradeConfirm = null },
            title = { Text(stringResource(R.string.upgrade_model)) },
            text = {
                Text(stringResource(R.string.upgrade_model_hint, model.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUpgradeConfirm = null
                        downloadingModel = model
                        currentProgress = null
                        model.startDownload(context)
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpgradeConfirm = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(
                            text = "DreamHub ✨",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (isSelectionMode) {
                                pluralStringResource(
                                    R.plurals.selected_items,
                                    selectedModels.size,
                                    selectedModels.size,
                                )
                            } else {
                                stringResource(R.string.available_models)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    if (isSelectionMode) {
                        IconButton(onClick = {
                            isSelectionMode = false
                            selectedModels = emptySet()
                        }) {
                            Icon(Icons.Default.Close, stringResource(R.string.cancel))
                        }
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        if (selectedModels.isNotEmpty()) {
                            IconButton(onClick = { showDeleteConfirm = true }) {
                                Icon(Icons.Default.Delete, stringResource(R.string.delete))
                            }
                        }
                    } else {
                        val helpLabel = stringResource(R.string.help)
                        val upscaleLabel = stringResource(R.string.image_upscale)
                        val settingsLabel = stringResource(R.string.settings)
                        IconButton(onClick = { showHelpDialog = true }) {
                            Icon(Icons.AutoMirrored.Filled.Help, helpLabel)
                        }
                        if (Model.isQualcommDevice()) {
                            IconButton(onClick = { navController.navigate(Screen.Upscale.route) }) {
                                Icon(Icons.Default.AutoFixHigh, upscaleLabel)
                            }
                        }
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(Icons.Default.Settings, settingsLabel)
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            PrimaryTabRow(
                selectedTabIndex = pagerState.currentPage,
                modifier = Modifier.fillMaxWidth(),
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        text = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleSmall,
                            )
                        },
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                val models = if (page == 0) cpuModels else npuModels

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (page == 0) {
                        item {
                            AddCustomModelButton(
                                onClick = { showCustomModelDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    if (page == 1 && Model.isQualcommDevice()) {
                        item {
                            AddCustomNpuModelButton(
                                onClick = { showCustomNpuModelDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    items(
                        items = models,
                        key = { model -> "${model.id}_$version" },
                    ) { model ->
                        ModelCard(
                            model = model,
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(Motion.DurationMedium),
                                fadeOutSpec = tween(Motion.DurationMedium),
                                placementSpec = Motion.springExpressiveSpatial(),
                            ),
                            isSelected = selectedModels.contains(model),
                            isSelectionMode = isSelectionMode,
                            onClick = {
                                if (!Model.isDeviceSupported() && !model.runOnCpu) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(msgUnsupportNpu)
                                    }
                                    return@ModelCard
                                }
                                if (isSelectionMode) {
                                    if (model.isDownloaded) {
                                        selectedModels = if (selectedModels.contains(model)) {
                                            selectedModels - model
                                        } else {
                                            selectedModels + model
                                        }

                                        if (selectedModels.isEmpty()) {
                                            isSelectionMode = false
                                        }
                                    }
                                } else {
                                    if (!model.isDownloaded) {
                                        showDownloadConfirm = model
                                    } else {
                                        navController.navigate(Screen.ModelRun.createRoute(model.id))
                                    }
                                }
                            },
                            onLongClick = {
                                if (model.isDownloaded && !isSelectionMode) {
                                    isSelectionMode = true
                                    selectedModels = setOf(model)
                                }
                            },
                            onUpdateClick = {
                                showUpgradeConfirm = model
                            },
                        )
                    }

                    if (models.isEmpty()) {
                        item {
                            var visible by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) { visible = true }
                            AnimatedVisibility(
                                visible = visible,
                                enter = fadeIn(animationSpec = Motion.Fade) + expandVertically(),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SearchOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = if (page == 0) {
                                            stringResource(R.string.no_cpu_models)
                                        } else {
                                            stringResource(R.string.no_npu_models)
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                TabPageIndicator(
                    pageCount = 2,
                    currentPage = pagerState.currentPage,
                )
            }
        }
    }

    // Settings overlay with predictive back support.
    // drawerOffset: 0f = fully open, 1f = fully off-screen to the right.
    val drawerOffset = remember { Animatable(1f) }
    val drawerAnimSpec = tween<Float>(Motion.DurationLong, easing = Motion.Emphasized)
    LaunchedEffect(showSettingsDialog) {
        drawerOffset.animateTo(
            targetValue = if (showSettingsDialog) 0f else 1f,
            animationSpec = drawerAnimSpec,
        )
    }
    if (showSettingsDialog) {
        PredictiveBackHandler { progressFlow ->
            try {
                progressFlow.collect { event ->
                    drawerOffset.snapTo(event.progress)
                }
                // Committed: close the drawer; LaunchedEffect finishes the animation.
                showSettingsDialog = false
            } catch (_: CancellationException) {
                // Cancelled: slide back to open.
                drawerOffset.animateTo(0f, animationSpec = drawerAnimSpec)
            }
        }
    }
    if (drawerOffset.value < 1f) {
        val settingsScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = size.width * drawerOffset.value }
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Scaffold(
                modifier = Modifier.nestedScroll(settingsScrollBehavior.nestedScrollConnection),
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.settings)) },
                        navigationIcon = {
                            IconButton(onClick = { showSettingsDialog = false }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    stringResource(R.string.back),
                                )
                            }
                        },
                        scrollBehavior = settingsScrollBehavior,
                    )
                },
            ) { paddingValues ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    contentPadding = PaddingValues(vertical = 16.dp),
                ) {
                    // Download source settings section
                    item {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(bottom = 12.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudDownload,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    stringResource(R.string.download_source),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Text(
                                stringResource(R.string.download_settings_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 12.dp),
                            )

                            var expanded by remember { mutableStateOf(false) }
                            val focusRequester = remember { FocusRequester() }

                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = { expanded = !expanded },
                            ) {
                                OutlinedTextField(
                                    value = when (selectedSource) {
                                        "huggingface" -> "https://huggingface.co/"
                                        "hf-mirror" -> "https://hf-mirror.com/"
                                        else -> tempBaseUrl
                                    },
                                    onValueChange = {
                                        if (selectedSource == "custom") tempBaseUrl = it
                                    },
                                    label = { Text(stringResource(R.string.download_from)) },
                                    readOnly = selectedSource != "custom",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(focusRequester)
                                        .onFocusChanged { focusState ->
                                            if (!focusState.isFocused && selectedSource == "custom") {
                                                scope.launch {
                                                    if (tempBaseUrl.isNotEmpty() && tempBaseUrl != currentBaseUrl) {
                                                        generationPreferences.saveBaseUrl(
                                                            tempBaseUrl,
                                                        )
                                                        currentBaseUrl = tempBaseUrl
                                                        version += 1
                                                    }
                                                }
                                            }
                                        },
                                    trailingIcon = {
                                        IconButton(onClick = {}) {
                                            ExposedDropdownMenuDefaults.TrailingIcon(
                                                expanded = expanded,
                                            )
                                        }
                                    },
                                    singleLine = true,
                                )

                                LaunchedEffect(selectedSource) {
                                    if (selectedSource == "custom") {
                                        focusRequester.requestFocus()
                                    }
                                }
                                ExposedDropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.source_huggingface)) },
                                        onClick = {
                                            selectedSource = "huggingface"
                                            val newUrl = "https://huggingface.co/"
                                            tempBaseUrl = newUrl
                                            expanded = false
                                            scope.launch {
                                                generationPreferences.saveSelectedSource("huggingface")
                                                generationPreferences.saveBaseUrl(newUrl)
                                                if (currentBaseUrl != newUrl) {
                                                    currentBaseUrl = newUrl
                                                    version += 1
                                                }
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.source_hf_mirror)) },
                                        onClick = {
                                            selectedSource = "hf-mirror"
                                            val newUrl = "https://hf-mirror.com/"
                                            tempBaseUrl = newUrl
                                            expanded = false
                                            scope.launch {
                                                generationPreferences.saveSelectedSource("hf-mirror")
                                                generationPreferences.saveBaseUrl(newUrl)
                                                if (currentBaseUrl != newUrl) {
                                                    currentBaseUrl = newUrl
                                                    version += 1
                                                }
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.source_custom)) },
                                        onClick = {
                                            selectedSource = "custom"
                                            tempBaseUrl = "https://"
                                            expanded = false
                                            scope.launch {
                                                generationPreferences.saveSelectedSource("custom")
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    // Appearance (theme) section
                    item { AppearanceSection() }
                    // Feature settings section
                    item {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(bottom = 12.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    stringResource(R.string.feature_settings),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                            ) {
                                val preferences = LocalContext.current.getSharedPreferences(
                                    "app_prefs",
                                    Context.MODE_PRIVATE,
                                )
                                var useImg2img by remember {
                                    mutableStateOf(
                                        preferences.getBoolean("use_img2img", true).also {
                                            if (!preferences.contains("use_img2img")) {
                                                preferences.edit {
                                                    putBoolean(
                                                        "use_img2img",
                                                        true,
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }
                                var showProcess by remember {
                                    mutableStateOf(
                                        preferences.getBoolean("show_diffusion_process", false),
                                    )
                                }
                                var captureLogs by remember {
                                    mutableStateOf(
                                        preferences.getBoolean("enable_log_capture", false),
                                    )
                                }
                                var listenOnAllAddresses by remember {
                                    mutableStateOf(
                                        preferences.getBoolean("listen_on_all_addresses", false),
                                    )
                                }
                                var enableTagAutocomplete by remember {
                                    mutableStateOf(
                                        preferences.getBoolean("enable_tag_autocomplete", true)
                                            .also {
                                                if (!preferences.contains("enable_tag_autocomplete")) {
                                                    preferences.edit {
                                                        putBoolean("enable_tag_autocomplete", true)
                                                    }
                                                }
                                            },
                                    )
                                }
                                val tagRepository =
                                    remember { TagAutocompleteRepository.getInstance(context) }
                                val tagDictState by tagRepository.state.collectAsState()
                                var tagImportInProgress by remember { mutableStateOf(false) }
                                val mainCsvPickerLauncher = rememberLauncherForActivityResult(
                                    contract = ActivityResultContracts.GetContent(),
                                ) { uri ->
                                    if (uri == null) return@rememberLauncherForActivityResult
                                    val displayName = getFileNameFromUri(context, uri)
                                    tagImportInProgress = true
                                    scope.launch {
                                        val result = tagRepository.importMainCsv(uri, displayName)
                                        tagImportInProgress = false
                                        val message = when (result) {
                                            is ImportResult.Success ->
                                                resources.getQuantityString(
                                                    R.plurals.tag_import_success,
                                                    result.lineCount,
                                                    result.lineCount,
                                                )

                                            is ImportResult.Error -> msgTagImportFailed
                                        }
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    }
                                }
                                val translationCsvPickerLauncher =
                                    rememberLauncherForActivityResult(
                                        contract = ActivityResultContracts.GetContent(),
                                    ) { uri ->
                                        if (uri == null) return@rememberLauncherForActivityResult
                                        val displayName = getFileNameFromUri(context, uri)
                                        tagImportInProgress = true
                                        scope.launch {
                                            val result =
                                                tagRepository.importTranslationCsv(uri, displayName)
                                            tagImportInProgress = false
                                            val message = when (result) {
                                                is ImportResult.Success ->
                                                    resources.getQuantityString(
                                                        R.plurals.tag_import_success,
                                                        result.lineCount,
                                                        result.lineCount,
                                                    )

                                                is ImportResult.Error -> msgTagImportFailed
                                            }
                                            Toast.makeText(context, message, Toast.LENGTH_SHORT)
                                                .show()
                                        }
                                    }
                                var sdxlLowRam by remember {
                                    mutableStateOf(
                                        preferences.getBoolean("sdxl_lowram", true).also {
                                            if (!preferences.contains("sdxl_lowram")) {
                                                preferences.edit {
                                                    putBoolean("sdxl_lowram", true)
                                                }
                                            }
                                        },
                                    )
                                }

                                SwitchSettingRow(
                                    title = "img2img",
                                    description = stringResource(R.string.img2img_hint),
                                    checked = useImg2img,
                                    onCheckedChange = {
                                        useImg2img = it
                                        preferences.edit { putBoolean("use_img2img", it) }
                                    },
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                SwitchSettingRow(
                                    title = stringResource(R.string.show_process),
                                    description = stringResource(R.string.show_process_hint),
                                    checked = showProcess,
                                    onCheckedChange = {
                                        showProcess = it
                                        preferences.edit {
                                            putBoolean("show_diffusion_process", it)
                                        }
                                    },
                                )
                                AnimatedVisibility(visible = showProcess) {
                                    Column {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                        )
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                        ) {
                                            var stride by remember {
                                                mutableFloatStateOf(
                                                    preferences.getInt("show_diffusion_stride", 1)
                                                        .toFloat(),
                                                )
                                            }
                                            Text(
                                                text = stringResource(R.string.preview_stride),
                                                style = MaterialTheme.typography.titleSmall,
                                            )
                                            Text(
                                                pluralStringResource(
                                                    R.plurals.preview_stride_hint,
                                                    stride.toInt(),
                                                    stride.toInt(),
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Slider(
                                                value = stride,
                                                onValueChange = {
                                                    stride = it
                                                    preferences.edit {
                                                        putInt("show_diffusion_stride", it.toInt())
                                                    }
                                                },
                                                valueRange = 1f..10f,
                                                steps = 8,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                    }
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                SwitchSettingRow(
                                    title = stringResource(R.string.capture_logs),
                                    description = stringResource(R.string.capture_logs_hint),
                                    checked = captureLogs,
                                    onCheckedChange = {
                                        captureLogs = it
                                        preferences.edit {
                                            putBoolean("enable_log_capture", it)
                                        }
                                    },
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                SwitchSettingRow(
                                    title = stringResource(R.string.tag_autocomplete),
                                    description = stringResource(R.string.tag_autocomplete_hint),
                                    checked = enableTagAutocomplete,
                                    onCheckedChange = {
                                        enableTagAutocomplete = it
                                        preferences.edit {
                                            putBoolean("enable_tag_autocomplete", it)
                                        }
                                    },
                                )
                                AnimatedVisibility(visible = enableTagAutocomplete) {
                                    Column {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                        )
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                        ) {
                                            Text(
                                                text = stringResource(R.string.tag_main_dictionary),
                                                style = MaterialTheme.typography.titleSmall,
                                            )
                                            Text(
                                                text = if (tagDictState.mainImported) {
                                                    pluralStringResource(
                                                        R.plurals.tag_imported_status,
                                                        tagDictState.mainEntryCount,
                                                        tagDictState.mainFileName ?: "",
                                                        tagDictState.mainEntryCount,
                                                    )
                                                } else {
                                                    stringResource(R.string.tag_main_dictionary_hint)
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 8.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Button(
                                                    onClick = { mainCsvPickerLauncher.launch("*/*") },
                                                    enabled = !tagImportInProgress,
                                                    modifier = Modifier.weight(1f),
                                                ) {
                                                    Text(
                                                        if (tagDictState.mainImported) {
                                                            stringResource(R.string.tag_reimport)
                                                        } else {
                                                            stringResource(R.string.tag_import)
                                                        },
                                                    )
                                                }
                                                if (tagDictState.mainImported) {
                                                    OutlinedButton(
                                                        onClick = { tagRepository.clearMainCsv() },
                                                        enabled = !tagImportInProgress,
                                                    ) {
                                                        Text(stringResource(R.string.tag_clear))
                                                    }
                                                }
                                            }
                                        }
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                        )
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                        ) {
                                            Text(
                                                text = stringResource(R.string.tag_translation_dictionary),
                                                style = MaterialTheme.typography.titleSmall,
                                            )
                                            Text(
                                                text = if (tagDictState.translationImported) {
                                                    pluralStringResource(
                                                        R.plurals.tag_imported_status,
                                                        tagDictState.translationEntryCount,
                                                        tagDictState.translationFileName ?: "",
                                                        tagDictState.translationEntryCount,
                                                    )
                                                } else {
                                                    stringResource(R.string.tag_translation_dictionary_hint)
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 8.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Button(
                                                    onClick = {
                                                        translationCsvPickerLauncher.launch(
                                                            "*/*",
                                                        )
                                                    },
                                                    enabled = !tagImportInProgress,
                                                    modifier = Modifier.weight(1f),
                                                ) {
                                                    Text(
                                                        if (tagDictState.translationImported) {
                                                            stringResource(R.string.tag_reimport)
                                                        } else {
                                                            stringResource(R.string.tag_import)
                                                        },
                                                    )
                                                }
                                                if (tagDictState.translationImported) {
                                                    OutlinedButton(
                                                        onClick = { tagRepository.clearTranslationCsv() },
                                                        enabled = !tagImportInProgress,
                                                    ) {
                                                        Text(stringResource(R.string.tag_clear))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                SwitchSettingRow(
                                    title = stringResource(R.string.sdxl_lowram),
                                    description = stringResource(R.string.sdxl_lowram_hint),
                                    checked = sdxlLowRam,
                                    onCheckedChange = {
                                        sdxlLowRam = it
                                        preferences.edit { putBoolean("sdxl_lowram", it) }
                                    },
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                SwitchSettingRow(
                                    title = stringResource(R.string.listen_on_all_addresses),
                                    description = stringResource(R.string.listen_on_all_addresses_hint),
                                    checked = listenOnAllAddresses,
                                    onCheckedChange = {
                                        listenOnAllAddresses = it
                                        preferences.edit {
                                            putBoolean("listen_on_all_addresses", it)
                                        }
                                    },
                                )
                                // ---- Timeout / interval settings ----
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                ) {
                                    var genTimeout by remember {
                                        mutableIntStateOf(
                                            preferences.getInt("generation_timeout_s", 60),
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.generation_timeout),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = stringResource(R.string.generation_timeout_hint, genTimeout),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Slider(
                                        value = genTimeout.toFloat(),
                                        onValueChange = {
                                            genTimeout = it.toInt()
                                            preferences.edit {
                                                putInt("generation_timeout_s", it.toInt())
                                            }
                                        },
                                        valueRange = 10f..300f,
                                        steps = 28,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                ) {
                                    var bitmapTimeout by remember {
                                        mutableIntStateOf(
                                            preferences.getInt("bitmap_consumed_timeout_s", 30),
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.bitmap_consumed_timeout),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = stringResource(R.string.bitmap_consumed_timeout_hint, bitmapTimeout),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Slider(
                                        value = bitmapTimeout.toFloat(),
                                        onValueChange = {
                                            bitmapTimeout = it.toInt()
                                            preferences.edit {
                                                putInt("bitmap_consumed_timeout_s", it.toInt())
                                            }
                                        },
                                        valueRange = 5f..120f,
                                        steps = 22,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                ) {
                                    var healthInterval by remember {
                                        mutableIntStateOf(
                                            preferences.getInt("health_check_retry_interval_s", 20),
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.health_check_retry_interval),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = stringResource(R.string.health_check_retry_interval_hint, healthInterval),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Slider(
                                        value = healthInterval.toFloat(),
                                        onValueChange = {
                                            healthInterval = it.toInt()
                                            preferences.edit {
                                                putInt("health_check_retry_interval_s", it.toInt())
                                            }
                                        },
                                        valueRange = 5f..120f,
                                        steps = 22,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                ) {
                                    var maxFails by remember {
                                        mutableIntStateOf(
                                            preferences.getInt("health_check_max_failures", 4),
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.health_check_max_failures),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = stringResource(R.string.health_check_max_failures_hint, maxFails),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Slider(
                                        value = maxFails.toFloat(),
                                        onValueChange = {
                                            maxFails = it.toInt()
                                            preferences.edit {
                                                putInt("health_check_max_failures", it.toInt())
                                            }
                                        },
                                        valueRange = 1f..10f,
                                        steps = 8,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                    // Embedding management
                    item {
                        SettingNavCard(
                            icon = Icons.Default.Description,
                            label = stringResource(R.string.embedding_manager),
                            onClick = { showEmbeddingManagerDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // File management
                    item {
                        SettingNavCard(
                            icon = Icons.Default.FolderOpen,
                            label = stringResource(R.string.file_manager),
                            onClick = { showFileManagerDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    BlockingProgressOverlay(visible = isConverting) {
        val byteProgress = extractByteProgress
        if (byteProgress != null) {
            SmoothCircularWavyProgressIndicator(
                progress = byteProgress.fraction,
                modifier = Modifier.size(72.dp),
            )
            Text(
                text = "${(byteProgress.fraction * 100).toInt()}%  ${formatBytes(byteProgress.extractedBytes)}",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            CircularProgressIndicator()
            Text(
                text = if (conversionProgress.isNotEmpty()) {
                    conversionProgress
                } else {
                    stringResource(R.string.converting)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    BlockingProgressOverlay(
        visible = downloadingModel != null,
        minWidth = 320.dp,
        innerPadding = 24.dp,
        verticalSpacing = 24.dp,
    ) {
        Text(
            text = stringResource(R.string.downloading_model, downloadingModel?.name ?: ""),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        currentProgress?.let { progress ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SmoothLinearWavyProgressIndicator(
                    progress = progress.progress,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "${(progress.progress * 100).toInt()}% - ${formatBytes(progress.downloadedBytes)} / ${
                        formatBytes(progress.totalBytes)
                    }",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } ?: Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.extracting),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = stringResource(R.string.download_background_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
