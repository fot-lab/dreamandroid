package io.github.dreamandroid.local.ui.frontend

import androidx.compose.runtime.Composable
import io.github.dreamandroid.local.data.RecordRepository
import io.github.dreamandroid.local.ui.screens.GenerateScreen

// =========== Generate Tab (parameterized wrapper) ===========

@Composable
fun TabGenerateScreen(
    modelId: String?,
    prompt: String,
    onPromptChange: (String) -> Unit,
    negativePrompt: String,
    onNegativePromptChange: (String) -> Unit,
    steps: Float,
    onStepsChange: (Float) -> Unit,
    cfg: Float,
    onCfgChange: (Float) -> Unit,
    seed: String,
    onSeedChange: (String) -> Unit,
    batchCounts: Int,
    onBatchCountsChange: (Int) -> Unit,
    scheduler: String,
    onSchedulerChange: (String) -> Unit,
    denoiseStrength: Float,
    onDenoiseStrengthChange: (Float) -> Unit,
    useOpenCL: Boolean,
    onUseOpenCLChange: (Boolean) -> Unit,
    width: Int,
    onWidthChange: (Int) -> Unit,
    height: Int,
    onHeightChange: (Int) -> Unit,
    onAddToQueue: (Int) -> Unit = {},
    recordRepository: RecordRepository? = null,
) {
    GenerateScreen(
        modelId = modelId,
        prompt = prompt,
        onPromptChange = onPromptChange,
        negativePrompt = negativePrompt,
        onNegativePromptChange = onNegativePromptChange,
        steps = steps,
        onStepsChange = onStepsChange,
        cfg = cfg,
        onCfgChange = onCfgChange,
        seed = seed,
        onSeedChange = onSeedChange,
        batchCounts = batchCounts,
        onBatchCountsChange = onBatchCountsChange,
        scheduler = scheduler,
        onSchedulerChange = onSchedulerChange,
        denoiseStrength = denoiseStrength,
        onDenoiseStrengthChange = onDenoiseStrengthChange,
        useOpenCL = useOpenCL,
        onUseOpenCLChange = onUseOpenCLChange,
        width = width,
        onWidthChange = onWidthChange,
        height = height,
        onHeightChange = onHeightChange,
        onAddToQueue = onAddToQueue,
        recordRepository = recordRepository,
    )
}
