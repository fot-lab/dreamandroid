package io.github.dreamandroid.local.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import io.github.dreamandroid.local.DreamAndroidApplication
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.service.backend.BackendService
import io.github.dreamandroid.local.ui.backend.*
import io.github.dreamandroid.local.ui.components.BlockingProgressOverlay
import io.github.dreamandroid.local.ui.components.ErrorMessageCard
import io.github.dreamandroid.local.ui.components.SmoothCircularWavyProgressIndicator
import io.github.dreamandroid.local.ui.theme.Motion
import io.github.dreamandroid.local.ui.viewmodel.UpscaleViewModel
import io.github.dreamandroid.local.utils.saveImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpscaleScreen(
    modifier: Modifier = Modifier,
    upscaleViewModel: UpscaleViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // §17.3: Observe unified BackendManager.state via BackendService
    val app = context.applicationContext as DreamAndroidApplication
    val backendService = app.backendService
    val upscaleBackendState by backendService.state.collectAsState()
    val isUpscaleBackendRunning = upscaleBackendState.isUpscalerLoaded()
    val loadedUpscalerId = upscaleBackendState.activeUpscalerId()

    // String resources
    val msgUpscaleModelNotLoaded = stringResource(R.string.upscale_model_not_loaded)

    // Clean temp files on entry
    LaunchedEffect(Unit) {
        upscaleViewModel.cleanTempFiles(context)
    }

    // ── Image Picker ──
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let { upscaleViewModel.onImageSelected(it, context) }
    }

    // ── UI Content ──
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().then(
                        if (upscaleViewModel.selectedImageUri == null) {
                            Modifier.clickable { imagePickerLauncher.launch("image/*") }
                        } else Modifier
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (upscaleViewModel.selectedImageUri == null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(32.dp),
                        ) {
                            val iconAlpha = remember { Animatable(0.4f) }
                            LaunchedEffect(Unit) {
                                iconAlpha.animateTo(
                                    targetValue = 0.8f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1200), repeatMode = RepeatMode.Reverse,
                                    ),
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.add_image),
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = iconAlpha.value),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                stringResource(R.string.click_to_add_image),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        ZoomableImage(
                            imageUri = upscaleViewModel.selectedImageUri,
                            contentDescription = stringResource(R.string.selected_image),
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            scale = upscaleViewModel.sharedScale,
                            offsetX = upscaleViewModel.sharedOffsetX,
                            offsetY = upscaleViewModel.sharedOffsetY,
                            onTransform = { s, ox, oy ->
                                upscaleViewModel.sharedScale = s
                                upscaleViewModel.sharedOffsetX = ox
                                upscaleViewModel.sharedOffsetY = oy
                            },
                            useOriginalSize = true,
                        )
                    }

                    if (upscaleViewModel.selectedImageUri != null) {
                        FilledTonalIconButton(
                            onClick = { upscaleViewModel.clearSelectedImage() },
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        ) {
                            Icon(Icons.Default.Close, stringResource(R.string.clear_image))
                        }
                    }

                    upscaleViewModel.selectedBitmap?.let { bmp ->
                        Surface(
                            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = "${bmp.width} × ${bmp.height}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            val fabEnabled = upscaleViewModel.selectedBitmap != null
                && !upscaleViewModel.isUpscaling && isUpscaleBackendRunning && loadedUpscalerId != null
            val fabContainerColor by animateColorAsState(
                targetValue = if (fabEnabled) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                animationSpec = tween(Motion.DurationMedium), label = "FabContainerColor",
            )
            val fabContentColor by animateColorAsState(
                targetValue = if (fabEnabled) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                animationSpec = tween(Motion.DurationMedium), label = "FabContentColor",
            )
            FloatingActionButton(
                onClick = {
                    if (fabEnabled) {
                        upscaleViewModel.startUpscale(context, loadedUpscalerId)
                    }
                },
                containerColor = fabContainerColor,
                contentColor = fabContentColor,
            ) {
                Icon(Icons.Default.AutoFixHigh, stringResource(R.string.upscale))
            }

            AnimatedVisibility(
                visible = upscaleViewModel.upscaledImageUri != null,
                enter = fadeIn(animationSpec = Motion.Fade) + expandVertically(expandFrom = Alignment.Top, animationSpec = Motion.Expand),
                exit = fadeOut(animationSpec = Motion.FadeOut) + shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = Motion.Shrink),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                ElevatedCard(modifier = Modifier.fillMaxSize(), shape = MaterialTheme.shapes.large) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ZoomableImage(
                            imageUri = upscaleViewModel.upscaledImageUri,
                            contentDescription = stringResource(R.string.upscaled_image),
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            scale = upscaleViewModel.sharedScale,
                            offsetX = upscaleViewModel.sharedOffsetX,
                            offsetY = upscaleViewModel.sharedOffsetY,
                            onTransform = { s, ox, oy ->
                                upscaleViewModel.sharedScale = s
                                upscaleViewModel.sharedOffsetX = ox
                                upscaleViewModel.sharedOffsetY = oy
                            },
                            useOriginalSize = true,
                        )

                        val savedMsg = stringResource(R.string.image_saved)
                        FilledTonalIconButton(
                            onClick = {
                                upscaleViewModel.upscaledBitmap?.let { bitmap ->
                                    scope.launch {
                                        saveImage(
                                            context = context, bitmap = bitmap,
                                            onSuccess = {
                                                Toast.makeText(context, savedMsg, Toast.LENGTH_SHORT).show()
                                            },
                                            onError = { error ->
                                                upscaleViewModel.errorMessage = error
                                            },
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        ) {
                            Icon(Icons.Default.Save, stringResource(R.string.save_image))
                        }

                        upscaleViewModel.upscaledBitmap?.let { bitmap ->
                            Surface(
                                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    text = "${bitmap.width} × ${bitmap.height}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (upscaleViewModel.upscaledImageUri == null) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        // Floating Error Message
        AnimatedVisibility(
            visible = upscaleViewModel.errorMessage != null,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            upscaleViewModel.errorMessage?.let { msg ->
                ErrorMessageCard(message = msg, onDismiss = { upscaleViewModel.dismissError() })
            }
        }
    }

    // ── Blocking Progress Overlay ──
    BlockingProgressOverlay(visible = upscaleViewModel.isUpscaling) {
        val progress = upscaleViewModel.tileProgress
        if (progress != null) {
            val (current, total) = progress
            val fraction = current.toFloat() / total
            SmoothCircularWavyProgressIndicator(progress = fraction, modifier = Modifier.size(72.dp))
            Text(
                text = "${(fraction * 100).toInt()}%  $current/$total",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            CircularProgressIndicator()
            if (upscaleViewModel.currentLog.isNotEmpty()) {
                Text(
                    text = upscaleViewModel.currentLog,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
fun ZoomableImage(
    imageUri: Uri?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    onTransform: (scale: Float, offsetX: Float, offsetY: Float) -> Unit,
    useOriginalSize: Boolean = false,
) {
    val context = LocalContext.current

    var currentScale by remember { mutableFloatStateOf(1f) }
    var currentOffsetX by remember { mutableFloatStateOf(0f) }
    var currentOffsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(scale, offsetX, offsetY) {
        currentScale = scale
        currentOffsetX = offsetX
        currentOffsetY = offsetY
    }

    val imageRequest = remember(imageUri, useOriginalSize) {
        ImageRequest.Builder(context)
            .data(imageUri)
            .apply {
                if (useOriginalSize) {
                    size(Size.ORIGINAL)
                    memoryCacheKey(imageUri.toString() + "_original")
                }
            }
            .build()
    }

    Box(
        modifier = modifier.pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                val newScale = (currentScale * zoom).coerceIn(1f, 5f)
                val newOffsetX = currentOffsetX + pan.x
                val newOffsetY = currentOffsetY + pan.y
                currentScale = newScale
                currentOffsetX = newOffsetX
                currentOffsetY = newOffsetY
                onTransform(newScale, newOffsetX, newOffsetY)
            }
        },
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = currentScale, scaleY = currentScale,
                translationX = currentOffsetX, translationY = currentOffsetY,
            ),
            contentScale = ContentScale.Fit,
        )
    }
}
