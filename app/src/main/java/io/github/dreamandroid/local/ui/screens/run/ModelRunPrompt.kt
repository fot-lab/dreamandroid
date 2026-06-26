package io.github.dreamandroid.local.ui.screens.run

/*
 * NOTE: This file is no longer in use.
 *
 * ModelRunPrompt was the prompt input page of the original ModelRunScreen.
 * Since ModelRunScreen has been replaced by tab-based UI (Generate / Upscale tabs),
 * this file is kept solely as reference.
 *
 * Do NOT instantiate any composable from this file in new code.
 */

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.GenerationMode
import io.github.dreamandroid.local.data.GenerationPreferences
import io.github.dreamandroid.local.data.ModelInfo
import io.github.dreamandroid.local.data.TagAutocompleteRepository
import io.github.dreamandroid.local.data.TagSuggestion
import io.github.dreamandroid.local.ui.components.ErrorMessageCard
import io.github.dreamandroid.local.ui.components.PromptTagTextField
import io.github.dreamandroid.local.ui.components.SmoothLinearWavyProgressIndicator
import io.github.dreamandroid.local.ui.theme.Motion
import io.github.dreamandroid.local.utils.ParamShare
import kotlin.math.roundToInt

/**
 * Prompt page composable extracted from ModelRunScreen.
 *
 * Contains: prompt/negative prompt fields with tag autocomplete, advanced settings dialog,
 * generation button, progress indicator, img2img preview, and error display.
 */
@Composable
fun ModelRunPromptPage(
    state: ModelRunState,
    model: ModelInfo?,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    generationPreferences: GenerationPreferences,
    tagAutocompleteRepository: TagAutocompleteRepository,
    tagAutocompleteAvailable: Boolean,
    tagSuggestionCount: Int,
    useImg2img: Boolean,
    pagerState: androidx.compose.foundation.pager.PagerState,
    // Callbacks
    onPromptFieldChanged: (TextFieldValue, Boolean) -> Unit,
    onNegativePromptFieldChanged: (TextFieldValue, Boolean) -> Unit,
    onApplyPromptSuggestion: (TagSuggestion) -> Unit,
    onApplyNegativePromptSuggestion: (TagSuggestion) -> Unit,
    onRunPromptTagAction: ((String, Int) -> Pair<String, Int>?) -> Unit,
    onRunNegativePromptTagAction: ((String, Int) -> Pair<String, Int>?) -> Unit,
    onUndoPrompt: () -> Unit,
    onRedoPrompt: () -> Unit,
    onUndoNegativePrompt: () -> Unit,
    onRedoNegativePrompt: () -> Unit,
    onSelectImageClick: () -> Unit,
    onSaveAllFields: () -> Unit,
    onClearImg2imgState: () -> Unit,
    onStepsChange: (Float) -> Unit,
    onCfgChange: (Float) -> Unit,
    onSizeChange: (Float) -> Unit,
    onDenoiseStrengthChange: (Float) -> Unit,
    onSeedChange: (String) -> Unit,
    onBatchCountsChange: (Float) -> Unit,
    onGenerateClick: () -> Unit,
    onResetAll: () -> Unit,
    onShareCurrent: () -> Unit,
    onPasteClipboard: () -> Unit,
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AnimatedVisibility(
            visible = state.intermediateBitmap == null,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.prompt_settings),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (useImg2img) {
                                TextButton(
                                    onClick = onSelectImageClick,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        "img2img",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(end = 4.dp),
                                    )
                                    Icon(Icons.Default.Image, contentDescription = "select image", modifier = Modifier.size(20.dp))
                                }
                            }
                            TextButton(
                                onClick = { state.showAdvancedSettings = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    stringResource(R.string.advanced_settings),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings), modifier = Modifier.size(20.dp))
                            }
                        }

                        if (state.showAdvancedSettings) {
                            AdvancedSettingsDialog(
                                state = state,
                                model = model,
                                context = context,
                                useImg2img = useImg2img,
                                isRunning = state.isRunning,
                                onPasteClipboard = onPasteClipboard,
                                onShareCurrent = onShareCurrent,
                                onResetAll = onResetAll,
                                onStepsChange = onStepsChange,
                                onCfgChange = onCfgChange,
                                onSizeChange = onSizeChange,
                                onDenoiseStrengthChange = onDenoiseStrengthChange,
                                onSeedChange = onSeedChange,
                                onBatchCountsChange = onBatchCountsChange,
                                onClearImg2imgState = onClearImg2imgState,
                                onSaveAllFields = onSaveAllFields,
                                onClose = { state.showAdvancedSettings = false },
                            )
                        }
                    }

                    PromptTagTextField(
                        value = state.promptFieldValue,
                        onValueChange = { onPromptFieldChanged(it, true) },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            PromptCountLabel(
                                label = stringResource(R.string.image_prompt),
                                count = state.promptTokenCount,
                                max = state.promptTokenMax,
                                showCount = state.prompt.isNotEmpty(),
                                overflow = state.promptOverflowOffset >= 0,
                            )
                        },
                        suggestions = state.promptSuggestions,
                        onSuggestionClick = onApplyPromptSuggestion,
                        showSuggestions = tagAutocompleteAvailable && state.isPromptFocused && !state.promptPopupDismissed,
                        showToolbar = tagAutocompleteAvailable && state.isPromptFocused && !state.promptPopupDismissed,
                        highlightQuery = state.promptActiveQuery,
                        overflowOffset = state.promptOverflowOffset,
                        onFocusChanged = {
                            state.isPromptFocused = it
                            if (!it) state.promptSuggestions = emptyList()
                        },
                        onDismissSuggestions = { state.promptPopupDismissed = true },
                        onUndo = onUndoPrompt,
                        onRedo = onRedoPrompt,
                        undoEnabled = state.promptUndoStack.isNotEmpty(),
                        redoEnabled = state.promptRedoStack.isNotEmpty(),
                        onAddTag = { onRunPromptTagAction(TagAutocompleteRepository::appendTagAfterActive) },
                        onClearTag = { onRunPromptTagAction(TagAutocompleteRepository::clearActiveTag) },
                        onIncreaseWeight = {
                            onRunPromptTagAction { text, sel ->
                                TagAutocompleteRepository.adjustActiveTagWeight(text, sel, 0.1)
                            }
                        },
                        onDecreaseWeight = {
                            onRunPromptTagAction { text, sel ->
                                TagAutocompleteRepository.adjustActiveTagWeight(text, sel, -0.1)
                            }
                        },
                    )

                    PromptTagTextField(
                        value = state.negativePromptFieldValue,
                        onValueChange = { onNegativePromptFieldChanged(it, true) },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            PromptCountLabel(
                                label = stringResource(R.string.negative_prompt),
                                count = state.negativePromptTokenCount,
                                max = state.negativePromptTokenMax,
                                showCount = state.negativePrompt.isNotEmpty(),
                                overflow = state.negativePromptOverflowOffset >= 0,
                            )
                        },
                        suggestions = state.negativePromptSuggestions,
                        onSuggestionClick = onApplyNegativePromptSuggestion,
                        showSuggestions = tagAutocompleteAvailable && state.isNegativePromptFocused && !state.negativePromptPopupDismissed,
                        showToolbar = tagAutocompleteAvailable && state.isNegativePromptFocused && !state.negativePromptPopupDismissed,
                        highlightQuery = state.negativePromptActiveQuery,
                        overflowOffset = state.negativePromptOverflowOffset,
                        onFocusChanged = {
                            state.isNegativePromptFocused = it
                            if (!it) state.negativePromptSuggestions = emptyList()
                        },
                        onDismissSuggestions = { state.negativePromptPopupDismissed = true },
                        onUndo = onUndoNegativePrompt,
                        onRedo = onRedoNegativePrompt,
                        undoEnabled = state.negativePromptUndoStack.isNotEmpty(),
                        redoEnabled = state.negativePromptRedoStack.isNotEmpty(),
                        onAddTag = { onRunNegativePromptTagAction(TagAutocompleteRepository::appendTagAfterActive) },
                        onClearTag = { onRunNegativePromptTagAction(TagAutocompleteRepository::clearActiveTag) },
                        onIncreaseWeight = {
                            onRunNegativePromptTagAction { text, sel ->
                                TagAutocompleteRepository.adjustActiveTagWeight(text, sel, 0.1)
                            }
                        },
                        onDecreaseWeight = {
                            onRunNegativePromptTagAction { text, sel ->
                                TagAutocompleteRepository.adjustActiveTagWeight(text, sel, -0.1)
                            }
                        },
                    )

                    Button(
                        onClick = { focusManager.clearFocus(); onGenerateClick() },
                        enabled = !state.isRunning && !state.isUpscaling,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        AnimatedContent(
                            targetState = state.isRunning || state.isUpscaling,
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(Motion.DurationShort)) + scaleIn(initialScale = 0.8f, animationSpec = tween(Motion.DurationShort)))
                                    .togetherWith(fadeOut(animationSpec = tween(Motion.DurationShort)) + scaleOut(targetScale = 0.8f, animationSpec = tween(Motion.DurationShort)))
                            },
                            label = "GenerateButtonContent",
                        ) { isLoading ->
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Text(stringResource(R.string.generate_image))
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.errorMessage != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            state.errorMessage?.let { msg ->
                ErrorMessageCard(message = msg, onDismiss = { state.errorMessage = null })
            }
        }

        AnimatedVisibility(
            visible = state.isRunning,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.generating),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    SmoothLinearWavyProgressIndicator(progress = state.progress, modifier = Modifier.fillMaxWidth())
                    Text("${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.intermediateBitmap?.let { bitmap ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                            androidx.compose.foundation.Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Generation Preview",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.selectedImageUri != null && state.base64EncodeDone,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
                    Card(modifier = Modifier.size(100.dp), shape = MaterialTheme.shapes.small) {
                        Box {
                            state.croppedBitmap?.let { bitmap ->
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current).data(bitmap).crossfade(true).build(),
                                    contentDescription = "Cropped Image",
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } ?: state.selectedImageUri?.let { uri ->
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current).data(uri).crossfade(true).build(),
                                    contentDescription = "Selected Image",
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            IconButton(
                                onClick = {
                                    state.selectedImageUri = null
                                    state.croppedBitmap = null
                                    state.maskBitmap = null
                                    state.isInpaintMode = false
                                    state.cropRect = null
                                    state.savedPathHistory = null
                                    state.hasOriginalImageForStitch = false
                                },
                                modifier = Modifier.size(24.dp).background(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), shape = CircleShape).align(Alignment.TopEnd),
                            ) {
                                Icon(Icons.Default.Clear, contentDescription = "Remove Image", modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = state.croppedBitmap != null && !state.isInpaintMode,
                        enter = fadeIn() + expandHorizontally(),
                        exit = fadeOut() + shrinkHorizontally(),
                    ) {
                        Row {
                            Spacer(modifier = Modifier.width(12.dp))
                            val ctx = LocalContext.current
                            SmallFloatingActionButton(onClick = {
                                if (state.croppedBitmap != null) {
                                    state.showInpaintScreen = true
                                } else {
                                    Toast.makeText(ctx, "Please Crop First", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(imageVector = Icons.Default.Brush, contentDescription = "Set Mask")
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = state.isInpaintMode && state.maskBitmap != null,
                        enter = fadeIn() + expandHorizontally(),
                        exit = fadeOut() + shrinkHorizontally(),
                    ) {
                        Row {
                            Spacer(modifier = Modifier.width(8.dp))
                            Card(
                                onClick = { if (state.croppedBitmap != null && state.maskBitmap != null) state.showInpaintScreen = true },
                                modifier = Modifier.size(100.dp),
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Box {
                                    state.maskBitmap?.let { mb ->
                                        AsyncImage(
                                            model = ImageRequest.Builder(LocalContext.current).data(mb).crossfade(true).build(),
                                            contentDescription = "Mask Image",
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                    IconButton(
                                        onClick = { state.maskBitmap = null; state.isInpaintMode = false; state.savedPathHistory = null },
                                        modifier = Modifier.size(24.dp).background(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), shape = CircleShape).align(Alignment.TopEnd),
                                    ) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear Mask", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ---- Advanced Settings Dialog (inline from PromptPage) ---- */

@Composable
fun AdvancedSettingsDialog(
    state: ModelRunState,
    model: ModelInfo?,
    context: Context,
    useImg2img: Boolean,
    isRunning: Boolean,
    onPasteClipboard: () -> Unit,
    onShareCurrent: () -> Unit,
    onResetAll: () -> Unit,
    onStepsChange: (Float) -> Unit,
    onCfgChange: (Float) -> Unit,
    onSizeChange: (Float) -> Unit,
    onDenoiseStrengthChange: (Float) -> Unit,
    onSeedChange: (String) -> Unit,
    onBatchCountsChange: (Float) -> Unit,
    onClearImg2imgState: () -> Unit,
    onSaveAllFields: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.advanced_settings_title), modifier = Modifier.weight(1f))
                IconButton(onClick = onPasteClipboard) {
                    Icon(imageVector = Icons.Default.ContentPaste, contentDescription = stringResource(R.string.import_from_clipboard))
                }
                IconButton(onClick = onShareCurrent) {
                    Icon(imageVector = Icons.Default.Share, contentDescription = stringResource(R.string.share))
                }
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
            ) {
                // Aspect ratio (SDXL + img2img only)
                if (model?.isSdxl == true && useImg2img) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.aspect_ratio), style = MaterialTheme.typography.bodyMedium)
                        val presets = listOf("1:1", "3:4", "4:3")
                        val isCustom = state.aspectRatio !in presets
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                            presets.forEachIndexed { _, ratio ->
                                FilterChip(
                                    selected = state.aspectRatio == ratio,
                                    onClick = { if (!isRunning && state.aspectRatio != ratio) { state.aspectRatio = ratio; onClearImg2imgState(); onSaveAllFields() } },
                                    label = { Text(ratio) },
                                    enabled = !isRunning,
                                )
                            }
                            FilterChip(
                                selected = isCustom,
                                onClick = { if (!isRunning) state.showCustomAspectRatioDialog = true },
                                label = { Text(if (isCustom) state.aspectRatio else stringResource(R.string.aspect_ratio_custom)) },
                                enabled = !isRunning,
                            )
                        }
                    }
                }

                // Resolution (non-SDXL, non-CPU)
                if (model?.runOnCpu == false && model.isSdxl == false && state.availableResolutions.isNotEmpty()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.resolution), style = MaterialTheme.typography.bodyMedium)
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                            state.availableResolutions.forEachIndexed { _, resolution ->
                                FilterChip(
                                    selected = state.currentWidth == resolution.width && state.currentHeight == resolution.height,
                                    onClick = {
                                        if (!isRunning && (resolution.width != state.currentWidth || resolution.height != state.currentHeight)) {
                                            state.pendingResolution = resolution
                                            state.showResolutionChangeDialog = true
                                        }
                                    },
                                    label = { Text(resolution.toString()) },
                                    enabled = !isRunning,
                                )
                            }
                        }
                    }
                }

                // Sampler (采样器)
                val samplerOptions = listOf("dpm" to "DPM++ 2M", "dpm_sde" to "DPM++ 2M SDE", "euler_a" to "Euler A", "euler" to "Euler", "lcm" to "LCM")
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("采样器", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        samplerOptions.forEachIndexed { _, (id, label) ->
                            FilterChip(
                                selected = state.sampler == id,
                                onClick = {
                                    if (state.sampler != id) {
                                        state.sampler = id
                                        onSaveAllFields()
                                    }
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                }

                // Denoise Curve (降噪曲线)
                val karrasSupported = state.sampler != "lcm"
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("降噪曲线", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("Karras", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp).alpha(if (karrasSupported) 1f else 0.4f))
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                            Switch(checked = state.denoiseCurve == "karras" && karrasSupported, enabled = karrasSupported, onCheckedChange = { enable ->
                                state.denoiseCurve = if (enable) "karras" else "scaled_linear"
                                onSaveAllFields()
                            }, modifier = Modifier.scale(0.8f))
                        }
                    }
                }

                // Steps
                Column {
                    Text(stringResource(R.string.steps, state.steps.roundToInt()), style = MaterialTheme.typography.bodyMedium)
                    Slider(value = state.steps, onValueChange = onStepsChange, valueRange = 1f..50f, steps = 48, modifier = Modifier.fillMaxWidth())
                }

                // CFG
                Column {
                    Text("CFG Scale: %.1f".format(state.cfg), style = MaterialTheme.typography.bodyMedium)
                    Slider(value = state.cfg, onValueChange = onCfgChange, valueRange = 1f..30f, steps = 57, modifier = Modifier.fillMaxWidth())
                }

                // Image size slider (CPU only)
                if (model?.runOnCpu ?: false) {
                    Column {
                        Text(stringResource(R.string.image_size, state.currentWidth, state.currentHeight), style = MaterialTheme.typography.bodyMedium)
                        Slider(value = state.currentWidth.toFloat(), onValueChange = onSizeChange, valueRange = 128f..512f, steps = 5, modifier = Modifier.fillMaxWidth())
                    }
                }

                // CPU/GPU switch (CPU only)
                if (model?.runOnCpu ?: false) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Runtime", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                            FilterChip(selected = !state.useOpenCL, onClick = { state.useOpenCL = false; onSaveAllFields() }, label = { Text("CPU") }, modifier = Modifier.weight(1f))
                            FilterChip(selected = state.useOpenCL, onClick = { if (!state.useOpenCL) state.showOpenCLWarningDialog = true }, label = { Text("GPU") }, modifier = Modifier.weight(1f))
                        }
                    }
                }

                // Batch count
                Column {
                    Text(stringResource(R.string.batch_count, state.batchCounts), style = MaterialTheme.typography.bodyMedium)
                    Slider(value = state.batchCounts.toFloat(), onValueChange = onBatchCountsChange, valueRange = 1f..10f, steps = 8, modifier = Modifier.fillMaxWidth())
                }

                // Denoise strength (img2img)
                if (useImg2img) {
                    Column {
                        Text("[img2img]Denoise Strength: %.2f".format(state.denoiseStrength), style = MaterialTheme.typography.bodyMedium)
                        Slider(value = state.denoiseStrength, onValueChange = onDenoiseStrengthChange, valueRange = 0f..1f, steps = 99, modifier = Modifier.fillMaxWidth())
                    }
                }

                // Seed + use last seed
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.seed,
                        onValueChange = onSeedChange,
                        label = { Text(stringResource(R.string.random_seed)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        trailingIcon = {
                            if (state.seed.isNotEmpty()) {
                                IconButton(onClick = { state.seed = ""; onSaveAllFields() }) { Icon(Icons.Default.Clear, contentDescription = "clear") }
                            }
                        },
                    )
                    if (state.returnedSeed != null) {
                        FilledTonalButton(onClick = { state.seed = state.returnedSeed.toString(); onSaveAllFields() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.use_last_seed), modifier = Modifier.size(20.dp).padding(end = 4.dp))
                            Text(stringResource(R.string.use_last_seed, state.returnedSeed.toString()))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onResetAll, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.reset), modifier = Modifier.size(20.dp).padding(end = 4.dp))
                    Text(stringResource(R.string.reset))
                }
                TextButton(onClick = onClose) { Text(stringResource(R.string.confirm)) }
            }
        },
    )
}
