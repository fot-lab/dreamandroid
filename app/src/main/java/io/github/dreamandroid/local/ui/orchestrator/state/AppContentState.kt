package io.github.dreamandroid.local.ui.orchestrator.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.dreamandroid.local.data.ModelInfo
import io.github.dreamandroid.local.data.GenerationPrefs
import io.github.dreamandroid.local.navigation.BottomTab
import io.github.dreamandroid.local.ui.frontend.ImportingModelState
import kotlinx.coroutines.flow.first

/**
 * Centralized mutable state holder for [AppContent].
 *
 * Groups all mutable state previously declared inline in the AppContent composable,
 * enabling extraction of dialog blocks, top-level helpers, and testing the state
 * logic independently of Compose rendering.
 *
 * Pattern: pure-Compose @Stable class (not AndroidX ViewModel), instantiated via
 * remember { AppContentState() }.
 */
@Stable
class AppContentState {

    // ── Navigation ────────────────────────────────────────────
    var selectedTab by mutableStateOf(BottomTab.Models)
    var selectedModelId by mutableStateOf<String?>(null)

    // ── Generation Parameters ─────────────────────────────────
    var genPrompt by mutableStateOf("")
    var genNegativePrompt by mutableStateOf("")
    var genSteps by mutableFloatStateOf(20f)
    var genCfg by mutableFloatStateOf(7f)
    var genSeed by mutableStateOf("")
    var genBatchCounts by mutableIntStateOf(1)
    var genSampler by mutableStateOf("dpm")
    var genDenoiseCurve by mutableStateOf("scaled_linear")
    var genDenoiseStrength by mutableFloatStateOf(0.6f)
    var genUseOpenCL by mutableStateOf(false)
    var genWidth by mutableIntStateOf(512)
    var genHeight by mutableIntStateOf(512)

    // ── Model Refresh ─────────────────────────────────────────
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

    // ── Warnings ──────────────────────────────────────────────
    var showNoModelWarning by mutableStateOf(false)

    // ── Model Info (derived, set externally) ──────────────────
    var currentModel by mutableStateOf<ModelInfo?>(null)

    // ── Helpers ───────────────────────────────────────────────

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

    suspend fun loadGlobalPrefs(prefs: io.github.dreamandroid.local.data.GenerationPreferences) {
        genPrompt = prefs.getGlobalPrompt()
        genNegativePrompt = prefs.getGlobalNegativePrompt()
        genBatchCounts = prefs.getGlobalBatchCounts().coerceAtLeast(1)
        genWidth = prefs.getGlobalWidth().coerceIn(64, 4096)
        genHeight = prefs.getGlobalHeight().coerceIn(64, 4096)
    }

    suspend fun loadModelPrefs(
        modelId: String,
        prefs: io.github.dreamandroid.local.data.GenerationPreferences,
    ) {
        val p = prefs.getPreferences(modelId).first()
        if (genPrompt.isEmpty() && p.prompt.isNotEmpty()) genPrompt = p.prompt
        if (genNegativePrompt.isEmpty() && p.negativePrompt.isNotEmpty()) genNegativePrompt = p.negativePrompt
        if (genBatchCounts == 1 && p.batchCounts > 1) genBatchCounts = p.batchCounts
        if (p.steps > 0) genSteps = p.steps
        if (p.cfgScale > 0) genCfg = p.cfgScale
        if (p.seed.isNotEmpty()) genSeed = p.seed
        genSampler = p.sampler
        genDenoiseCurve = p.denoiseCurve
        genDenoiseStrength = p.denoisingStrength
        genUseOpenCL = p.useOpenCL
    }
}
