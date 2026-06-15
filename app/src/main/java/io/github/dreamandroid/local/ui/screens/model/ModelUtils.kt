package io.github.dreamandroid.local.ui.screens.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.text.DecimalFormat
import java.util.Locale

data class LoRAFile(val uri: Uri, val weight: Float = 1.0f)

fun getCleanFileName(uri: Uri): String {
    val fileName = uri.lastPathSegment ?: "Unknown file"
    return if (fileName.startsWith("primary:")) {
        fileName.removePrefix("primary:")
    } else {
        fileName
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

fun formatFileSize(size: Long): String {
    val df = DecimalFormat("#.##")
    return when {
        size < 1024 -> "${size}B"
        size < 1024 * 1024 -> "${df.format(size / 1024.0)}KB"
        size < 1024 * 1024 * 1024 -> "${df.format(size / (1024.0 * 1024.0))}MB"
        else -> "${df.format(size / (1024.0 * 1024.0 * 1024.0))}GB"
    }
}

fun getFileNameFromUri(context: Context, uri: Uri): String? = try {
    when (uri.scheme) {
        "content" -> {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) {
                    cursor.getString(nameIndex)
                } else {
                    null
                }
            }
        }

        "file" -> {
            uri.lastPathSegment
        }

        else -> {
            DocumentFile.fromSingleUri(context, uri)?.name
        }
    }
} catch (e: Exception) {
    Log.e("GetFileName", "Get file name from uri failed", e)
    null
}
