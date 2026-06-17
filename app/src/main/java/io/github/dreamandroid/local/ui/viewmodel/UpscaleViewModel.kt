package io.github.dreamandroid.local.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dreamandroid.local.BuildConfig
import io.github.dreamandroid.local.DreamAndroidApplication
import io.github.dreamandroid.local.service.backend.BackendService
import io.github.dreamandroid.local.utils.performUpscale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Upscale ViewModel extracted from UpscaleScreen (UILA-COMP-0001).
 *
 * Manages:
 * - Image selection (uri + bitmap)
 * - Upscale execution via BackendManager
 * - Upscaled result management (bitmap + temp file)
 * - Bitmap lifecycle (recycle on clear / dispose)
 * - Zoom/pan state (shared between input and output images)
 */
class UpscaleViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as DreamAndroidApplication
    val backendService: BackendService = app.backendService

    // ── Image Selection ───────────────────────────────────────
    var selectedImageUri by mutableStateOf<Uri?>(null)
    var selectedBitmap by mutableStateOf<Bitmap?>(null)

    // ── Upscaled Result ───────────────────────────────────────
    var upscaledImageUri by mutableStateOf<Uri?>(null)
    var upscaledBitmap by mutableStateOf<Bitmap?>(null)

    // ── Process State ─────────────────────────────────────────
    var isUpscaling by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var currentLog by mutableStateOf("")
    var tileProgress by mutableStateOf<Pair<Int, Int>?>(null)

    // ── Zoom/Pan ──────────────────────────────────────────────
    var sharedScale by mutableFloatStateOf(1f)
    var sharedOffsetX by mutableFloatStateOf(0f)
    var sharedOffsetY by mutableFloatStateOf(0f)

    // ── Image Picking ─────────────────────────────────────────

    fun onImageSelected(uri: Uri, context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }

                if (bitmap != null) {
                    val totalPixels = bitmap.width.toLong() * bitmap.height.toLong()
                    val maxPixels = 2048L * 2048L
                    val enforceMaxPixels = BuildConfig.FLAVOR == "filter"

                    if (enforceMaxPixels && totalPixels > maxPixels) {
                        withContext(Dispatchers.Main) {
                            errorMessage = "Image resolution too large: ${bitmap.width}x${bitmap.height}. Please select an image smaller than 2048x2048."
                        }
                    } else {
                        selectedImageUri = uri
                        selectedBitmap = bitmap
                        sharedScale = 1f
                        sharedOffsetX = 0f
                        sharedOffsetY = 0f
                    }
                }
            } catch (e: Exception) {
                Log.e("UpscaleVM", "Failed to load image", e)
                withContext(Dispatchers.Main) {
                    errorMessage = "Failed to load image: ${e.message}"
                }
            }
        }
    }

    fun clearSelectedImage() {
        selectedBitmap?.recycle()
        selectedBitmap = null
        selectedImageUri = null
        sharedScale = 1f
        sharedOffsetX = 0f
        sharedOffsetY = 0f
    }

    // ── Upscale Execution ─────────────────────────────────────

    fun startUpscale(
        context: android.content.Context,
        loadedUpscalerId: String?,
    ) {
        val bitmap = selectedBitmap ?: return
        val upscalerId = loadedUpscalerId ?: return

        tileProgress = null
        currentLog = ""
        isUpscaling = true

        viewModelScope.launch {
            try {
                // Recycle previous upscaled bitmap
                upscaledBitmap?.recycle()
                upscaledBitmap = null
                upscaledImageUri = null

                val resultBitmap = performUpscale(
                    context = context,
                    bitmap = bitmap,
                    upscalerId = upscalerId,
                )
                upscaledBitmap = resultBitmap

                withContext(Dispatchers.IO) {
                    try {
                        // Clean up previous temp files
                        context.cacheDir.listFiles { f ->
                            f.name.startsWith("upscaled_temp_")
                        }?.forEach { it.delete() }

                        val tempFile = File(
                            context.cacheDir,
                            "upscaled_temp_${System.currentTimeMillis()}.jpg",
                        )
                        tempFile.outputStream().use { out ->
                            resultBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        }
                        upscaledImageUri = Uri.fromFile(tempFile)
                    } catch (e: Exception) {
                        Log.e("UpscaleVM", "Failed to save temp file", e)
                    }
                }
            } catch (e: Exception) {
                errorMessage = "Upscale failed: ${e.message}"
            } finally {
                isUpscaling = false
            }
        }
    }

    // ── Temp File Cleanup on Entry ────────────────────────────

    fun cleanTempFiles(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.cacheDir.listFiles { file ->
                    file.name.startsWith("upscaled_temp_") && file.name.endsWith(".jpg")
                }?.forEach { file ->
                    if (file.delete()) {
                        Log.d("UpscaleVM", "Deleted temp file: ${file.name}")
                    }
                }
            } catch (e: Exception) {
                Log.e("UpscaleVM", "Failed to clean temp files", e)
            }
        }
    }

    fun dismissError() {
        errorMessage = null
    }

    override fun onCleared() {
        super.onCleared()
        selectedBitmap?.recycle()
        upscaledBitmap?.recycle()
        selectedBitmap = null
        upscaledBitmap = null
    }
}
