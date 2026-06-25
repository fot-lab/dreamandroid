package io.github.dreamandroid.local.ui.orchestrator

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.GenerationTask
import io.github.dreamandroid.local.data.RecordRepository
import io.github.dreamandroid.local.ui.frontend.QueueSettingsDrawerContent
import io.github.dreamandroid.local.ui.frontend.QueueTopBar
import io.github.dreamandroid.local.ui.queue.BatchGroupDisplay
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
                    onStop = { queueViewModel.stop(LocalContext.current) },
                    onResume = { queueViewModel.resume(LocalContext.current) },
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
