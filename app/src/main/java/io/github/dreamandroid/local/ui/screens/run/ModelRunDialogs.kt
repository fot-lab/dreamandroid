package io.github.dreamandroid.local.ui.screens.run

/*
 * NOTE: This file is no longer in use.
 *
 * ModelRunDialogs was the dialog layer for the original ModelRunScreen.
 * Since ModelRunScreen has been replaced by tab-based UI (Generate / Upscale tabs),
 * this file is kept solely as reference.
 *
 * Do NOT instantiate any composable from this file in new code.
 */

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.edit
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.*
import io.github.dreamandroid.local.service.ModelDownloadService
import io.github.dreamandroid.local.ui.components.*
import io.github.dreamandroid.local.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ═══════════════════════════════════════════════════════════════
// Exit & Warning Dialogs
// ═══════════════════════════════════════════════════════════════

@Composable
fun ModelRunExitDialog(state: ModelRunState, onExit: () -> Unit) {
    if (!state.showExitDialog) return
    AlertDialog(
        onDismissRequest = { state.showExitDialog = false },
        title = { Text(stringResource(R.string.confirm_exit)) },
        text = { Text(stringResource(R.string.confirm_exit_hint)) },
        confirmButton = {
            TextButton(onClick = { state.showExitDialog = false; onExit() }) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = { state.showExitDialog = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ModelRunOpenCLWarningDialog(state: ModelRunState, onSaveAll: () -> Unit) {
    if (!state.showOpenCLWarningDialog) return
    AlertDialog(
        onDismissRequest = { state.showOpenCLWarningDialog = false },
        title = { Text("GPU Runtime Warning") },
        text = { Text(stringResource(R.string.opencl_warning)) },
        confirmButton = {
            TextButton(onClick = { state.showOpenCLWarningDialog = false; state.useOpenCL = true; onSaveAll() }) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = { state.showOpenCLWarningDialog = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

// ═══════════════════════════════════════════════════════════════
// Resolution Change Dialog
// ═══════════════════════════════════════════════════════════════

@Composable
fun ModelRunResolutionChangeDialog(
    state: ModelRunState,
    model: ModelInfo?,
    modelId: String,
    context: Context,
    scope: CoroutineScope,
    generationPreferences: GenerationPreferences,
    onRestart: (Resolution) -> Unit,
) {
    if (!state.showResolutionChangeDialog || state.pendingResolution == null) return
    val resolution = state.pendingResolution ?: return
    AlertDialog(
        onDismissRequest = { state.showResolutionChangeDialog = false; state.pendingResolution = null },
        title = { Text(stringResource(R.string.switch_resolution)) },
        text = { Text(stringResource(R.string.switch_resolution_hint)) },
        confirmButton = {
            TextButton(onClick = {
                val oldRatio = if (state.currentHeight > 0) state.currentWidth.toFloat() / state.currentHeight else 1f
                val newRatio = if (resolution.height > 0) resolution.width.toFloat() / resolution.height else 1f
                if (kotlin.math.abs(oldRatio - newRatio) > 0.01f) { state.clearImg2imgState() }
                state.currentWidth = resolution.width; state.currentHeight = resolution.height
                scope.launch { generationPreferences.saveResolution(modelId, resolution.width, resolution.height) }
                onRestart(resolution)
                state.showResolutionChangeDialog = false; state.pendingResolution = null; state.showAdvancedSettings = false
            }) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = { state.showResolutionChangeDialog = false; state.pendingResolution = null }) { Text(stringResource(R.string.cancel)) } },
    )
}

// ═══════════════════════════════════════════════════════════════
// History Detail Dialog (ZoomableImageOverlay)
// ═══════════════════════════════════════════════════════════════

@Composable
fun ModelRunHistoryDetailDialog(
    state: ModelRunState,
    context: Context,
    scope: CoroutineScope,
    msgImageSaved: String,
) {
    if (!state.showHistoryDetailDialog || state.selectedHistoryItem == null) return
    val detailItem = state.selectedHistoryItem ?: return
    val historyBitmap = remember(detailItem.imageFile.absolutePath) {
        BitmapFactory.decodeFile(detailItem.imageFile.absolutePath)
    }
    val dismissDetail: () -> Unit = { state.showHistoryDetailDialog = false; state.selectedHistoryItem = null }
    ZoomableImageOverlay(
        bitmap = historyBitmap,
        onDismiss = dismissDetail,
        topEndContent = {
            OverlayIconButton(icon = Icons.Default.Info, contentDescription = "View parameters", onClick = { if (state.selectedHistoryItem != null) state.showHistoryParametersDialog = true })
            OverlayIconButton(icon = Icons.Default.Save, contentDescription = "Save to gallery", onClick = {
                if (historyBitmap != null) {
                    scope.launch {
                        saveImage(context = context, bitmap = historyBitmap,
                            onSuccess = { Toast.makeText(context, msgImageSaved, Toast.LENGTH_SHORT).show() },
                            onError = { errorMsg -> Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show() })
                    }
                }
            })
        },
    )
}

// ═══════════════════════════════════════════════════════════════
// History Parameters Dialog
// ═══════════════════════════════════════════════════════════════

@Composable
fun ModelRunHistoryParametersDialog(
    state: ModelRunState,
    context: Context,
    useImg2img: Boolean,
    onSendToImg2img: (Bitmap) -> Unit,
) {
    if (!state.showHistoryParametersDialog || state.selectedHistoryItem == null) return
    val historyItem = state.selectedHistoryItem ?: return
    val params = historyItem.params
    GenerationParamsDialog(
        title = stringResource(R.string.generation_params_title),
        params = params, modelId = historyItem.modelId ?: "",
        displayMode = historyItem.mode, showImg2imgButton = useImg2img,
        onShare = { state.shareSourceParams = params; state.shareSourceModelId = historyItem.modelId },
        onSendToImg2img = {
            val item = state.selectedHistoryItem
            if (item != null) {
                val bmp = BitmapFactory.decodeFile(item.imageFile.absolutePath)
                if (bmp != null) { onSendToImg2img(bmp); state.showHistoryParametersDialog = false; state.showHistoryDetailDialog = false; state.selectedHistoryItem = null }
                else { Toast.makeText(context, "Failed to load image", Toast.LENGTH_SHORT).show() }
            }
        },
        onReproduce = { historyItem.params.let { state.pendingReproduceParams = it; state.showHistoryParametersDialog = false; state.showReproduceParamsDialog = true } },
        onDismiss = { state.showHistoryParametersDialog = false },
    )
}

// ═══════════════════════════════════════════════════════════════
// Reproduce Parameters Dialog
// ═══════════════════════════════════════════════════════════════

@Composable
fun ModelRunReproduceParamsDialog(
    state: ModelRunState,
    model: ModelInfo?,
    scope: CoroutineScope,
    pagerState: androidx.compose.foundation.pager.PagerState,
    useImg2img: Boolean,
    onSaveAll: () -> Unit,
    onClearImg2img: () -> Unit,
) {
    if (!state.showReproduceParamsDialog || state.pendingReproduceParams == null) return
    val params = state.pendingReproduceParams ?: return
    ReproduceParametersDialog(
        params = params,
        onApply = { selectedFields ->
            if (ParamShareField.PROMPT in selectedFields) { state.prompt = params.prompt; state.promptFieldValue = TextFieldValue(state.prompt, TextRange(state.prompt.length)); state.promptSuggestions = emptyList() }
            if (ParamShareField.NEGATIVE_PROMPT in selectedFields) { state.negativePrompt = params.negativePrompt; state.negativePromptFieldValue = TextFieldValue(state.negativePrompt, TextRange(state.negativePrompt.length)); state.negativePromptSuggestions = emptyList() }
            if (ParamShareField.STEPS in selectedFields) { state.steps = params.steps.toFloat() }
            if (ParamShareField.CFG_SCALE in selectedFields) { state.cfg = params.cfgScale }
            if (ParamShareField.SEED in selectedFields) { state.seed = params.seed?.toString() ?: "" }
            if (ParamShareField.SAMPLER in selectedFields) { state.sampler = params.sampler }
            if (ParamShareField.DENOISING_STRENGTH in selectedFields) { state.denoiseStrength = params.denoisingStrength }
            if (model?.isSdxl == true && useImg2img) {
                val newRatio = inferAspectRatioString(params.width, params.height)
                if (newRatio != state.aspectRatio) { state.aspectRatio = newRatio; onClearImg2img() }
            }
            onSaveAll()
            state.showReproduceParamsDialog = false; state.pendingReproduceParams = null
            state.showHistoryDetailDialog = false; state.selectedHistoryItem = null
            scope.launch { pagerState.animateScrollToPage(0) }
        },
        onDismiss = { state.showReproduceParamsDialog = false; state.pendingReproduceParams = null; state.showHistoryDetailDialog = false; state.selectedHistoryItem = null },
    )
}

// ═══════════════════════════════════════════════════════════════
// Delete History Dialog
// ═══════════════════════════════════════════════════════════════

@Composable
fun ModelRunDeleteHistoryDialog(
    state: ModelRunState,
    historyManager: HistoryManager,
    scope: CoroutineScope,
    msgDeleted: String,
    msgDeleteFailedMessage: String,
) {
    val context = LocalContext.current
    if (!state.showDeleteHistoryDialog || state.selectedHistoryItem == null) return
    val deleteItem = state.selectedHistoryItem ?: return
    AlertDialog(
        onDismissRequest = { state.showDeleteHistoryDialog = false },
        title = { Text(stringResource(R.string.delete_image)) },
        text = { Text(stringResource(R.string.delete_image_confirm)) },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val success = historyManager.deleteHistoryItem(item = deleteItem)
                    if (success) {
                        state.showDeleteHistoryDialog = false; state.showHistoryDetailDialog = false; state.selectedHistoryItem = null
                        Toast.makeText(context, msgDeleted, Toast.LENGTH_SHORT).show()
                    } else { Toast.makeText(context, msgDeleteFailedMessage, Toast.LENGTH_SHORT).show() }
                }
            }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { state.showDeleteHistoryDialog = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

// ═══════════════════════════════════════════════════════════════
// Batch Save Dialog
// ═══════════════════════════════════════════════════════════════

@Composable
fun ModelRunBatchSaveDialog(
    state: ModelRunState,
    context: Context,
    scope: CoroutineScope,
    msgSavedCountWithFailed: String,
    resources: android.content.res.Resources,
) {
    if (!state.showBatchSaveDialog || state.selectedItems.isEmpty()) return
    AlertDialog(
        onDismissRequest = { state.showBatchSaveDialog = false },
        title = { Text(stringResource(R.string.batch_save)) },
        text = { Text(pluralStringResource(R.plurals.batch_save_confirm, state.selectedItems.size, state.selectedItems.size)) },
        confirmButton = {
            TextButton(onClick = {
                val items = state.selectedItems.toList(); state.showBatchSaveDialog = false
                if (items.isEmpty()) return@TextButton
                state.batchSaveTotal = items.size; state.batchSaveCurrent = 0; state.batchSaveFailed = 0; state.isBatchSaving = true
                scope.launch(Dispatchers.IO) {
                    items.forEach { item ->
                        var success = false
                        if (item.imageFile.exists()) { saveImageFromFile(context = context, sourceFile = item.imageFile, width = item.params.width, height = item.params.height, onSuccess = { success = true }, onError = { }) }
                        withContext(Dispatchers.Main) { state.batchSaveCurrent += 1; if (!success) state.batchSaveFailed += 1 }
                    }
                    withContext(Dispatchers.Main) {
                        val total = state.batchSaveTotal; val failed = state.batchSaveFailed; val saved = total - failed
                        val message = if (failed == 0) { resources.getQuantityString(R.plurals.saved_count, saved, saved) } else { msgSavedCountWithFailed.format(saved, failed) }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        state.isBatchSaving = false; state.selectedItems.clear(); state.isSelectionMode = false
                    }
                }
            }) { Text(stringResource(R.string.yes)) }
        },
        dismissButton = { TextButton(onClick = { state.showBatchSaveDialog = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

// ═══════════════════════════════════════════════════════════════
// Batch Save Progress Dialog
// ═══════════════════════════════════════════════════════════════

@Composable
fun ModelRunBatchSaveProgressDialog(state: ModelRunState) {
    if (!state.isBatchSaving) return
    AlertDialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.batch_save)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.batch_saving_progress, state.batchSaveCurrent, state.batchSaveTotal), style = MaterialTheme.typography.bodyMedium)
                val saveProgress = if (state.batchSaveTotal > 0) state.batchSaveCurrent.toFloat() / state.batchSaveTotal else 0f
                SmoothLinearWavyProgressIndicator(progress = saveProgress, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {},
    )
}

// ═══════════════════════════════════════════════════════════════
// Batch Delete Dialog
// ═══════════════════════════════════════════════════════════════

@Composable
fun ModelRunBatchDeleteDialog(
    state: ModelRunState,
    historyManager: HistoryManager,
    scope: CoroutineScope,
    msgDeletedCountWithFailed: String,
    resources: android.content.res.Resources,
) {
    val context = LocalContext.current
    if (!state.showBatchDeleteDialog || state.selectedItems.isEmpty()) return
    AlertDialog(
        onDismissRequest = { state.showBatchDeleteDialog = false },
        title = { Text(stringResource(R.string.batch_delete)) },
        text = { Text(pluralStringResource(R.plurals.batch_delete_confirm, state.selectedItems.size, state.selectedItems.size)) },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val itemsToDelete = state.selectedItems.toList(); var successCount = 0; var failCount = 0
                    itemsToDelete.forEach { item -> val success = historyManager.deleteHistoryItem(item = item); if (success) successCount++ else failCount++ }
                    state.selectedItems.clear(); state.isSelectionMode = false; state.showBatchDeleteDialog = false
                    val message = if (failCount == 0) { resources.getQuantityString(R.plurals.deleted_count, successCount, successCount) } else { msgDeletedCountWithFailed.format(successCount, failCount) }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { state.showBatchDeleteDialog = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

// ═══════════════════════════════════════════════════════════════
// Share Parameters Dialog
// ═══════════════════════════════════════════════════════════════

@Composable
fun ModelRunShareParamsDialog(
    state: ModelRunState,
    context: Context,
    scope: CoroutineScope,
    generationPreferences: GenerationPreferences,
    shareUseBase64: Boolean,
    msgShareCopied: String,
) {
    val source = state.shareSourceParams ?: return
    val available = remember(source) {
        val list = mutableListOf<ParamShareField>()
        list += ParamShareField.PROMPT; list += ParamShareField.NEGATIVE_PROMPT; list += ParamShareField.STEPS; list += ParamShareField.CFG_SCALE
        if (source.seed != null) list += ParamShareField.SEED; list += ParamShareField.SAMPLER
        if (source.mode != GenerationMode.UNKNOWN && source.mode != GenerationMode.TXT2IMG) { list += ParamShareField.DENOISING_STRENGTH }
        list
    }
    ShareParametersDialog(
        availableFields = available,
        fieldPreview = { field ->
            when (field) {
                ParamShareField.PROMPT -> source.prompt
                ParamShareField.NEGATIVE_PROMPT -> source.negativePrompt
                ParamShareField.STEPS -> source.steps.toString()
                ParamShareField.CFG_SCALE -> "%.1f".format(source.cfgScale)
                ParamShareField.SEED -> source.seed?.toString()
                ParamShareField.SAMPLER -> samplerDisplayName(source.sampler)
                ParamShareField.DENOISING_STRENGTH -> "%.2f".format(source.denoisingStrength)
                ParamShareField.MODE -> source.mode.name.lowercase()
            }
        },
        useBase64Initial = shareUseBase64,
        onUseBase64Changed = { value -> scope.launch { generationPreferences.setShareUseBase64(value) } },
        onConfirm = { selectedFields, useBase64 ->
            val json = ParamShare.buildJson(source, state.shareSourceModelId, selectedFields)
            val payload = ParamShare.encodeForClipboard(json, useBase64)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("dreamandroid params", payload))
            state.clipboardImportChecked = true; state.shareSourceParams = null; state.shareSourceModelId = null
            Toast.makeText(context, msgShareCopied, Toast.LENGTH_SHORT).show()
        },
        onDismiss = { state.shareSourceParams = null; state.shareSourceModelId = null },
    )
}

// ═══════════════════════════════════════════════════════════════
// Import Parameters Dialog
// ═══════════════════════════════════════════════════════════════

@Composable
fun ModelRunImportParamsDialog(
    state: ModelRunState,
    context: Context,
    scope: CoroutineScope,
    generationPreferences: GenerationPreferences,
    shareClearClipboardOnImport: Boolean,
    msgImportApplied: String,
    onSaveAll: () -> Unit,
) {
    val imported = state.pendingImport ?: return
    val clearClipboardAction: () -> Unit = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        runCatching { clipboard?.clearPrimaryClip() }
    }
    ImportParametersDialog(
        imported = imported,
        clearClipboardInitial = shareClearClipboardOnImport,
        onClearClipboardChanged = { value -> scope.launch { generationPreferences.setShareClearClipboardOnImport(value) } },
        onApply = { selectedFields, clearClipboard ->
            if (ParamShareField.PROMPT in selectedFields) { imported.prompt?.let { state.prompt = it; state.promptFieldValue = TextFieldValue(it, TextRange(it.length)); state.promptSuggestions = emptyList() } }
            if (ParamShareField.NEGATIVE_PROMPT in selectedFields) { imported.negativePrompt?.let { state.negativePrompt = it; state.negativePromptFieldValue = TextFieldValue(it, TextRange(it.length)); state.negativePromptSuggestions = emptyList() } }
            if (ParamShareField.STEPS in selectedFields) { imported.steps?.let { state.steps = it.toFloat() } }
            if (ParamShareField.CFG_SCALE in selectedFields) { imported.cfgScale?.let { state.cfg = it } }
            if (ParamShareField.SEED in selectedFields) { state.seed = imported.seed?.toString() ?: "" }
            if (ParamShareField.SAMPLER in selectedFields) { imported.sampler?.let { state.sampler = it } }
            if (ParamShareField.DENOISING_STRENGTH in selectedFields) { imported.denoisingStrength?.let { state.denoiseStrength = it } }
            onSaveAll()
            if (clearClipboard) clearClipboardAction()
            state.pendingImport = null
            Toast.makeText(context, msgImportApplied, Toast.LENGTH_SHORT).show()
        },
        onDismiss = { clearClipboard ->
            if (clearClipboard) clearClipboardAction()
            state.pendingImport = null
        },
    )
}

// ═══════════════════════════════════════════════════════════════
// Blocking Overlays (loading / upscaling)
// ═══════════════════════════════════════════════════════════════

@Composable
fun ModelRunBlockingOverlays(state: ModelRunState) {
    BlockingProgressOverlay(visible = state.isCheckingBackend) {
        CircularProgressIndicator()
        Text(stringResource(R.string.loading_model), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
    BlockingProgressOverlay(visible = state.isUpscaling) {
        CircularProgressIndicator()
        Text(stringResource(R.string.upscaling_image), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ═══════════════════════════════════════════════════════════════
// Upscaler Select Dialog (complex — with internal download state)
// ═══════════════════════════════════════════════════════════════

@Composable
fun ModelRunUpscalerDialog(
    state: ModelRunState,
    modelId: String,
    upscalerRepository: UpscalerRepository,
    upscalerPreferences: android.content.SharedPreferences,
    context: Context,
    historyManager: HistoryManager,
    scope: CoroutineScope,
    msgDownloadDone: String,
    msgErrorDownloadFailed: String,
    msgDownloadModelFirst: String,
    msgUpscaleFailed: String,
) {
    if (!state.showUpscalerDialog) return

    var tempSelectedUpscalerId by remember {
        mutableStateOf(upscalerPreferences.getString("${modelId}_selected_upscaler", null))
    }
    var downloadingUpscalerId by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }
    val downloadState by ModelDownloadService.downloadState.collectAsState()

    LaunchedEffect(downloadState) {
        when (val ds = downloadState) {
            is ModelDownloadService.DownloadState.Downloading -> {
                val upscaler = upscalerRepository.upscalers.find { it.id == ds.modelId }
                if (upscaler != null) { downloadingUpscalerId = upscaler.id; downloadProgress = DownloadProgress(progress = ds.progress, downloadedBytes = ds.downloadedBytes, totalBytes = ds.totalBytes) }
            }
            is ModelDownloadService.DownloadState.Success -> {
                upscalerRepository.refreshUpscalerState(ds.modelId); downloadingUpscalerId = null; downloadProgress = null
                Toast.makeText(context, msgDownloadDone, Toast.LENGTH_SHORT).show()
            }
            is ModelDownloadService.DownloadState.Error -> {
                downloadingUpscalerId = null; downloadProgress = null
                Toast.makeText(context, msgErrorDownloadFailed.format(ds.message), Toast.LENGTH_SHORT).show()
            }
            is ModelDownloadService.DownloadState.Extracting -> {
                val upscaler = upscalerRepository.upscalers.find { it.id == ds.modelId }
                if (upscaler != null) { downloadingUpscalerId = upscaler.id; downloadProgress = null }
            }
            is ModelDownloadService.DownloadState.Idle -> {
                if (downloadingUpscalerId != null && downloadProgress == null) { downloadingUpscalerId = null }
            }
        }
    }

    UpscalerSelectDialog(
        upscalers = upscalerRepository.upscalers,
        selectedUpscalerId = tempSelectedUpscalerId,
        downloadingUpscalerId = downloadingUpscalerId,
        downloadProgress = downloadProgress,
        onDismiss = { state.showUpscalerDialog = false },
        onSelectUpscaler = { upscalerId -> tempSelectedUpscalerId = upscalerId },
        onConfirm = {
            val selectedUpscaler = upscalerRepository.upscalers.find { it.id == tempSelectedUpscalerId }
            if (selectedUpscaler != null && selectedUpscaler.isDownloaded) {
                upscalerPreferences.edit { putString("${modelId}_selected_upscaler", selectedUpscaler.id) }
                state.showUpscalerDialog = false
                state.currentBitmap?.let { bitmap ->
                    val sourceIsStitchable = state.currentDisplayedHistoryId != null && state.currentDisplayedHistoryId in state.stitchableHistoryIds
                    state.isUpscaling = true
                    scope.launch {
                        try {
                            val upscaledBitmap = performUpscale(context = context, bitmap = bitmap, upscalerId = selectedUpscaler.id)
                            state.generationParams?.let { params ->
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val updatedParams = params.copy(width = upscaledBitmap.width, height = upscaledBitmap.height)
                                        val sourceMode = state.selectedHistoryItem?.mode ?: GenerationMode.UNKNOWN
                                        val saved = historyManager.saveGeneratedImage(modelId = modelId, bitmap = upscaledBitmap, params = updatedParams, mode = sourceMode, upscalerId = selectedUpscaler.id)
                                        if (saved != null) {
                                            withContext(Dispatchers.Main) {
                                                state.currentBitmap = upscaledBitmap; state.generationParams = updatedParams
                                                state.generationParamsModelId = modelId; state.currentDisplayedHistoryId = saved.id.toLongOrNull()
                                                if (sourceIsStitchable) saved.id.toLongOrNull()?.let { state.stitchableHistoryIds = state.stitchableHistoryIds + it }
                                                state.imageVersion++
                                            }
                                        }
                                    } catch (e: Exception) { Log.e("ModelRunScreen", "Failed to save upscaled image", e) }
                                }
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, msgUpscaleFailed.format(e.message ?: "Unknown error"), Toast.LENGTH_SHORT).show()
                        } finally { state.isUpscaling = false }
                    }
                }
            } else if (selectedUpscaler != null) { Toast.makeText(context, msgDownloadModelFirst, Toast.LENGTH_SHORT).show() }
        },
        onDownload = { upscaler -> downloadingUpscalerId = upscaler.id; downloadProgress = null; upscaler.startDownload(context) },
    )
}

// ═══════════════════════════════════════════════════════════════
// UpscalerSelectDialog (from ModelRunSatellites)
// ═══════════════════════════════════════════════════════════════

@Composable
fun UpscalerSelectDialog(
    upscalers: List<UpscalerModel>,
    selectedUpscalerId: String?,
    downloadingUpscalerId: String?,
    downloadProgress: DownloadProgress?,
    onDismiss: () -> Unit,
    onSelectUpscaler: (String) -> Unit,
    onConfirm: () -> Unit,
    onDownload: (UpscalerModel) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_upscaler_model)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(upscalers) { upscaler ->
                    UpscalerModelCard(
                        upscaler = upscaler, isSelected = upscaler.id == selectedUpscalerId, isDownloading = upscaler.id == downloadingUpscalerId,
                        downloadProgress = if (upscaler.id == downloadingUpscalerId) downloadProgress else null,
                        onSelect = { onSelectUpscaler(upscaler.id) }, onDownload = { onDownload(upscaler) },
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Button(onClick = onConfirm, enabled = selectedUpscalerId != null) { Text(stringResource(R.string.confirm)) }
            }
        },
    )
}

// ═══════════════════════════════════════════════════════════════
// UpscalerModelCard (from ModelRunSatellites)
// ═══════════════════════════════════════════════════════════════

@Composable
fun UpscalerModelCard(
    upscaler: UpscalerModel, isSelected: Boolean, isDownloading: Boolean, downloadProgress: DownloadProgress?,
    onSelect: () -> Unit, onDownload: () -> Unit,
) {
    Card(
        onClick = onSelect, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) { Text(text = upscaler.name, style = MaterialTheme.typography.titleMedium); Text(text = upscaler.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (isDownloading) { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
                else if (!upscaler.isDownloaded) {
                    FilledTonalButton(onClick = onDownload) {
                        Icon(Icons.Default.Download, contentDescription = stringResource(R.string.download), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp)); Text(stringResource(R.string.download))
                    }
                } else if (isSelected) { Icon(Icons.Default.CheckCircle, contentDescription = "selected", tint = MaterialTheme.colorScheme.primary) }
            }
            if (isDownloading && downloadProgress != null) {
                SmoothLinearWavyProgressIndicator(progress = downloadProgress.progress, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// PromptCountLabel (from ModelRunSatellites)
// ═══════════════════════════════════════════════════════════════

@Composable
fun PromptCountLabel(label: String, count: Int, max: Int, showCount: Boolean, overflow: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        if (showCount) { Spacer(Modifier.width(6.dp)); Text("$count/$max") }
        if (overflow) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.Report, contentDescription = stringResource(R.string.prompt_token_overflow), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Custom Aspect Ratio Dialog
// ═══════════════════════════════════════════════════════════════

@Composable
fun ModelRunCustomAspectRatioDialog(
    state: ModelRunState,
    onClearImg2img: () -> Unit,
    onSaveAll: () -> Unit,
) {
    if (!state.showCustomAspectRatioDialog) return
    var ratioWStr by remember { mutableStateOf("") }
    var ratioHStr by remember { mutableStateOf("") }
    var ratioError by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { state.showCustomAspectRatioDialog = false },
        title = { Text(stringResource(R.string.aspect_ratio_custom_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.aspect_ratio_custom_hint), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = ratioWStr, onValueChange = { ratioWStr = it.filter { c -> c.isDigit() }.take(5); ratioError = false }, label = { Text("W") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium, isError = ratioError)
                    Text(":", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(value = ratioHStr, onValueChange = { ratioHStr = it.filter { c -> c.isDigit() }.take(5); ratioError = false }, label = { Text("H") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium, isError = ratioError)
                }
                if (ratioError) { Text(stringResource(R.string.aspect_ratio_invalid), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = {
            val w = ratioWStr.toIntOrNull(); val h = ratioHStr.toIntOrNull()
            if (w != null && h != null && w > 0 && h > 0) {
                val newRatio = "$w:$h"
                if (newRatio != state.aspectRatio) { state.aspectRatio = newRatio; onClearImg2img(); onSaveAll() }
                state.showCustomAspectRatioDialog = false
            } else { ratioError = true }
        }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { state.showCustomAspectRatioDialog = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
