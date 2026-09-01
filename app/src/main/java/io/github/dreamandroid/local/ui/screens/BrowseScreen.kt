package io.github.dreamandroid.local.ui.screens

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.text.style.TextAlign
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
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Converts a raw millisecond generation time string to "yyyy-MM-dd HH:mm:ss".
 * Centralized here so all timestamp displays in the app use the same format.
 * Falls back to the original string if it can't be parsed as a long.
 */
fun formatBrowseTime(generationTime: String?): String {
    if (generationTime.isNullOrBlank()) return ""
    val millis = generationTime.toLongOrNull() ?: return generationTime
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(millis))
}

/** Layout modes for the Browse / Gallery screen. */
enum class BrowseLayoutMode {
    DETAIL_LIST,
    SINGLE_COLUMN,
    TWO_COLUMNS,
    THREE_COLUMNS;

    fun next(): BrowseLayoutMode = entries[(ordinal + 1) % entries.size]
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowseScreen(
    modifier: Modifier = Modifier,
    recordRepository: RecordRepository? = null,
    browseViewModel: BrowseViewModel = viewModel(),
    layoutMode: BrowseLayoutMode = BrowseLayoutMode.SINGLE_COLUMN,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Observe ViewModel state
    val historyItems by browseViewModel.historyItems.collectAsState()

    val isSelectionMode = browseViewModel.isSelectionMode
    val filterModelIds = browseViewModel.filterModelIds

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
                        scope.launch {
                            val deleted = browseViewModel.batchDelete()
                            Toast.makeText(
                                context, "Deleted $deleted items", Toast.LENGTH_SHORT,
                            ).show()
                        }
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
                            val result = browseViewModel.batchSaveToGallery(context)
                            val message = when {
                                result.jsonFailed > 0 -> context.resources.getQuantityString(
                                    R.plurals.saved_count_json_failed,
                                    result.jsonFailed,
                                    result.saved,
                                    result.jsonFailed,
                                )
                                result.failed > 0 -> context.getString(
                                    R.string.saved_count_with_failed,
                                    result.saved,
                                    result.failed,
                                )
                                else -> context.resources.getQuantityString(
                                    R.plurals.saved_count,
                                    result.saved,
                                    result.saved,
                                )
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
                        scope.launch {
                            val saved = recordRepository?.let { browseViewModel.batchSaveParams(it) } ?: 0
                            Toast.makeText(context, "$saved parameters saved", Toast.LENGTH_SHORT).show()
                        }
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

    // ── Compute filtered display items (hoisted for dialog pager access) ──
    val displayItems = remember(historyItems, filterModelIds) {
        historyItems.filter { item ->
            filterModelIds.isEmpty() || item.modelId in filterModelIds
        }
    }

    // ── Single Item Detail / Delete Dialog ──
    browseViewModel.showHistoryDetailDialog?.let { _ ->
        val pagerState = rememberPagerState(
            initialPage = 0,
            pageCount = { displayItems.size },
        )

        // Sync: tap thumbnail → scroll pager to matching index
        LaunchedEffect(browseViewModel.showHistoryDetailDialog?.id) {
            val item = browseViewModel.showHistoryDetailDialog ?: return@LaunchedEffect
            val idx = displayItems.indexOfFirst { it.id == item.id }
            if (idx >= 0 && idx != pagerState.currentPage) {
                pagerState.scrollToPage(idx)
            }
        }

        // Sync: swipe pager → update dialog item (actions apply to current page)
        LaunchedEffect(pagerState.currentPage) {
            displayItems.getOrNull(pagerState.currentPage)?.let { newItem ->
                if (browseViewModel.showHistoryDetailDialog?.id != newItem.id) {
                    browseViewModel.showHistoryDetailDialog = newItem
                }
            }
        }

        val currentItem = displayItems.getOrNull(pagerState.currentPage) ?: return@let

        var showDelete by remember { mutableStateOf(false) }
        if (showDelete) {
            AlertDialog(
                onDismissRequest = { showDelete = false },
                title = { Text(stringResource(R.string.delete_image)) },
                text = { Text(stringResource(R.string.delete_image_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch { browseViewModel.deleteSingleItem(currentItem) }
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
            confirmButton = {},
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = currentItem.modelId,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Row {
                        IconButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                val result = browseViewModel.saveSingleToGallery(context, currentItem)
                                withContext(Dispatchers.Main) {
                                    val message = when {
                                        result.imageSaved && result.jsonError != null ->
                                            context.getString(R.string.image_saved_json_failed)
                                        result.imageSaved -> context.getString(R.string.image_saved)
                                        else -> context.getString(R.string.image_save_failed)
                                    }
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                }
                            }
                        }) {
                            Icon(Icons.Default.Save, stringResource(R.string.save))
                        }
                        IconButton(onClick = {
                            scope.launch {
                                recordRepository?.let { browseViewModel.saveSingleParams(currentItem, it) }
                                Toast.makeText(context, context.getString(R.string.parameters_saved), Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Default.Bookmark, stringResource(R.string.save_info),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { showDelete = true }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                        }
                        IconButton(onClick = { browseViewModel.showHistoryDetailDialog = null }) {
                            Icon(Icons.Default.Close, stringResource(R.string.close))
                        }
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Page indicator
                    Text(
                        text = "${pagerState.currentPage + 1} / ${displayItems.size}",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Swipeable image pager
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth(),
                        beyondViewportPageCount = 1,
                    ) { page ->
                        val pageItem = displayItems[page]
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(pageItem.imageFile.absolutePath)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Generated image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit,
                        )
                    }

                    // Metadata for current page (outside pager for stable layout)
                    if (currentItem.params.prompt.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.image_prompt),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = currentItem.params.prompt,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = stringResource(R.string.result_params).format(
                            currentItem.params.steps.toString(), formatCfgScale(currentItem.params.cfgScale),
                            currentItem.params.seed?.toString() ?: "-",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Sampler & Scheduler
                    val samplerScheduler = buildString {
                        append(currentItem.params.sampler)
                        if (currentItem.params.scheduler.isNotEmpty()) {
                            append(" · ").append(currentItem.params.scheduler)
                        }
                    }
                    if (samplerScheduler.isNotEmpty()) {
                        Text(
                            text = samplerScheduler,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Time Elapsed
                    if (currentItem.params.timeElapsedMs != null && currentItem.params.timeElapsedMs > 0) {
                        Text(
                            text = "Time: %.3fs".format(currentItem.params.timeElapsedMs / 1000.0),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "${currentItem.params.width}×${currentItem.params.height} · ${formatBrowseTime(currentItem.params.generationTime)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }

    // ── Preview Overlay ──
    browseViewModel.showHistoryDetailDialog?.let { _ ->
        if (browseViewModel.isPreviewMode) {
            val previewPagerState = rememberPagerState(
                initialPage = 0,
                pageCount = { displayItems.size },
            )

            LaunchedEffect(browseViewModel.showHistoryDetailDialog?.id) {
                val item = browseViewModel.showHistoryDetailDialog ?: return@LaunchedEffect
                val idx = displayItems.indexOfFirst { it.id == item.id }
                if (idx >= 0 && idx != previewPagerState.currentPage) {
                    previewPagerState.scrollToPage(idx)
                }
            }

            AlertDialog(
                onDismissRequest = { browseViewModel.isPreviewMode = false },
                confirmButton = {
                    TextButton(onClick = { browseViewModel.isPreviewMode = false }) {
                        Text(stringResource(R.string.close))
                    }
                },
                text = {
                    Column {
                        Text(
                            text = "${previewPagerState.currentPage + 1} / ${displayItems.size}",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalPager(
                            state = previewPagerState,
                            modifier = Modifier.fillMaxWidth(),
                            beyondViewportPageCount = 1,
                        ) { page ->
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(displayItems[page].imageFile.absolutePath)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Preview",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                },
            )
        }
    }

    // ── Main Content ──
    Column(modifier = modifier.fillMaxSize()) {

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
            when (layoutMode) {
                BrowseLayoutMode.TWO_COLUMNS, BrowseLayoutMode.THREE_COLUMNS -> {
                    val columns = if (layoutMode == BrowseLayoutMode.TWO_COLUMNS) 2 else 3
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        gridItems(items = displayItems, key = { it.id }) { item ->
                            GridCard(
                                item = item,
                                context = context,
                                isSelectionMode = isSelectionMode,
                                isSelected = browseViewModel.selectedItems.contains(item),
                                onClick = { browseViewModel.toggleSelection(item) },
                                onLongClick = { browseViewModel.startSelection(item) },
                                onSelectionChange = { checked ->
                                    if (checked) browseViewModel.selectedItems.add(item)
                                    else {
                                        browseViewModel.selectedItems.remove(item)
                                        if (browseViewModel.selectedItems.isEmpty())
                                            browseViewModel.exitSelection()
                                    }
                                },
                            )
                        }
                    }
                }

                BrowseLayoutMode.DETAIL_LIST -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(items = displayItems, key = { it.id }) { item ->
                            DetailListItem(
                                item = item,
                                context = context,
                                isSelectionMode = isSelectionMode,
                                isSelected = browseViewModel.selectedItems.contains(item),
                                onClick = { browseViewModel.toggleSelection(item) },
                                onLongClick = { browseViewModel.startSelection(item) },
                                onSelectionChange = { checked ->
                                    if (checked) browseViewModel.selectedItems.add(item)
                                    else {
                                        browseViewModel.selectedItems.remove(item)
                                        if (browseViewModel.selectedItems.isEmpty())
                                            browseViewModel.exitSelection()
                                    }
                                },
                            )
                        }
                    }
                }

                BrowseLayoutMode.SINGLE_COLUMN -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(items = displayItems, key = { it.id }) { item ->
                            SingleColumnCard(
                                item = item,
                                context = context,
                                isSelectionMode = isSelectionMode,
                                isSelected = browseViewModel.selectedItems.contains(item),
                                onClick = { browseViewModel.toggleSelection(item) },
                                onLongClick = { browseViewModel.startSelection(item) },
                                onSelectionChange = { checked ->
                                    if (checked) browseViewModel.selectedItems.add(item)
                                    else {
                                        browseViewModel.selectedItems.remove(item)
                                        if (browseViewModel.selectedItems.isEmpty())
                                            browseViewModel.exitSelection()
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

// ── Layout-specific item composables ──

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SingleColumnCard(
    item: HistoryItem,
    context: android.content.Context,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectionChange: (Boolean) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.imageFile.absolutePath).crossfade(true).build(),
                contentDescription = "Generated image",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
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
                    Checkbox(checked = isSelected, onCheckedChange = onSelectionChange)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCard(
    item: HistoryItem,
    context: android.content.Context,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectionChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column {
            Box {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.imageFile.absolutePath).crossfade(true).build(),
                    contentDescription = "Generated image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    contentScale = ContentScale.Fit,
                )
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = onSelectionChange,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(24.dp),
                    )
                }
            }
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                if (item.params.prompt.isNotEmpty()) {
                    Text(
                        text = item.params.prompt,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = item.modelId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailListItem(
    item: HistoryItem,
    context: android.content.Context,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectionChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.imageFile.absolutePath).crossfade(true).build(),
                contentDescription = "Thumbnail",
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (item.params.prompt.isNotEmpty()) {
                    Text(
                        text = item.params.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
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
                    checked = isSelected,
                    onCheckedChange = onSelectionChange,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

private val gallerySaveSequence = AtomicLong(0L)

/** Album folder used when the user has not picked a custom export folder. */
private const val DREAMHUB_ALBUM = "DreamHub"

/** Built-in destination for exported images when no folder has been picked. */
private val DEFAULT_IMAGE_PATH = Environment.DIRECTORY_PICTURES + "/$DREAMHUB_ALBUM"

/** Built-in destination for exported params JSON when no folder has been picked. */
private val DEFAULT_PARAMS_PATH = Environment.DIRECTORY_DOCUMENTS + "/$DREAMHUB_ALBUM"

/**
 * Outcome of exporting one image to the shared gallery.
 *
 * [imageSaved] describes the image only — a params JSON sidecar failure never flips it to
 * `false`, so a broken sidecar can never block the primary image export. The sidecar
 * problem travels in [jsonError] so the UI can surface it (e.g. as a Toast).
 */
data class GallerySaveResult(
    val imageSaved: Boolean,
    /** Non-null when the params JSON sidecar could not be written; holds the reason. */
    val jsonError: String? = null,
)

/** Human readable location of a stored export folder; empty when none has been picked. */
fun describeExportDir(dirUri: String?): String {
    if (dirUri.isNullOrBlank()) return ""
    val uri = Uri.parse(dirUri)
    val raw = DocumentsContract.getTreeDocumentId(uri) ?: uri.lastPathSegment.orEmpty()
    val decoded = Uri.decode(raw)
    return decoded.substringAfter(':', decoded).trim('/').ifEmpty { decoded }
}

/**
 * Creates [displayName] inside the SAF tree the user picked and streams [write] into it.
 *
 * @return `null` on success, otherwise a human readable failure reason.
 */
private fun writeIntoSafTree(
    context: Context,
    treeUri: Uri,
    displayName: String,
    mimeType: String,
    write: (OutputStream) -> Unit,
): String? = try {
    val docUri = DocumentsContract.createDocument(
        context.contentResolver,
        treeUri,
        mimeType,
        displayName,
    ) ?: return "could not create $displayName in the selected folder"
    context.contentResolver.openOutputStream(docUri)?.use(write)
        ?: return "could not open $displayName for writing"
    null
} catch (e: Exception) {
    Log.w("Gallery", "SAF write failed: $displayName", e)
    e.message ?: e.javaClass.simpleName
}

/**
 * Creates [displayName] under [relativePath] of public storage and streams [write] into it.
 *
 * Android 10+ goes through MediaStore; Android 9 has no `RELATIVE_PATH` and therefore
 * writes straight to the public directory.
 *
 * @return `null` on success, otherwise a human readable failure reason.
 */
private fun writeIntoPublicAlbum(
    context: Context,
    relativePath: String,
    displayName: String,
    mimeType: String,
    write: (OutputStream) -> Unit,
): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val collection = if (mimeType.startsWith("image/")) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }
            val uri = context.contentResolver.insert(collection, values)
                ?: return "MediaStore rejected $displayName under $relativePath"
            context.contentResolver.openOutputStream(uri)?.use(write)
                ?: return "could not open $displayName for writing"
            null
        } catch (e: Exception) {
            Log.w("Gallery", "MediaStore write failed: $displayName", e)
            e.message ?: e.javaClass.simpleName
        }
    }

    return try {
        val root = Environment.getExternalStoragePublicDirectory(relativePath.substringBefore('/'))
        val dir = File(root, relativePath.substringAfter('/'))
        if (!dir.exists()) dir.mkdirs()
        File(dir, displayName).outputStream().use(write)
        null
    } catch (e: Exception) {
        Log.w("Gallery", "Legacy write failed: $displayName", e)
        e.message ?: e.javaClass.simpleName
    }
}

/**
 * Writes one export artifact to the folder the user picked, or to the built-in
 * [defaultRelativePath] album when no folder has been picked.
 */
private fun exportFile(
    context: Context,
    dirUri: String?,
    defaultRelativePath: String,
    displayName: String,
    mimeType: String,
    write: (OutputStream) -> Unit,
): String? {
    val tree = dirUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
    return if (tree != null) {
        writeIntoSafTree(context, tree, displayName, mimeType, write)
    } else {
        writeIntoPublicAlbum(context, defaultRelativePath, displayName, mimeType, write)
    }
}

suspend fun saveBitmapToGallery(
    context: Context,
    bitmap: android.graphics.Bitmap,
    modelId: String,
    saveParamsJson: String? = null,
    imageDirUri: String? = null,
    paramsDirUri: String? = null,
): GallerySaveResult = withContext(Dispatchers.IO) {
    val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        .format(java.util.Date())
    val seq = gallerySaveSequence.getAndIncrement()
    val filename = "DreamHub_${modelId}_${timestamp}_$seq.png"

    val imageError = exportFile(
        context = context,
        dirUri = imageDirUri,
        defaultRelativePath = DEFAULT_IMAGE_PATH,
        displayName = filename,
        mimeType = "image/png",
    ) { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
    if (imageError != null) {
        Log.w("Gallery", "Image export failed ($filename): $imageError")
        return@withContext GallerySaveResult(imageSaved = false)
    }

    // Best effort: a params sidecar problem must never turn a successful image export into
    // a failed one — it is reported separately so the UI can toast it.
    val jsonError = if (saveParamsJson != null) {
        exportFile(
            context = context,
            dirUri = paramsDirUri,
            defaultRelativePath = DEFAULT_PARAMS_PATH,
            displayName = filename.substringBeforeLast(".png") + ".json",
            mimeType = "application/json",
        ) { out -> out.write(saveParamsJson.toByteArray(Charsets.UTF_8)) }
    } else {
        null
    }

    GallerySaveResult(imageSaved = true, jsonError = jsonError)
}
