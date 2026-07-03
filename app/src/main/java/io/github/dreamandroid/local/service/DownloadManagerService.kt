package io.github.dreamandroid.local.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.Model
import io.github.dreamandroid.local.data.db.download.DownloadDatabase
import io.github.dreamandroid.local.data.db.download.DownloadTaskDao
import io.github.dreamandroid.local.data.db.download.DownloadTaskEntity
import io.github.dreamandroid.local.service.http.HttpClientProvider
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Download Manager Service with resume support.
 *
 * Features:
 * - HTTP Range-based resume (断点续传)
 * - Room DB persistence for download state
 * - Sequential queue (one download at a time)
 * - Clean task cancellation with temp file removal
 * - Foreground notification with progress
 */
class DownloadManagerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var currentJob: Job? = null

    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    private val client: OkHttpClient by lazy { HttpClientProvider.createForDownload() }

    private lateinit var db: DownloadDatabase

    companion object {
        private const val TAG = "DownloadManagerSvc"
        private const val CHANNEL_ID = "download_manager_channel"
        private const val NOTIFICATION_ID = 2002

        private val _activeState = MutableStateFlow<DownloadManagerState>(DownloadManagerState.Idle)
        val activeState: StateFlow<DownloadManagerState> = _activeState

        const val ACTION_START = "action_dm_start"
        const val ACTION_CANCEL = "action_dm_cancel"
        const val ACTION_RESUME = "action_dm_resume"
        const val ACTION_PAUSE = "action_dm_pause"
        const val ACTION_STOP = "action_dm_stop"

        const val EXTRA_TASK_ID = "task_id"
    }

    sealed class DownloadManagerState {
        object Idle : DownloadManagerState()
        data class Active(
            val taskId: String,
            val modelId: String,
            val modelName: String,
            val progress: Float,
            val downloadedBytes: Long,
            val totalBytes: Long,
            val status: String,
        ) : DownloadManagerState()
    }

    override fun onCreate() {
        super.onCreate()
        db = DownloadDatabase.get(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return START_NOT_STICKY
                startForeground(NOTIFICATION_ID, createNotification("Preparing...", 0f))
                processQueue()
            }
            ACTION_RESUME -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return START_NOT_STICKY
                startForeground(NOTIFICATION_ID, createNotification("Resuming...", 0f))
                processQueue()
            }
            ACTION_PAUSE -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return START_NOT_STICKY
                pauseTask(taskId)
            }
            ACTION_STOP -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return START_NOT_STICKY
                stopTask(taskId)
            }
            ACTION_CANCEL -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return START_NOT_STICKY
                cancelTask(taskId)
            }
        }
        return START_NOT_STICKY
    }

    private fun processQueue() {
        if (currentJob?.isActive == true) return // Already processing

        currentJob = serviceScope.launch {
            try {
                // Find the first PENDING task
                val pending = db.downloadTaskDao().getByStatus(DownloadTaskEntity.STATUS_PENDING)
                if (pending.isEmpty()) {
                    stopServiceIfIdle()
                    return@launch
                }

                val task = pending.first()
                executeDownload(task)
                // After completion, process next
                processQueue()
            } catch (e: Exception) {
                Log.e(TAG, "Queue processing failed", e)
                stopServiceIfIdle()
            }
        }
    }

    private suspend fun executeDownload(task: DownloadTaskEntity) {
        val dao = db.downloadTaskDao()
        var tempFile: File? = null
        var extractTempDir: File? = null

        try {
            dao.updateStatus(task.id, DownloadTaskEntity.STATUS_DOWNLOADING)

            val tempDir = File(cacheDir, "dm_temp")
            if (!tempDir.exists()) tempDir.mkdirs()

            val resumeBytes = task.downloadedBytes
            tempFile = File(tempDir, "${task.id}.tmp")

            if (resumeBytes > 0 && tempFile.exists() && tempFile.length() >= resumeBytes) {
                Log.d(TAG, "Resuming ${task.modelName} from byte $resumeBytes")
            } else if (resumeBytes > 0) {
                // Temp file missing/corrupt, restart
                Log.w(TAG, "Temp file mismatch for ${task.modelName}, restarting from 0")
                tempFile.delete()
                dao.updateProgress(
                    task.id,
                    DownloadTaskEntity.STATUS_DOWNLOADING,
                    0, task.totalBytes,
                )
            }

            emitState(task, 0f, 0, task.totalBytes, DownloadTaskEntity.STATUS_DOWNLOADING)
            downloadFileWithResume(task, tempFile, dao)

            // Download complete — extract if zip
            when (task.modelType) {
                "sd", "sdxl" -> {
                    if (task.isZip) {
                        dao.updateStatus(task.id, DownloadTaskEntity.STATUS_EXTRACTING)
                        emitState(task, 1f, tempFile.length(), tempFile.length(), DownloadTaskEntity.STATUS_EXTRACTING)
                        updateNotification(task.modelName, 1f, extracting = true)

                        val modelDir = File(Model.getModelsDir(this), task.modelId)
                        if (modelDir.exists()) modelDir.deleteRecursively()
                        modelDir.mkdirs()

                        extractTempDir = File(tempDir, "${task.id}_extract")
                        extractTempDir.mkdirs()
                        unzipFile(tempFile, extractTempDir)

                        extractTempDir.listFiles()?.forEach { file ->
                            file.renameTo(File(modelDir, file.name))
                        }
                        extractTempDir.delete()
                        extractTempDir = null

                        if (task.isNpu) {
                            File(modelDir, "v3").createNewFile()
                        }
                    }
                }
                "upscaler" -> {
                    val upscalerDir = File(Model.getModelsDir(this), task.modelId).apply {
                        if (!exists()) mkdirs()
                    }
                    val targetFile = File(upscalerDir, "upscaler.bin")
                    if (targetFile.exists()) targetFile.delete()
                    tempFile.renameTo(targetFile)
                }
            }

            // Cleanup
            tempFile.delete()
            tempFile = null

            dao.updateStatus(task.id, DownloadTaskEntity.STATUS_COMPLETED)
            emitState(task, 1f, task.totalBytes, task.totalBytes, DownloadTaskEntity.STATUS_COMPLETED)
            updateNotification(task.modelName, 1f, complete = true)

            kotlinx.coroutines.delay(2000)

        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancelled — keep temp file for resume
            dao.updateStatus(task.id, DownloadTaskEntity.STATUS_PENDING)
            emitState(task, 0f, task.downloadedBytes, task.totalBytes, DownloadTaskEntity.STATUS_PENDING)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${task.modelName}", e)
            tempFile?.delete()
            extractTempDir?.deleteRecursively()
            dao.updateStatus(task.id, DownloadTaskEntity.STATUS_FAILED, e.message ?: "Unknown error")
            emitState(task, 0f, task.downloadedBytes, task.totalBytes, DownloadTaskEntity.STATUS_FAILED)
            updateNotification(task.modelName, 0f, error = e.message)
        }
    }

    private suspend fun downloadFileWithResume(
        task: DownloadTaskEntity,
        destFile: File,
        dao: DownloadTaskDao,
    ) = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(task.fileUrl)

        val resumeOffset = if (destFile.exists()) destFile.length() else 0L
        if (resumeOffset > 0) {
            requestBuilder.header("Range", "bytes=$resumeOffset-")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val isPartial = response.code == 206
            val isFull = response.code == 200

            if (!isPartial && !isFull) {
                throw Exception("HTTP ${response.code}")
            }

            val body = response.body ?: throw Exception("Response body is null")
            val totalFromServer = body.contentLength()
            val actualTotal = if (isPartial) {
                resumeOffset + totalFromServer
            } else {
                totalFromServer
            }

            var downloadedBytes = if (isPartial) resumeOffset else 0L
            var lastPersistTime = 0L

            FileOutputStream(destFile, isPartial).buffered().use { output ->
                body.byteStream().buffered().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var bytes: Int
                    while (input.read(buffer).also { bytes = it } != -1) {
                        output.write(buffer, 0, bytes)
                        downloadedBytes += bytes

                        val now = System.currentTimeMillis()
                        if (now - lastPersistTime >= 2000) {
                            lastPersistTime = now
                            val progress = if (actualTotal > 0) downloadedBytes.toFloat() / actualTotal else 0f
                            dao.updateProgress(task.id, DownloadTaskEntity.STATUS_DOWNLOADING, downloadedBytes, actualTotal)
                            emitState(task, progress, downloadedBytes, actualTotal, DownloadTaskEntity.STATUS_DOWNLOADING)
                            updateNotification(task.modelName, progress)
                        }
                    }
                }
            }

            // Final persist
            val finalProgress = if (actualTotal > 0) downloadedBytes.toFloat() / actualTotal else 1f
            dao.updateProgress(task.id, DownloadTaskEntity.STATUS_DOWNLOADING, downloadedBytes, actualTotal)
            emitState(task, finalProgress, downloadedBytes, actualTotal, DownloadTaskEntity.STATUS_DOWNLOADING)
        }
    }

    private suspend fun unzipFile(zipFile: File, destDir: File) = withContext(Dispatchers.IO) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val fileName = entry.name.substringAfterLast('/')
                    if (fileName.isNotEmpty() && !fileName.startsWith(".") && !fileName.startsWith("__MACOSX")) {
                        File(destDir, fileName).outputStream().buffered().use { output ->
                            zis.copyTo(output)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Pause the active download — cancel the coroutine job but KEEP the temp
     * file and partial progress in Room so the user can resume later.
     */
    private fun pauseTask(taskId: String) {
        currentJob?.cancel()
        currentJob = null

        serviceScope.launch {
            val dao = db.downloadTaskDao()
            val task = dao.getById(taskId) ?: return@launch

            // Mark as PENDING (with preserved downloadedBytes for resume)
            dao.updateStatus(
                taskId,
                DownloadTaskEntity.STATUS_PENDING,
            )
            emitState(task, 0f, task.downloadedBytes, task.totalBytes, DownloadTaskEntity.STATUS_PENDING)
            updateNotification(task.modelName, 0f)
            stopServiceIfIdle()
        }
    }

    /**
     * Stop and fully clean a task — cancels the job, deletes temp files,
     * and removes the Room record entirely.
     */
    private fun stopTask(taskId: String) {
        currentJob?.cancel()
        currentJob = null

        serviceScope.launch {
            val dao = db.downloadTaskDao()
            val task = dao.getById(taskId) ?: return@launch

            // Clean temp file
            val tempFile = File(cacheDir, "dm_temp/${task.id}.tmp")
            if (tempFile.exists()) tempFile.delete()

            // Clean extract temp dir
            val extractDir = File(cacheDir, "dm_temp/${task.id}_extract")
            if (extractDir.exists()) extractDir.deleteRecursively()

            dao.delete(taskId)
            emitState(task, 0f, 0, task.totalBytes, DownloadTaskEntity.STATUS_CANCELLED)
            stopServiceIfIdle()
        }
    }

    private fun cancelTask(taskId: String) {
        currentJob?.cancel()
        currentJob = null

        serviceScope.launch {
            val dao = db.downloadTaskDao()
            val task = dao.getById(taskId) ?: return@launch

            // Clean temp file
            val tempFile = File(cacheDir, "dm_temp/${task.id}.tmp")
            if (tempFile.exists()) tempFile.delete()

            // Clean extract temp dir
            val extractDir = File(cacheDir, "dm_temp/${task.id}_extract")
            if (extractDir.exists()) extractDir.deleteRecursively()

            dao.delete(taskId)
            emitState(task, 0f, 0, task.totalBytes, DownloadTaskEntity.STATUS_CANCELLED)
            stopServiceIfIdle()
        }
    }

    private fun stopServiceIfIdle() {
        serviceScope.launch {
            val pending = db.downloadTaskDao().countByStatus(DownloadTaskEntity.STATUS_PENDING)
            if (pending == 0) {
                _activeState.value = DownloadManagerState.Idle
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun emitState(
        task: DownloadTaskEntity,
        progress: Float,
        downloadedBytes: Long,
        totalBytes: Long,
        status: String,
    ) {
        _activeState.value = DownloadManagerState.Active(
            taskId = task.id,
            modelId = task.modelId,
            modelName = task.modelName,
            progress = progress,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            status = status,
        )
    }

    // ── Notification ──

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_manager_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.download_manager_channel_desc)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(
        title: String,
        progress: Float,
        extracting: Boolean = false,
    ): android.app.Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, (progress * 100).toInt(), extracting)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(
        modelName: String,
        progress: Float,
        complete: Boolean = false,
        error: String? = null,
        extracting: Boolean = false,
    ) {
        val notification = when {
            complete -> NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.download_complete))
                .setContentText(modelName)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(false)
                .build()
            error != null -> NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.download_failed))
                .setContentText(error)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setOngoing(false)
                .build()
            else -> createNotification(
                if (extracting) getString(R.string.extracting_model, modelName)
                else getString(R.string.downloading_model, modelName),
                progress,
                extracting,
            )
        }
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
