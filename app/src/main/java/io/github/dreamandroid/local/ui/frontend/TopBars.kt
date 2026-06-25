package io.github.dreamandroid.local.ui.frontend

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.*
import kotlinx.coroutines.launch

// =========== Top App Bars ===========

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsTopBar(
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

            if (selectedModelId != null && !isModelLoaded && !isModelLoading) {
                TextButton(onClick = { onLoadModel(selectedModelId) }) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.load_model))
                }
            }

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
fun QueueTopBar(
    drawerState: DrawerState,
    processingActive: Boolean = false,
    queuePaused: Boolean = false,
    hasPendingTasks: Boolean = false,
    onStop: () -> Unit = {},
    onResume: () -> Unit = {},
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
            // Show Stop while worker is actively processing
            if (processingActive && !queuePaused) {
                IconButton(onClick = onStop) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop queue",
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
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateTopBar(
    drawerState: DrawerState,
    modelId: String?,
    isModelLoaded: Boolean,
    onGenTaskParamReset: () -> Unit = {},
    onGenTaskAddToQueue: () -> Unit = {},
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
        actions = {
            IconButton(onClick = onGenTaskParamReset) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.reset))
            }
            IconButton(onClick = onGenTaskAddToQueue) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.generate_image))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseTopBar(
    drawerState: DrawerState,
    onToggleLayout: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                Icon(Icons.Default.Menu, stringResource(R.string.settings))
            }
        },
        actions = {
            IconButton(onClick = onToggleLayout) {
                Icon(
                    Icons.Default.Apps,
                    contentDescription = "Toggle layout",
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpscaleTopBar(
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
