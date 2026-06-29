package io.github.dreamandroid.local.ui.screens.download

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.GenerationPreferences
import io.github.dreamandroid.local.data.ModelRepository
import io.github.dreamandroid.local.data.UpscalerRepository
import io.github.dreamandroid.local.data.db.download.DownloadDatabase
import io.github.dreamandroid.local.data.db.download.DownloadTaskEntity
import io.github.dreamandroid.local.service.DownloadManagerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Lightweight data class representing a model entry in the download manager list.
 */
data class DownloadableModel(
    val id: String,
    val name: String,
    val description: String,
    val modelType: String,       // "sd", "sdxl", "upscaler"
    val fileUrl: String,
    val sizeLabel: String,
    val isZip: Boolean,
    val isNpu: Boolean,
    val isDownloaded: Boolean,
)

/**
 * Download manager: shows all downloadable models grouped by type.
 *
 * - SDXL / SD / Upscaler sections with badge indicators
 * - Per-model download cards with progress
 * - Resume / Cancel support
 * - X close button + BackHandler
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagerScreen(
    onClose: () -> Unit,
    onModelStateChanged: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { DownloadDatabase.get(context) }
    val dao = remember { db.downloadTaskDao() }
    val modelRepo = remember { ModelRepository(context) }
    val upscalerRepo = remember { UpscalerRepository(context) }

    val tasks by dao.observeAll().collectAsState(initial = emptyList())
    val activeState by DownloadManagerService.activeState.collectAsState()

    BackHandler { onClose() }

    // ── Build downloadable model entries ──
    val entries = remember(modelRepo.models, upscalerRepo.upscalers, tasks) {
        val baseUrl = modelRepo.baseUrl
        val upscalerBaseUrl = upscalerRepo.baseUrl

        val sdxl = modelRepo.models
            .filter { it.isSdxl && !it.isCustom && it.fileUri.isNotEmpty() }
            .map { m -> downloadableModel(m, baseUrl, "sdxl") }

        val sd = modelRepo.models
            .filter { !it.isSdxl && !it.isCustom && it.fileUri.isNotEmpty() }
            .map { m -> downloadableModel(m, baseUrl, "sd") }

        val upscaler = upscalerRepo.upscalers
            .filter { it.fileUri.isNotEmpty() }
            .map { u ->
                DownloadableModel(
                    id = u.id, name = u.name, description = u.description,
                    modelType = "upscaler",
                    fileUrl = "${upscalerBaseUrl.removeSuffix("/")}/${u.fileUri}",
                    sizeLabel = "~200MB", isZip = false, isNpu = false,
                    isDownloaded = u.isDownloaded,
                )
            }

        listOf("sdxl" to sdxl, "sd" to sd, "upscaler" to upscaler)
            .filter { it.second.isNotEmpty() }
    }

    // ── Actions ──
    val startDownload: (DownloadableModel) -> Unit = { entry ->
        scope.launch(Dispatchers.IO) {
            val taskId = UUID.randomUUID().toString()
            dao.insert(
                DownloadTaskEntity(
                    id = taskId,
                    modelId = entry.id,
                    modelName = entry.name,
                    modelType = entry.modelType,
                    fileUrl = entry.fileUrl,
                    isZip = entry.isZip,
                    isNpu = entry.isNpu,
                    status = DownloadTaskEntity.STATUS_PENDING,
                )
            )
            withContext(Dispatchers.Main) {
                val intent = Intent(context, DownloadManagerService::class.java).apply {
                    action = DownloadManagerService.ACTION_START
                    putExtra(DownloadManagerService.EXTRA_TASK_ID, taskId)
                }
                context.startForegroundService(intent)
            }
        }
    }

    val sendCancel: (DownloadTaskEntity) -> Unit = { task ->
        context.startForegroundService(
            Intent(context, DownloadManagerService::class.java).apply {
                action = DownloadManagerService.ACTION_CANCEL
                putExtra(DownloadManagerService.EXTRA_TASK_ID, task.id)
            }
        )
    }

    val sendResume: (DownloadTaskEntity) -> Unit = { task ->
        context.startForegroundService(
            Intent(context, DownloadManagerService::class.java).apply {
                action = DownloadManagerService.ACTION_RESUME
                putExtra(DownloadManagerService.EXTRA_TASK_ID, task.id)
            }
        )
    }

    val sendPause: (DownloadTaskEntity) -> Unit = { task ->
        context.startForegroundService(
            Intent(context, DownloadManagerService::class.java).apply {
                action = DownloadManagerService.ACTION_PAUSE
                putExtra(DownloadManagerService.EXTRA_TASK_ID, task.id)
            }
        )
    }

    val sendStop: (DownloadTaskEntity) -> Unit = { task ->
        context.startForegroundService(
            Intent(context, DownloadManagerService::class.java).apply {
                action = DownloadManagerService.ACTION_STOP
                putExtra(DownloadManagerService.EXTRA_TASK_ID, task.id)
            }
        )
    }

    // Active task for TopAppBar controls
    val activeTask = tasks.find { it.status == DownloadTaskEntity.STATUS_DOWNLOADING }
    val pausedTask = tasks.find { it.status == DownloadTaskEntity.STATUS_PENDING && it.downloadedBytes > 0 }

    // Notify parent when a model finishes downloading
    LaunchedEffect(tasks) {
        tasks.filter { it.status == DownloadTaskEntity.STATUS_COMPLETED }.forEach { task ->
            onModelStateChanged(task.modelId)
        }
    }

    // ── UI ──
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.download_manager_title)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, stringResource(R.string.close))
                        }
                    },
                    actions = {
                        // ── Global download controls ──
                        // Active download → Pause + Stop
                        if (activeTask != null && activeState is DownloadManagerService.DownloadManagerState.Active) {
                            val s = activeState as DownloadManagerService.DownloadManagerState.Active
                            if (s.status == DownloadTaskEntity.STATUS_DOWNLOADING) {
                                IconButton(onClick = { sendPause(activeTask) }) {
                                    Icon(Icons.Default.Pause,
                                        contentDescription = stringResource(R.string.pause),
                                        tint = Color(0xFFFF9800),
                                    )
                                }
                                IconButton(onClick = { sendStop(activeTask) }) {
                                    Icon(Icons.Default.Stop,
                                        contentDescription = stringResource(R.string.stop),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                        // Paused task → Resume + Stop
                        if (pausedTask != null && activeTask == null) {
                            IconButton(onClick = { sendResume(pausedTask) }) {
                                Icon(Icons.Default.PlayArrow,
                                    contentDescription = stringResource(R.string.resume),
                                    tint = Color(0xFF4CAF50),
                                )
                            }
                            IconButton(onClick = { sendStop(pausedTask) }) {
                                Icon(Icons.Default.Stop,
                                    contentDescription = stringResource(R.string.stop),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                entries.forEach { (type, models) ->
                    item(key = "header_$type") {
                        SectionHeader(type = type, downloadedCount = models.count { it.isDownloaded }, totalCount = models.size)
                    }

                    items(models, key = { "model_${it.id}" }) { model ->
                        val task = tasks.find { it.modelId == model.id }
                        DownloadModelCard(
                            model = model,
                            task = task,
                            activeState = activeState,
                            onDownload = { startDownload(model) },
                            onCancel = { task?.let(sendCancel) },
                            onResume = { task?.let(sendResume) },
                        )
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

// ── Helpers ──

private fun downloadableModel(m: io.github.dreamandroid.local.data.Model, baseUrl: String, type: String) =
    DownloadableModel(
        id = m.id, name = m.name, description = m.description,
        modelType = type,
        fileUrl = "${baseUrl.removeSuffix("/")}/${m.fileUri}",
        sizeLabel = m.approximateSize,
        isZip = m.fileUri.endsWith(".zip"),
        isNpu = !m.runOnCpu,
        isDownloaded = m.isDownloaded,
    )

// ── Section Header ──

@Composable
private fun SectionHeader(type: String, downloadedCount: Int, totalCount: Int) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModelTypeBadge(type = type)
            Spacer(Modifier.width(8.dp))
            Text(
                text = typeLabel(type),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "$downloadedCount/$totalCount",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
    }
}

// ── Type Badge ──

@Composable
private fun ModelTypeBadge(type: String) {
    val (label, bgColor) = when (type) {
        "sdxl" -> "SDXL" to Color(0xFF7C4DFF)
        "sd" -> "SD" to Color(0xFF2196F3)
        "upscaler" -> "UP" to Color(0xFFFF9800)
        else -> type.uppercase() to Color.Gray
    }
    Surface(color = bgColor, shape = RoundedCornerShape(4.dp)) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun typeLabel(type: String): String = when (type) {
    "sdxl" -> "SDXL Models"
    "sd" -> "SD 1.5 Models"
    "upscaler" -> "Upscaler Models"
    else -> type
}

// ── Model Card ──

@Composable
private fun DownloadModelCard(
    model: DownloadableModel,
    task: DownloadTaskEntity?,
    activeState: DownloadManagerService.DownloadManagerState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onResume: () -> Unit,
) {
    val isThisActive = activeState is DownloadManagerService.DownloadManagerState.Active
            && activeState.modelId == model.id
    val isDone = model.isDownloaded

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isThisActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Row 1: Badge + Name + Size
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModelTypeBadge(type = model.modelType)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = model.sizeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Row 2: Description
            Spacer(Modifier.height(4.dp))
            Text(
                text = model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Row 3: Progress or Action
            Spacer(Modifier.height(8.dp))

            when {
                isDone -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null,
                            tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.downloaded),
                            style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50))
                    }
                }

                isThisActive && activeState is DownloadManagerService.DownloadManagerState.Active -> {
                    val state = activeState as DownloadManagerService.DownloadManagerState.Active
                    val progress = state.progress
                    val statusText = when (state.status) {
                        DownloadTaskEntity.STATUS_DOWNLOADING -> stringResource(
                            R.string.downloading_progress,
                            formatBytes(state.downloadedBytes),
                            formatBytes(state.totalBytes),
                        )
                        DownloadTaskEntity.STATUS_EXTRACTING -> stringResource(R.string.extracting)
                        else -> state.status
                    }

                    Column {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(statusText, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (state.status != DownloadTaskEntity.STATUS_EXTRACTING) {
                                TextButton(onClick = onCancel,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                    Text(stringResource(R.string.cancel).uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                // Paused / pending resume
                task != null && task.status == DownloadTaskEntity.STATUS_PENDING && task.downloadedBytes > 0 -> {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.download_paused, formatBytes(task.downloadedBytes)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            TextButton(onClick = onCancel,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                Text(stringResource(R.string.cancel).uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                            Spacer(Modifier.width(4.dp))
                            FilledTonalButton(onClick = onResume,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.resume), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                // Failed
                task != null && task.status == DownloadTaskEntity.STATUS_FAILED -> {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(task.errorMessage ?: stringResource(R.string.download_failed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        FilledTonalButton(onClick = onDownload,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.retry), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // Waiting in queue (pending but no bytes yet)
                task != null && task.status == DownloadTaskEntity.STATUS_PENDING -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.queued),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onCancel,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Text(stringResource(R.string.cancel).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                // Ready to download
                else -> {
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 6.dp)) {
                        Icon(Icons.Default.Download, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.download))
                    }
                }
            }
        }
    }
}

// ── Byte formatting ──

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.size - 1) {
        value /= 1024
        unitIndex++
    }
    return if (value >= 100 || unitIndex == 0) {
        "%.0f %s".format(value, units[unitIndex])
    } else {
        "%.1f %s".format(value, units[unitIndex])
    }
}
