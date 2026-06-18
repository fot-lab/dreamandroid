package io.github.dreamandroid.local.ui.screens.run

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import io.github.dreamandroid.local.data.GenerationMode

// Prompt undo/redo: cap on stored steps, and the window within which continuous
// typing collapses into a single step.
const val HISTORY_LIMIT = 100
const val HISTORY_COALESCE_MS = 600L

fun checkStoragePermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    true // Android 10
} else {
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    ) == PackageManager.PERMISSION_GRANTED
}

/**
 * For SDXL with a non-1:1 aspectRatio, returns the centered (target_w, target_h)
 * region inside the 1024x1024 generation canvas. The longest side is forced to
 * canvasMax (1024), the shortest side is scaled by the ratio and aligned down to
 * a multiple of 8. Returns null in all other cases (non-SDXL, 1:1, malformed),
 * meaning "no padding, use canvas size directly."
 */
fun computeAspectTargetSize(isSdxl: Boolean, aspectRatio: String, canvasMax: Int = 1024): Pair<Int, Int>? {
    if (!isSdxl) return null
    val parts = aspectRatio.split(":")
    if (parts.size != 2) return null
    val rw = parts[0].toIntOrNull() ?: return null
    val rh = parts[1].toIntOrNull() ?: return null
    if (rw <= 0 || rh <= 0 || rw == rh) return null
    return if (rw >= rh) {
        val th = ((canvasMax.toDouble() * rh / rw).toInt() / 8 * 8).coerceAtLeast(8)
        Pair(canvasMax, th)
    } else {
        val tw = ((canvasMax.toDouble() * rw / rh).toInt() / 8 * 8).coerceAtLeast(8)
        Pair(tw, canvasMax)
    }
}

/**
 * GCD-reduces (width, height) into a "W:H" aspect-ratio string.
 * Used by reproduce/import paths to recover an aspect from a recorded result size.
 */
fun inferAspectRatioString(width: Int, height: Int): String {
    if (width <= 0 || height <= 0) return "1:1"
    var a = width
    var b = height
    while (b != 0) {
        val t = b
        b = a % b
        a = t
    }
    return "${width / a}:${height / a}"
}

/**
 * Pads `src` (already at targetW x targetH) into a canvas of size canvasW x canvasH
 * with a centered placement and black borders. If src already matches canvas size,
 * returns the source unchanged.
 */
fun padBitmapToCanvas(src: Bitmap, canvasW: Int, canvasH: Int): Bitmap {
    if (src.width == canvasW && src.height == canvasH) return src
    val out = createBitmap(canvasW, canvasH)
    val canvas = Canvas(out)
    canvas.drawColor(Color.BLACK)
    val left = ((canvasW - src.width) / 2).toFloat()
    val top = ((canvasH - src.height) / 2).toFloat()
    canvas.drawBitmap(src, left, top, null)
    return out
}

@Immutable
data class GenerationParameters(
    val steps: Int,
    val cfgScale: Float,
    val seed: Long?,
    val prompt: String,
    val negativePrompt: String,
    val generationTime: String?,
    val width: Int,
    val height: Int,
    val runOnCpu: Boolean,
    val denoisingStrength: Float = 0.6f,
    val useOpenCL: Boolean = false,
    val sampler: String = "dpm",
    val mode: GenerationMode = GenerationMode.UNKNOWN,
)
