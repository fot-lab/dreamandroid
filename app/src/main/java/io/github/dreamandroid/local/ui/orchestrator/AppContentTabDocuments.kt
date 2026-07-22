package io.github.dreamandroid.local.ui.orchestrator

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
import io.github.dreamandroid.local.ui.screens.model.formatFileSize
import java.io.File

/**
 * Documents screen: reachable via the expandable bottom bar (Document icon).
 *
 * Wraps the file manager — originally [FileManagerDialog] — as a full-screen
 * Scaffold-based peer screen alongside Downloads / Settings / Info.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContentTabDocuments(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var modelFolders by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var folderFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var showDeleteConfirm by remember { mutableStateOf<File?>(null) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
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

    // ── Dialogs ────────────────────────────────────────────────────

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
                        // The original used Toast here, but we're in a Scaffold now —
                        // simply refresh the listing so the user sees the cache is gone.
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

    // ── Scaffold ───────────────────────────────────────────────────

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        selectedFolder?.let {
                            stringResource(R.string.model_folder, it)
                        } ?: stringResource(R.string.file_manager),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedFolder != null) {
                            selectedFolder = null
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    if (selectedFolder != null && cacheDir != null) {
                        IconButton(onClick = { showClearCacheConfirm = true }) {
                            Icon(
                                imageVector = Icons.Default.CleaningServices,
                                contentDescription = stringResource(R.string.clear_cache),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.loading_files),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else if (selectedFolder == null) {
                // ── Folder list ──
                if (modelFolders.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                        contentPadding = PaddingValues(16.dp),
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
                // ── File list within a folder ──
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
                        contentPadding = PaddingValues(16.dp),
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
    }
}
