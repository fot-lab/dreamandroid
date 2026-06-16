package io.github.dreamandroid.local.ui.screens

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.*
import io.github.dreamandroid.local.ui.viewmodel.BrowseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowseScreen(
    modifier: Modifier = Modifier,
    recordRepository: RecordRepository? = null,
    browseViewModel: BrowseViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Observe ViewModel state
    val historyItems by browseViewModel.historyItems.collectAsState()
    val knownModelIds by browseViewModel.knownModelIds.collectAsState()

    val isSelectionMode = browseViewModel.isSelectionMode
    val filterModelId = browseViewModel.filterModelId

    // ── Batch Delete Dialog ──
    if (browseViewModel.showBatchDeleteDialog && browseViewModel.selectedItems.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { browseViewModel.showBatchDeleteDialog = false },
            title = { Text(stringResource(R.string.batch_delete)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.batch_delete_confirm,
                        browseViewModel.selectedItems.size,
                        browseViewModel.selectedItems.size,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val deleted = browseViewModel.batchDelete()
                        Toast.makeText(
                            context, "Deleted $deleted items", Toast.LENGTH_SHORT,
                        ).show()
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { browseViewModel.showBatchDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── Batch Save Dialog ──
    if (browseViewModel.showBatchSaveDialog && browseViewModel.selectedItems.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { browseViewModel.showBatchSaveDialog = false },
            title = { Text(stringResource(R.string.batch_save)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.batch_save_confirm,
                        browseViewModel.selectedItems.size,
                        browseViewModel.selectedItems.size,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val (saved, _) = browseViewModel.batchSaveToGallery(context)
                            Toast.makeText(
                                context,
                                context.resources.getQuantityString(R.plurals.saved_count, saved, saved),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { browseViewModel.showBatchSaveDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── Batch Save Info Dialog ──
    if (browseViewModel.showBatchSaveInfoDialog && browseViewModel.selectedItems.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { browseViewModel.showBatchSaveInfoDialog = false },
            title = { Text(stringResource(R.string.batch_save_params)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.batch_save_params_confirm,
                        browseViewModel.selectedItems.size,
                        browseViewModel.selectedItems.size,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val saved = recordRepository?.let { browseViewModel.batchSaveParams(it) } ?: 0
                        Toast.makeText(context, "$saved parameters saved", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(stringResource(R.string.save_info))
                }
            },
            dismissButton = {
                TextButton(onClick = { browseViewModel.showBatchSaveInfoDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── Single Item Detail / Delete Dialog ──
    browseViewModel.showHistoryDetailDialog?.let { item ->
        var showDelete by remember { mutableStateOf(false) }
        if (showDelete) {
            AlertDialog(
                onDismissRequest = { showDelete = false },
                title = { Text(stringResource(R.string.delete_image)) },
                text = { Text(stringResource(R.string.delete_image_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            browseViewModel.deleteSingleItem(item)
                            showDelete = false
                        }
                    ) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDelete = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        AlertDialog(
            onDismissRequest = { browseViewModel.showHistoryDetailDialog = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.modelId,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Row {
                        IconButton(onClick = {
                            scope.launch {
                                recordRepository?.let { browseViewModel.saveSingleParams(item, it) }
                                Toast.makeText(
                                    context, context.getString(R.string.parameters_saved), Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }) {
                            Icon(Icons.Default.NoteAdd, stringResource(R.string.save_info),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                val ok = browseViewModel.saveSingleToGallery(context, item)
                                if (ok) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, context.getString(R.string.image_saved), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }) {
                            Icon(Icons.Default.SaveAlt, stringResource(R.string.save))
                        }
                        IconButton(onClick = { showDelete = true }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(item.imageFile.absolutePath)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Generated image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (item.params.prompt.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.image_prompt),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = item.params.prompt,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = stringResource(R.string.result_params).format(
                            item.params.steps.toString(), item.params.cfg,
                            item.params.seed?.toString() ?: "-",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${item.params.width}×${item.params.height} · ${item.params.generationTime ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { browseViewModel.showHistoryDetailDialog = null }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    // ── Preview Overlay ──
    browseViewModel.showHistoryDetailDialog?.let { item ->
        if (browseViewModel.isPreviewMode) {
            AlertDialog(
                onDismissRequest = { browseViewModel.isPreviewMode = false },
                confirmButton = {
                    TextButton(onClick = { browseViewModel.isPreviewMode = false }) {
                        Text(stringResource(R.string.close))
                    }
                },
                text = {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(item.imageFile.absolutePath)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Preview",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                    )
                },
            )
        }
    }

    // ── Main Content ──
    Column(modifier = modifier.fillMaxSize()) {
        // Filter chips
        if (knownModelIds.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filterModelId == null,
                    onClick = { browseViewModel.filterModelId = null },
                    label = { Text(stringResource(R.string.history_filter_all)) },
                )
                knownModelIds.forEach { id ->
                    FilterChip(
                        selected = filterModelId == id,
                        onClick = { browseViewModel.filterModelId = id },
                        label = { Text(id) },
                    )
                }
            }
        }

        val displayItems = remember(historyItems, filterModelId) {
            historyItems.filter { item ->
                filterModelId == null || item.modelId == filterModelId
            }
        }

        if (displayItems.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.ImageSearch, contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.no_generated_images),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.no_generated_images_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        } else {
            // Selection mode top bar
            if (isSelectionMode) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        pluralStringResource(
                            R.plurals.selected_items_count,
                            browseViewModel.selectedItems.size,
                            browseViewModel.selectedItems.size,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { browseViewModel.showBatchSaveInfoDialog = true }) {
                            Icon(Icons.Default.NoteAdd, stringResource(R.string.save_info),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { browseViewModel.showBatchSaveDialog = true }) {
                            Icon(Icons.Default.SaveAlt, stringResource(R.string.save))
                        }
                        IconButton(onClick = { browseViewModel.showBatchDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete),
                                tint = MaterialTheme.colorScheme.error)
                        }
                        IconButton(onClick = { browseViewModel.exitSelection() }) {
                            Icon(Icons.Default.Close, stringResource(R.string.cancel))
                        }
                    }
                }
                HorizontalDivider()
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = displayItems, key = { it.id }) { item ->
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { browseViewModel.toggleSelection(item) },
                                onLongClick = { browseViewModel.startSelection(item) },
                            ),
                    ) {
                        Column {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(item.imageFile.absolutePath).crossfade(true).build(),
                                contentDescription = "Generated image",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(
                                        if (item.params.width > 0 && item.params.height > 0)
                                            item.params.width.toFloat() / item.params.height
                                        else 1f,
                                    ),
                                contentScale = ContentScale.Fit,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    if (item.params.prompt.isNotEmpty()) {
                                        Text(
                                            text = item.params.prompt,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text(
                                        text = "${item.modelId} · ${item.params.width}×${item.params.height}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (isSelectionMode) {
                                    Checkbox(
                                        checked = browseViewModel.selectedItems.contains(item),
                                        onCheckedChange = { checked ->
                                            if (checked) browseViewModel.selectedItems.add(item)
                                            else {
                                                browseViewModel.selectedItems.remove(item)
                                                if (browseViewModel.selectedItems.isEmpty()) {
                                                    browseViewModel.exitSelection()
                                                }
                                            }
                                        },
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

suspend fun saveBitmapToGallery(
    context: Context,
    bitmap: android.graphics.Bitmap,
    modelId: String,
): Boolean = withContext(Dispatchers.IO) {
    try {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val filename = "DreamHub_${modelId}_$timestamp.png"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DreamHub")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false
            context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            } ?: return@withContext false
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "DreamHub",
            )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, filename)
            file.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        true
    } catch (_: Exception) {
        false
    }
}
