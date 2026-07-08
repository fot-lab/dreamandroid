package io.github.dreamandroid.local.ui.screens

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.*
import io.github.dreamandroid.local.ui.screens.run.inferAspectRatioString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import io.github.dreamandroid.local.service.backend.BackendManager.TokenizeResult

/**
 * GenerateScreen – image generation parameter configuration.
 * All generation parameters are managed by the parent (MainActivity) and passed down.
 * When the user clicks Generate, parameters are sent to the Queue for background processing.
 *
 * The screen is split into two sub-tabs:
 *  - Parameters: parameter editing form
 *  - Records:    saved prompt/parameter records from Queue & Gallery
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateScreen(
    modelId: String?,
    modifier: Modifier = Modifier,
    // Generation parameters passed from parent
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
    sampler: String,
    onSamplerChange: (String) -> Unit,
    denoiseCurve: String,
    onDenoiseCurveChange: (String) -> Unit,
    denoiseStrength: Float,
    onDenoiseStrengthChange: (Float) -> Unit,
    useOpenCL: Boolean,
    onUseOpenCLChange: (Boolean) -> Unit,
    width: Int,
    onWidthChange: (Int) -> Unit,
    height: Int,
    onHeightChange: (Int) -> Unit,
    // Queue interaction — sends current params to the queue
    onAddToQueue: (Int) -> Unit = {},
    // Record Manager
    recordRepository: RecordRepository? = null,
    // Records tab selection (lifted to parent for top bar access)
    selectedGenerateTab: Int = 0,
    onSelectedGenerateTabChange: (Int) -> Unit = {},
    selectedRecordIds: Set<String> = emptySet(),
    onSelectedRecordIdsChange: (Set<String>) -> Unit = {},
    onRecordsListChange: (List<GenerateParameterRecord>) -> Unit = {},
    // Tokenize callbacks (UILA-COMP-0005: moved from direct BackendManager in UI to ViewModel)
    onTokenizePrompt: (suspend (String) -> TokenizeResult?)? = null,
    onTokenizeNegativePrompt: (suspend (String) -> TokenizeResult?)? = null,
    // Tokenize read state (from GenerateViewModel)
    promptTokenCount: Int = 0,
    promptTokenMax: Int = 77,
    promptOverflowOffset: Int = -1,
    negativePromptTokenCount: Int = 0,
    negativePromptTokenMax: Int = 77,
    negativePromptOverflowOffset: Int = -1,
    cfgFineGranularity: Boolean = false,
    acceptAnyCfg: Boolean = false,
    // Trigger: parent increments this to commit all fields + persist before generation
    commitAndPersistTrigger: Int = 0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val modelRepository = remember { ModelRepository(context) }
    val model = remember(modelId) { modelRepository.models.find { it.id == modelId } }
    val generationPreferences = remember { GenerationPreferences(context) }

    // ---- Load preferences for this model (global prefs + per-model) ----

    // ---- Sub-tab selection (state owned by parent) ----
    // selectedGenerateTab, onSelectedGenerateTabChange are passed from parent

    // ---- Queue add feedback ----
    var queueAddMessage by remember { mutableStateOf<String?>(null) }

    // ---- No model warning ----
    var showNoModelWarning by remember { mutableStateOf(false) }
    if (showNoModelWarning) {
        AlertDialog(
            onDismissRequest = { showNoModelWarning = false },
            title = { Text(stringResource(R.string.no_model_loaded)) },
            text = { Text(stringResource(R.string.no_model_loaded_hint)) },
            confirmButton = {
                TextButton(onClick = { showNoModelWarning = false }) {
                    Text(stringResource(R.string.got_it))
                }
            },
        )
    }

    // ---- Token count / CLIP limit debounced requests (UILA-COMP-0005: via ViewModel callbacks) ----
    LaunchedEffect(prompt) {
        delay(400)
        onTokenizePrompt?.invoke(prompt)
    }
    LaunchedEffect(negativePrompt) {
        delay(400)
        onTokenizeNegativePrompt?.invoke(negativePrompt)
    }

    // Clear queue feedback message after a delay
    LaunchedEffect(queueAddMessage) {
        if (queueAddMessage != null) {
            delay(3000)
            queueAddMessage = null
        }
    }

    var saveJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    suspend fun writeAllFields() {
        // Screen-level fields — persist regardless of model
        generationPreferences.saveGlobalFields(
            prompt = prompt,
            negativePrompt = negativePrompt,
            batchCounts = batchCounts,
            width = width,
            height = height,
        )
        // Per-model fields
        if (modelId != null) {
            generationPreferences.saveAllFields(
                modelId = modelId,
                prompt = prompt,
                negativePrompt = negativePrompt,
                steps = steps,
                cfgScale = cfg,
                seed = seed,
                width = width,
                height = height,
                denoisingStrength = denoiseStrength,
                useOpenCL = useOpenCL,
                batchCounts = batchCounts,
                sampler = sampler,
                aspectRatio = inferAspectRatioString(width, height),
            )
        }
    }

    /** Debounced save: cancels previous pending job, saves after 1s delay. */
    fun saveAllFields() {
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(1000)
            writeAllFields()
        }
    }

    /** Immediate save — used on focus loss when the user has finished editing. */
    fun saveFieldsImmediate() {
        saveJob?.cancel()
        scope.launch(Dispatchers.IO) {
            writeAllFields()
        }
    }

    // ---- UI ----
    val tabs = listOf(stringResource(R.string.tab_parameters) to Icons.Default.Tune, stringResource(R.string.tab_records) to Icons.Default.Bookmarks)

    Column(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { focusManager.clearFocus() },
    ) {
        // ---- Sub-tab Row ----
        TabRow(selectedTabIndex = selectedGenerateTab) {
            tabs.forEachIndexed { index, (title, icon) ->
                Tab(
                    selected = selectedGenerateTab == index,
                    onClick = { onSelectedGenerateTabChange(index) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, contentDescription = title)
                            Text(title)
                        }
                    },
                )
            }
        }

        // ---- Tab Content ----
        when (selectedGenerateTab) {
            0 -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            // ---- Batch Count ----
            var batchText by remember(batchCounts) { mutableStateOf(batchCounts.toString()) }
            var batchFieldFocused by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconButton(
                    onClick = {
                        val newVal = (batchCounts - 1).coerceAtLeast(1)
                        onBatchCountsChange(newVal)
                        batchText = newVal.toString()
                        saveAllFields()
                    },
                ) {
                    Icon(Icons.Default.Remove, stringResource(R.string.a11y_decrease))
                }
                OutlinedTextField(
                    value = batchText,
                    onValueChange = { newText ->
                        // Allow typing digits (including empty)
                        if (newText.isEmpty()) {
                            batchText = newText
                            return@OutlinedTextField
                        }
                        val digits = newText.filter { it.isDigit() }
                        // While focused, show raw digits; clamp only on commit
                        batchText = digits
                        val num = digits.toIntOrNull()
                        if (num != null) {
                            onBatchCountsChange(num.coerceIn(1, 60))
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium,
                    label = { Text(stringResource(R.string.batch_count_label)) },
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { state ->
                            batchFieldFocused = state.isFocused
                            if (!state.isFocused) {
                                // On focus lost, clamp the value
                                val num = batchText.toIntOrNull() ?: batchCounts
                                val clamped = num.coerceIn(1, 60)
                                batchText = clamped.toString()
                                onBatchCountsChange(clamped)
                                saveFieldsImmediate()
                            }
                        },
                )
                FilledIconButton(
                    onClick = {
                        val newVal = (batchCounts + 1).coerceAtMost(60)
                        onBatchCountsChange(newVal)
                        batchText = newVal.toString()
                        saveAllFields()
                    },
                ) {
                    Icon(Icons.Default.Add, stringResource(R.string.a11y_increase))
                }
            }

            HorizontalDivider()

            // ---- Prompt Fields ----
            Text(
                stringResource(R.string.prompt_settings),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )

            // Grey-out overflow for prompt: characters past the 77-token CLIP limit
            // are rendered at 38% opacity.
            val promptOverflowColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            val promptOverflowTransformation = remember(promptOverflowOffset, promptOverflowColor) {
                VisualTransformation { text ->
                    if (promptOverflowOffset in 0 until text.length) {
                        val styled = buildAnnotatedString {
                            append(text.subSequence(0, promptOverflowOffset))
                            withStyle(SpanStyle(color = promptOverflowColor)) {
                                append(text.subSequence(promptOverflowOffset, text.length))
                            }
                        }
                        TransformedText(styled, OffsetMapping.Identity)
                    } else {
                        TransformedText(text, OffsetMapping.Identity)
                    }
                }
            }

            val negativePromptOverflowColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            val negativePromptOverflowTransformation = remember(negativePromptOverflowOffset, negativePromptOverflowColor) {
                VisualTransformation { text ->
                    if (negativePromptOverflowOffset in 0 until text.length) {
                        val styled = buildAnnotatedString {
                            append(text.subSequence(0, negativePromptOverflowOffset))
                            withStyle(SpanStyle(color = negativePromptOverflowColor)) {
                                append(text.subSequence(negativePromptOverflowOffset, text.length))
                            }
                        }
                        TransformedText(styled, OffsetMapping.Identity)
                    } else {
                        TransformedText(text, OffsetMapping.Identity)
                    }
                }
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.image_prompt))
                        if (prompt.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Text("$promptTokenCount/$promptTokenMax")
                        }
                        if (promptOverflowOffset >= 0) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Report,
                                contentDescription = stringResource(R.string.prompt_token_overflow),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                },
                visualTransformation = promptOverflowTransformation,
                minLines = 2,
                maxLines = 4,
                shape = MaterialTheme.shapes.medium,
            )

            OutlinedTextField(
                value = negativePrompt,
                onValueChange = onNegativePromptChange,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.negative_prompt))
                        if (negativePrompt.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Text("$negativePromptTokenCount/$negativePromptTokenMax")
                        }
                        if (negativePromptOverflowOffset >= 0) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Report,
                                contentDescription = stringResource(R.string.prompt_token_overflow),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                },
                visualTransformation = negativePromptOverflowTransformation,
                minLines = 1,
                maxLines = 3,
                shape = MaterialTheme.shapes.medium,
            )

            // ---- Width / Height (screen-level, below negative prompt) ----/
            var widthText by remember(width) { mutableStateOf(width.toString()) }
            var heightText by remember(height) { mutableStateOf(height.toString()) }
            var widthFocused by remember { mutableStateOf(false) }
            var heightFocused by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = widthText,
                    onValueChange = { newText ->
                        val digits = newText.filter { it.isDigit() }
                        widthText = digits
                        val num = digits.toIntOrNull()
                        if (num != null) {
                            onWidthChange(num.coerceIn(64, 4096))
                            saveAllFields()
                        }
                    },
                    label = { Text(stringResource(R.string.width_short)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { state ->
                            widthFocused = state.isFocused
                            if (!state.isFocused) {
                                val num = widthText.toIntOrNull() ?: width
                                val clamped = num.coerceIn(64, 4096)
                                widthText = clamped.toString()
                                onWidthChange(clamped)
                                saveFieldsImmediate()
                            }
                        },
                    shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    value = heightText,
                    onValueChange = { newText ->
                        val digits = newText.filter { it.isDigit() }
                        heightText = digits
                        val num = digits.toIntOrNull()
                        if (num != null) {
                            onHeightChange(num.coerceIn(64, 4096))
                            saveAllFields()
                        }
                    },
                    label = { Text(stringResource(R.string.height_short)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { state ->
                            heightFocused = state.isFocused
                            if (!state.isFocused) {
                                val num = heightText.toIntOrNull() ?: height
                                val clamped = num.coerceIn(64, 4096)
                                heightText = clamped.toString()
                                onHeightChange(clamped)
                                saveFieldsImmediate()
                            }
                        },
                    shape = MaterialTheme.shapes.medium,
                )
            }

            HorizontalDivider()

            // Steps
            var stepsText by remember(steps) { mutableStateOf(steps.roundToInt().toString()) }
            var stepsFocused by remember { mutableStateOf(false) }
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledIconButton(onClick = {
                        val newVal = (steps - 1f).coerceAtLeast(1f)
                        onStepsChange(newVal)
                        stepsText = newVal.roundToInt().toString()
                        saveAllFields()
                    }) {
                        Icon(Icons.Default.Remove, stringResource(R.string.a11y_decrease))
                    }
                    OutlinedTextField(
                        value = stepsText,
                        onValueChange = { newText ->
                            if (newText.isEmpty()) {
                                stepsText = newText
                                return@OutlinedTextField
                            }
                            val digits = newText.filter { it.isDigit() }
                        stepsText = digits
                        val num = digits.toIntOrNull()
                        if (num != null) {
                            onStepsChange(num.coerceIn(1, 50).toFloat())
                        }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium,
                        label = { Text(stringResource(R.string.generation_steps)) },
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { state ->
                                stepsFocused = state.isFocused
                                if (!state.isFocused) {
                                    val num = stepsText.toIntOrNull() ?: steps.roundToInt()
                                    val clamped = num.coerceIn(1, 50)
                                    stepsText = clamped.toString()
                                    onStepsChange(clamped.toFloat())
                                    saveFieldsImmediate()
                                }
                            },
                    )
                    FilledIconButton(onClick = {
                        val newVal = (steps + 1f).coerceAtMost(50f)
                        onStepsChange(newVal)
                        stepsText = newVal.roundToInt().toString()
                        saveAllFields()
                    }) {
                        Icon(Icons.Default.Add, stringResource(R.string.a11y_increase))
                    }
                }
                Slider(
                    value = steps,
                    onValueChange = {
                        onStepsChange(it)
                        stepsText = it.roundToInt().toString()
                        saveAllFields()
                    },
                    valueRange = 1f..50f,
                    steps = 48,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HorizontalDivider()

            // CFG Scale
            val cfgStep = if (cfgFineGranularity) 0.01f else 0.1f
            val cfgDecimals: Int? = if (acceptAnyCfg) null else if (cfgFineGranularity) 2 else 1
            fun formatCfg(v: Float): String = cfgDecimals?.let { "%.${it}f".format(v) } ?: v.toString()
            var cfgText by remember(cfg, cfgFineGranularity, acceptAnyCfg) {
                mutableStateOf(formatCfg(cfg))
            }
            // Sync cfgText when acceptAnyCfg & not focused (external cfg changes)
            if (acceptAnyCfg && !cfgFocused) {
                LaunchedEffect(cfg) { cfgText = formatCfg(cfg) }
            }
            var cfgFocused by remember { mutableStateOf(false) }
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val cfgLow = if (acceptAnyCfg) Float.NEGATIVE_INFINITY else 1f
                    FilledIconButton(onClick = {
                        val newVal = (cfg - cfgStep).coerceAtLeast(cfgLow)
                        onCfgChange(newVal)
                        cfgText = formatCfg(newVal)
                        saveAllFields()
                    }) {
                        Icon(Icons.Default.Remove, stringResource(R.string.a11y_decrease))
                    }
                    OutlinedTextField(
                        value = cfgText,
                        onValueChange = { newText ->
                            if (newText.isEmpty()) {
                                cfgText = newText
                                return@OutlinedTextField
                            }
                            if (acceptAnyCfg) {
                                // Accept any valid float string: optional leading '-', digits, one '.'
                                val allowed = newText.filter { it.isDigit() || it == '.' || it == '-' }
                                if (allowed.isEmpty()) return@OutlinedTextField
                                if (allowed.count { it == '-' } > (if (allowed.startsWith('-')) 1 else 0)) return@OutlinedTextField
                                val dotCount = allowed.count { it == '.' }
                                if (dotCount > 1) return@OutlinedTextField
                                if (dotCount == 1) {
                                    val dotIdx = allowed.indexOf('.')
                                    if (allowed.indexOf('.', dotIdx + 1) >= 0) return@OutlinedTextField
                                }
                                cfgText = allowed
                                val num = allowed.toFloatOrNull()
                                if (num != null) onCfgChange(num)
                            } else {
                                val filtered = newText.filter { it.isDigit() || it == '.' }
                                val dotIdx = filtered.indexOf('.')
                                val cleaned = if (dotIdx >= 0) {
                                    filtered.substring(0, dotIdx + 1) +
                                        filtered.substring(dotIdx + 1).filter { it.isDigit() }
                                } else filtered
                                cfgText = cleaned
                                val num = cleaned.toFloatOrNull()
                                if (num != null) onCfgChange(num.coerceIn(1f, 30f))
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium,
                        label = { Text(stringResource(R.string.cfg_scale)) },
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { state ->
                                cfgFocused = state.isFocused
                                if (!state.isFocused) {
                                    val num = cfgText.toFloatOrNull()
                                    if (acceptAnyCfg) {
                                        if (num != null) {
                                            onCfgChange(num)
                                            saveFieldsImmediate()
                                        } else {
                                            // fallback: restore current cfg
                                            cfgText = formatCfg(cfg)
                                        }
                                    } else {
                                        val clamped = (num ?: cfg).coerceIn(1f, 30f)
                                        cfgText = formatCfg(clamped)
                                        onCfgChange(clamped)
                                        saveFieldsImmediate()
                                    }
                                }
                            },
                    )
                    FilledIconButton(onClick = {
                        val cfgHigh = if (acceptAnyCfg) Float.MAX_VALUE else 30f
                        val newVal = (cfg + cfgStep).coerceAtMost(cfgHigh)
                        onCfgChange(newVal)
                        cfgText = formatCfg(newVal)
                        saveAllFields()
                    }) {
                        Icon(Icons.Default.Add, stringResource(R.string.a11y_increase))
                    }
                }
                if (!acceptAnyCfg) {
                    val cfgSliderSteps = if (cfgFineGranularity) 2899 else 57
                    Slider(
                        value = cfg,
                        onValueChange = {
                            onCfgChange(it)
                            cfgText = formatCfg(it)
                            saveAllFields()
                        },
                        valueRange = 1f..30f,
                        steps = cfgSliderSteps,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            HorizontalDivider()

            // Sampler
            val samplerOptions = listOf(
                "dpm" to "DPM++ 2M",
                "dpm_sde" to "DPM++ 2M SDE",
                "euler_a" to "Euler A",
                "euler" to "Euler",
                "lcm" to "LCM",
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.sampler_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    samplerOptions.forEach { (id, label) ->
                        FilterChip(
                            selected = sampler == id,
                            onClick = {
                                if (sampler != id) {
                                    onSamplerChange(id)
                                    saveAllFields()
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }

            HorizontalDivider()

            // Denoise Curve
            val karrasSupported = sampler != "lcm"
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.denoise_curve_label),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.denoise_karras),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.alpha(if (karrasSupported) 1f else 0.4f),
                    )
                    CompositionLocalProvider(
                        LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
                    ) {
                        Switch(
                            checked = denoiseCurve == "karras" && karrasSupported,
                            enabled = karrasSupported,
                            onCheckedChange = { enable ->
                                onDenoiseCurveChange(if (enable) "karras" else "scaled_linear")
                                saveAllFields()
                            },
                            modifier = Modifier.scale(0.8f),
                        )
                    }
                }
            }

            // Width/Height for CPU models
            if (model?.runOnCpu == true) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.image_size, width, height),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.cpu_width, width), style = MaterialTheme.typography.labelSmall)
                            Slider(
                                value = width.toFloat(),
                                onValueChange = {
                                    onWidthChange(it.roundToInt())
                                    saveAllFields()
                                },
                                valueRange = 128f..512f,
                                steps = 5,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.cpu_height, height), style = MaterialTheme.typography.labelSmall)
                            Slider(
                                value = height.toFloat(),
                                onValueChange = {
                                    onHeightChange(it.roundToInt())
                                    saveAllFields()
                                },
                                valueRange = 128f..512f,
                                steps = 5,
                            )
                        }
                    }
                }

                // Runtime (CPU/GPU) for CPU models
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.runtime_label), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = !useOpenCL,
                            onClick = { onUseOpenCLChange(false); saveAllFields() },
                            label = { Text(stringResource(R.string.runtime_cpu)) },
                        )
                        FilterChip(
                            selected = useOpenCL,
                            onClick = { onUseOpenCLChange(true); saveAllFields() },
                            label = { Text(stringResource(R.string.runtime_gpu)) },
                        )
                    }
                }
            }

            HorizontalDivider()

            // Seed
            val isSeedValid = seed.isEmpty() || seed.toLongOrNull() != null
            OutlinedTextField(
                value = seed,
                onValueChange = { onSeedChange(it) },
                label = { Text(stringResource(R.string.random_seed)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                singleLine = true,
                isError = !isSeedValid,
                supportingText = if (!isSeedValid) {
                    { Text(stringResource(R.string.invalid_seed_format), color = MaterialTheme.colorScheme.error) }
                } else null,
                trailingIcon = {
                    if (seed.isNotEmpty()) {
                        IconButton(onClick = { onSeedChange("") }) {
                            Icon(Icons.Default.Clear, stringResource(R.string.a11y_clear))
                        }
                    }
                },
            )
        
            // ── Commit all fields + persist when parent requests (before generate) ──
            LaunchedEffect(commitAndPersistTrigger) {
                if (commitAndPersistTrigger > 0) {
                    // Batch Count: clamp + push
                    batchText.toIntOrNull()?.coerceIn(1, 60)?.let {
                        batchText = it.toString(); onBatchCountsChange(it)
                    }
                    // Width: clamp + push
                    widthText.toIntOrNull()?.coerceIn(64, 4096)?.let {
                        widthText = it.toString(); onWidthChange(it)
                    }
                    // Height: clamp + push
                    heightText.toIntOrNull()?.coerceIn(64, 4096)?.let {
                        heightText = it.toString(); onHeightChange(it)
                    }
                    // Steps: clamp + push
                    stepsText.toIntOrNull()?.coerceIn(1, 50)?.let {
                        stepsText = it.toString(); onStepsChange(it.toFloat())
                    }
                    // CFG Scale: clamp + push (or raw push for acceptAnyCfg)
                    cfgText.toFloatOrNull()?.let { num ->
                        if (acceptAnyCfg) {
                            onCfgChange(num)
                        } else {
                            val clamped = num.coerceIn(1f, 30f)
                            cfgText = formatCfg(clamped)
                            onCfgChange(clamped)
                        }
                    }
                    // Clear focus so any in-progress editing is finalized
                    focusManager.clearFocus()
                    saveFieldsImmediate()
                }
            }

        } // end Parameters tab Column

            1 -> {
                val records by recordRepository?.records?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
                val recordsValue = records ?: emptyList()
                // Notify parent of records list for selection operations
                LaunchedEffect(recordsValue) { onRecordsListChange(recordsValue) }
                RecordsTabContent(
                    records = recordsValue,
                    selectedRecordIds = selectedRecordIds,
                    onToggleSelection = { id ->
                        onSelectedRecordIdsChange(
                            if (id in selectedRecordIds) selectedRecordIds - id
                            else selectedRecordIds + id
                        )
                    },
                )
            }
        } // end when(selectedGenerateTab)
    } // end outer Column
}

// =========== Records Tab Content ===========

@Composable
private fun RecordsTabContent(
    records: List<GenerateParameterRecord>,
    selectedRecordIds: Set<String>,
    onToggleSelection: (String) -> Unit,
) {
    if (records.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Bookmarks,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.no_saved_records),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.no_saved_records_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(records, key = { it.id }) { record ->
            RecordCard(
                record = record,
                isSelected = record.id in selectedRecordIds,
                onToggle = { onToggleSelection(record.id) },
            )
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun RecordCard(
    record: GenerateParameterRecord,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        onClick = onToggle,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Checkbox for multi-select
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Source chip + prompt
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Source badge
                    val sourceColor = when (record.source) {
                        RecordSource.QUEUE -> MaterialTheme.colorScheme.primaryContainer
                        RecordSource.GALLERY -> MaterialTheme.colorScheme.tertiaryContainer
                    }
                    val sourceTextColor = when (record.source) {
                        RecordSource.QUEUE -> MaterialTheme.colorScheme.onPrimaryContainer
                        RecordSource.GALLERY -> MaterialTheme.colorScheme.onTertiaryContainer
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = sourceColor,
                    ) {
                        Text(
                            text = record.source.name,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = sourceTextColor,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = record.prompt.ifEmpty { stringResource(R.string.empty_prompt) },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Params summary + seed
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = record.paramsSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (record.seed != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.basic_seed, record.seed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
