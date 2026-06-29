package io.github.dreamandroid.local.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dreamandroid.local.DreamAndroidApplication
import io.github.dreamandroid.local.data.Model
import io.github.dreamandroid.local.data.ModelInfo
import io.github.dreamandroid.local.data.ModelRepository
import io.github.dreamandroid.local.service.backend.BackendManager
import io.github.dreamandroid.local.service.backend.BackendService
import io.github.dreamandroid.local.ui.frontend.ImportingModelState
import io.github.dreamandroid.local.ui.screens.model.ExtractByteProgress
import io.github.dreamandroid.local.ui.screens.model.convertCustomModel
import io.github.dreamandroid.local.ui.screens.model.extractNpuModel
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Models ViewModel extracted from AppContent God Object (UILA-COMP-0001).
 *
 * Manages:
 * - Model list & selection
 * - Model load/unload (Diffusion & Upscaler)
 * - Model import dialogs (custom, NPU, upscale)
 * - Model rename/delete
 * - Upscaler preferences
 */
class ModelsViewModel(application: Application) : AndroidViewModel(application) {

    val app get() = getApplication<DreamAndroidApplication>()
    val backendService: BackendService = app.backendService
    val modelRepository = ModelRepository(application)

    // ── Model Selection (multi-selection by default) ────────────
    val modelViewSelectedModelIds = mutableStateListOf<String>()
    val selectedModelId: String? get() = modelViewSelectedModelIds.firstOrNull()
    var modelRefreshVersion by mutableIntStateOf(0)

    fun modelViewToggleModelSelection(modelId: String) {
        if (modelId in modelViewSelectedModelIds) {
            modelViewSelectedModelIds.remove(modelId)
        } else {
            modelViewSelectedModelIds.add(modelId)
        }
    }

    fun modelViewSelectAll(models: List<Model>) {
        modelViewSelectedModelIds.clear()
        modelViewSelectedModelIds.addAll(models.map { it.id })
    }

    fun modelViewInvertSelection(models: List<Model>) {
        val allIds = models.map { it.id }.toSet()
        val inverted = allIds - modelViewSelectedModelIds.toSet()
        modelViewSelectedModelIds.clear()
        modelViewSelectedModelIds.addAll(inverted)
    }

    fun modelViewDeselectAll() {
        modelViewSelectedModelIds.clear()
    }

    // ── Import Dialog State ───────────────────────────────────
    var showCustomModelDialog by mutableStateOf(false)
    var showCustomNpuModelDialog by mutableStateOf(false)
    var showCustomUpscaleModelDialog by mutableStateOf(false)
    var showDownloadManager by mutableStateOf(false)
    var importingModels by mutableStateOf<List<ImportingModelState>>(emptyList())

    // ── Rename / Delete ───────────────────────────────────────
    var showRenameDialog by mutableStateOf(false)
    var showDeleteConfirm by mutableStateOf(false)
    var renameText by mutableStateOf("")

    // ── Upscaler ──────────────────────────────────────────────
    var upscalerPreferences by mutableStateOf<SharedPreferences?>(null)

    // ── Current Model ─────────────────────────────────────────
    var currentModel by mutableStateOf<ModelInfo?>(null)

    // ── Helpers: Importing Models ─────────────────────────────
    fun addImportingModel(state: ImportingModelState) {
        importingModels = importingModels + state
    }

    fun updateImportingModel(modelId: String, update: (ImportingModelState) -> ImportingModelState) {
        importingModels = importingModels.map { existing ->
            if (existing.modelId == modelId) update(existing) else existing
        }
    }

    fun removeImportingModel(modelId: String) {
        importingModels = importingModels.filterNot { it.modelId == modelId }
    }

    fun refreshModels() {
        modelRepository.refreshAllModels()
        modelRefreshVersion++
    }

    // ── Model Load / Unload ───────────────────────────────────

    suspend fun loadModel(
        mId: String,
        genWidth: Int,
        genHeight: Int,
        genUseOpenCL: Boolean,
    ): Result<Unit> {
        return backendService.startDiffusion(mId, genWidth, genHeight, genUseOpenCL)
    }

    suspend fun unloadModel(): Result<Unit> {
        return try {
            backendService.stop()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun loadUpscaleModel(upscalerId: String): Result<Unit> {
        val result = backendService.startUpscaler(upscalerId)
        result.onSuccess {
            upscalerPreferences?.edit {
                putString("upscaler_standalone_selected_upscaler", upscalerId)
            }
        }
        return result
    }

    suspend fun unloadUpscaleModel(): Result<Unit> {
        return try {
            backendService.stop()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Custom Model Import ───────────────────────────────────

    suspend fun importCustomModel(
        context: Context,
        modelName: String,
        fileUri: android.net.Uri,
        clipSkip: Int,
        loraFiles: List<io.github.dreamandroid.local.ui.screens.model.LoRAFile>,
    ) {
        val modelId = modelName.replace(" ", "")
        addImportingModel(
            ImportingModelState(
                modelId = modelId,
                modelName = modelName,
                isNpu = false,
                progressText = context.getString(io.github.dreamandroid.local.R.string.preparing_model),
                byteProgress = null,
            ),
        )
        convertCustomModel(
            context = context,
            modelName = modelName,
            fileUri = fileUri,
            clipSkip = clipSkip,
            loraFiles = loraFiles,
            onProgress = { progress ->
                updateImportingModel(modelId) { it.copy(progressText = progress) }
            },
            onStart = {},
            onSuccess = {
                removeImportingModel(modelId)
                refreshModels()
            },
            onError = { error ->
                removeImportingModel(modelId)
            },
        )
    }

    suspend fun importNpuModel(
        context: Context,
        modelName: String,
        zipUri: android.net.Uri,
    ) {
        val modelId = modelName.replace(" ", "")
        addImportingModel(
            ImportingModelState(
                modelId = modelId,
                modelName = modelName,
                isNpu = true,
                progressText = context.getString(io.github.dreamandroid.local.R.string.preparing_model),
                byteProgress = null,
            ),
        )
        extractNpuModel(
            context = context,
            modelName = modelName,
            zipUri = zipUri,
            onProgress = { progress ->
                updateImportingModel(modelId) { it.copy(progressText = progress) }
            },
            onByteProgress = { extracted, total, fraction ->
                updateImportingModel(modelId) {
                    it.copy(byteProgress = ExtractByteProgress(extracted, total, fraction))
                }
            },
            onStart = {},
            onSuccess = {
                removeImportingModel(modelId)
                refreshModels()
            },
            onError = { error ->
                removeImportingModel(modelId)
            },
        )
    }

    suspend fun importUpscaleModel(
        context: Context,
        modelName: String,
        fileUri: android.net.Uri,
    ): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val modelId = modelName.replace(" ", "")
                val modelDir = File(io.github.dreamandroid.local.data.Model.getModelsDir(context), modelId)
                if (modelDir.exists()) {
                    modelDir.deleteRecursively()
                }
                modelDir.mkdirs()

                val inputStream = context.contentResolver.openInputStream(fileUri)
                    ?: throw Exception("Cannot open file")
                val binFile = File(modelDir, "$modelId.bin")
                inputStream.use { input ->
                    binFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                File(modelDir, "upscaler_custom").createNewFile()
            }
            refreshModels()
            true
        } catch (_: Exception) {
            false
        }
    }

    // ── Rename / Delete ───────────────────────────────────────

    fun prepareRename() {
        val targetId = modelViewSelectedModelIds.firstOrNull()
        val m = modelRepository.models.find { it.id == targetId }
        renameText = m?.name ?: (targetId ?: "")
        showRenameDialog = true
    }

    fun isRenameDuplicate(newName: String): Boolean {
        val targetId = modelViewSelectedModelIds.firstOrNull() ?: return false
        val newId = newName.replace(" ", "")
        return modelRepository.models.any { it.id == newId && it.id != targetId }
    }

    suspend fun renameModel(context: Context, newName: String): Boolean {
        val targetId = modelViewSelectedModelIds.firstOrNull()
        val renameModel = modelRepository.models.find { it.id == targetId }
        if (newName.isEmpty() || renameModel == null) return false
        if (isRenameDuplicate(newName)) return false
        val success = renameModel.renameModel(context, newName)
        if (success) {
            refreshModels()
            // update selection to new id after rename
            val newId = newName.replace(" ", "")
            val idx = modelViewSelectedModelIds.indexOfFirst { it == targetId }
            if (idx >= 0) modelViewSelectedModelIds[idx] = newId
        }
        showRenameDialog = false
        return success
    }

    suspend fun deleteModel(context: Context): Boolean {
        showDeleteConfirm = false
        val targetId = modelViewSelectedModelIds.firstOrNull()
        val delModel = modelRepository.models.find { it.id == targetId }
        if (delModel == null) return false

        // If the target model is currently loaded in backend, unload it first
        val currentState = backendService.state.value
        if (currentState is BackendManager.State.Running &&
            currentState.modelId == delModel.id) {
            unloadModel()
        }
        val success = delModel.deleteModel(context)
        if (success) {
            modelViewSelectedModelIds.remove(delModel.id)
            refreshModels()
        }
        return success
    }
}
