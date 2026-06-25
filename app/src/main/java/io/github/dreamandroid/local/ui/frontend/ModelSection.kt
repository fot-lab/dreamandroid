package io.github.dreamandroid.local.ui.frontend

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.*
import io.github.dreamandroid.local.service.ModelDownloadService
import io.github.dreamandroid.local.service.backend.BackendManager
import io.github.dreamandroid.local.ui.screens.model.ExtractByteProgress
import androidx.core.content.edit
import kotlin.math.roundToInt

// =========== Importing model state ===========

data class ImportingModelState(
    val modelId: String,
    val modelName: String,
    val isNpu: Boolean,
    val progressText: String,
    val byteProgress: ExtractByteProgress?,
)

// =========== Models Tab ===========

@Composable
fun ModelListTab(
    loadedModelId: String?,
    loadedModelType: BackendManager.Mode?,
    isModelLoaded: Boolean,
    isUpscaleModelLoaded: Boolean,
    onLoadModel: (String) -> Unit,
    modelRepository: ModelRepository,
    refreshVersion: Int,
    importingModels: List<ImportingModelState> = emptyList(),
    onLoadUpscaleModel: (String) -> Unit = {},
    onUnloadUpscaleModel: () -> Unit = {},
    persistedUpscalerId: String? = null,
    // ── Multi-selection ──
    modelViewSelectedModelIds: List<String> = emptyList(),
    modelViewOnToggleModelSelection: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val upscalerRepository = remember(refreshVersion) { UpscalerRepository(context) }
    val downloadState by ModelDownloadService.downloadState.collectAsState()

    // Only show custom (imported) models
    val customModels = remember(modelRepository.models, refreshVersion) {
        modelRepository.models.filter { it.isCustom }
    }

    val downloadedUpscalers = remember(upscalerRepository.upscalers) {
        upscalerRepository.upscalers.filter { it.isDownloaded }
    }

    val hasAnyContent = customModels.isNotEmpty() || importingModels.isNotEmpty() ||
        downloadedUpscalers.isNotEmpty()

    if (!hasAnyContent) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Default.Memory,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                Text(
                    stringResource(R.string.select_model_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            importingModels.forEach { imp ->
                item(key = "importing_${imp.modelId}") {
                    ImportingModelCard(state = imp)
                }
            }

            items(
                items = customModels,
                key = { "${it.id}_$refreshVersion" },
            ) { model ->
                ModelSelectCard(
                    model = model,
                    isActive = isModelLoaded && loadedModelId == model.id,
                    isChecked = model.id in modelViewSelectedModelIds,
                    onToggle = { modelViewOnToggleModelSelection(model.id) },
                )
            }

            // ---- Upscale Models Section (downloaded/imported only) ----
            if (downloadedUpscalers.isNotEmpty()) {
                item(key = "upscale_header") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        stringResource(R.string.upscale_models_section),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                items(downloadedUpscalers, key = { "upscaler_${it.id}" }) { upscaler ->
                    val isThisUpscalerLoaded = loadedModelType == BackendManager.Mode.Upscaler &&
                        loadedModelId == upscaler.id

                    UpscaleModelCardInline(
                        upscaler = upscaler,
                        isLoaded = isThisUpscalerLoaded,
                        isSelected = persistedUpscalerId == upscaler.id && !isThisUpscalerLoaded,
                        onSelect = {
                            context.getSharedPreferences("upscaler_prefs", android.content.Context.MODE_PRIVATE).edit {
                                putString("upscaler_standalone_selected_upscaler", upscaler.id)
                            }
                        },
                        onLoad = { onLoadUpscaleModel(upscaler.id) },
                        onUnload = { onUnloadUpscaleModel() },
                    )
                }
            }
        }
    }
}

@Composable
private fun UpscaleModelCardInline(
    upscaler: UpscalerModel,
    isLoaded: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isLoaded -> MaterialTheme.colorScheme.primaryContainer
                isSelected -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surface
            },
        ),
        border = when {
            isLoaded -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            isSelected -> BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
            else -> null
        },
    ) {
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
                if (isLoaded) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.model_loaded),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (isLoaded) {
                Button(onClick = onUnload) {
                    Text(stringResource(R.string.unload_upscale_model))
                }
            } else {
                Button(onClick = onLoad) {
                    Text(stringResource(R.string.load_upscale_model))
                }
            }
        }
    }
}

@Composable
private fun ImportingModelCard(state: ImportingModelState) {
    val fraction = state.byteProgress?.fraction ?: 0f
    val bytesProgress = state.byteProgress

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Badge(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.model_type_generation),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.modelName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.progressText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (bytesProgress != null) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${(fraction * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.5.dp,
            )
        }
    }
}

@Composable
private fun ModelSelectCard(
    model: Model,
    isActive: Boolean,
    isChecked: Boolean,
    onToggle: () -> Unit,
) {
    val targetContainer = when {
        isActive -> MaterialTheme.colorScheme.primaryContainer
        isChecked -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val backgroundColor by animateColorAsState(
        targetValue = targetContainer,
        animationSpec = tween(300),
        label = "CardBg",
    )

    ElevatedCard(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = backgroundColor),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Badge(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.model_type_generation),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (isActive) {
                Spacer(Modifier.width(8.dp))
                Badge(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Text(
                        text = stringResource(R.string.model_running),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Checkbox(
                checked = isChecked,
                onCheckedChange = { onToggle() },
            )
        }
    }
}
