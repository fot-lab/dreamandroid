package io.github.dreamandroid.local.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
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
class BrowseViewModel(application: Application) : ViewModel() {

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
    var filterModelId by mutableStateOf<String?>(null)

    // ── Selection helpers ─────────────────────────────────────

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

    // ── Batch Delete ──────────────────────────────────────────

    fun batchDelete(): Int {
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
                    cfg = item.params.cfg,
                    seed = item.params.seed,
                    width = item.params.width,
                    height = item.params.height,
                    scheduler = item.params.scheduler,
                    timestamp = item.timestamp,
                    source = RecordSource.GALLERY,
                )
                recordRepository.addRecord(record)
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
        historyManager.deleteHistoryItem(item)
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
            cfg = item.params.cfg,
            seed = item.params.seed,
            width = item.params.width,
            height = item.params.height,
            scheduler = item.params.scheduler,
            timestamp = item.timestamp,
            source = RecordSource.GALLERY,
        )
        recordRepository.addRecord(record)
    }
}
