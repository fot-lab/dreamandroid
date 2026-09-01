package io.github.dreamandroid.local.ui.orchestrator

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.RecordRepository
import io.github.dreamandroid.local.ui.frontend.BrowseTopBar
import io.github.dreamandroid.local.ui.screens.BrowseLayoutMode
import io.github.dreamandroid.local.ui.screens.BrowseScreen
import io.github.dreamandroid.local.ui.screens.describeExportDir
import io.github.dreamandroid.local.ui.viewmodel.BrowseViewModel
import io.github.dreamandroid.local.ui.viewmodel.ExportDirKind
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
    saveParamsEnabled: Boolean = false,
    onSaveParamsEnabledChange: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val knownModelIds by browseViewModel.knownModelIds.collectAsState()
    val filterModelIds = browseViewModel.filterModelIds
    val imageDirUri by browseViewModel.imageDirUri.collectAsState()
    val paramsDirUri by browseViewModel.paramsDirUri.collectAsState()

    // ── Export folder pickers (SAF) ──
    fun reportPickResult(error: String?) {
        Toast.makeText(
            context,
            if (error == null) {
                context.getString(R.string.export_dir_updated)
            } else {
                context.getString(R.string.export_dir_pick_failed)
            },
            Toast.LENGTH_LONG,
        ).show()
    }

    val pickImageDir = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) reportPickResult(browseViewModel.onExportDirPicked(ExportDirKind.IMAGE, uri))
    }
    val pickParamsDir = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) reportPickResult(browseViewModel.onExportDirPicked(ExportDirKind.PARAMS, uri))
    }

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
                saveParamsEnabled = saveParamsEnabled,
                onSaveParamsEnabledChange = onSaveParamsEnabledChange,
                imageDirLabel = describeExportDir(imageDirUri)
                    .ifEmpty { stringResource(R.string.export_dir_default_image) },
                paramsDirLabel = describeExportDir(paramsDirUri)
                    .ifEmpty { stringResource(R.string.export_dir_default_params) },
                onPickImageDir = { pickImageDir.launch(null) },
                onPickParamsDir = { pickParamsDir.launch(null) },
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
    saveParamsEnabled: Boolean = false,
    onSaveParamsEnabledChange: (Boolean) -> Unit = {},
    imageDirLabel: String = "",
    paramsDirLabel: String = "",
    onPickImageDir: () -> Unit = {},
    onPickParamsDir: () -> Unit = {},
) {
    val isAllSelected = filterModelIds.isEmpty()

    // ── Save params toggle ──
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, top = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.gallery_save_params_toggle),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = saveParamsEnabled,
            onCheckedChange = onSaveParamsEnabledChange,
        )
    }
    HorizontalDivider()

    // ── Export destinations (SAF folder pickers) ──
    ExportDirSettingRow(
        title = stringResource(R.string.export_image_dir),
        value = imageDirLabel,
        onPick = onPickImageDir,
    )
    HorizontalDivider()
    ExportDirSettingRow(
        title = stringResource(R.string.export_params_dir),
        value = paramsDirLabel,
        onPick = onPickParamsDir,
    )
    HorizontalDivider()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, top = 8.dp, end = 4.dp),
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

// =========== Browse Drawer: Export destination row ===========

/**
 * One export-destination setting: a title, the currently effective folder and an affordance
 * that opens the system folder picker (SAF `ACTION_OPEN_DOCUMENT_TREE`).
 */
@Composable
private fun ExportDirSettingRow(
    title: String,
    value: String,
    onPick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(start = 28.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = stringResource(R.string.export_dir_pick),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
