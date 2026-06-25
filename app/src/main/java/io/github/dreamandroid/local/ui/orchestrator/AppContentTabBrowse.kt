package io.github.dreamandroid.local.ui.orchestrator

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.RecordRepository
import io.github.dreamandroid.local.ui.frontend.BrowseTopBar
import io.github.dreamandroid.local.ui.screens.BrowseLayoutMode
import io.github.dreamandroid.local.ui.screens.BrowseScreen
import io.github.dreamandroid.local.ui.viewmodel.BrowseViewModel
import kotlinx.coroutines.launch

/**
 * Browse tab: ModalNavigationDrawer with model filter + Scaffold + BrowseTopBar + BrowseScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContentTabBrowse(
    drawerState: DrawerState,
    snackbarHostState: SnackbarHostState,
    browseViewModel: BrowseViewModel,
    recordRepository: RecordRepository,
    browseLayoutMode: BrowseLayoutMode,
    onToggleLayout: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val knownModelIds by browseViewModel.knownModelIds.collectAsState()
    val filterModelIds = browseViewModel.filterModelIds

    // ── BackHandler: close drawer on system back press ──
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.8f)) {
                BrowseModelFilterDrawer(
                    knownModelIds = knownModelIds,
                    filterModelIds = filterModelIds,
                    onToggleModel = { browseViewModel.toggleModelFilter(it) },
                    onSelectAll = { browseViewModel.selectAllModelFilters() },
                    onClose = { scope.launch { drawerState.close() } },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                BrowseTopBar(
                    drawerState = drawerState,
                    onToggleLayout = onToggleLayout,
                    isGalleryBrowseSelectionMode = browseViewModel.isSelectionMode,
                    galleryBrowseSelectedCount = browseViewModel.selectedItems.size,
                    onGalleryBrowseBatchSaveInfo = { browseViewModel.showBatchSaveInfoDialog = true },
                    onGalleryBrowseBatchSave = { browseViewModel.showBatchSaveDialog = true },
                    onGalleryBrowseBatchDelete = { browseViewModel.showBatchDeleteDialog = true },
                    onGalleryBrowseExitSelection = { browseViewModel.exitSelection() },
                    onGalleryBrowseSelectAll = { browseViewModel.galleryBrowseSelectAll() },
                    onGalleryBrowseInvertSelection = { browseViewModel.galleryBrowseInvertSelection() },
                    onGalleryBrowseDeselectAll = { browseViewModel.galleryBrowseDeselectAll() },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                BrowseScreen(
                    recordRepository = recordRepository,
                    browseViewModel = browseViewModel,
                    layoutMode = browseLayoutMode,
                )
            }
        }
    }
}

// =========== Browse Drawer: Model Filter ===========

/**
 * Drawer content for Browse tab: checkbox-based model filter.
 *
 * - "All" checkbox: when checked → clears all individual filters (show everything).
 * - Individual model checkboxes: multi-select; checking any auto-unchecks "All".
 */
@Composable
private fun ColumnScope.BrowseModelFilterDrawer(
    knownModelIds: List<String>,
    filterModelIds: Set<String>,
    onToggleModel: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClose: () -> Unit,
) {
    val isAllSelected = filterModelIds.isEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, top = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.history_filter_by_model),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, stringResource(R.string.close))
        }
    }
    Spacer(Modifier.height(8.dp))
    HorizontalDivider()

    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // "All" checkbox
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelectAll() }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = isAllSelected,
                onCheckedChange = { if (it) onSelectAll() },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.history_filter_all),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Individual model checkboxes
        knownModelIds.forEach { modelId ->
            val isChecked = modelId in filterModelIds
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleModel(modelId) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = { onToggleModel(modelId) },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = modelId,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}
