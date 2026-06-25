package io.github.dreamandroid.local.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dreamandroid.local.data.HistoryFilter
import io.github.dreamandroid.local.data.HistoryItem
import io.github.dreamandroid.local.data.HistoryManager
import io.github.dreamandroid.local.data.RecordRepository
import io.github.dreamandroid.local.data.RecordSource
import io.github.dreamandroid.local.data.GenerateParameterRecord
import io.github.dreamandroid.local.ui.screens.saveBitmapToGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Browse ViewModel extracted from BrowseScreen (UILA-COMP-0001).
 *
 * Manages:
 * - History items observation
 * - Selection mode with multi-select
 * - Batch operations (delete, save to gallery, save params)
 * - Filtering by modelId
 * - Detail/preview dialogs
 */
class BrowseViewModel(application: Application) : AndroidViewModel(application) {

    private val historyManager = HistoryManager(application)

    val knownModelIds: StateFlow<List<String>> = historyManager.observeKnownModelIds()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val historyFilter = HistoryFilter(modelIds = emptySet())
    val historyItems: StateFlow<List<HistoryItem>> = historyManager.observe(historyFilter)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── Selection Mode ────────────────────────────────────────
    var isSelectionMode by mutableStateOf(false)
    val selectedItems = mutableStateListOf<HistoryItem>()

    // ── Dialog State ──────────────────────────────────────────
    var showBatchDeleteDialog by mutableStateOf(false)
    var showBatchSaveDialog by mutableStateOf(false)
    var showBatchSaveInfoDialog by mutableStateOf(false)
    var showHistoryDetailDialog by mutableStateOf<HistoryItem?>(null)
    var isPreviewMode by mutableStateOf(false)

    // ── Filter ────────────────────────────────────────────────
    /** Model IDs to filter by. Empty set = show all models. */
    var filterModelIds by mutableStateOf<Set<String>>(emptySet())

    fun toggleModelFilter(modelId: String) {
        filterModelIds = if (modelId in filterModelIds) {
            filterModelIds - modelId
        } else {
            filterModelIds + modelId
        }
    }

    fun selectAllModelFilters() {
        filterModelIds = emptySet() // empty = show all
    }

    // ── Selection helpers ─────────────────────────────────────

    /** Returns items that pass the current model filter. */
    private fun filteredItems(): List<HistoryItem> =
        historyItems.value.filter { item ->
            filterModelIds.isEmpty() || item.modelId in filterModelIds
        }

    fun toggleSelection(item: HistoryItem) {
        if (isSelectionMode) {
            if (selectedItems.contains(item)) {
                selectedItems.remove(item)
                if (selectedItems.isEmpty()) isSelectionMode = false
            } else {
                selectedItems.add(item)
            }
        } else {
            showHistoryDetailDialog = item
        }
    }

    fun startSelection(item: HistoryItem) {
        if (!isSelectionMode) {
            isSelectionMode = true
            selectedItems.add(item)
        }
    }

    fun exitSelection() {
        isSelectionMode = false
        selectedItems.clear()
    }

    fun galleryBrowseSelectAll() {
        val items = filteredItems()
        if (items.isEmpty()) return
        isSelectionMode = true
        selectedItems.clear()
        selectedItems.addAll(items)
    }

    fun galleryBrowseInvertSelection() {
        val items = filteredItems()
        if (!isSelectionMode) {
            isSelectionMode = true
            selectedItems.clear()
            selectedItems.addAll(items)
        } else {
            items.forEach { item ->
                if (selectedItems.contains(item)) selectedItems.remove(item)
                else selectedItems.add(item)
            }
            if (selectedItems.isEmpty()) isSelectionMode = false
        }
    }

    fun galleryBrowseDeselectAll() {
        exitSelection()
    }

    // ── Batch Delete ──────────────────────────────────────────

    suspend fun batchDelete(): Int {
        var successCount = 0
        selectedItems.toList().forEach { item ->
            if (historyManager.deleteHistoryItem(item)) successCount++
        }
        selectedItems.clear()
        isSelectionMode = false
        showBatchDeleteDialog = false
        return successCount
    }

    // ── Batch Save to Gallery ─────────────────────────────────

    suspend fun batchSaveToGallery(context: Context): Pair<Int, Int> {
        var saved = 0
        var failed = 0
        selectedItems.toList().forEach { item ->
            val bitmap = try {
                withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(item.imageFile.absolutePath)
                }
            } catch (_: Exception) { null }
            if (bitmap != null) {
                val result = withContext(Dispatchers.IO) {
                    saveBitmapToGallery(context, bitmap, item.modelId)
                }
                if (result) saved++ else failed++
            } else {
                failed++
            }
        }
        selectedItems.clear()
        isSelectionMode = false
        showBatchSaveDialog = false
        return Pair(saved, failed)
    }

    // ── Batch Save Params ─────────────────────────────────────

    fun batchSaveParams(recordRepository: RecordRepository): Int {
        var savedCount = 0
        selectedItems.toList().forEach { item ->
            try {
                val record = GenerateParameterRecord(
                    prompt = item.params.prompt,
                    negativePrompt = item.params.negativePrompt,
                    modelId = item.modelId,
                    steps = item.params.steps,
                    cfg = item.params.cfgScale,
                    seed = item.params.seed,
                    width = item.params.width,
                    height = item.params.height,
                    sampler = item.params.sampler,
                    timestamp = item.timestamp,
                    source = RecordSource.GALLERY,
                )
                viewModelScope.launch { recordRepository.addRecord(record) }
                savedCount++
            } catch (_: Exception) { }
        }
        selectedItems.clear()
        isSelectionMode = false
        showBatchSaveInfoDialog = false
        return savedCount
    }

    // ── Single Item Actions ───────────────────────────────────

    fun deleteSingleItem(item: HistoryItem) {
        viewModelScope.launch { historyManager.deleteHistoryItem(item) }
        showHistoryDetailDialog = null
    }

    suspend fun saveSingleToGallery(context: Context, item: HistoryItem): Boolean {
        return withContext(Dispatchers.IO) {
            val bitmap = try {
                BitmapFactory.decodeFile(item.imageFile.absolutePath)
            } catch (_: Exception) { null }
            if (bitmap != null) {
                saveBitmapToGallery(context, bitmap, item.modelId)
            } else {
                false
            }
        }
    }

    fun saveSingleParams(item: HistoryItem, recordRepository: RecordRepository) {
        val record = GenerateParameterRecord(
            prompt = item.params.prompt,
            negativePrompt = item.params.negativePrompt,
            modelId = item.modelId,
            steps = item.params.steps,
            cfg = item.params.cfgScale,
            seed = item.params.seed,
            width = item.params.width,
            height = item.params.height,
            sampler = item.params.sampler,
            timestamp = item.timestamp,
            source = RecordSource.GALLERY,
        )
        viewModelScope.launch { recordRepository.addRecord(record) }
    }
}
