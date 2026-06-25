package io.github.dreamandroid.local.ui.orchestrator

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.dreamandroid.local.data.BrowseLayoutMode
import io.github.dreamandroid.local.data.RecordRepository
import io.github.dreamandroid.local.ui.frontend.BrowseTopBar
import io.github.dreamandroid.local.ui.screens.BrowseScreen
import io.github.dreamandroid.local.ui.viewmodel.BrowseViewModel

/**
 * Browse tab: ModalNavigationDrawer (empty) + Scaffold + BrowseTopBar + BrowseScreen.
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
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(2f / 3f)) {
                // Browse tab: drawer opens empty
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
