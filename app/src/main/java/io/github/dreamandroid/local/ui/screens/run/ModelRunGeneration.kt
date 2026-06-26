package io.github.dreamandroid.local.ui.screens.run

/*
 * NOTE: This file is no longer in use.
 *
 * ModelRunGeneration was the generation logic layer of the original ModelRunScreen.
 * Since ModelRunScreen has been replaced by tab-based UI (Generate / Upscale tabs),
 * this file is kept solely as reference.
 *
 * Do NOT call any function from this file in new code.
 */

import android.Manifest
import android.content.Context
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
import io.github.dreamandroid.local.ui.screens.PathData
import io.github.dreamandroid.local.ui.screens.run.GenerationParameters
import io.github.dreamandroid.local.data.GenerationPreferences
import io.github.dreamandroid.local.data.ModelInfo

import io.github.dreamandroid.local.utils.saveImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
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
            cfgScale = state.cfg,
            seed = state.seed,
            width = state.currentWidth,
            height = state.currentHeight,
            denoisingStrength = state.denoiseStrength,
            useOpenCL = state.useOpenCL,
            batchCounts = state.batchCounts,
            sampler = state.sampler,
            denoiseCurve = state.denoiseCurve,
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
                java.util.Base64.getEncoder().encodeToString(baos.toByteArray())
            } else {
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 90, baos)
                java.util.Base64.getEncoder().encodeToString(baos.toByteArray())
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
                java.util.Base64.getEncoder().encodeToString(baos.toByteArray())
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
                java.util.Base64.getEncoder().encodeToString(baos.toByteArray())
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

        // NEVER stop the backend from cleanup/teardown paths.
        // Backend lifecycle is exclusively controlled by the model screen
        // via manual user action (load/unload model buttons).
        // Killing the backend here would break the queue Worker's SSE stream.

        state.isRunning = false; state.progress = 0f; state.errorMessage = null
        state.generationStartTime = null
        coroutineScope.launch { pagerState.scrollToPage(0) }
        state.saveAllJob?.cancel()
    } catch (e: Exception) { Log.e("ModelRunScreen", "error", e) }
}

// ── Storage Permission ───────────────────────────────────────────
// Delegates to checkStoragePermission in ModelRunUtils.kt (same package)
