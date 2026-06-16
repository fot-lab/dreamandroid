package io.github.dreamandroid.local.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dreamandroid.local.DreamAndroidApplication
import io.github.dreamandroid.local.data.GenerationPreferences
import io.github.dreamandroid.local.service.QueueRepository
import io.github.dreamandroid.local.ui.screens.run.inferAspectRatioString
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Generate ViewModel extracted from AppContent God Object (UILA-COMP-0001).
 *
 * Manages:
 * - All generation parameters (prompt, steps, cfg, seed, etc.)
 * - Preference loading/saving (global + per-model)
 * - Tokenize calls (via BackendManager)
 * - Add-to-queue logic
 */
class GenerateViewModel(application: Application) : ViewModel() {

    private val app = application as DreamAndroidApplication
    private val backendManager = app.backendManager

    // ── Generation Parameters ─────────────────────────────────
    var genPrompt by mutableStateOf("")
    var genNegativePrompt by mutableStateOf("")
    var genSteps by mutableFloatStateOf(20f)
    var genCfg by mutableFloatStateOf(7f)
    var genSeed by mutableStateOf("")
    var genBatchCounts by mutableIntStateOf(1)
    var genScheduler by mutableStateOf("dpm")
    var genDenoiseStrength by mutableFloatStateOf(0.6f)
    var genUseOpenCL by mutableStateOf(false)
    var genWidth by mutableIntStateOf(512)
    var genHeight by mutableIntStateOf(512)

    // ── Tokenize State ────────────────────────────────────────
    var promptTokenCount by mutableIntStateOf(0)
    var negativePromptTokenCount by mutableIntStateOf(0)

    // ── Preferences ───────────────────────────────────────────

    fun loadGlobalPrefs(prefs: GenerationPreferences) {
        genPrompt = prefs.getGlobalPrompt()
        genNegativePrompt = prefs.getGlobalNegativePrompt()
        genBatchCounts = prefs.getGlobalBatchCounts().coerceAtLeast(1)
        genWidth = prefs.getGlobalWidth().coerceIn(64, 4096)
        genHeight = prefs.getGlobalHeight().coerceIn(64, 4096)
    }

    suspend fun loadModelPrefs(modelId: String, prefs: GenerationPreferences) {
        val p = prefs.getPreferences(modelId).first()
        if (genPrompt.isEmpty() && p.prompt.isNotEmpty()) genPrompt = p.prompt
        if (genNegativePrompt.isEmpty() && p.negativePrompt.isNotEmpty()) genNegativePrompt = p.negativePrompt
        if (genBatchCounts == 1 && p.batchCounts > 1) genBatchCounts = p.batchCounts
        if (p.steps > 0) genSteps = p.steps
        if (p.cfg > 0) genCfg = p.cfg
        if (p.seed.isNotEmpty()) genSeed = p.seed
        genScheduler = p.scheduler
        genDenoiseStrength = p.denoiseStrength
        genUseOpenCL = p.useOpenCL
    }

    // ── Tokenize ──────────────────────────────────────────────

    suspend fun tokenizePrompt(prompt: String) {
        if (prompt.isBlank()) {
            promptTokenCount = 0
            return
        }
        val result = backendManager.tokenize(prompt)
        promptTokenCount = result.count
    }

    suspend fun tokenizeNegativePrompt(prompt: String) {
        if (prompt.isBlank()) {
            negativePromptTokenCount = 0
            return
        }
        val result = backendManager.tokenize(prompt)
        negativePromptTokenCount = result.count
    }

    // ── Save All Fields ───────────────────────────────────────

    suspend fun saveAllFields(prefs: GenerationPreferences, modelId: String) {
        prefs.saveAllFields(
            modelId = modelId,
            prompt = genPrompt,
            negativePrompt = genNegativePrompt,
            steps = genSteps,
            cfg = genCfg,
            seed = genSeed,
            width = genWidth,
            height = genHeight,
            denoiseStrength = genDenoiseStrength,
            useOpenCL = genUseOpenCL,
            batchCounts = genBatchCounts,
            scheduler = genScheduler,
            aspectRatio = inferAspectRatioString(genWidth, genHeight),
        )
    }

    // ── Add to Queue ──────────────────────────────────────────

    fun addToQueue(
        modelId: String,
        count: Int,
        queueRepository: QueueRepository,
    ) {
        queueRepository.addBatch(
            modelId = modelId,
            prompt = genPrompt,
            negativePrompt = genNegativePrompt,
            steps = genSteps.roundToInt(),
            cfg = genCfg,
            seed = genSeed,
            width = genWidth,
            height = genHeight,
            effectiveWidth = genWidth,
            effectiveHeight = genHeight,
            denoiseStrength = genDenoiseStrength,
            useOpenCL = genUseOpenCL,
            scheduler = genScheduler,
            aspectRatio = inferAspectRatioString(genWidth, genHeight),
            count = count.coerceAtLeast(1),
        )
    }

    // ── Reset ─────────────────────────────────────────────────

    fun resetToDefaults() {
        genPrompt = ""
        genSteps = 20f
        genCfg = 7f
        genSeed = ""
        genBatchCounts = 1
        genScheduler = "dpm"
        genDenoiseStrength = 0.6f
        genUseOpenCL = false
        genWidth = 512
        genHeight = 512
        promptTokenCount = 0
        negativePromptTokenCount = 0
    }
}
