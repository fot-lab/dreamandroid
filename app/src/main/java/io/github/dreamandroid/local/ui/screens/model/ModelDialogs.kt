package io.github.dreamandroid.local.ui.screens.model

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.Model
import io.github.dreamandroid.local.data.ModelRepository
import java.io.File
import kotlinx.coroutines.launch

@Composable
internal fun FileManagerDialog(context: Context, onDismiss: () -> Unit, onFileDeleted: () -> Unit) {
    var modelFolders by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var folderFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var showDeleteConfirm by remember { mutableStateOf<File?>(null) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    // Tracked separately so the "Clear Cache" button can light up without
    // exposing the cache directory as a fake "file" entry in the list.
    var cacheDir by remember { mutableStateOf<File?>(null) }
    var cacheSize by remember { mutableLongStateOf(0L) }

    val msgCacheCleared = stringResource(R.string.cache_cleared)

    fun loadFolders() {
        val modelsDir = Model.getModelsDir(context)
        val folders = mutableListOf<Pair<String, Int>>()

        if (modelsDir.exists() && modelsDir.isDirectory) {
            modelsDir.listFiles()?.forEach { modelDir ->
                if (modelDir.isDirectory) {
                    val fileCount = modelDir.listFiles()?.size ?: 0
                    if (fileCount > 0) {
                        folders.add(Pair(modelDir.name, fileCount))
                    }
                }
            }
        }
        modelFolders = folders
        isLoading = false
    }

    fun loadFilesForFolder(folderName: String) {
        val modelsDir = Model.getModelsDir(context)
        val folderDir = File(modelsDir, folderName)
        val all = folderDir.listFiles()?.toList() ?: emptyList()
        val cd = all.firstOrNull { it.isDirectory && it.name == "cache" }
        cacheDir = cd
        cacheSize = cd?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        folderFiles = all.filter { it.isFile }
    }

    LaunchedEffect(Unit) {
        loadFolders()
    }

    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.delete_file)) },
            text = { Text(stringResource(R.string.delete_file_confirm, showDeleteConfirm!!.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val fileToDelete = showDeleteConfirm!!
                        if (fileToDelete.delete()) {
                            onFileDeleted()
                            selectedFolder?.let { loadFilesForFolder(it) }
                            loadFolders()
                        }
                        showDeleteConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text(stringResource(R.string.clear_cache)) },
            text = { Text(stringResource(R.string.clear_cache_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        cacheDir?.deleteRecursively()
                        showClearCacheConfirm = false
                        Toast.makeText(
                            context,
                            msgCacheCleared,
                            Toast.LENGTH_SHORT,
                        ).show()
                        onFileDeleted()
                        selectedFolder?.let { loadFilesForFolder(it) }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.clear_cache))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (selectedFolder != null) {
                    IconButton(
                        onClick = { selectedFolder = null },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_to_folders),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Text(
                    text = selectedFolder?.let {
                        stringResource(R.string.model_folder, it)
                    } ?: stringResource(R.string.file_manager),
                )
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp),
            ) {
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            stringResource(R.string.loading_files),
                            modifier = Modifier.padding(top = 48.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else if (selectedFolder == null) {
                    if (modelFolders.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    stringResource(R.string.no_model_files),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(modelFolders) { (folderName, fileCount) ->
                                Card(
                                    onClick = {
                                        selectedFolder = folderName
                                        loadFilesForFolder(folderName)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Folder,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                            Column {
                                                Text(
                                                    text = folderName,
                                                    style = MaterialTheme.typography.titleSmall,
                                                )
                                                Text(
                                                    text = pluralStringResource(
                                                        R.plurals.file_count,
                                                        fileCount,
                                                        fileCount,
                                                    ),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (folderFiles.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.no_model_files),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(folderFiles) { file ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.secondary,
                                            )
                                            Column {
                                                Text(
                                                    text = file.name,
                                                    style = MaterialTheme.typography.titleSmall,
                                                )
                                                Text(
                                                    text = formatFileSize(file.length()),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }

                                        IconButton(
                                            onClick = { showDeleteConfirm = file },
                                            colors = IconButtonDefaults.iconButtonColors(
                                                contentColor = MaterialTheme.colorScheme.error,
                                            ),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = stringResource(R.string.delete_file),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
        dismissButton = {
            if (selectedFolder != null && cacheDir != null) {
                TextButton(
                    onClick = { showClearCacheConfirm = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.CleaningServices,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        stringResource(
                            R.string.clear_cache_with_size,
                            formatFileSize(cacheSize),
                        ),
                    )
                }
            }
        },
    )
}

@Composable
internal fun CustomNpuModelDialog(context: Context, onDismiss: () -> Unit, onModelAdded: (String, Uri) -> Unit) {
    var modelName by remember { mutableStateOf("") }
    var selectedZipUri by remember { mutableStateOf<Uri?>(null) }
    val isIdReserved = modelName.isNotBlank() &&
        ModelRepository.isReservedModelId(modelName.replace(" ", ""))

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let {
            selectedZipUri = it
            if (modelName.isBlank()) {
                getFileNameFromUri(context, it)?.let { fileName ->
                    modelName = fileName.substringBeforeLast(".").substringBefore("_qnn")
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_custom_npu_model)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.custom_npu_model_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    label = { Text(stringResource(R.string.custom_model_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.custom_model_name_hint)) },
                    isError = isIdReserved,
                    supportingText = if (isIdReserved) {
                        { Text(stringResource(R.string.custom_model_id_reserved)) }
                    } else {
                        null
                    },
                )

                FilledTonalButton(
                    onClick = {
                        zipPickerLauncher.launch("application/zip")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = selectedZipUri?.let { stringResource(R.string.zip_file_selected) }
                            ?: stringResource(R.string.select_zip_file),
                    )
                }

                selectedZipUri?.let { uri ->
                    Text(
                        text = "Selected: ${getCleanFileName(uri)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (modelName.isNotBlank() && selectedZipUri != null && !isIdReserved) {
                        onModelAdded(modelName, selectedZipUri!!)
                    }
                },
                enabled = modelName.isNotBlank() && selectedZipUri != null && !isIdReserved,
            ) {
                Text(stringResource(R.string.add_model))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun CustomUpscaleModelDialog(
    context: Context,
    onDismiss: () -> Unit,
    onModelAdded: (String, Uri) -> Unit,
) {
    var modelName by remember { mutableStateOf("") }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    val isIdReserved = modelName.isNotBlank() &&
        ModelRepository.isReservedModelId(modelName.replace(" ", ""))

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let {
            selectedFileUri = it
            if (modelName.isBlank()) {
                getFileNameFromUri(context, it)?.let { fileName ->
                    modelName = fileName.substringBeforeLast(".")
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_custom_upscale_model)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    label = { Text(stringResource(R.string.custom_model_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.custom_upscale_model_name_hint)) },
                    isError = isIdReserved,
                    supportingText = if (isIdReserved) {
                        { Text(stringResource(R.string.custom_model_id_reserved)) }
                    } else {
                        null
                    },
                )

                FilledTonalButton(
                    onClick = {
                        filePickerLauncher.launch("*/*")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = selectedFileUri?.let { stringResource(R.string.upscale_file_selected) }
                            ?: stringResource(R.string.select_upscale_file),
                    )
                }

                selectedFileUri?.let { uri ->
                    Text(
                        text = "Selected: ${getCleanFileName(uri)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (modelName.isNotBlank() && selectedFileUri != null && !isIdReserved) {
                        onModelAdded(modelName, selectedFileUri!!)
                    }
                },
                enabled = modelName.isNotBlank() && selectedFileUri != null && !isIdReserved,
            ) {
                Text(stringResource(R.string.add_model))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun CustomModelDialog(
    context: Context,
    onDismiss: () -> Unit,
    onModelAdded: (String, Uri, Int, List<LoRAFile>) -> Unit,
) {
    var modelName by remember { mutableStateOf("") }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var clipSkip by remember { mutableIntStateOf(1) }
    var selectedLoraFiles by remember { mutableStateOf<List<LoRAFile>>(emptyList()) }
    val isIdReserved = modelName.isNotBlank() &&
        ModelRepository.isReservedModelId(modelName.replace(" ", ""))

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let {
            selectedFileUri = it
            if (modelName.isBlank()) {
                getFileNameFromUri(context, it)?.let { fileName ->
                    modelName = fileName.substringBeforeLast(".")
                }
            }
        }
    }

    val loraPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let {
            selectedLoraFiles = selectedLoraFiles + LoRAFile(it)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_custom_model)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.custom_model_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    label = { Text(stringResource(R.string.custom_model_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.custom_model_name_hint)) },
                    isError = isIdReserved,
                    supportingText = if (isIdReserved) {
                        { Text(stringResource(R.string.custom_model_id_reserved)) }
                    } else {
                        null
                    },
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        FilterChip(
                            selected = clipSkip == 1,
                            onClick = { clipSkip = 1 },
                            label = { Text("Clip Skip 1") },
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = clipSkip == 2,
                            onClick = { clipSkip = 2 },
                            label = { Text("Clip Skip 2") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = stringResource(R.string.clip_skip_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                FilledTonalButton(
                    onClick = {
                        filePickerLauncher.launch("application/octet-stream")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = selectedFileUri?.let { stringResource(R.string.file_selected) }
                            ?: stringResource(R.string.select_model_file),
                    )
                }

                selectedFileUri?.let { uri ->
                    Text(
                        text = "Selected: ${getCleanFileName(uri)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.lora_files_optional),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    FilledTonalButton(
                        onClick = {
                            loraPickerLauncher.launch("application/octet-stream")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.add_lora_file))
                    }

                    if (selectedLoraFiles.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.selected_lora_files),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        selectedLoraFiles.forEachIndexed { index, loraFile ->
                            key(loraFile.uri.toString()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "${index + 1}. ${getCleanFileName(loraFile.uri)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f),
                                        )

                                        IconButton(
                                            onClick = {
                                                selectedLoraFiles =
                                                    selectedLoraFiles.filterIndexed { i, _ -> i != index }
                                            },
                                            modifier = Modifier.size(24.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "delete",
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.lora_weight),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))

                                        Slider(
                                            value = loraFile.weight,
                                            onValueChange = { newWeight ->
                                                selectedLoraFiles =
                                                    selectedLoraFiles.mapIndexed { i, file ->
                                                        if (i == index) file.copy(weight = newWeight) else file
                                                    }
                                            },
                                            valueRange = 0f..2f,
                                            steps = 39,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(24.dp),
                                        )

                                        Text(
                                            text = "%.2f".format(loraFile.weight),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.width(35.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (modelName.isNotBlank() && selectedFileUri != null && !isIdReserved) {
                        onModelAdded(modelName, selectedFileUri!!, clipSkip, selectedLoraFiles)
                    }
                },
                enabled = modelName.isNotBlank() && selectedFileUri != null && !isIdReserved,
            ) {
                Text(stringResource(R.string.add_model))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun EmbeddingManagerDialog(
    context: Context,
    onDismiss: () -> Unit,
    onEmbeddingDeleted: () -> Unit,
    onEmbeddingImported: () -> Unit,
) {
    var embeddingFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var showDeleteConfirm by remember { mutableStateOf<File?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun loadEmbeddings() {
        val embeddingsDir = File(context.filesDir, "embeddings")
        if (!embeddingsDir.exists()) {
            embeddingsDir.mkdirs()
        }
        embeddingFiles = embeddingsDir.listFiles()?.filter {
            it.isFile && it.extension == "safetensors"
        }?.sortedBy { it.name } ?: emptyList()
        isLoading = false
    }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    val embeddingPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let {
            scope.launch {
                importEmbedding(context, it, {
                    loadEmbeddings()
                    onEmbeddingImported()
                }) { error ->
                    errorMessage = error
                }
            }
        }
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text(stringResource(R.string.embedding_import_failed, "")) },
            text = { Text(errorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }

    LaunchedEffect(Unit) {
        loadEmbeddings()
    }

    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.delete_embedding)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_embedding_confirm,
                        showDeleteConfirm!!.name,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val fileToDelete = showDeleteConfirm!!
                        if (fileToDelete.delete()) {
                            onEmbeddingDeleted()
                            loadEmbeddings()
                        }
                        showDeleteConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.embedding_manager)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp),
            ) {
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (embeddingFiles.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                stringResource(R.string.no_embeddings),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(embeddingFiles) { file ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Description,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                        Column {
                                            Text(
                                                text = file.nameWithoutExtension,
                                                style = MaterialTheme.typography.titleSmall,
                                            )
                                            Text(
                                                text = formatFileSize(file.length()),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = { showDeleteConfirm = file },
                                        colors = IconButtonDefaults.iconButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error,
                                        ),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.delete_embedding),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                FilledTonalButton(
                    onClick = {
                        embeddingPickerLauncher.launch("application/octet-stream")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.import_embedding))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}
