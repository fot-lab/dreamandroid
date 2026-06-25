package io.github.dreamandroid.local.ui.orchestrator

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.GenerationTask
import io.github.dreamandroid.local.data.RecordRepository
import io.github.dreamandroid.local.ui.frontend.QueueSettingsDrawerContent
import io.github.dreamandroid.local.ui.frontend.QueueTopBar
import io.github.dreamandroid.local.data.BatchGroupDisplay
import io.github.dreamandroid.local.ui.queue.TabQueueScreen
import io.github.dreamandroid.local.ui.viewmodel.QueueViewModel
import kotlinx.coroutines.launch

/**
 * Queue tab: drawer (Generation + Health settings) + Scaffold + QueueTopBar + TabQueueScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContentTabQueue(
    drawerState: DrawerState,
    snackbarHostState: SnackbarHostState,
    queueViewModel: QueueViewModel,
    tasks: List<GenerationTask>,
    batchGroups: List<BatchGroupDisplay>,
    processingActive: Boolean,
    queuePaused: Boolean,
    hasPendingTasks: Boolean,
    recordRepository: RecordRepository,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val queueSelectedCount = queueViewModel.queueComputeSelectedCount(batchGroups)

    // ── Batch Delete Confirmation Dialog ──
    if (queueViewModel.queueShowBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { queueViewModel.queueShowBatchDeleteDialog = false },
            title = { Text(stringResource(R.string.batch_delete)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.batch_delete_confirm,
                        queueSelectedCount,
                        queueSelectedCount,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        queueViewModel.queueBatchDelete(batchGroups)
                        queueViewModel.queueShowBatchDeleteDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { queueViewModel.queueShowBatchDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── Batch Save Info Confirmation Dialog ──
    if (queueViewModel.queueShowBatchSaveInfoDialog) {
        AlertDialog(
            onDismissRequest = { queueViewModel.queueShowBatchSaveInfoDialog = false },
            title = { Text(stringResource(R.string.batch_save_params)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.batch_save_params_confirm,
                        queueSelectedCount,
                        queueSelectedCount,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    queueViewModel.queueBatchSaveInfo(batchGroups, tasks, recordRepository)
                    queueViewModel.queueShowBatchSaveInfoDialog = false
                    scope.launch {
                        snackbarHostState.showSnackbar("Parameters saved (${queueSelectedCount} tasks)")
                    }
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { queueViewModel.queueShowBatchSaveInfoDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(2f / 3f)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 16.dp, end = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { scope.launch { drawerState.close() } }) {
                        Icon(Icons.Default.Close, stringResource(R.string.close))
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                QueueSettingsDrawerContent(
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                QueueTopBar(
                    drawerState = drawerState,
                    processingActive = processingActive,
                    queuePaused = queuePaused,
                    hasPendingTasks = hasPendingTasks,
                    onStop = { queueViewModel.stop(context) },
                    onResume = { queueViewModel.resume(context) },
                    // Queue selection mode
                    queueIsSelectionMode = queueViewModel.queueIsSelectionMode,
                    queueSelectedCount = queueSelectedCount,
                    queueOnExitSelection = { queueViewModel.queueExitSelection() },
                    queueOnBatchSaveInfo = {
                        queueViewModel.queueShowBatchSaveInfoDialog = true
                    },
                    queueOnBatchDelete = {
                        queueViewModel.queueShowBatchDeleteDialog = true
                    },
                    queueOnSelectAll = { queueViewModel.queueSelectAll(batchGroups) },
                    queueOnInvertSelection = { queueViewModel.queueInvertSelection(batchGroups) },
                    queueOnDeselectAll = { queueViewModel.queueDeselectAll() },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                TabQueueScreen(
                    tasks = tasks,
                    batchGroups = batchGroups,
                    processingActive = processingActive,
                    onRemoveTask = { queueViewModel.removeTask(it) },
                    onRemoveBatch = { queueViewModel.removeBatch(it) },
                    recordRepository = recordRepository,
                    // Queue selection
                    queueIsSelectionMode = queueViewModel.queueIsSelectionMode,
                    queueSelectedBatchIds = queueViewModel.queueSelectedBatchIds.toSet(),
                    queueSelectedTaskIds = queueViewModel.queueSelectedTaskIds.toSet(),
                    queueOnLongPressBatch = { queueViewModel.queueStartSelection(it) },
                    queueOnToggleBatch = { queueViewModel.queueToggleBatchSelection(it) },
                    queueOnToggleTask = { queueViewModel.queueToggleTaskSelection(it) },
                )
            }
        }
    }
}
