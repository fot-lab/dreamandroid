package io.github.dreamandroid.local.ui.orchestrator

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.dreamandroid.local.data.GenerationTask
import io.github.dreamandroid.local.data.RecordRepository
import io.github.dreamandroid.local.ui.frontend.QueueTopBar
import io.github.dreamandroid.local.ui.queue.BatchGroupDisplay
import io.github.dreamandroid.local.ui.queue.TabQueueScreen
import io.github.dreamandroid.local.ui.viewmodel.QueueViewModel

/**
 * Queue tab: ModalNavigationDrawer (empty) + Scaffold + QueueTopBar + TabQueueScreen.
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
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(2f / 3f)) {
                // Queue tab: drawer opens empty
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
                    onStop = { queueViewModel.stop(androidx.compose.ui.platform.LocalContext.current) },
                    onResume = { queueViewModel.resume(androidx.compose.ui.platform.LocalContext.current) },
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
                )
            }
        }
    }
}
