package io.github.dreamandroid.local.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.Immutable
import io.github.dreamandroid.local.data.db.AppDatabase
import io.github.dreamandroid.local.data.db.TaskEntity
import io.github.dreamandroid.local.ui.screens.run.GenerationParameters
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Immutable
data class HistoryItem(
    val id: String,
    val modelId: String,
    val imageFile: File,
    val params: GenerationParameters,
    val timestamp: Long,
    val mode: GenerationMode,
    val upscalerId: String?,
) {
    companion object {
        fun fromEntity(filesDir: File, e: TaskEntity): HistoryItem {
            val imageFile = File(filesDir, e.imagePath ?: "")
            val mode = GenerationMode.fromString(e.mode)
            return HistoryItem(
                id = e.id,
                modelId = e.modelId,
                imageFile = imageFile,
                timestamp = e.timestamp,
                mode = mode,
                upscalerId = e.upscalerId,
                params = GenerationParameters(
                    steps = e.steps,
                    cfg = e.cfg,
                    seed = e.seed,
                    prompt = e.prompt,
                    negativePrompt = e.negativePrompt,
                    generationTime = e.generationTime,
                    width = e.width,
                    height = e.height,
                    runOnCpu = e.runOnCpu ?: false,
                    denoiseStrength = e.denoiseStrength ?: 0.6f,
                    useOpenCL = e.useOpenCL,
                    sampler = e.sampler,
                    mode = mode,
                ),
            )
        }
    }
}

class HistoryManager(private val context: Context) {

    private val dao = AppDatabase.get(context).taskDao()
    private val filesDir: File = context.filesDir

    private fun getHistoryDir(modelId: String): File {
        val dir = File(filesDir, "history/$modelId")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Save a generated image to the history store.
     *
     * **Write ordering (DB-first strategy):**
     * 1. Insert into Room DB first (Single Source of Truth)
     * 2. Write image file to disk
     * 3. If file write fails, roll back the Room record to prevent orphan DB rows
     *
     * **Callers MUST check the return value.**
     * A `null` return means the save failed (disk full, permission error, etc.)
     * and the task should be marked ERROR, NOT COMPLETED.
     *
     * @return [HistoryItem] on success, `null` on failure (DB or file write error)
     */
    suspend fun saveGeneratedImage(
        modelId: String,
        bitmap: Bitmap,
        params: GenerationParameters,
        mode: GenerationMode,
        upscalerId: String? = null,
    ): HistoryItem? = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val historyDir = getHistoryDir(modelId)

        val isUpscaled = upscalerId != null
        val ext = if (isUpscaled) "jpg" else "png"
        val relativePath = "history/$modelId/$timestamp.$ext"
        val imageFile = File(historyDir, "$timestamp.$ext")

        try {
            val taskId = UUID.randomUUID().toString()

            // Step 1: Insert into Room DB first (SSOT)
            val entity = TaskEntity(
                id = taskId,
                taskType = TaskEntity.TYPE_HISTORY,
                modelId = modelId,
                prompt = params.prompt,
                negativePrompt = params.negativePrompt,
                steps = params.steps,
                cfg = params.cfg,
                seed = params.seed,
                width = params.width,
                height = params.height,
                denoiseStrength = if (mode == GenerationMode.IMG2IMG || mode == GenerationMode.INPAINT) {
                    params.denoiseStrength
                } else {
                    null
                },
                useOpenCL = params.useOpenCL,
                scheduler = params.sampler,
                timestamp = timestamp,
                imagePath = relativePath,
                mode = mode.name,
                upscalerId = upscalerId,
                generationTime = params.generationTime,
                runOnCpu = params.runOnCpu,
            )
            dao.insert(entity)

            // Step 2: Write image file to disk
            try {
                imageFile.outputStream().use { out ->
                    if (isUpscaled) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    } else {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
            } catch (fileEx: Exception) {
                // File write failed → roll back Room record to prevent orphan DB rows
                Log.e("HistoryManager", "File write failed, rolling back DB record", fileEx)
                dao.deleteById(taskId)
                return@withContext null
            }

            HistoryItem.fromEntity(filesDir, entity)
        } catch (e: Exception) {
            // DB insert failed or other error → clean up any partial file
            Log.e("HistoryManager", "Failed to save image", e)
            if (imageFile.exists()) imageFile.delete()
            null
        }
    }

    suspend fun loadHistoryForModel(modelId: String): List<HistoryItem> = withContext(Dispatchers.IO) {
        try {
            val filter = HistoryFilter(modelIds = setOf(modelId))
            dao.queryOnce(filter.toSqlQuery())
                .map { HistoryItem.fromEntity(filesDir, it) }
        } catch (e: Exception) {
            Log.e("HistoryManager", "Failed to load history", e)
            emptyList()
        }
    }

    fun observe(filter: HistoryFilter): Flow<List<HistoryItem>> = dao.query(filter.toSqlQuery()).map { entities ->
        entities.map { HistoryItem.fromEntity(filesDir, it) }
    }

    fun observeKnownModelIds(): Flow<List<String>> = dao.observeHistoryKnownModelIds()
    fun observeKnownSamplers(): Flow<List<String>> = dao.observeHistoryKnownSamplers()
    fun observeKnownSizes(): Flow<List<String>> = dao.observeHistoryKnownSizes()

    suspend fun deleteHistoryItem(item: HistoryItem): Boolean = withContext(Dispatchers.IO) {
        try {
            dao.deleteById(item.id)
            if (item.imageFile.exists()) item.imageFile.delete()
            true
        } catch (e: Exception) {
            Log.e("HistoryManager", "Failed to delete history item", e)
            false
        }
    }

    suspend fun clearHistoryForModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            dao.deleteHistoryForModel(modelId)
            File(filesDir, "history/$modelId").deleteRecursively()
            true
        } catch (e: Exception) {
            Log.e("HistoryManager", "Failed to clear history", e)
            false
        }
    }
}
