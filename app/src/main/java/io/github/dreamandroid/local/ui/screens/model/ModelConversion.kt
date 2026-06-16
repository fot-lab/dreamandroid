package io.github.dreamandroid.local.ui.screens.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.Immutable
import io.github.dreamandroid.local.data.Model
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class ExtractByteProgress(val extractedBytes: Long, val totalCompressedBytes: Long, val fraction: Float)

private class CountingInputStream(delegate: java.io.InputStream) : java.io.FilterInputStream(delegate) {
    @Volatile
    var bytesRead: Long = 0L
        private set

    override fun read(): Int {
        val b = `in`.read()
        if (b != -1) bytesRead++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = `in`.read(b, off, len)
        if (n > 0) bytesRead += n
        return n
    }
}

suspend fun extractNpuModel(
    context: Context,
    modelName: String,
    zipUri: Uri,
    onProgress: (String) -> Unit,
    onByteProgress: (extractedBytes: Long, totalCompressedBytes: Long, fraction: Float) -> Unit,
    onStart: () -> Unit,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) = withContext(Dispatchers.IO) {
    try {
        withContext(Dispatchers.Main) {
            onStart()
            onProgress(context.getString(io.github.dreamandroid.local.R.string.preparing_npu_model))
        }

        if (!Model.isQualcommDevice()) {
            withContext(Dispatchers.Main) {
                onError("Only Qualcomm devices are supported for custom NPU models")
            }
            return@withContext
        }

        val modelId = modelName.replace(" ", "")

        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }

        val modelDir = File(modelsDir, modelId)
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }
        modelDir.mkdirs()

        val totalCompressedBytes: Long = try {
            context.contentResolver.openAssetFileDescriptor(zipUri, "r")?.use { it.length }
                ?: -1L
        } catch (_: Exception) {
            -1L
        }

        withContext(Dispatchers.Main) {
            onProgress(context.getString(io.github.dreamandroid.local.R.string.extracting_zip_file))
        }

        val rawInputStream = context.contentResolver.openInputStream(zipUri)
            ?: throw Exception("Cannot open selected zip file")

        val countingStream = CountingInputStream(rawInputStream)
        val extractedBytesAtomic = AtomicLong(0L)

        coroutineScope {
            val progressJob = launch {
                while (isActive) {
                    delay(120L)
                    val fraction = if (totalCompressedBytes > 0) {
                        (countingStream.bytesRead.toFloat() / totalCompressedBytes)
                            .coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    onByteProgress(extractedBytesAtomic.get(), totalCompressedBytes, fraction)
                }
            }

            try {
                ZipInputStream(countingStream.buffered()).use { zipInputStream ->
                    var zipEntry = zipInputStream.nextEntry

                    while (zipEntry != null) {
                        if (!zipEntry.isDirectory) {
                            val fileName = zipEntry.name.substringAfterLast('/')

                            if (fileName.isNotEmpty() &&
                                !fileName.startsWith(".") &&
                                !fileName.startsWith("__MACOSX")
                            ) {
                                val outputFile = File(modelDir, fileName)

                                outputFile.outputStream().buffered().use { outputStream ->
                                    val tracking = object : OutputStream() {
                                        override fun write(b: Int) {
                                            outputStream.write(b)
                                            extractedBytesAtomic.incrementAndGet()
                                        }
                                        override fun write(b: ByteArray, off: Int, len: Int) {
                                            outputStream.write(b, off, len)
                                            extractedBytesAtomic.addAndGet(len.toLong())
                                        }
                                    }
                                    zipInputStream.copyTo(tracking)
                                }
                            }
                        }
                        zipEntry = zipInputStream.nextEntry
                    }
                }
            } finally {
                progressJob.cancel()
            }
        }

        onByteProgress(extractedBytesAtomic.get(), totalCompressedBytes, 1f)

        if (modelId != "upscaler_anime" && modelId != "upscaler_realistic") {
            val npuCustomFile = File(modelDir, "npucustom")
            npuCustomFile.createNewFile()
        }

        withContext(Dispatchers.Main) {
            onSuccess()
        }
    } catch (e: Exception) {
        Log.e("NpuModelExtract", "Extraction failed", e)

        val modelId = modelName.replace(" ", "")
        val modelDir = File(File(context.filesDir, "models"), modelId)
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }

        withContext(Dispatchers.Main) {
            onError("Extraction failed: ${e.message}")
        }
    }
}

suspend fun importEmbedding(context: Context, fileUri: Uri, onSuccess: () -> Unit, onError: (String) -> Unit) = withContext(Dispatchers.IO) {
    try {
        val embeddingsDir = File(context.filesDir, "embeddings")
        if (!embeddingsDir.exists()) {
            embeddingsDir.mkdirs()
        }

        val fileName =
            context.contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            } ?: "embedding_${System.currentTimeMillis()}.safetensors"

        // Validate file extension
        if (!fileName.endsWith(".safetensors", ignoreCase = true)) {
            withContext(Dispatchers.Main) {
                onError(context.getString(io.github.dreamandroid.local.R.string.only_safetensors_supported))
            }
            return@withContext
        }

        val targetFile = File(embeddingsDir, fileName)

        context.contentResolver.openInputStream(fileUri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        withContext(Dispatchers.Main) {
            onSuccess()
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            onError(e.message ?: "Unknown error")
        }
    }
}

suspend fun convertCustomModel(
    context: Context,
    modelName: String,
    fileUri: Uri,
    clipSkip: Int,
    loraFiles: List<LoRAFile>,
    onProgress: (String) -> Unit,
    onStart: () -> Unit,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) = withContext(Dispatchers.IO) {
    try {
        withContext(Dispatchers.Main) {
            onStart()
            onProgress(context.getString(io.github.dreamandroid.local.R.string.preparing_model))
        }

        val modelId = modelName.replace(" ", "")

        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }

        val modelDir = File(modelsDir, modelId)
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }
        modelDir.mkdirs()

        withContext(Dispatchers.Main) {
            onProgress(context.getString(io.github.dreamandroid.local.R.string.copying_model_file))
        }

        val inputStream = context.contentResolver.openInputStream(fileUri)
            ?: throw Exception("Cannot open selected file")
        val modelFile = File(modelDir, "model.safetensors")

        inputStream.use { input ->
            modelFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        withContext(Dispatchers.Main) {
            onProgress(context.getString(io.github.dreamandroid.local.R.string.copying_lora_files))
        }

        loraFiles.forEachIndexed { index, loraFile ->
            val loraInputStream = context.contentResolver.openInputStream(loraFile.uri)
                ?: throw Exception("Cannot open LoRA file ${index + 1}")
            val loraFileTarget = File(modelDir, "lora.${index + 1}.safetensors")
            val loraWeightFile = File(modelDir, "lora.${index + 1}.weight")

            loraInputStream.use { input ->
                loraFileTarget.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            loraWeightFile.writeText(loraFile.weight.toString())
        }

        withContext(Dispatchers.Main) {
            onProgress(context.getString(io.github.dreamandroid.local.R.string.copying_base_files))
        }

        fun copyAssetsRecursively(assetPath: String, targetDir: File) {
            val assetManager = context.assets
            val assets = assetManager.list(assetPath) ?: emptyArray()

            if (assets.isEmpty()) {
                try {
                    val assetInputStream = assetManager.open(assetPath)
                    val fileName = assetPath.substringAfterLast("/")
                    val targetFile = File(targetDir, fileName)

                    assetInputStream.use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("ModelConvert", "Could not copy asset: $assetPath", e)
                }
            } else {
                for (asset in assets) {
                    val subAssetPath = "$assetPath/$asset"
                    val subAssets = assetManager.list(subAssetPath) ?: emptyArray()

                    if (subAssets.isEmpty()) {
                        try {
                            val assetInputStream = assetManager.open(subAssetPath)
                            val targetFile = File(targetDir, asset)

                            assetInputStream.use { input ->
                                targetFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(
                                "ModelConvert",
                                "Could not copy file: $subAssetPath",
                                e,
                            )
                        }
                    } else {
                        val subTargetDir = File(targetDir, asset)
                        subTargetDir.mkdirs()
                        copyAssetsRecursively(subAssetPath, subTargetDir)
                    }
                }
            }
        }

        copyAssetsRecursively("cvtbase", modelDir)

        withContext(Dispatchers.Main) {
            onProgress(context.getString(io.github.dreamandroid.local.R.string.converting_model))
        }

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val executableFile = File(nativeDir, "libstable_diffusion_core.so")

        if (!executableFile.exists()) {
            throw Exception("Executable not found: ${executableFile.absolutePath}")
        }

        var command = listOf(
            executableFile.absolutePath,
            "--convert",
            modelDir.absolutePath,
        )
        val clipSourceFile =
            File(modelDir, if (clipSkip == 2) "clip_skip_2.mnn" else "clip_skip_1.mnn")
        val clipTargetFile = File(modelDir, "clip_v2.mnn")
        clipSourceFile.copyTo(clipTargetFile, overwrite = true)
        if (clipSkip == 2) {
            command += listOf("--clip_skip_2")
        }
        val env = mutableMapOf<String, String>()
        val systemLibPaths = listOf(
            nativeDir,
            "/system/lib64",
            "/vendor/lib64",
            "/vendor/lib64/egl",
        ).joinToString(":")

        env["LD_LIBRARY_PATH"] = systemLibPaths
        env["DSP_LIBRARY_PATH"] = nativeDir

        val processBuilder = ProcessBuilder(command).apply {
            directory(File(nativeDir))
            redirectErrorStream(true)
            environment().putAll(env)
        }

        val process = processBuilder.start()

        process.inputStream.bufferedReader().use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                Log.i("ModelConvert", "Convert: $line")
                withContext(Dispatchers.Main) {
                    onProgress("Converting: $line")
                }
            }
        }

        val exitCode = process.waitFor()
        Log.i("ModelConvert", "Conversion process exited with code: $exitCode")

        val finishedFile = File(modelDir, "finished")
        if (finishedFile.exists()) {
            modelFile.delete()
            val clipSkip1File = File(modelDir, "clip_skip_1.mnn")
            if (clipSkip1File.exists()) {
                clipSkip1File.delete()
            }
            val clipSkip2File = File(modelDir, "clip_skip_2.mnn")
            if (clipSkip2File.exists()) {
                clipSkip2File.delete()
            }

            loraFiles.forEachIndexed { index, _ ->
                val loraFile = File(modelDir, "lora.${index + 1}.safetensors")
                val loraWeightFile = File(modelDir, "lora.${index + 1}.weight")
                if (loraFile.exists()) {
                    loraFile.delete()
                }
                if (loraWeightFile.exists()) {
                    loraWeightFile.delete()
                }
            }

            withContext(Dispatchers.Main) {
                onSuccess()
            }
        } else {
            modelDir.deleteRecursively()
            withContext(Dispatchers.Main) {
                onError("Model conversion failed: Please use SD1.5 safetensors model")
            }
        }
    } catch (e: Exception) {
        Log.e("ModelConvert", "Conversion failed", e)

        val modelId = modelName.replace(" ", "")
        val modelDir = File(File(context.filesDir, "models"), modelId)
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }

        withContext(Dispatchers.Main) {
            onError("Conversion failed: ${e.message}")
        }
    }
}
