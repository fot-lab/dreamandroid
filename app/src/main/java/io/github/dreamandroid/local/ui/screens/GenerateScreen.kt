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

    fun saveAllFields() {
        scope.launch(Dispatchers.IO) {
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
            Spacer(Modifier.height(4.dp))

            // ---- Batch Count (moved to top, above prompt) ----
            var batchText by remember(batchCounts) { mutableStateOf(batchCounts.toString()) }
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.batch_count_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    FilledIconButton(
                        onClick = {
                            val newVal = (batchCounts - 1).coerceAtLeast(1)
                            onBatchCountsChange(newVal)
                            batchText = newVal.toString()
                            saveAllFields()
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Default.Remove, "Decrease")
                    }
                    Spacer(Modifier.width(12.dp))
                    var batchFieldFocused by remember { mutableStateOf(false) }
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
                            if (num != null && !batchFieldFocused) {
                                val clamped = num.coerceIn(1, 60)
                                batchText = clamped.toString()
                                onBatchCountsChange(clamped)
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .width(80.dp)
                            .onFocusChanged { state ->
                                batchFieldFocused = state.isFocused
                                if (!state.isFocused) {
                                    // On focus lost, clamp the value
                                    val num = batchText.toIntOrNull() ?: batchCounts
                                    val clamped = num.coerceIn(1, 60)
                                    batchText = clamped.toString()
                                    onBatchCountsChange(clamped)
                                    saveAllFields()
                                }
                            },
                    )
                    Spacer(Modifier.width(12.dp))
                    FilledIconButton(
                        onClick = {
                            val newVal = (batchCounts + 1).coerceAtMost(60)
                            onBatchCountsChange(newVal)
                            batchText = newVal.toString()
                            saveAllFields()
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Default.Add, "Increase")
                    }
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
            Spacer(Modifier.height(8.dp))

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
                        // Allow free typing; clamp only on focus loss
                        val digits = newText.filter { it.isDigit() }
                        widthText = digits
                        val num = digits.toIntOrNull()
                        if (num != null && !widthFocused) {
                            val clamped = num.coerceIn(64, 4096)
                            widthText = clamped.toString()
                            onWidthChange(clamped)
                            saveAllFields()
                        }
                    },
                    label = { Text("W") },
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
                                saveAllFields()
                            }
                        },
                    shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    value = heightText,
                    onValueChange = { newText ->
                        // Allow free typing; clamp only on focus loss
                        val digits = newText.filter { it.isDigit() }
                        heightText = digits
                        val num = digits.toIntOrNull()
                        if (num != null && !heightFocused) {
                            val clamped = num.coerceIn(64, 4096)
                            heightText = clamped.toString()
                            onHeightChange(clamped)
                            saveAllFields()
                        }
                    },
                    label = { Text("H") },
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
                                saveAllFields()
                            }
                        },
                    shape = MaterialTheme.shapes.medium,
                )
            }

            HorizontalDivider()

            // ---- Flattened Advanced Settings ----
            Text(
                stringResource(R.string.advanced_settings),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )

            // Steps
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.steps, steps.roundToInt()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = steps,
                    onValueChange = { onStepsChange(it); saveAllFields() },
                    valueRange = 1f..50f,
                    steps = 48,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // CFG Scale
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "CFG Scale: %.1f".format(cfg),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = cfg,
                    onValueChange = { onCfgChange(it); saveAllFields() },
                    valueRange = 1f..30f,
                    steps = 57,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Sampler (采样器)
            val samplerOptions = listOf(
                "dpm" to "DPM++ 2M",
                "dpm_sde" to "DPM++ 2M SDE",
                "euler_a" to "Euler A",
                "euler" to "Euler",
                "lcm" to "LCM",
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "采样器",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
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

            // Denoise Curve (降噪曲线)
            val karrasSupported = sampler != "lcm"
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "降噪曲线",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Karras",
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
                            Text("Width: $width", style = MaterialTheme.typography.labelSmall)
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
                            Text("Height: $height", style = MaterialTheme.typography.labelSmall)
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
                ) {
                    Text("Runtime", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = !useOpenCL,
                            onClick = { onUseOpenCLChange(false); saveAllFields() },
                            label = { Text("CPU") },
                        )
                        FilterChip(
                            selected = useOpenCL,
                            onClick = { onUseOpenCLChange(true); saveAllFields() },
                            label = { Text("GPU") },
                        )
                    }
                }
            }

            // Seed
            val isSeedValid = seed.isEmpty() || seed.toLongOrNull() != null
            OutlinedTextField(
                value = seed,
                onValueChange = { onSeedChange(it); saveAllFields() },
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
                        IconButton(onClick = { onSeedChange(""); saveAllFields() }) {
                            Icon(Icons.Default.Clear, "clear")
                        }
                    }
                },
            )

            Spacer(Modifier.height(16.dp))
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
                    "No saved records",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Save prompts from Queue or Gallery to see them here",
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
                        text = record.prompt.ifEmpty { "(empty prompt)" },
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
                            text = "Seed: ${record.seed}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
