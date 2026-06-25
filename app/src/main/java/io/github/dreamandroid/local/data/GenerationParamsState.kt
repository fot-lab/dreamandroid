package io.github.dreamandroid.local.data

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Shared runtime state for generation parameters.
 *
 * Serves as the single source of truth for both [GenerateViewModel] (Generate tab)
 * and [ModelRunState] (ModelRunScreen), ensuring consistency across screens.
 *
 * Persisted via [GenerationPreferences]; loaded on startup / model switch
 * and saved on every parameter change.
 */
@Stable
class GenerationParamsState {
    // ── Core Generation Parameters ────────────────────────────
    var prompt by mutableStateOf("")
    var negativePrompt by mutableStateOf("")
    var steps by mutableFloatStateOf(20f)
    var cfg by mutableFloatStateOf(7f)
    var seed by mutableStateOf("")
    var batchCounts by mutableIntStateOf(1)
    var sampler by mutableStateOf("dpm")
    var denoiseCurve by mutableStateOf("scaled_linear")
    var denoiseStrength by mutableFloatStateOf(0.6f)
    var useOpenCL by mutableStateOf(false)
    var width by mutableIntStateOf(512)
    var height by mutableIntStateOf(512)

    // ── Token State ───────────────────────────────────────────
    var promptTokenCount by mutableIntStateOf(0)
    var promptTokenMax by mutableIntStateOf(77)
    var promptOverflowOffset by mutableIntStateOf(-1)
    var negativePromptTokenCount by mutableIntStateOf(0)
    var negativePromptTokenMax by mutableIntStateOf(77)
    var negativePromptOverflowOffset by mutableIntStateOf(-1)

    fun resetToDefaults() {
        prompt = ""
        negativePrompt = ""
        steps = 20f
        cfg = 7f
        seed = ""
        batchCounts = 1
        sampler = "dpm"
        denoiseCurve = "scaled_linear"
        denoiseStrength = 0.6f
        useOpenCL = false
        width = 512
        height = 512
        promptTokenCount = 0
        negativePromptTokenCount = 0
        promptOverflowOffset = -1
        negativePromptOverflowOffset = -1
    }
}
