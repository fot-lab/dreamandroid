package io.github.dreamandroid.local.core.model

data class GenerateParams(
    val prompt: String,
    val negativePrompt: String = "",
    val steps: Int = 28,
    val cfgScale: Float = 7.0f,
    val useCfg: Boolean = true,
    val width: Int = 512,
    val height: Int = 512,
    val denoisingStrength: Float = 0.6f,
    val useOpenCL: Boolean = false,
    val sampler: String = "dpm",
    val denoiseCurve: String = "scaled_linear",
    val showDiffusionProcess: Boolean = false,
    val showDiffusionStride: Int = 1,
    val aspectRatio: String = "1:1",
    val seed: Long? = null,        // null = random; explicit 0 = random (Stability-AI)
    val imageBase64: String? = null,
    val maskBase64: String? = null,
)
