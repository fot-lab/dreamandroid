package io.github.dreamandroid.local.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import io.github.dreamandroid.local.DreamAndroidApplication
import io.github.dreamandroid.local.core.error.AppError
import io.github.dreamandroid.local.data.GenerationPreferences
import io.github.dreamandroid.local.service.QueueRepository
import io.github.dreamandroid.local.service.backend.BackendManager.TokenizeResult
import kotlinx.coroutines.flow.first
import io.github.dreamandroid.local.service.backend.BackendService
import io.github.dreamandroid.local.ui.screens.run.inferAspectRatioString
import kotlin.math.roundToInt

/**
 * Generate ViewModel extracted from AppContent God Object (UILA-COMP-0001).
 *
 * Manages:
 * - All generation parameters (prompt, steps, cfg, seed, etc.)
 * - Preference loading/saving (global + per-model)
 * - Tokenize calls (via BackendService HTTP middleware) with AppError-based error handling
 * - Add-to-queue logic
 */
class GenerateViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as DreamAndroidApplication
    private val backendService: BackendService = app.backendService

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

    // ── Tokenize State ────────────────────────────────────────
    var promptTokenCount by mutableIntStateOf(0)
    var promptTokenMax by mutableIntStateOf(77)
    var promptOverflowOffset by mutableIntStateOf(-1)
    var negativePromptTokenCount by mutableIntStateOf(0)
    var negativePromptTokenMax by mutableIntStateOf(77)
    var negativePromptOverflowOffset by mutableIntStateOf(-1)

    // ── Error State (AppError-sealed, UILA-COMP-0003) ────────
    var tokenizeError by mutableStateOf<AppError?>(null)

    // ── Preferences ───────────────────────────────────────────

    suspend fun loadGlobalPrefs(prefs: GenerationPreferences) {
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
        if (p.cfgScale > 0) genCfg = p.cfgScale
        if (p.seed.isNotEmpty()) genSeed = p.seed
        genSampler = p.sampler
        genDenoiseCurve = p.denoiseCurve
        genDenoiseStrength = p.denoisingStrength
        genUseOpenCL = p.useOpenCL
    }

    // ── Tokenize (HTTP via BackendService middleware, no direct BackendManager access) ──

    suspend fun tokenizePrompt(prompt: String): TokenizeResult? {
        if (prompt.isBlank()) {
            promptTokenCount = 0
            promptTokenMax = 77
            promptOverflowOffset = -1
            return null
        }
        return try {
            val result = backendService.tokenize(prompt)
            promptTokenCount = result.count
            promptTokenMax = result.maxLength
            promptOverflowOffset = result.overflowOffset
            tokenizeError = null
            result
        } catch (e: Exception) {
            tokenizeError = AppError.from(e)
            null
        }
    }

    suspend fun tokenizeNegativePrompt(prompt: String): TokenizeResult? {
        if (prompt.isBlank()) {
            negativePromptTokenCount = 0
            negativePromptTokenMax = 77
            negativePromptOverflowOffset = -1
            return null
        }
        return try {
            val result = backendService.tokenize(prompt)
            negativePromptTokenCount = result.count
            negativePromptTokenMax = result.maxLength
            negativePromptOverflowOffset = result.overflowOffset
            tokenizeError = null
            result
        } catch (e: Exception) {
            tokenizeError = AppError.from(e)
            null
        }
    }

    // ── Save All Fields ───────────────────────────────────────

    suspend fun saveAllFields(prefs: GenerationPreferences, modelId: String) {
        prefs.saveAllFields(
            modelId = modelId,
            prompt = genPrompt,
            negativePrompt = genNegativePrompt,
            steps = genSteps,
            cfgScale = genCfg,
            seed = genSeed,
            width = genWidth,
            height = genHeight,
            denoisingStrength = genDenoiseStrength,
            useOpenCL = genUseOpenCL,
            batchCounts = genBatchCounts,
            sampler = genSampler,
            denoiseCurve = genDenoiseCurve,
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
            sampler = genSampler,
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
        genSampler = "dpm"
        genDenoiseCurve = "scaled_linear"
        genDenoiseStrength = 0.6f
        genUseOpenCL = false
        genWidth = 512
        genHeight = 512
        promptTokenCount = 0
        negativePromptTokenCount = 0
    }
}
