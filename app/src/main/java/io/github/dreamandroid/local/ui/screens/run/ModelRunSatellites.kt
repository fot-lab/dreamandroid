package io.github.dreamandroid.local.ui.screens.run

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.DownloadProgress
import io.github.dreamandroid.local.data.UpscalerModel
import io.github.dreamandroid.local.ui.components.SmoothLinearWavyProgressIndicator

@Composable
fun UpscalerSelectDialog(
    upscalers: List<UpscalerModel>,
    selectedUpscalerId: String?,
    downloadingUpscalerId: String?,
    downloadProgress: DownloadProgress?,
    onDismiss: () -> Unit,
    onSelectUpscaler: (String) -> Unit,
    onConfirm: () -> Unit,
    onDownload: (UpscalerModel) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_upscaler_model)) },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(upscalers) { upscaler ->
                    UpscalerModelCard(
                        upscaler = upscaler,
                        isSelected = upscaler.id == selectedUpscalerId,
                        isDownloading = upscaler.id == downloadingUpscalerId,
                        downloadProgress = if (upscaler.id == downloadingUpscalerId) downloadProgress else null,
                        onSelect = { onSelectUpscaler(upscaler.id) },
                        onDownload = { onDownload(upscaler) },
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = onConfirm,
                    enabled = selectedUpscalerId != null,
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }
        },
    )
}

@Composable
fun UpscalerModelCard(
    upscaler: UpscalerModel,
    isSelected: Boolean,
    isDownloading: Boolean,
    downloadProgress: DownloadProgress?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = upscaler.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = upscaler.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                } else if (!upscaler.isDownloaded) {
                    FilledTonalButton(onClick = onDownload) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = stringResource(R.string.download),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.download))
                    }
                } else if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "selected",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Show progress bar when downloading
            if (isDownloading && downloadProgress != null) {
                SmoothLinearWavyProgressIndicator(
                    progress = downloadProgress.progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
fun PromptCountLabel(label: String, count: Int, max: Int, showCount: Boolean, overflow: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        if (showCount) {
            Spacer(Modifier.width(6.dp))
            Text("$count/$max")
        }
        if (overflow) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.Report,
                contentDescription = stringResource(R.string.prompt_token_overflow),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
