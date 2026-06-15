package io.github.dreamandroid.local.ui.screens.run

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Rect as AndroidRect
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import io.github.dreamandroid.local.data.GenerationMode
import io.github.dreamandroid.local.data.GenerationParameters
import io.github.dreamandroid.local.data.GenerationPreferences
import io.github.dreamandroid.local.data.HistoryManager
import io.github.dreamandroid.local.data.ModelInfo
import io.github.dreamandroid.local.service.BackendService
import io.github.dreamandroid.local.service.BackgroundGenerationService
import io.github.dreamandroid.local.service.BackgroundGenerationService.GenerationState
import io.github.dreamandroid.local.utils.computeAspectTargetSize
import io.github.dreamandroid.local.utils.padBitmapToCanvas
import io.github.dreamandroid.local.utils.saveImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Generation logic extracted from ModelRunScreen.
 *
 * Contains saveAllFields, parameter change callbacks, the batch generation loop,
 * service state handler, and image-processing functions (crop, inpaint, img2img).
 * All functions receive [ModelRunState] plus required dependencies as parameters.
 */

// ── Persistence ──────────────────────────────────────────────────

fun saveAllFields(
    state: ModelRunState,
    scope: CoroutineScope,
    generationPreferences: GenerationPreferences,
    modelId: String,
) {
    state.saveAllJob?.cancel()
    state.saveAllJob = scope.launch(Dispatchers.IO) {
        delay(1000)
        generationPreferences.saveAllFields(
            modelId = modelId,
            prompt = state.prompt,
            negativePrompt = state.negativePrompt,
            steps = state.steps,
            cfg = state.cfg,
            seed = state.seed,
            width = state.currentWidth,
            height = state.currentHeight,
            denoiseStrength = state.denoiseStrength,
            useOpenCL = state.useOpenCL,
            batchCounts = state.batchCounts,
            scheduler = state.scheduler,
            aspectRatio = state.aspectRatio,
        )
    }
}

// ── Parameter Change Callbacks ────────────────────────────────────

fun onStepsChange(state: ModelRunState, value: Float, save: () -> Unit) {
    state.steps = value; save()
}

fun onCfgChange(state: ModelRunState, value: Float, save: () -> Unit) {
    state.cfg = value; save()
}

fun onSizeChange(state: ModelRunState, value: Float, save: () -> Unit) {
    val rounded = (value / 64).roundToInt() * 64
    val newSize = rounded.coerceIn(128, 512)
    state.currentWidth = newSize; state.currentHeight = newSize
    save()
}

fun onDenoiseStrengthChange(state: ModelRunState, value: Float, save: () -> Unit) {
    state.denoiseStrength = value; save()
}

fun onSeedChange(state: ModelRunState, value: String, save: () -> Unit) {
    state.seed = value; save()
}

fun onBatchCountsChange(state: ModelRunState, value: Float, save: () -> Unit) {
    state.batchCounts = value.roundToInt().coerceIn(1, 10); save()
}

// ── Service State Handler ─────────────────────────────────────────

fun handleServiceState(
    serviceState: GenerationState?,
    state: ModelRunState,
    model: ModelInfo?,
    modelId: String,
    historyManager: HistoryManager,
    coroutineScope: CoroutineScope,
    pagerState: androidx.compose.foundation.pager.PagerState,
) {
    when (serviceState) {
        is GenerationState.Progress -> {
            if (state.generationStartTime == null) {
                state.generationStartTime = System.currentTimeMillis()
            }
            state.progress = serviceState.progress
            state.isRunning = true
            serviceState.intermediateImage?.let { state.intermediateBitmap = it }
        }

        is GenerationState.Complete -> {
            state.intermediateBitmap = null
            coroutineScope.launch(Dispatchers.Main) {
                Log.d("ModelRunScreen", "update bitmap")
                serviceState.seed?.let { state.returnedSeed = it }
                state.progress = 0f

                val genTime = state.generationStartTime?.let { startTime ->
                    val duration = System.currentTimeMillis() - startTime
                    when {
                        duration < 1000 -> "${duration}ms"
                        duration < 60000 -> String.format(Locale.US, "%.1fs", duration / 1000.0)
                        else -> String.format(Locale.US, "%dm%ds", duration / 60000, (duration % 60000) / 1000)
                    }
                }

                val currentGenerationMode = when {
                    state.isInpaintMode -> GenerationMode.INPAINT
                    state.selectedImageUri != null -> GenerationMode.IMG2IMG
                    else -> GenerationMode.TXT2IMG
                }

                val newParams = GenerationParameters(
                    steps = state.generationParamsTmp.steps,
                    cfg = state.generationParamsTmp.cfg,
                    seed = state.returnedSeed,
                    prompt = state.generationParamsTmp.prompt,
                    negativePrompt = state.generationParamsTmp.negativePrompt,
                    generationTime = genTime,
                    width = if (model?.runOnCpu == true) state.generationParamsTmp.width else serviceState.bitmap.width,
                    height = if (model?.runOnCpu == true) state.generationParamsTmp.height else serviceState.bitmap.height,
                    runOnCpu = model?.runOnCpu ?: false,
                    denoiseStrength = state.generationParamsTmp.denoiseStrength,
                    useOpenCL = state.generationParamsTmp.useOpenCL,
                    scheduler = state.generationParamsTmp.scheduler,
                    mode = currentGenerationMode,
                )

                coroutineScope.launch(Dispatchers.IO) {
                    val savedItem = historyManager.saveGeneratedImage(
                        modelId = modelId, bitmap = serviceState.bitmap,
                        params = newParams, mode = currentGenerationMode,
                    )
                    if (savedItem != null) {
                        withContext(Dispatchers.Main) {
                            state.stitchableHistoryIds = setOf(savedItem.id)
                            state.currentDisplayedHistoryId = savedItem.id
                        }
                    }
                }

                state.currentBitmap = serviceState.bitmap
                state.generationParams = newParams
                state.generationParamsModelId = modelId
                state.imageVersion++

                state.snapshotIsInpaintMode = state.isInpaintMode
                state.snapshotSelectedImageUri = state.selectedImageUri
                state.snapshotCropRect = state.cropRect
                state.snapshotHasOriginalImage = state.hasOriginalImageForStitch
                state.stitchableHistoryIds = emptySet()
                state.currentDisplayedHistoryId = null

                Log.d("ModelRunScreen", "params update: ${state.generationParams?.steps}, ${state.generationParams?.cfg}")
                state.generationStartTime = null

                if (pagerState.currentPage == 0 && !state.showAdvancedSettings) {
                    try { pagerState.animateScrollToPage(1) }
                    finally { BackgroundGenerationService.markBitmapConsumed() }
                } else {
                    BackgroundGenerationService.markBitmapConsumed()
                }
            }
        }

        is GenerationState.Error -> {
            state.intermediateBitmap = null
            state.errorMessage = serviceState.message
            state.isRunning = false
            state.progress = 0f
            state.generationStartTime = null
        }

        else -> {
            if (serviceState !is GenerationState.Progress) {
                state.isRunning = false
                state.progress = 0f
            }
        }
    }
}

// ── Batch Generation Loop ─────────────────────────────────────────

fun startBatchGeneration(
    state: ModelRunState,
    context: Context,
    model: ModelInfo?,
    coroutineScope: CoroutineScope,
    effectiveWidth: Int,
    effectiveHeight: Int,
) {
    state.generationParamsTmp = GenerationParameters(
        steps = state.steps.roundToInt(), cfg = state.cfg, seed = 0,
        prompt = state.prompt, negativePrompt = state.negativePrompt,
        generationTime = "", width = state.currentWidth, height = state.currentHeight,
        runOnCpu = model?.runOnCpu ?: false, denoiseStrength = state.denoiseStrength,
        useOpenCL = state.useOpenCL, scheduler = state.scheduler,
    )

    val actualBatchCount = if (state.seed.isNotBlank()) 1 else state.batchCounts

    state.batchGenerationJob = coroutineScope.launch {
        for (i in 0 until actualBatchCount) {
            BackgroundGenerationService.forceResetIfStale()
            state.currentBatchIndex = i + 1
            Log.d("ModelRunScreen", "preparing batch $i")

            state.generationParamsTmp = GenerationParameters(
                steps = state.steps.roundToInt(), cfg = state.cfg, seed = 0,
                prompt = state.prompt, negativePrompt = state.negativePrompt,
                generationTime = "", width = state.currentWidth, height = state.currentHeight,
                runOnCpu = model?.runOnCpu ?: false, denoiseStrength = state.denoiseStrength,
                useOpenCL = state.useOpenCL, scheduler = state.scheduler,
            )

            var batchItemSucceeded = false
            for (retryAttempt in 1..BackgroundGenerationService.MAX_RETRIES) {
                if (retryAttempt > 1) {
                    Log.w("ModelRunScreen", "Batch $i retry $retryAttempt")
                    delay(BackgroundGenerationService.RETRY_DELAY_MS)
                    BackgroundGenerationService.forceResetIfStale()
                }

                val batchIntent = Intent(context, BackgroundGenerationService::class.java).apply {
                    putExtra("prompt", state.prompt)
                    putExtra("negative_prompt", state.negativePrompt)
                    putExtra("steps", state.steps.roundToInt())
                    putExtra("cfg", state.cfg)
                    state.seed.toLongOrNull()?.let { putExtra("seed", it) }
                    putExtra("width", state.currentWidth)
                    putExtra("height", state.currentHeight)
                    putExtra("effective_width", effectiveWidth)
                    putExtra("effective_height", effectiveHeight)
                    putExtra("denoise_strength", state.denoiseStrength)
                    putExtra("use_opencl", state.useOpenCL)
                    putExtra("scheduler", state.scheduler)
                    putExtra("aspect_ratio", state.aspectRatio)
                    putExtra("batch_index", i)
                    if (state.selectedImageUri != null && state.base64EncodeDone) {
                        putExtra("has_image", true)
                        if (state.isInpaintMode && state.maskBitmap != null) {
                            putExtra("has_mask", true)
                        }
                    }
                }

                Log.d("ModelRunScreen", "start service - batch $i")
                context.startForegroundService(batchIntent)
                Log.d("ModelRunScreen", "start service sent - batch $i")

                val result = withTimeoutOrNull(BackgroundGenerationService.getServiceWaitTimeoutMs(context)) {
                    BackgroundGenerationService.generationState.first { s ->
                        s is GenerationState.Complete || s is GenerationState.Error
                    }
                }

                when {
                    result == null -> {
                        Log.w("ModelRunScreen", "Batch $i timed out")
                        BackgroundGenerationService.resetState()
                        context.sendBroadcast(Intent(BackgroundGenerationService.ACTION_STOP))
                        continue
                    }
                    result is GenerationState.Complete -> batchItemSucceeded = true
                    result is GenerationState.Error -> {
                        Log.w("ModelRunScreen", "Batch $i error: ${result.message}")
                        BackgroundGenerationService.resetState()
                        continue
                    }
                }

                Log.d("ModelRunScreen", "batch $i completed")
                BackgroundGenerationService.markBitmapConsumed()

                val waitStartTime = System.currentTimeMillis()
                while (BackgroundGenerationService.isServiceRunning.value) {
                    if (System.currentTimeMillis() - waitStartTime > BackgroundGenerationService.getServiceWaitTimeoutMs(context)) {
                        Log.w("ModelRunScreen", "Service stop timeout")
                        context.sendBroadcast(Intent(BackgroundGenerationService.ACTION_STOP))
                        delay(500)
                        break
                    }
                    delay(100)
                }

                Log.d("ModelRunScreen", "service stopped")
                BackgroundGenerationService.forceResetIfStale()
                if (batchItemSucceeded) break
            }

            if (!batchItemSucceeded) {
                Log.e("ModelRunScreen", "Batch $i failed after all retries")
                break
            }
        }
        state.currentBatchIndex = 0
        state.isRunning = false
        Log.d("ModelRunScreen", "all batches completed")
    }
}

// ── Image Selection ──────────────────────────────────────────────

fun onSelectImageClick(
    state: ModelRunState,
    context: Context,
    msgMediaPermissionHint: String,
    photoPickerLauncher: (PickVisualMediaRequest) -> Unit,
    contentPickerLauncher: () -> Unit,
    requestStoragePermissionLauncher: (String) -> Unit,
) {
    when {
        Build.VERSION.SDK_INT >= 33 -> photoPickerLauncher(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
        else -> {
            when {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ->
                    contentPickerLauncher()
                else -> requestStoragePermissionLauncher(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }
}

// ── Image Processing ──────────────────────────────────────────────

fun processSelectedImage(state: ModelRunState, uri: Uri) {
    state.imageUriForCrop = uri
    state.showCropScreen = true
}

fun handleCropComplete(
    state: ModelRunState,
    context: Context,
    model: ModelInfo?,
    scope: CoroutineScope,
    base64String: String,
    bitmap: Bitmap,
    rect: AndroidRect,
) {
    state.showCropScreen = false
    val sourceUri = state.imageUriForCrop
    state.selectedImageUri = sourceUri
    state.imageUriForCrop = null
    state.hasOriginalImageForStitch = true

    scope.launch(Dispatchers.IO) {
        try {
            state.base64EncodeDone = false
            val aspectTarget = computeAspectTargetSize(model?.isSdxl == true, state.aspectRatio)
            val targetW = aspectTarget?.first ?: state.currentWidth
            val targetH = aspectTarget?.second ?: state.currentHeight

            var clampedRect = rect
            val freshCropped: Bitmap? = try {
                sourceUri?.let { uri ->
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        @Suppress("DEPRECATION")
                        val decoder = BitmapRegionDecoder.newInstance(input, false)
                            ?: throw IllegalStateException("BitmapRegionDecoder.newInstance returned null")
                        try {
                            val safeLeft = rect.left.coerceAtLeast(0)
                            val safeTop = rect.top.coerceAtLeast(0)
                            val safeRight = rect.right.coerceAtMost(decoder.width)
                            val safeBottom = rect.bottom.coerceAtMost(decoder.height)
                            if (safeRight > safeLeft && safeBottom > safeTop) {
                                val region = AndroidRect(safeLeft, safeTop, safeRight, safeBottom)
                                clampedRect = region
                                decoder.decodeRegion(region, BitmapFactory.Options())
                            } else null
                        } finally { decoder.recycle() }
                    }
                }
            } catch (e: Exception) {
                Log.w("ModelRunScreen", "BitmapRegionDecoder failed: ${e.message}")
                null
            }

            val sourceBitmap = freshCropped ?: bitmap
            val scaled = withContext(Dispatchers.Default) {
                if (sourceBitmap.width != targetW || sourceBitmap.height != targetH) {
                    sourceBitmap.scale(targetW, targetH)
                } else sourceBitmap
            }

            val needsPad = scaled.width != state.currentWidth || scaled.height != state.currentHeight
            val payload = if (needsPad) {
                val padded = padBitmapToCanvas(scaled, state.currentWidth, state.currentHeight)
                val baos = ByteArrayOutputStream()
                padded.compress(Bitmap.CompressFormat.PNG, 90, baos)
                Base64.getEncoder().encodeToString(baos.toByteArray())
            } else {
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 90, baos)
                Base64.getEncoder().encodeToString(baos.toByteArray())
            }

            withContext(Dispatchers.Main) {
                state.cropRect = clampedRect
                state.croppedBitmap = scaled
            }

            val tmpFile = File(context.filesDir, "tmp.txt")
            tmpFile.writeText(payload)
            state.base64EncodeDone = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                state.selectedImageUri = null; state.croppedBitmap = null
                state.cropRect = null; state.hasOriginalImageForStitch = false
            }
        }
    }
}

fun handleInpaintComplete(
    state: ModelRunState,
    context: Context,
    scope: CoroutineScope,
    maskBase64: String,
    maskBmp: Bitmap,
    pathHistory: List<PathData>,
) {
    state.showInpaintScreen = false
    state.isInpaintMode = true
    state.maskBitmap = maskBmp
    state.savedPathHistory = pathHistory

    scope.launch(Dispatchers.IO) {
        try {
            val needsPad = maskBmp.width != state.currentWidth || maskBmp.height != state.currentHeight
            val payload = if (needsPad) {
                val padded = padBitmapToCanvas(maskBmp, state.currentWidth, state.currentHeight)
                val baos = ByteArrayOutputStream()
                padded.compress(Bitmap.CompressFormat.PNG, 90, baos)
                Base64.getEncoder().encodeToString(baos.toByteArray())
            } else maskBase64
            val maskFile = File(context.filesDir, "mask.txt")
            maskFile.writeText(payload)
            withContext(Dispatchers.Main) { state.base64EncodeDone = true }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                state.isInpaintMode = false; state.maskBitmap = null; state.savedPathHistory = null
            }
        }
    }
}

fun sendBitmapToImg2img(
    state: ModelRunState,
    context: Context,
    model: ModelInfo?,
    scope: CoroutineScope,
    bitmap: Bitmap,
    pagerState: androidx.compose.foundation.pager.PagerState,
) {
    scope.launch {
        val ready = try {
            state.base64EncodeDone = false
            val aspectTarget = computeAspectTargetSize(model?.isSdxl == true, state.aspectRatio)
            val targetW = aspectTarget?.first ?: state.currentWidth
            val targetH = aspectTarget?.second ?: state.currentHeight

            val resized = withContext(Dispatchers.Default) {
                val srcRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val dstRatio = targetW.toFloat() / targetH.toFloat()
                val centerCropped = if (kotlin.math.abs(srcRatio - dstRatio) < 1e-3f) bitmap
                else {
                    val (cropW, cropH) = if (srcRatio > dstRatio) {
                        Pair((bitmap.height * dstRatio).toInt(), bitmap.height)
                    } else {
                        Pair(bitmap.width, (bitmap.width / dstRatio).toInt())
                    }
                    val cx = (bitmap.width - cropW) / 2; val cy = (bitmap.height - cropH) / 2
                    Bitmap.createBitmap(bitmap, cx, cy, cropW, cropH)
                }
                if (centerCropped.width != targetW || centerCropped.height != targetH)
                    centerCropped.scale(targetW, targetH)
                else centerCropped.copy(Bitmap.Config.ARGB_8888, false)
            }

            val uploadBitmap = if (resized.width != state.currentWidth || resized.height != state.currentHeight)
                padBitmapToCanvas(resized, state.currentWidth, state.currentHeight)
            else resized

            val base64String = withContext(Dispatchers.IO) {
                val baos = ByteArrayOutputStream()
                uploadBitmap.compress(Bitmap.CompressFormat.PNG, 90, baos)
                Base64.getEncoder().encodeToString(baos.toByteArray())
            }

            withContext(Dispatchers.IO) { File(context.filesDir, "tmp.txt").writeText(base64String) }

            state.croppedBitmap = resized
            state.cropRect = AndroidRect(0, 0, resized.width, resized.height)
            state.selectedImageUri = Uri.fromFile(File(context.filesDir, "tmp.txt"))
            state.hasOriginalImageForStitch = false
            state.base64EncodeDone = true
            true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Toast.makeText(context, "img2img failed: ${e.message}", Toast.LENGTH_SHORT).show()
            state.base64EncodeDone = false
            state.selectedImageUri = null; state.croppedBitmap = null
            state.cropRect = null; state.hasOriginalImageForStitch = false
            false
        }

        if (ready) {
            try { pagerState.animateScrollToPage(0) }
            catch (_: CancellationException) { }
        }
    }
}

// ── Save Image with Stitch ───────────────────────────────────────

fun handleSaveImage(
    state: ModelRunState,
    context: Context,
    coroutineScope: CoroutineScope,
    bitmap: Bitmap,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) {
    if (!checkStoragePermission(context)) {
        onError("need storage permission to save image"); return
    }

    val shouldStitch = state.snapshotIsInpaintMode && state.snapshotCropRect != null &&
        state.snapshotSelectedImageUri != null && state.snapshotHasOriginalImage &&
        state.currentDisplayedHistoryId != null && state.currentDisplayedHistoryId in state.stitchableHistoryIds

    coroutineScope.launch {
        if (shouldStitch) {
            withContext(Dispatchers.IO) {
                try {
                    val originalBitmap = context.contentResolver.openInputStream(state.snapshotSelectedImageUri!!)!!
                        .use { BitmapFactory.decodeStream(it) }
                    val mutableOriginal = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
                    val rectW = state.snapshotCropRect!!.width(); val rectH = state.snapshotCropRect!!.height()
                    val resizedPatch = bitmap.scale(rectW, rectH)
                    val canvas = Canvas(mutableOriginal)
                    canvas.drawBitmap(resizedPatch, state.snapshotCropRect!!.left.toFloat(), state.snapshotCropRect!!.top.toFloat(), null)
                    saveImage(context = context, bitmap = mutableOriginal, onSuccess = onSuccess, onError = onError)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { onError("Failed to create composite image: ${e.localizedMessage}") }
                }
            }
        } else {
            saveImage(context = context, bitmap = bitmap, onSuccess = onSuccess, onError = onError)
        }
    }
}

// ── Cleanup / Exit ───────────────────────────────────────────────

fun cleanupModelRun(
    state: ModelRunState,
    context: Context,
    coroutineScope: CoroutineScope,
    pagerState: androidx.compose.foundation.pager.PagerState,
) {
    try {
        state.currentBitmap = null; state.generationParams = null
        context.sendBroadcast(Intent(BackgroundGenerationService.ACTION_STOP))
        val backendServiceIntent = Intent(context, BackendService::class.java)
        context.stopService(backendServiceIntent)
        state.isRunning = false; state.progress = 0f; state.errorMessage = null
        state.currentBatchIndex = 0; state.generationStartTime = null
        BackgroundGenerationService.resetState()
        coroutineScope.launch { pagerState.scrollToPage(0) }
        state.saveAllJob?.cancel(); state.batchGenerationJob?.cancel()
    } catch (e: Exception) { Log.e("ModelRunScreen", "error", e) }
}

// ── Storage Permission ───────────────────────────────────────────

private fun checkStoragePermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= 30) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}
