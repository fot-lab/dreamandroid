package io.github.dreamandroid.local.ui.frontend

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.*
import io.github.dreamandroid.local.service.backend.BackendManager
import kotlinx.coroutines.launch

// =========== Top App Bars ===========

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsTopBar(
    drawerState: DrawerState,
    modelViewSelectedCount: Int,
    isModelLoaded: Boolean,
    isModelLoading: Boolean,
    onLoadModel: () -> Unit,
    onUnloadModel: () -> Unit,
    onImportModel: () -> Unit = {},
    onImportNpuModel: () -> Unit = {},
    onImportUpscaleModel: () -> Unit = {},
    onDownloadManager: () -> Unit = {},
    onRenameModel: () -> Unit = {},
    onDeleteModel: () -> Unit = {},
    // ── ModelView multi-selection (3-dot dropdown) ─────────────
    modelViewOnSelectAll: () -> Unit = {},
    modelViewOnInvertSelection: () -> Unit = {},
    modelViewOnDeselectAll: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var showImportMenu by remember { mutableStateOf(false) }
    var showModelViewMenu by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            // Start / Stop model service — left-aligned next to drawer icon
            if (isModelLoaded) {
                if (isModelLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
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
            } else if (modelViewSelectedCount == 1) {
                TextButton(onClick = onLoadModel) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.load_model))
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                Icon(Icons.Default.Menu, stringResource(R.string.settings))
            }
        },
        actions = {
            when {
                modelViewSelectedCount == 1 -> {
                    // Edit: only when exactly 1 model is selected
                    IconButton(onClick = onRenameModel) {
                        Icon(Icons.Default.Edit, stringResource(R.string.rename_model))
                    }
                    // Delete: replaces Add/+ position
                    IconButton(onClick = onDeleteModel) {
                        Icon(Icons.Default.Delete, stringResource(R.string.delete_model))
                    }
                }
                modelViewSelectedCount > 1 -> {
                    // Delete only
                    IconButton(onClick = onDeleteModel) {
                        Icon(Icons.Default.Delete, stringResource(R.string.delete_model))
                    }
                }
                else -> {
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
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.download_manager_menu)) },
                                onClick = {
                                    showImportMenu = false
                                    onDownloadManager()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null)
                                },
                            )
                        }
                    }
                }
            }

            // 3-dot dropdown — always visible: Select All / Invert / Deselect All
            Box {
                IconButton(onClick = { showModelViewMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "ModelView menu",
                    )
                }
                DropdownMenu(
                    expanded = showModelViewMenu,
                    onDismissRequest = { showModelViewMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.select_all)) },
                        onClick = {
                            showModelViewMenu = false
                            modelViewOnSelectAll()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.SelectAll, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.invert_selection)) },
                        onClick = {
                            showModelViewMenu = false
                            modelViewOnInvertSelection()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.FlipToBack, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.deselect_all)) },
                        onClick = {
                            showModelViewMenu = false
                            modelViewOnDeselectAll()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Deselect, contentDescription = null)
                        },
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueTopBar(
    drawerState: DrawerState,
    processingActive: Boolean = false,
    queuePaused: Boolean = false,
    hasPendingTasks: Boolean = false,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    // ── Queue selection mode ──────────────────────────────────
    queueIsSelectionMode: Boolean = false,
    queueSelectedCount: Int = 0,
    queueOnExitSelection: () -> Unit = {},
    queueOnBatchSaveInfo: () -> Unit = {},
    queueOnBatchDelete: () -> Unit = {},
    queueOnSelectAll: () -> Unit = {},
    queueOnInvertSelection: () -> Unit = {},
    queueOnDeselectAll: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var showQueueMenu by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            if (queueIsSelectionMode) {
                Text(
                    pluralStringResource(R.plurals.selected_items_count, queueSelectedCount, queueSelectedCount),
                    maxLines = 1,
                )
            } else {
                val statusText = when {
                    queuePaused -> stringResource(R.string.queue_paused)
                    processingActive -> stringResource(R.string.model_running)
                    else -> null
                }
                if (statusText != null) {
                    Text(text = statusText, maxLines = 1)
                }
            }
        },
        navigationIcon = {
            if (!queueIsSelectionMode) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(Icons.Default.Menu, stringResource(R.string.settings))
                }
            }
        },
        actions = {
            if (queueIsSelectionMode) {
                IconButton(onClick = queueOnBatchSaveInfo) {
                    Icon(Icons.Default.Bookmark, stringResource(R.string.save_info),
                        tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = queueOnBatchDelete) {
                    Icon(Icons.Default.Delete, stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error)
                }
                // X Close — exits selection mode
                IconButton(onClick = queueOnExitSelection) {
                    Icon(Icons.Default.Close, stringResource(R.string.cancel))
                }
            } else {
                // Show Pause while worker is actively processing
                if (processingActive && !queuePaused) {
                    IconButton(onClick = onPause) {
                        Icon(
                            Icons.Default.Pause,
                            contentDescription = "Pause queue",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                // Show Play (resume) when queue is paused or has pending tasks but no worker
                if (queuePaused || (!processingActive && hasPendingTasks)) {
                    IconButton(onClick = onResume) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Resume queue",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            // 3-dot dropdown — always visible, provides Select All / Invert / Deselect All
            Box {
                IconButton(onClick = { showQueueMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Queue menu",
                    )
                }
                DropdownMenu(
                    expanded = showQueueMenu,
                    onDismissRequest = { showQueueMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.select_all)) },
                        onClick = {
                            showQueueMenu = false
                            queueOnSelectAll()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.SelectAll, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.invert_selection)) },
                        onClick = {
                            showQueueMenu = false
                            queueOnInvertSelection()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.FlipToBack, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.deselect_all)) },
                        onClick = {
                            showQueueMenu = false
                            queueOnDeselectAll()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Deselect, contentDescription = null)
                        },
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateTopBar(
    drawerState: DrawerState,
    loadedModelId: String?,
    loadedModelType: BackendManager.Mode?,
    onGenTaskParamReset: () -> Unit = {},
    onGenTaskAddToQueue: () -> Unit = {},
    // Records tab selection
    selectedGenerateTab: Int = 0,
    selectedRecordCount: Int = 0,
    onRecordsSelectAll: () -> Unit = {},
    onRecordsInvertSelection: () -> Unit = {},
    onRecordsDeselectAll: () -> Unit = {},
    onLoadSelectedRecord: () -> Unit = {},
    onDeleteSelectedRecords: () -> Unit = {},
    onRecordsSave: () -> Unit = {},
    onRecordsExport: () -> Unit = {},
    onRecordsImport: () -> Unit = {},
    onPlayButtonPositioned: (Offset) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val modelRepository = remember { ModelRepository(context) }
    val isModelLoaded = loadedModelType == BackendManager.Mode.Diffusion && loadedModelId != null
    val model = remember(loadedModelId) { loadedModelId?.let { modelRepository.models.find { m -> m.id == it } } }
    var showGenerateMenu by remember { mutableStateOf(false) }
    val isRecordsTab = selectedGenerateTab == 1

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
        actions = {
            if (!isRecordsTab) {
                IconButton(onClick = onGenTaskParamReset) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.reset))
                }
                IconButton(
                    onClick = onGenTaskAddToQueue,
                    modifier = Modifier.onGloballyPositioned { coords ->
                        onPlayButtonPositioned(coords.positionInRoot())
                    },
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.generate_image))
                }
            } else {
                if (selectedRecordCount >= 1) {
                    IconButton(onClick = onRecordsSave) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.record_save))
                    }
                    IconButton(onClick = onDeleteSelectedRecords) {
                        Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                    }
                }
                if (selectedRecordCount == 1) {
                    IconButton(onClick = onLoadSelectedRecord) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.load_record))
                    }
                }
            }

            // 3-dot dropdown — always visible
            Box {
                IconButton(onClick = { showGenerateMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Generate menu",
                    )
                }
                DropdownMenu(
                    expanded = showGenerateMenu,
                    onDismissRequest = { showGenerateMenu = false },
                ) {
                    if (isRecordsTab) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.select_all)) },
                            onClick = {
                                showGenerateMenu = false
                                onRecordsSelectAll()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.SelectAll, contentDescription = null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.invert_selection)) },
                            onClick = {
                                showGenerateMenu = false
                                onRecordsInvertSelection()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.FlipToBack, contentDescription = null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.deselect_all)) },
                            onClick = {
                                showGenerateMenu = false
                                onRecordsDeselectAll()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Deselect, contentDescription = null)
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.record_export)) },
                            onClick = {
                                showGenerateMenu = false
                                onRecordsExport()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Upload, contentDescription = null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.record_import)) },
                            onClick = {
                                showGenerateMenu = false
                                onRecordsImport()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Download, contentDescription = null)
                            },
                        )
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseTopBar(
    drawerState: DrawerState,
    onToggleLayout: () -> Unit = {},
    isGalleryBrowseSelectionMode: Boolean = false,
    galleryBrowseSelectedCount: Int = 0,
    onGalleryBrowseBatchSaveInfo: () -> Unit = {},
    onGalleryBrowseBatchSave: () -> Unit = {},
    onGalleryBrowseBatchDelete: () -> Unit = {},
    onGalleryBrowseExitSelection: () -> Unit = {},
    onGalleryBrowseSelectAll: () -> Unit = {},
    onGalleryBrowseInvertSelection: () -> Unit = {},
    onGalleryBrowseDeselectAll: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var showGalleryBrowseMenu by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            if (isGalleryBrowseSelectionMode) {
                Text(
                    pluralStringResource(R.plurals.selected_items_count, galleryBrowseSelectedCount, galleryBrowseSelectedCount),
                    maxLines = 1,
                )
            }
        },
        navigationIcon = {
            if (!isGalleryBrowseSelectionMode) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(Icons.Default.Menu, stringResource(R.string.settings))
                }
            }
        },
        actions = {
            if (isGalleryBrowseSelectionMode) {
                IconButton(onClick = onGalleryBrowseBatchSave) {
                    Icon(Icons.Default.Save, stringResource(R.string.save))
                }
                IconButton(onClick = onGalleryBrowseBatchSaveInfo) {
                    Icon(Icons.Default.Bookmark, stringResource(R.string.save_info),
                        tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onGalleryBrowseBatchDelete) {
                    Icon(Icons.Default.Delete, stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error)
                }
                // X Close — exits selection mode
                IconButton(onClick = onGalleryBrowseExitSelection) {
                    Icon(Icons.Default.Close, stringResource(R.string.cancel))
                }
            } else {
                // Toggle layout — only visible in non-selection mode
                IconButton(onClick = onToggleLayout) {
                    Icon(
                        Icons.Default.Apps,
                        contentDescription = "Toggle layout",
                    )
                }
            }
            // 3-dot dropdown — always visible, provides Select All / Invert / Deselect All
            Box {
                IconButton(onClick = { showGalleryBrowseMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Gallery browse menu",
                    )
                }
                DropdownMenu(
                    expanded = showGalleryBrowseMenu,
                    onDismissRequest = { showGalleryBrowseMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.select_all)) },
                        onClick = {
                            showGalleryBrowseMenu = false
                            onGalleryBrowseSelectAll()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.SelectAll, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.invert_selection)) },
                        onClick = {
                            showGalleryBrowseMenu = false
                            onGalleryBrowseInvertSelection()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.FlipToBack, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.deselect_all)) },
                        onClick = {
                            showGalleryBrowseMenu = false
                            onGalleryBrowseDeselectAll()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Deselect, contentDescription = null)
                        },
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpscaleTopBar(
    drawerState: DrawerState,
    loadedModelId: String?,
    loadedModelType: BackendManager.Mode?,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val upscalerRepository = remember { UpscalerRepository(context) }
    val isUpscaleModelLoaded = loadedModelType == BackendManager.Mode.Upscaler && loadedModelId != null
    val upscalerName = remember(loadedModelId, upscalerRepository.upscalers) {
        loadedModelId?.let { id -> upscalerRepository.upscalers.find { it.id == id }?.name }
    }
    var showUpscaleMenu by remember { mutableStateOf(false) }

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
        actions = {
            // 3-dot dropdown — placeholder, always visible
            Box {
                IconButton(onClick = { showUpscaleMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Upscale menu",
                    )
                }
                DropdownMenu(
                    expanded = showUpscaleMenu,
                    onDismissRequest = { showUpscaleMenu = false },
                ) {
                }
            }
        },
    )
}
