package io.github.dreamandroid.local.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.github.dreamandroid.local.DreamAndroidApplication
import io.github.dreamandroid.local.core.error.AppError
import io.github.dreamandroid.local.data.GenerationParamsState
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
 * - All generation parameters (delegates to shared [GenerationParamsState])
 * - Preference loading/saving (global + per-model)
 * - Tokenize calls (via BackendService HTTP middleware) with AppError-based error handling
 * - Add-to-queue logic
 */
class GenerateViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as DreamAndroidApplication
    private val backendService: BackendService = app.backendService

    /** Shared runtime state for generation parameters (single source of truth). */
    private val genParams: GenerationParamsState = app.generationParamsState

    // ── Generation Parameters (forwarding to shared state) ────
    var genPrompt: String get() = genParams.prompt set(v) { genParams.prompt = v }
    var genNegativePrompt: String get() = genParams.negativePrompt set(v) { genParams.negativePrompt = v }
    var genSteps: Float get() = genParams.steps set(v) { genParams.steps = v }
    var genCfg: Float get() = genParams.cfg set(v) { genParams.cfg = v }
    var genSeed: String get() = genParams.seed set(v) { genParams.seed = v }
    var genBatchCounts: Int get() = genParams.batchCounts set(v) { genParams.batchCounts = v }
    var genSampler: String get() = genParams.sampler set(v) { genParams.sampler = v }
    var genDenoiseCurve: String get() = genParams.denoiseCurve set(v) { genParams.denoiseCurve = v }
    var genDenoiseStrength: Float get() = genParams.denoiseStrength set(v) { genParams.denoiseStrength = v }
    var genUseOpenCL: Boolean get() = genParams.useOpenCL set(v) { genParams.useOpenCL = v }
    var genWidth: Int get() = genParams.width set(v) { genParams.width = v }
    var genHeight: Int get() = genParams.height set(v) { genParams.height = v }

    // ── Tokenize State (forwarding to shared state) ───────────
    var promptTokenCount: Int get() = genParams.promptTokenCount set(v) { genParams.promptTokenCount = v }
    var promptTokenMax: Int get() = genParams.promptTokenMax set(v) { genParams.promptTokenMax = v }
    var promptOverflowOffset: Int get() = genParams.promptOverflowOffset set(v) { genParams.promptOverflowOffset = v }
    var negativePromptTokenCount: Int get() = genParams.negativePromptTokenCount set(v) { genParams.negativePromptTokenCount = v }
    var negativePromptTokenMax: Int get() = genParams.negativePromptTokenMax set(v) { genParams.negativePromptTokenMax = v }
    var negativePromptOverflowOffset: Int get() = genParams.negativePromptOverflowOffset set(v) { genParams.negativePromptOverflowOffset = v }

    // ── Error State (AppError-sealed, UILA-COMP-0003) ────────
    var tokenizeError by androidx.compose.runtime.mutableStateOf<AppError?>(null)

    // ── Preferences ───────────────────────────────────────────

    suspend fun loadGlobalPrefs(prefs: GenerationPreferences) {
        genParams.prompt = prefs.getGlobalPrompt()
        genParams.negativePrompt = prefs.getGlobalNegativePrompt()
        genParams.batchCounts = prefs.getGlobalBatchCounts().coerceAtLeast(1)
        genParams.width = prefs.getGlobalWidth().coerceIn(64, 4096)
        genParams.height = prefs.getGlobalHeight().coerceIn(64, 4096)
    }

    suspend fun loadModelPrefs(modelId: String, prefs: GenerationPreferences) {
        val p = prefs.getPreferences(modelId).first()
        if (genParams.prompt.isEmpty() && p.prompt.isNotEmpty()) genParams.prompt = p.prompt
        if (genParams.negativePrompt.isEmpty() && p.negativePrompt.isNotEmpty()) genParams.negativePrompt = p.negativePrompt
        if (genParams.batchCounts == 1 && p.batchCounts > 1) genParams.batchCounts = p.batchCounts
        if (p.steps > 0) genParams.steps = p.steps
        if (p.cfgScale > 0) genParams.cfg = p.cfgScale
        if (p.seed.isNotEmpty()) genParams.seed = p.seed
        genParams.sampler = p.sampler
        genParams.denoiseCurve = p.denoiseCurve
        genParams.denoiseStrength = p.denoisingStrength
        genParams.useOpenCL = p.useOpenCL
    }

    // ── Tokenize (HTTP via BackendService middleware, no direct BackendManager access) ──

    suspend fun tokenizePrompt(prompt: String): TokenizeResult? {
        if (prompt.isBlank()) {
            genParams.promptTokenCount = 0
            genParams.promptTokenMax = 77
            genParams.promptOverflowOffset = -1
            return null
        }
        return try {
            val result = backendService.tokenize(prompt)
            genParams.promptTokenCount = result.count
            genParams.promptTokenMax = result.maxLength
            genParams.promptOverflowOffset = result.overflowOffset
            tokenizeError = null
            result
        } catch (e: Exception) {
            tokenizeError = AppError.from(e)
            null
        }
    }

    suspend fun tokenizeNegativePrompt(prompt: String): TokenizeResult? {
        if (prompt.isBlank()) {
            genParams.negativePromptTokenCount = 0
            genParams.negativePromptTokenMax = 77
            genParams.negativePromptOverflowOffset = -1
            return null
        }
        return try {
            val result = backendService.tokenize(prompt)
            genParams.negativePromptTokenCount = result.count
            genParams.negativePromptTokenMax = result.maxLength
            genParams.negativePromptOverflowOffset = result.overflowOffset
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
            prompt = genParams.prompt,
            negativePrompt = genParams.negativePrompt,
            steps = genParams.steps,
            cfgScale = genParams.cfg,
            seed = genParams.seed,
            width = genParams.width,
            height = genParams.height,
            denoisingStrength = genParams.denoiseStrength,
            useOpenCL = genParams.useOpenCL,
            batchCounts = genParams.batchCounts,
            sampler = genParams.sampler,
            denoiseCurve = genParams.denoiseCurve,
            aspectRatio = inferAspectRatioString(genParams.width, genParams.height),
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
            prompt = genParams.prompt,
            negativePrompt = genParams.negativePrompt,
            steps = genParams.steps.roundToInt(),
            cfg = genParams.cfg,
            seed = genParams.seed,
            width = genParams.width,
            height = genParams.height,
            effectiveWidth = genParams.width,
            effectiveHeight = genParams.height,
            denoiseStrength = genParams.denoiseStrength,
            useOpenCL = genParams.useOpenCL,
            sampler = genParams.sampler,
            aspectRatio = inferAspectRatioString(genParams.width, genParams.height),
            count = count.coerceAtLeast(1),
        )
    }

    // ── Reset ─────────────────────────────────────────────────

    fun resetToDefaults() {
        genParams.resetToDefaults()
    }
}
