package io.github.dreamandroid.local.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
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
import io.github.dreamandroid.local.ui.screens.GallerySaveResult
import io.github.dreamandroid.local.ui.screens.saveBitmapToGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Aggregated outcome of a batch export to the gallery.
 *
 * [jsonFailed] counts images that were exported successfully but whose params JSON
 * sidecar could not be produced. Those images are counted in [saved], never in [failed],
 * so a sidecar problem never looks like (or causes) an image export failure.
 */
data class BatchSaveResult(
    val saved: Int,
    val failed: Int,
    val jsonFailed: Int,
)

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

    // ── Settings ──────────────────────────────────────────────
    /** When enabled, saves generation params as .json sidecar alongside exported images. */
    var saveParamsEnabled by mutableStateOf(false)

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

    suspend fun batchSaveToGallery(context: Context): BatchSaveResult {
        var saved = 0
        var failed = 0
        var jsonFailed = 0
        val saveParams = saveParamsEnabled
        selectedItems.toList().forEach { item ->
            val bitmap = try {
                withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(item.imageFile.absolutePath)
                }
            } catch (_: Exception) { null }
            if (bitmap != null) {
                // Serializing the sidecar must never abort the batch: on failure we still
                // export the image and only record a json failure.
                val paramsJson = if (saveParams) buildSaveParamsJson(item) else null
                val jsonUnavailable = saveParams && paramsJson == null
                val result = withContext(Dispatchers.IO) {
                    saveBitmapToGallery(context, bitmap, item.modelId, saveParamsJson = paramsJson)
                }
                if (result.imageSaved) {
                    saved++
                    if (jsonUnavailable || result.jsonError != null) jsonFailed++
                } else {
                    failed++
                }
            } else {
                failed++
            }
        }
        selectedItems.clear()
        isSelectionMode = false
        showBatchSaveDialog = false
        return BatchSaveResult(saved, failed, jsonFailed)
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
                    scheduler = item.params.scheduler,
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

    suspend fun saveSingleToGallery(context: Context, item: HistoryItem): GallerySaveResult {
        val saveParams = saveParamsEnabled
        val paramsJson = if (saveParams) buildSaveParamsJson(item) else null
        val jsonUnavailable = saveParams && paramsJson == null
        return withContext(Dispatchers.IO) {
            val bitmap = try {
                BitmapFactory.decodeFile(item.imageFile.absolutePath)
            } catch (_: Exception) { null }
            if (bitmap == null) return@withContext GallerySaveResult(imageSaved = false)

            val result = saveBitmapToGallery(context, bitmap, item.modelId, saveParamsJson = paramsJson)
            if (jsonUnavailable && result.jsonError == null) {
                result.copy(jsonError = "params JSON could not be serialized")
            } else {
                result
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
            scheduler = item.params.scheduler,
            timestamp = item.timestamp,
            source = RecordSource.GALLERY,
        )
        viewModelScope.launch { recordRepository.addRecord(record) }
    }

    // ── JSON sidecar helper ───────────────────────────────────

    companion object {
        /**
         * Builds a JSON representation of [item]'s generation params,
         * compatible with [GenerateParameterRecord.toJson] format for import/export.
         *
         * Returns `null` instead of throwing when the params cannot be serialized.
         * Serialization is an optional add-on to the image export, so it must never
         * abort the export itself.
         */
        fun buildSaveParamsJson(item: HistoryItem): String? {
            return try {
                JSONObject().apply {
                    put("id", item.id)
                    put("prompt", item.params.prompt)
                    put("negativePrompt", item.params.negativePrompt)
                    put("modelId", item.modelId)
                    put("steps", item.params.steps)
                    put("cfg", item.params.cfgScale.toDouble())
                    if (item.params.seed != null) put("seed", item.params.seed)
                    put("width", item.params.width)
                    put("height", item.params.height)
                    put("sampler", item.params.sampler)
                    put("scheduler", item.params.scheduler)
                    put("timestamp", item.timestamp)
                    put("source", RecordSource.GALLERY.name)
                }.toString()
            } catch (e: Exception) {
                Log.w("BrowseViewModel", "Failed to build params JSON for ${item.id}", e)
                null
            }
        }
    }
}
