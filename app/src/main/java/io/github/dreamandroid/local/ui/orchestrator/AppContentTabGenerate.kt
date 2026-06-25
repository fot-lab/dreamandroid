package io.github.dreamandroid.local.ui.orchestrator

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.GenerationPreferences
import io.github.dreamandroid.local.data.ModelRepository
import io.github.dreamandroid.local.data.RecordRepository
import io.github.dreamandroid.local.service.QueueRepository
import io.github.dreamandroid.local.ui.frontend.GenerateTopBar
import io.github.dreamandroid.local.ui.frontend.TabGenerateScreen
import io.github.dreamandroid.local.ui.screens.run.inferAspectRatioString
import io.github.dreamandroid.local.ui.viewmodel.GenerateViewModel
import io.github.dreamandroid.local.ui.viewmodel.ModelsViewModel
import kotlinx.coroutines.launch

/**
 * Generate tab: ModalNavigationDrawer (empty) + Scaffold + GenerateTopBar + TabGenerateScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContentTabGenerate(
    drawerState: DrawerState,
    snackbarHostState: SnackbarHostState,
    modelsViewModel: ModelsViewModel,
    generateViewModel: GenerateViewModel,
    queueRepository: QueueRepository,
    isModelLoaded: Boolean,
    recordRepository: RecordRepository,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val generationPreferences = remember { GenerationPreferences(context) }

    // ── Reset all generation params to defaults ──
    val onGenTaskParamReset: () -> Unit = {
        generateViewModel.genSteps = 20f
        generateViewModel.genCfg = 7f
        generateViewModel.genSeed = ""
        generateViewModel.genBatchCounts = 1
        generateViewModel.genSampler = "dpm"
        generateViewModel.genDenoiseCurve = "scaled_linear"
        generateViewModel.genDenoiseStrength = 0.6f
        val repo = ModelRepository(context)
        val m = modelsViewModel.selectedModelId?.let { id -> repo.models.find { it.id == id } }
        generateViewModel.genPrompt = m?.defaultPrompt ?: ""
        generateViewModel.genNegativePrompt = m?.defaultNegativePrompt ?: ""
        kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
            generationPreferences.saveGlobalFields(
                prompt = generateViewModel.genPrompt,
                negativePrompt = generateViewModel.genNegativePrompt,
                batchCounts = generateViewModel.genBatchCounts,
                width = generateViewModel.genWidth,
                height = generateViewModel.genHeight,
            )
            modelsViewModel.selectedModelId?.let { modelId ->
                generationPreferences.saveAllFields(
                    modelId = modelId,
                    prompt = generateViewModel.genPrompt,
                    negativePrompt = generateViewModel.genNegativePrompt,
                    steps = generateViewModel.genSteps,
                    cfgScale = generateViewModel.genCfg,
                    seed = generateViewModel.genSeed,
                    width = generateViewModel.genWidth,
                    height = generateViewModel.genHeight,
                    denoisingStrength = generateViewModel.genDenoiseStrength,
                    useOpenCL = generateViewModel.genUseOpenCL,
                    batchCounts = generateViewModel.genBatchCounts,
                    sampler = generateViewModel.genSampler,
                    aspectRatio = inferAspectRatioString(generateViewModel.genWidth, generateViewModel.genHeight),
                )
            }
        }
    }

    // ── Add to queue ──
    val onAddToQueue: (Int) -> Unit = { count ->
        val modelId = modelsViewModel.selectedModelId ?: return@AppContentTabGenerate
        generateViewModel.addToQueue(modelId, count, queueRepository)
        scope.launch {
            snackbarHostState.showSnackbar(context.getString(R.string.added_to_queue, count))
        }
    }

    val onGenTaskAddToQueue: () -> Unit = {
        val mid = modelsViewModel.selectedModelId ?: return@AppContentTabGenerate
        val count = if (generateViewModel.genSeed.isNotBlank()) 1
        else generateViewModel.genBatchCounts.coerceAtLeast(1)
        generateViewModel.addToQueue(mid, count, queueRepository)
        scope.launch {
            snackbarHostState.showSnackbar(context.getString(R.string.added_to_queue, count))
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(2f / 3f)) {
                // Generate tab: drawer opens empty
            }
        },
    ) {
        Scaffold(
            topBar = {
                GenerateTopBar(
                    drawerState = drawerState,
                    modelId = modelsViewModel.selectedModelId,
                    isModelLoaded = isModelLoaded,
                    onGenTaskParamReset = onGenTaskParamReset,
                    onGenTaskAddToQueue = onGenTaskAddToQueue,
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                TabGenerateScreen(
                    modelId = if (isModelLoaded) modelsViewModel.selectedModelId else null,
                    prompt = generateViewModel.genPrompt,
                    onPromptChange = { generateViewModel.genPrompt = it },
                    negativePrompt = generateViewModel.genNegativePrompt,
                    onNegativePromptChange = { generateViewModel.genNegativePrompt = it },
                    steps = generateViewModel.genSteps,
                    onStepsChange = { generateViewModel.genSteps = it },
                    cfg = generateViewModel.genCfg,
                    onCfgChange = { generateViewModel.genCfg = it },
                    seed = generateViewModel.genSeed,
                    onSeedChange = { generateViewModel.genSeed = it },
                    batchCounts = generateViewModel.genBatchCounts,
                    onBatchCountsChange = { generateViewModel.genBatchCounts = it },
                    sampler = generateViewModel.genSampler,
                    onSamplerChange = { generateViewModel.genSampler = it },
                    denoiseCurve = generateViewModel.genDenoiseCurve,
                    onDenoiseCurveChange = { generateViewModel.genDenoiseCurve = it },
                    denoiseStrength = generateViewModel.genDenoiseStrength,
                    onDenoiseStrengthChange = { generateViewModel.genDenoiseStrength = it },
                    useOpenCL = generateViewModel.genUseOpenCL,
                    onUseOpenCLChange = { generateViewModel.genUseOpenCL = it },
                    width = generateViewModel.genWidth,
                    onWidthChange = { generateViewModel.genWidth = it },
                    height = generateViewModel.genHeight,
                    onHeightChange = { generateViewModel.genHeight = it },
                    onAddToQueue = onAddToQueue,
                    recordRepository = recordRepository,
                    onTokenizePrompt = { prompt -> generateViewModel.tokenizePrompt(prompt) },
                    onTokenizeNegativePrompt = { nPrompt -> generateViewModel.tokenizeNegativePrompt(nPrompt) },
                    promptTokenCount = generateViewModel.promptTokenCount,
                    promptTokenMax = generateViewModel.promptTokenMax,
                    promptOverflowOffset = generateViewModel.promptOverflowOffset,
                    negativePromptTokenCount = generateViewModel.negativePromptTokenCount,
                    negativePromptTokenMax = generateViewModel.negativePromptTokenMax,
                    negativePromptOverflowOffset = generateViewModel.negativePromptOverflowOffset,
                )
            }
        }
    }
}
