package io.github.dreamandroid.local.ui.screens.run

/*
 * NOTE: This file is no longer in use.
 *
 * ModelRunResult was the result page (Page 1) of the original ModelRunScreen.
 * Since ModelRunScreen has been replaced by tab-based UI (Generate / Upscale tabs),
 * this file is kept solely as reference.
 *
 * Do NOT instantiate any composable from this file in new code.
 */

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.dreamandroid.local.BuildConfig
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.ui.screens.run.GenerationParameters
import io.github.dreamandroid.local.ui.screens.formatBrowseTime
import io.github.dreamandroid.local.data.HistoryItem
import io.github.dreamandroid.local.data.ModelInfo
import io.github.dreamandroid.local.ui.components.GenerationParamsDialog
import io.github.dreamandroid.local.ui.components.ZoomableImageOverlay
import io.github.dreamandroid.local.ui.components.OverlayIconButton
import io.github.dreamandroid.local.ui.theme.Motion
import io.github.dreamandroid.local.utils.saveImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Result page composable extracted from ModelRunScreen.
 * Displays generated image + history thumbnails + parameter summary card + dialogs.
 */
@Composable
fun ModelRunResultPage(
    state: ModelRunState,
    model: ModelInfo?,
    context: Context,
    scope: CoroutineScope,
    coroutineScope: CoroutineScope,
    useImg2img: Boolean,
    generationParamsModelId: String,
    pagerState: PagerState,
    msgImageSaved: String,
    historyItems: List<HistoryItem>,
    isTimedOut: Boolean = false,
    timeoutSeconds: Int = 60,
    msgTimedOutTitle: String = "Generation Timed Out",
    msgTimedOutMessage: String = "",
    msgTimedOutKill: String = "Kill Process",
    msgTimedOutIgnore: String = "Ignore",
    onKillBackend: (() -> Unit)? = null,
    onIgnoreTimeout: (() -> Unit)? = null,
    onSaveImage: (Context, Bitmap, () -> Unit, (String) -> Unit) -> Unit,
    onSendToImg2img: (Bitmap) -> Unit,
    onPreview: () -> Unit,
    onHistoryThumbnailClick: (HistoryItem) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Timeout warning card ──
        if (isTimedOut) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            msgTimedOutTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    Text(
                        if (msgTimedOutMessage.isNotEmpty()) msgTimedOutMessage
                        else stringResource(R.string.generation_timed_out_message, timeoutSeconds),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { onIgnoreTimeout?.invoke() },
                        ) {
                            Text(msgTimedOutIgnore)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onKillBackend?.invoke() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            Text(msgTimedOutKill)
                        }
                    }
                }
            }
        }

        Crossfade(targetState = state.currentBitmap != null, label = "result_crossfade") { hasResult ->
            if (!hasResult) {
                ElevatedCard(modifier = Modifier.padding(16.dp)) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val iconScale = remember { Animatable(1f) }
                        LaunchedEffect(Unit) {
                            iconScale.animateTo(1.1f, animationSpec = infiniteRepeatable(animation = tween(1500), repeatMode = RepeatMode.Reverse))
                        }
                        Icon(
                            Icons.Default.Image, contentDescription = null,
                            modifier = Modifier.size(48.dp).graphicsLayer { scaleX = iconScale.value; scaleY = iconScale.value },
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(stringResource(R.string.no_results), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.no_results_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } }, modifier = Modifier.padding(top = 8.dp)) {
                            Text(stringResource(R.string.go_to_generate))
                        }
                    }
                }
            } else {
                ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.result_tab), style = MaterialTheme.typography.titleMedium)
                            state.currentBitmap?.let { bitmap ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (BuildConfig.FLAVOR == "filter") {
                                        FilledTonalIconButton(onClick = { state.showReportDialog = true }) {
                                            Icon(Icons.Default.Report, contentDescription = "report inappropriate content")
                                        }
                                    }
                                    if (model?.runOnCpu == false && state.generationParams?.let { maxOf(it.width, it.height) <= 1024 } == true) {
                                        FilledTonalIconButton(onClick = { state.showUpscalerDialog = true }, enabled = !state.isRunning && !state.isUpscaling) {
                                            Icon(Icons.Default.AutoFixHigh, contentDescription = "upscale image")
                                        }
                                    }
                                    FilledTonalIconButton(onClick = {
                                        onSaveImage(context, bitmap,
                                            { Toast.makeText(context, msgImageSaved, Toast.LENGTH_SHORT).show() },
                                            { error -> Toast.makeText(context, error, Toast.LENGTH_SHORT).show() })
                                    }) { Icon(Icons.Default.Save, contentDescription = "save image") }
                                }
                            }
                        }
                        Surface(
                            onClick = { onPreview() },
                            enabled = state.currentBitmap != null,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                            shape = MaterialTheme.shapes.medium,
                            shadowElevation = 4.dp,
                        ) {
                            AnimatedContent(
                                targetState = state.imageVersion to state.currentBitmap,
                                modifier = Modifier.fillMaxSize(),
                                transitionSpec = { fadeIn(animationSpec = Motion.Fade) togetherWith fadeOut(animationSpec = Motion.FadeOut) },
                                label = "ImagePreviewCrossfade",
                            ) { (_, bitmap) ->
                                bitmap?.let {
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current).data(it).size(coil.size.Size.ORIGINAL).crossfade(true).build(),
                                        contentDescription = "generated image",
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                        if (historyItems.size > 1) {
                            LazyRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(historyItems.take(20).size) { idx ->
                                    val item = historyItems[idx]
                                    Card(
                                        onClick = { onHistoryThumbnailClick(item) },
                                        modifier = Modifier.size(72.dp), shape = MaterialTheme.shapes.small,
                                    ) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(LocalContext.current).data(item.imageFile).crossfade(true).build(),
                                            contentDescription = "thumb", modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                            }
                        }
                        Card(
                            onClick = { state.showParametersDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.generation_params), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                                    Icon(Icons.Default.Info, contentDescription = "view details", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                state.generationParams?.let { params ->
                                    Text(stringResource(R.string.result_params, params.steps, params.cfgScale, params.seed.toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(stringResource(R.string.result_params_2, params.width, params.height, formatBrowseTime(params.generationTime).ifBlank { "unknown" },
                                        if (params.runOnCpu) { if (params.useOpenCL) "GPU" else "CPU" } else { "NPU" }),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Report dialog
        if (state.showReportDialog && state.currentBitmap != null && state.generationParams != null) {
            AlertDialog(
                onDismissRequest = { state.showReportDialog = false },
                title = { Text("Report") },
                text = { Column { Text("Report this image if you feel it is inappropriate. Params and image will be sent to the server for review.", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                confirmButton = {
                    TextButton(
                        onClick = {
                            state.showReportDialog = false
                            coroutineScope.launch {
                                state.currentBitmap?.let { bitmap ->
                                    val reportParams = state.generationParams ?: return@let
                                    io.github.dreamandroid.local.utils.reportImage(bitmap = bitmap, modelName = model?.name ?: "", params = reportParams,
                                        onSuccess = { Toast.makeText(context, "Thanks for your report.", Toast.LENGTH_SHORT).show() },
                                        onError = { error -> Toast.makeText(context, "Error: $error", Toast.LENGTH_SHORT).show() })
                                }
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Report") }
                },
                dismissButton = { TextButton(onClick = { state.showReportDialog = false }) { Text("Cancel") } },
            )
        }

        // Parameters dialog
        if (state.showParametersDialog && state.generationParams != null) {
            val dialogParams = state.generationParams ?: return
            GenerationParamsDialog(
                title = stringResource(R.string.params_detail),
                params = dialogParams,
                modelId = generationParamsModelId,
                showImg2imgButton = useImg2img,
                onShare = { state.shareSourceParams = state.generationParams; state.shareSourceModelId = generationParamsModelId },
                onSendToImg2img = {
                    val bmp = state.currentBitmap
                    if (bmp != null) { onSendToImg2img(bmp); state.showParametersDialog = false }
                    else { Toast.makeText(context, "No image available", Toast.LENGTH_SHORT).show() }
                },
                onReproduce = {
                    state.generationParams?.let { state.pendingReproduceParams = it; state.showParametersDialog = false; state.showReproduceParamsDialog = true }
                },
                onDismiss = { state.showParametersDialog = false },
            )
        }
    }
}
