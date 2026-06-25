package io.github.dreamandroid.local.ui.orchestrator

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.GenerationPreferences
import io.github.dreamandroid.local.data.ModelRepository
import io.github.dreamandroid.local.data.RecordRepository
import io.github.dreamandroid.local.service.QueueRepository
import io.github.dreamandroid.local.service.backend.BackendManager
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
    loadedModelId: String?,
    loadedModelType: BackendManager.Mode?,
    recordRepository: RecordRepository,
) {
    // Derive whether the currently loaded model is a generator (Diffusion mode)
    val isModelLoaded = loadedModelType == BackendManager.Mode.Diffusion && loadedModelId != null
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
        val m = loadedModelId?.let { id -> repo.models.find { it.id == id } }
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
            loadedModelId?.let { modelId ->
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
        loadedModelId?.let { modelId ->
            generateViewModel.addToQueue(modelId, count, queueRepository)
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.added_to_queue, count))
            }
        }
    }

    val onGenTaskAddToQueue: () -> Unit = {
        loadedModelId?.let { mid ->
            val count = if (generateViewModel.genSeed.isNotBlank()) 1
            else generateViewModel.genBatchCounts.coerceAtLeast(1)
            generateViewModel.addToQueue(mid, count, queueRepository)
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.added_to_queue, count))
            }
        }
    }

    // ── BackHandler: close drawer on system back press ──
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.8f)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { scope.launch { drawerState.close() } }) {
                        Icon(Icons.Default.Close, stringResource(R.string.close))
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                GenerateTopBar(
                    drawerState = drawerState,
                    loadedModelId = loadedModelId,
                    loadedModelType = loadedModelType,
                    onGenTaskParamReset = onGenTaskParamReset,
                    onGenTaskAddToQueue = onGenTaskAddToQueue,
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                TabGenerateScreen(
                    modelId = if (isModelLoaded) loadedModelId else null,
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
