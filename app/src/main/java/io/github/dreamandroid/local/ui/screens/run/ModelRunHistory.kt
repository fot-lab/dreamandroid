package io.github.dreamandroid.local.ui.screens.run

/*
 * NOTE: This file is no longer in use.
 *
 * ModelRunHistory was the history gallery of the original ModelRunScreen.
 * Since ModelRunScreen has been replaced by tab-based UI (Generate / Upscale tabs),
 * this file is kept solely as reference.
 *
 * Do NOT instantiate any composable from this file in new code.
 */

import android.content.res.Resources
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.HistoryFilter
import io.github.dreamandroid.local.data.HistoryItem
import io.github.dreamandroid.local.data.HistoryManager
import io.github.dreamandroid.local.ui.components.ZoomableImageOverlay
import io.github.dreamandroid.local.ui.components.OverlayIconButton
import io.github.dreamandroid.local.ui.theme.Motion
import io.github.dreamandroid.local.utils.saveImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * History page + filter bar + filter sheet, extracted from ModelRunScreen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModelRunHistoryPage(
    state: ModelRunState,
    historyItems: List<HistoryItem>,
    knownModelIds: List<String>,
    knownSamplers: List<String>,
    knownSizes: List<String>,
    scope: CoroutineScope,
    modelId: String,
    msgImageSaved: String,
    msgDeleted: String,
    msgDeleteFailedMessage: String,
    resources: Resources,
    msgSavedCountWithFailed: String,
    msgDeletedCountWithFailed: String,
    historyManager: HistoryManager,
) {
    val locale = LocalConfiguration.current.locales[0]
    val context = LocalContext.current

    BackHandler(enabled = state.isSelectionMode && !state.isBatchSaving) {
        state.isSelectionMode = false
        state.selectedItems.clear()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HistoryFilterBar(
            filter = state.historyFilter,
            currentModelId = modelId,
            onShowFilterSheet = { state.showHistoryFilterSheet = true },
            onSetCurrentModelOnly = { state.historyFilter = state.historyFilter.copy(modelIds = setOf(modelId)) },
            onSetAllModels = { state.historyFilter = state.historyFilter.copy(modelIds = null) },
        )

        Box(modifier = Modifier.fillMaxSize()) {
            if (historyItems.isEmpty()) {
                var emptyVisible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { emptyVisible = true }
                val emptyAlpha by animateFloatAsState(targetValue = if (emptyVisible) 1f else 0f, animationSpec = Motion.Fade, label = "emptyAlpha")
                Box(modifier = Modifier.fillMaxSize().alpha(emptyAlpha), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.offset(y = (-60).dp)) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.no_history), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.no_history_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2), contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(historyItems.size) { index ->
                        val item = historyItems[index]
                        val isSelected = state.selectedItems.contains(item)
                        Card(
                            modifier = Modifier.aspectRatio(1f), shape = MaterialTheme.shapes.medium,
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        ) {
                            Box(modifier = Modifier.combinedClickable(
                                onClick = {
                                    if (state.isSelectionMode) {
                                        if (isSelected) { state.selectedItems.remove(item); if (state.selectedItems.isEmpty()) state.isSelectionMode = false }
                                        else { state.selectedItems.add(item) }
                                    } else {
                                        state.selectedHistoryItem = item
                                        state.showHistoryDetailDialog = true
                                    }
                                },
                                onLongClick = {
                                    if (!state.isSelectionMode) { state.isSelectionMode = true; state.selectedItems.clear(); state.selectedItems.add(item) }
                                },
                            )) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current).data(item.imageFile).crossfade(true).build(),
                                    contentDescription = "Generated image", modifier = Modifier.fillMaxSize(),
                                )
                                if (isSelected) {
                                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)))
                                }
                                Surface(
                                    modifier = Modifier.align(Alignment.BottomStart),
                                    shape = RoundedCornerShape(topStart = 0.dp, topEnd = 4.dp, bottomStart = 12.dp, bottomEnd = 0.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f),
                                ) {
                                    Text(
                                        text = remember(item.timestamp, locale) {
                                            java.text.SimpleDateFormat("MM/dd HH:mm", locale).format(java.util.Date(item.timestamp))
                                        },
                                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                    )
                                }
                                if (state.isSelectionMode) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            if (checked) state.selectedItems.add(item)
                                            else { state.selectedItems.remove(item); if (state.selectedItems.isEmpty()) state.isSelectionMode = false }
                                        },
                                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Selection mode bottom bar
            if (state.isSelectionMode) {
                val visibleItems = historyItems
                val isAllSelected = state.selectedItems.size == visibleItems.size && visibleItems.all { it in state.selectedItems }
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    shape = MaterialTheme.shapes.extraLarge, shadowElevation = 6.dp,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(modifier = Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { state.isSelectionMode = false; state.selectedItems.clear() }, enabled = !state.isBatchSaving) {
                            Icon(Icons.Default.Close, contentDescription = "Exit selection mode")
                        }
                        Text(
                            text = pluralStringResource(R.plurals.selected_items_count, state.selectedItems.size, state.selectedItems.size),
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                        )
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                            IconButton(
                                onClick = {
                                    if (isAllSelected) { state.selectedItems.clear(); state.isSelectionMode = false }
                                    else { state.selectedItems.clear(); state.selectedItems.addAll(visibleItems) }
                                },
                                enabled = !state.isBatchSaving,
                                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                            ) {
                                Icon(imageVector = if (isAllSelected) Icons.Default.CheckCircle else Icons.Default.CheckCircleOutline, contentDescription = if (isAllSelected) "Deselect all" else "Select all")
                            }
                            IconButton(onClick = { state.showBatchSaveDialog = true }, enabled = state.selectedItems.isNotEmpty() && !state.isBatchSaving,
                                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                                Icon(Icons.Default.Save, contentDescription = "Save selected")
                            }
                            IconButton(onClick = { state.showBatchDeleteDialog = true }, enabled = state.selectedItems.isNotEmpty() && !state.isBatchSaving,
                                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── HistoryFilterBar (inline composable from original) ──

@Composable
fun HistoryFilterBar(
    filter: HistoryFilter,
    currentModelId: String,
    onShowFilterSheet: () -> Unit,
    onSetCurrentModelOnly: () -> Unit,
    onSetAllModels: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = filter.modelIds == setOf(currentModelId),
                onClick = onSetCurrentModelOnly,
                label = { Text(stringResource(R.string.history_filter_current_model_only), maxLines = 1) },
            )
            FilterChip(
                selected = filter.modelIds == null,
                onClick = onSetAllModels,
                label = { Text(stringResource(R.string.history_filter_all_models)) },
            )
        }
        IconButton(onClick = onShowFilterSheet) {
            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.history_view_filter))
        }
    }
}
