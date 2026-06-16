package io.github.dreamandroid.local.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dreamandroid.local.DreamAndroidApplication
import io.github.dreamandroid.local.data.ModelInfo
import io.github.dreamandroid.local.data.ModelRepository
import io.github.dreamandroid.local.service.backend.BackendManager
import io.github.dreamandroid.local.ui.frontend.ImportingModelState
import io.github.dreamandroid.local.ui.screens.model.ExtractByteProgress
import io.github.dreamandroid.local.ui.screens.model.convertCustomModel
import io.github.dreamandroid.local.ui.screens.model.extractNpuModel
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
class ModelsViewModel(application: Application) : ViewModel() {

    val app = application as DreamAndroidApplication
    val backendManager: BackendManager = app.backendManager
    val modelRepository = ModelRepository(application)

    // ── Model Selection ───────────────────────────────────────
    var selectedModelId by mutableStateOf<String?>(null)
    var modelRefreshVersion by mutableIntStateOf(0)

    // ── Import Dialog State ───────────────────────────────────
    var showCustomModelDialog by mutableStateOf(false)
    var showCustomNpuModelDialog by mutableStateOf(false)
    var showCustomUpscaleModelDialog by mutableStateOf(false)
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
        val result = backendManager.startDiffusion(mId, genWidth, genHeight, genUseOpenCL)
        result.onSuccess { selectedModelId = mId }
        return result
    }

    suspend fun unloadModel(): Result<Unit> {
        return try {
            backendManager.stop()
            selectedModelId = null
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun loadUpscaleModel(upscalerId: String): Result<Unit> {
        val result = backendManager.startUpscaler(upscalerId)
        result.onSuccess {
            upscalerPreferences?.edit {
                putString("upscaler_standalone_selected_upscaler", upscalerId)
            }
        }
        return result
    }

    suspend fun unloadUpscaleModel(): Result<Unit> {
        return try {
            backendManager.stop()
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
        val m = modelRepository.models.find { it.id == selectedModelId }
        renameText = m?.name ?: (selectedModelId ?: "")
        showRenameDialog = true
    }

    suspend fun renameModel(context: Context, newName: String): Boolean {
        val renameModel = modelRepository.models.find { it.id == selectedModelId }
        if (newName.isEmpty() || renameModel == null) return false
        val success = renameModel.renameModel(context, newName)
        if (success) {
            refreshModels()
            selectedModelId = newName.replace(" ", "")
        }
        showRenameDialog = false
        return success
    }

    suspend fun deleteModel(context: Context, wasLoaded: Boolean): Boolean {
        showDeleteConfirm = false
        val delModel = modelRepository.models.find { it.id == selectedModelId }
        if (delModel == null) return false

        if (wasLoaded && selectedModelId == delModel.id) {
            unloadModel()
        }
        val success = delModel.deleteModel(context)
        if (success) {
            if (selectedModelId == delModel.id) selectedModelId = null
            refreshModels()
        }
        return success
    }
}
