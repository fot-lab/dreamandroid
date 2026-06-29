package io.github.dreamandroid.local.ui.screens

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.BatchGroupDisplay
import io.github.dreamandroid.local.data.GenerationTask
import io.github.dreamandroid.local.data.TaskStatus

private const val TAG_QUEUE = "QueueScreenDbg"

@Composable
fun QueueScreen(
    tasks: List<GenerationTask>,
    batchGroups: List<BatchGroupDisplay>,
    processingActive: Boolean,
    onRemoveTask: (String) -> Unit,
    onRemoveBatch: (String) -> Unit,
    onSaveInfo: (GenerationTask) -> Unit = {},
    // ── Queue selection mode ──────────────────────────────────
    queueIsSelectionMode: Boolean = false,
    queueSelectedBatchIds: Set<String> = emptySet(),
    queueSelectedTaskIds: Set<String> = emptySet(),
    queueOnLongPressBatch: (String) -> Unit = {},
    queueOnToggleBatch: (String) -> Unit = {},
    queueOnToggleTask: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Log.d(TAG_QUEUE, "QueueScreen() entered | tasks.size=${tasks.size} batchGroups.size=${batchGroups.size} selection=$queueIsSelectionMode")
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    if (tasks.isEmpty()) {
        Log.d(TAG_QUEUE, "QueueScreen: rendering EMPTY state")
        // Empty state
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "No tasks in queue",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Tap Generate to add tasks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
        return
    }

    Log.d(TAG_QUEUE, "QueueScreen: rendering LazyColumn with ${batchGroups.size} batchGroups")
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(batchGroups, key = { it.batchGroupId }) { group ->
            Log.d(TAG_QUEUE, "QueueScreen: rendering batchGroup id=${group.batchGroupId.take(8)} count=${group.count} prompt=${group.prompt.take(30)}")
            val isExpanded = expandedGroups[group.batchGroupId] ?: false
            val isBatchSelected = group.batchGroupId in queueSelectedBatchIds

            // All batches render as a unified collapsible group
            BatchGroupCard(
                group = group,
                isExpanded = isExpanded,
                processingActive = processingActive,
                onToggleExpand = {
                    expandedGroups[group.batchGroupId] = !isExpanded
                },
                onRemoveTask = onRemoveTask,
                onRemoveBatch = onRemoveBatch,
                onSaveInfo = onSaveInfo,
                // Queue selection
                queueIsSelectionMode = queueIsSelectionMode,
                queueIsBatchSelected = isBatchSelected,
                queueSelectedTaskIds = queueSelectedTaskIds,
                queueOnLongPress = { queueOnLongPressBatch(group.batchGroupId) },
                queueOnToggleBatch = { queueOnToggleBatch(group.batchGroupId) },
                queueOnToggleTask = queueOnToggleTask,
            )
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun TaskCard(
    task: GenerationTask,
    processingActive: Boolean,
    onRemove: () -> Unit,
    onSaveInfo: () -> Unit = {},
    onToggleExpand: (() -> Unit)?,
    isExpanded: Boolean,
    // ── Queue selection mode ──────────────────────────────────
    queueIsSelectionMode: Boolean = false,
    queueIsTaskSelected: Boolean = false,
    queueOnToggleTask: () -> Unit = {},
) {
    Log.d(TAG_QUEUE, "TaskCard() id=${task.id.take(8)} status=${task.status} prompt=${task.prompt.take(30)} selected=$queueIsTaskSelected selectionMode=$queueIsSelectionMode")
    val statusColor = statusColor(task.status, processingActive)
    val canSwipe = task.status != TaskStatus.PROCESSING && !queueIsSelectionMode

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> {
                    onRemove()
                    true
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    onSaveInfo()
                    false // don't dismiss — saving is a copy operation
                }
                else -> false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            // Use real-time offset instead of targetValue so the background
            // appears immediately on drag, not just near the dismiss threshold.
            val currentOffset = try {
                dismissState.requireOffset()
            } catch (_: IllegalStateException) {
                0f
            }
            val direction = when {
                currentOffset < -1f -> SwipeToDismissBoxValue.EndToStart  // swiping left → delete (red)
                currentOffset > 1f  -> SwipeToDismissBoxValue.StartToEnd  // swiping right → save (blue)
                else                -> SwipeToDismissBoxValue.Settled
            }
            val bgColor by animateColorAsState(
                targetValue = when (direction) {
                    SwipeToDismissBoxValue.EndToStart ->
                        MaterialTheme.colorScheme.errorContainer
                    SwipeToDismissBoxValue.StartToEnd ->
                        MaterialTheme.colorScheme.primaryContainer
                    else -> Color.Transparent
                },
                animationSpec = tween(200),
                label = "swipeBg",
            )
            val iconTint by animateColorAsState(
                targetValue = when (direction) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> Color.Transparent
                },
                animationSpec = tween(200),
                label = "swipeIcon",
            )
            val alignment = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                else -> Alignment.CenterEnd
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(bgColor)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                contentAlignment = alignment,
            ) {
                Icon(
                    imageVector = when (direction) {
                        SwipeToDismissBoxValue.StartToEnd -> Icons.Default.Bookmark
                        else -> Icons.Default.Delete
                    },
                    contentDescription = when (direction) {
                        SwipeToDismissBoxValue.StartToEnd -> "Save"
                        else -> "Delete"
                    },
                    tint = iconTint,
                )
            }
        },
        enableDismissFromStartToEnd = canSwipe,
        enableDismissFromEndToStart = canSwipe,
    ) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Checkbox in selection mode
                    if (queueIsSelectionMode) {
                        Checkbox(
                            checked = queueIsTaskSelected,
                            onCheckedChange = { queueOnToggleTask() },
                        )
                        Spacer(Modifier.width(4.dp))
                    }

                    // Status indicator
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(statusColor),
                    )
                    Spacer(Modifier.width(8.dp))

                    // Prompt preview
                    Text(
                        text = task.prompt.ifEmpty { "(empty prompt)" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )

                    Spacer(Modifier.width(4.dp))

                    // Status label
                    Text(
                        text = task.statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                    )

                    // Info icon for details
                    if (onToggleExpand != null) {
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = onToggleExpand,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = "Details",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Spacer(Modifier.width(2.dp))
                    }
                }

                // Processing progress
                if (task.status == TaskStatus.PROCESSING && task.progress > 0f) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = statusColor,
                    )
                }

                // Expanded details
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        TaskDetailRow("Steps", "${task.steps}")
                        TaskDetailRow("CFG", "%.1f".format(task.cfg))
                        TaskDetailRow("Size", "${task.width}x${task.height}")
                        TaskDetailRow("Sampler", task.sampler)
                        if (task.seed != null) TaskDetailRow("Seed", task.seed.toString())
                        if (!task.negativePrompt.isNullOrEmpty()) {
                            Text(
                                text = "Neg: ${task.negativePrompt}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // Error message
                if (task.status == TaskStatus.ERROR && !task.errorMessage.isNullOrEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = task.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun BatchGroupCard(
    group: BatchGroupDisplay,
    isExpanded: Boolean,
    processingActive: Boolean,
    onToggleExpand: () -> Unit,
    onRemoveTask: (String) -> Unit,
    onRemoveBatch: (String) -> Unit,
    onSaveInfo: (GenerationTask) -> Unit = {},
    // ── Queue selection mode ──────────────────────────────────
    queueIsSelectionMode: Boolean = false,
    queueIsBatchSelected: Boolean = false,
    queueSelectedTaskIds: Set<String> = emptySet(),
    queueOnLongPress: () -> Unit = {},
    queueOnToggleBatch: () -> Unit = {},
    queueOnToggleTask: (String) -> Unit = {},
) {
    Log.d(TAG_QUEUE, "BatchGroupCard() id=${group.batchGroupId.take(8)} tasks=${group.count} selected=$queueIsBatchSelected selectionMode=$queueIsSelectionMode")
    val canSwipe = group.tasks.none { it.status == TaskStatus.PROCESSING } && !queueIsSelectionMode

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> {
                    onRemoveBatch(group.batchGroupId)
                    true
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    group.tasks.forEach { onSaveInfo(it) }
                    false // don't dismiss — saving is a copy operation
                }
                else -> false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            // Use real-time offset instead of targetValue so the background
            // appears immediately on drag, not just near the dismiss threshold.
            val currentOffset = try {
                dismissState.requireOffset()
            } catch (_: IllegalStateException) {
                0f
            }
            val direction = when {
                currentOffset < -1f -> SwipeToDismissBoxValue.EndToStart  // swiping left → delete (red)
                currentOffset > 1f  -> SwipeToDismissBoxValue.StartToEnd  // swiping right → save (blue)
                else                -> SwipeToDismissBoxValue.Settled
            }
            val bgColor by animateColorAsState(
                targetValue = when (direction) {
                    SwipeToDismissBoxValue.EndToStart ->
                        MaterialTheme.colorScheme.errorContainer
                    SwipeToDismissBoxValue.StartToEnd ->
                        MaterialTheme.colorScheme.primaryContainer
                    else -> Color.Transparent
                },
                animationSpec = tween(200),
                label = "swipeBg",
            )
            val iconTint by animateColorAsState(
                targetValue = when (direction) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> Color.Transparent
                },
                animationSpec = tween(200),
                label = "swipeIcon",
            )
            val alignment = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                else -> Alignment.CenterEnd
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(bgColor)
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment,
            ) {
                Icon(
                    imageVector = when (direction) {
                        SwipeToDismissBoxValue.StartToEnd -> Icons.Default.Bookmark
                        else -> Icons.Default.Delete
                    },
                    contentDescription = when (direction) {
                        SwipeToDismissBoxValue.StartToEnd -> "Save batch"
                        else -> "Delete batch"
                    },
                    tint = iconTint,
                )
            }
        },
        enableDismissFromStartToEnd = canSwipe,
        enableDismissFromEndToStart = canSwipe,
    ) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (queueIsSelectionMode) {
                            queueOnToggleBatch()
                        } else {
                            onToggleExpand()
                        }
                    },
                    onLongClick = {
                        if (!queueIsSelectionMode) {
                            queueOnLongPress()
                        }
                    },
                ),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Batch header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Checkbox in selection mode
                    if (queueIsSelectionMode) {
                        Checkbox(
                            checked = queueIsBatchSelected,
                            onCheckedChange = { queueOnToggleBatch() },
                        )
                        Spacer(Modifier.width(4.dp))
                    }

                    // Batch count badge
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = "${group.count}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Spacer(Modifier.width(8.dp))

                    // Prompt preview
                    Text(
                        text = group.prompt.ifEmpty { "(empty prompt)" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )

                    // Expand/collapse chevron
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Status summary
                val pending = group.tasks.count { it.status == TaskStatus.PENDING }
                val completed = group.tasks.count { it.status == TaskStatus.COMPLETED }
                val processing = group.tasks.count { it.status == TaskStatus.PROCESSING }
                val errors = group.tasks.count { it.status == TaskStatus.ERROR }

                // Total progress = average of subtask progress
                val totalProgress = group.tasks.map { it.progress }.average().toFloat()
                if (totalProgress > 0f) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { totalProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = statusColor(TaskStatus.PROCESSING, processingActive),
                    )
                }

                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (pending > 0) Text(
                        "$pending pending",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    if (completed > 0) Text(
                        "$completed done",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (processing > 0) Text(
                        "$processing running",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor(TaskStatus.PROCESSING, processingActive),
                    )
                    if (errors > 0) Text(
                        "$errors failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // Expanded child tasks
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        group.tasks.forEach { task ->
                            val isTaskSelected = queueIsBatchSelected ||
                                (task.id in queueSelectedTaskIds)
                            TaskCard(
                                task = task,
                                processingActive = processingActive,
                                onRemove = { onRemoveTask(task.id) },
                                onSaveInfo = { onSaveInfo(task) },
                                onToggleExpand = null,
                                isExpanded = false,
                                // Queue selection
                                queueIsSelectionMode = queueIsSelectionMode,
                                queueIsTaskSelected = isTaskSelected,
                                queueOnToggleTask = { queueOnToggleTask(task.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun statusColor(status: TaskStatus, processingActive: Boolean): Color {
    return when (status) {
        TaskStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        TaskStatus.PROCESSING -> if (processingActive)
            MaterialTheme.colorScheme.primary
        else
            MaterialTheme.colorScheme.tertiary
        TaskStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        TaskStatus.ERROR -> MaterialTheme.colorScheme.error
        TaskStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    }
}
