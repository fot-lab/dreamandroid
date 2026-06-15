package io.github.dreamandroid.local.ui.screens.model

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.Model
import io.github.dreamandroid.local.ui.theme.Motion

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ModelCard(
    model: Model,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onUpdateClick: () -> Unit = {},
) {
    val isDisabledInSelection = !model.isDownloaded && isSelectionMode

    val elevation by animateFloatAsState(
        targetValue = if (isSelected) 4f else 1f,
        animationSpec = Motion.springExpressiveSpatial(),
        label = "CardElevationAnimation",
    )

    val targetContainer = when {
        isSelected -> MaterialTheme.colorScheme.secondaryContainer
        isDisabledInSelection -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val backgroundColor by animateColorAsState(
        targetValue = targetContainer,
        animationSpec = tween(Motion.DurationMedium, easing = Motion.Standard),
        label = "CardBackgroundColorAnimation",
    )

    val primaryContent = when {
        isSelected -> MaterialTheme.colorScheme.onSecondaryContainer
        isDisabledInSelection -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val secondaryContent = when {
        isSelected -> MaterialTheme.colorScheme.onSecondaryContainer
        isDisabledInSelection -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = backgroundColor,
            contentColor = primaryContent,
        ),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = elevation.dp,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        // The clickable lives inside the card so its press/ripple indication is
        // clipped to the card's rounded shape. On the outer modifier the ripple
        // would render as a rectangle and show square corners on long-press.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (!isSelectionMode || model.isDownloaded) onClick()
                    },
                    onLongClick = {
                        if (model.isDownloaded && !isSelectionMode) onLongClick()
                    },
                ),
        ) {
            Badge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.model_type_generation),
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Normal,
                    color = primaryContent,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = model.description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = secondaryContent,
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        InfoChip(
                            icon = Icons.Default.SdStorage,
                            label = model.approximateSize,
                            color = secondaryContent,
                        )
                        InfoChip(
                            icon = Icons.Default.AspectRatio,
                            label = if (model.runOnCpu) {
                                "128~512"
                            } else {
                                "${model.generationSize}×${model.generationSize}"
                            },
                            color = secondaryContent,
                        )
                    }

                    when {
                        model.isDownloaded -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val statusColor =
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "downloaded",
                                        tint = statusColor,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    if (!model.needsUpgrade or isSelectionMode) {
                                        Text(
                                            text = stringResource(R.string.downloaded),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = statusColor,
                                        )
                                    }
                                }

                                if (model.needsUpgrade && !isSelectionMode) {
                                    AssistChip(
                                        onClick = onUpdateClick,
                                        label = { Text(stringResource(R.string.update)) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Update,
                                                contentDescription = null,
                                                modifier = Modifier.size(AssistChipDefaults.IconSize),
                                            )
                                        },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                            labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                            leadingIconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                        ),
                                        border = null,
                                    )
                                }
                            }
                        }

                        else -> {
                            InfoChip(
                                icon = Icons.Default.CloudDownload,
                                label = stringResource(R.string.download),
                                color = secondaryContent,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun InfoChip(icon: ImageVector, label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

@Composable
fun TabPageIndicator(pageCount: Int, currentPage: Int, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        repeat(pageCount) { index ->
            val isSelected = currentPage == index
            val sizeFloat by animateFloatAsState(
                targetValue = if (isSelected) 10f else 8f,
                animationSpec = Motion.springExpressiveSpatial(),
                label = "IndicatorSize",
            )
            val color by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                animationSpec = tween(Motion.DurationMedium),
                label = "IndicatorColor",
            )
            Box(
                modifier = Modifier
                    .size(sizeFloat.dp)
                    .background(
                        color = color,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCustomModelButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    AddModelOutlinedCard(
        label = stringResource(R.string.add_custom_model),
        onClick = onClick,
        modifier = modifier,
        accent = false,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCustomNpuModelButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    AddModelOutlinedCard(
        label = stringResource(R.string.add_custom_npu_model),
        onClick = onClick,
        modifier = modifier,
        accent = true,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddModelOutlinedCard(label: String, onClick: () -> Unit, accent: Boolean, modifier: Modifier = Modifier) {
    val accentColor = if (accent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    OutlinedCard(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = accentColor,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
internal fun DeleteConfirmDialog(selectedCount: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_model)) },
        text = { Text(stringResource(R.string.delete_confirm, selectedCount)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
