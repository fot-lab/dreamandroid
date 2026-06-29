package io.github.dreamandroid.local.ui.orchestrator

import androidx.activity.compose.BackHandler
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
import io.github.dreamandroid.local.service.backend.BackendManager
import io.github.dreamandroid.local.ui.frontend.AppSettingsDrawerContent
import io.github.dreamandroid.local.ui.frontend.ModelListTab
import io.github.dreamandroid.local.ui.frontend.ModelsTopBar
import io.github.dreamandroid.local.ui.viewmodel.ModelsViewModel
import kotlinx.coroutines.launch

/**
 * Models tab: ModalNavigationDrawer (settings) + Scaffold + ModelsTopBar + ModelListTab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContentTabModels(
    drawerState: DrawerState,
    snackbarHostState: SnackbarHostState,
    modelsViewModel: ModelsViewModel,
    loadedModelId: String?,
    loadedModelType: BackendManager.Mode?,
    isModelLoaded: Boolean,
    isModelLoading: Boolean,
    isUpscaleModelLoaded: Boolean,
    persistedUpscalerId: String?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── Model load/unload (ViewModel + UI feedback) ──
    val loadModel: (String) -> Unit = { mId ->
        scope.launch {
            modelsViewModel.loadModel(mId, 512, 512, false)
                .onSuccess {
                    snackbarHostState.showSnackbar(context.getString(R.string.loading_model_label))
                }.onFailure { error ->
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.model_load_failed, error.message ?: "unknown")
                    )
                }
        }
    }
    val unloadModel: () -> Unit = {
        scope.launch {
            modelsViewModel.unloadModel()
            snackbarHostState.showSnackbar(context.getString(R.string.model_unloaded))
        }
    }

    // ── ModelView multi-selection (3-dot dropdown) ──
    // derivedStateOf: only recomputes when source list elements change,
    // not on every recomposition of AppContentTabModels.
    val customModels by derivedStateOf {
        modelsViewModel.modelRepository.models.filter { it.isCustom }
    }
    val modelViewSelectAll: () -> Unit = {
        modelsViewModel.modelViewSelectAll(customModels)
    }
    val modelViewInvertSelection: () -> Unit = {
        modelsViewModel.modelViewInvertSelection(customModels)
    }
    val modelViewDeselectAll: () -> Unit = {
        modelsViewModel.modelViewDeselectAll()
    }
    val modelViewSelectedCount = modelsViewModel.modelViewSelectedModelIds.size
    val modelViewLoadModel: () -> Unit = {
        modelsViewModel.modelViewSelectedModelIds.firstOrNull()?.let { loadModel(it) }
    }
    val loadUpscaleModel: (String) -> Unit = { id ->
        scope.launch {
            modelsViewModel.loadUpscaleModel(id).onFailure { error ->
                snackbarHostState.showSnackbar(
                    context.getString(R.string.model_load_failed, error.message ?: "unknown")
                )
            }
        }
    }
    val unloadUpscaleModel: () -> Unit = {
        scope.launch { modelsViewModel.unloadUpscaleModel() }
    }

    // ── BackHandler: close drawer on system back press ──
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.8f)) {
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
                AppSettingsDrawerContent(
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                ModelsTopBar(
                    drawerState = drawerState,
                    modelViewSelectedCount = modelViewSelectedCount,
                    isModelLoaded = isModelLoaded,
                    isModelLoading = isModelLoading,
                    onLoadModel = modelViewLoadModel,
                    onUnloadModel = unloadModel,
                    onImportModel = { modelsViewModel.showCustomModelDialog = true },
                    onImportNpuModel = { modelsViewModel.showCustomNpuModelDialog = true },
                    onImportUpscaleModel = { modelsViewModel.showCustomUpscaleModelDialog = true },
                    onDownloadManager = { modelsViewModel.showDownloadManager = true },
                    onRenameModel = { modelsViewModel.prepareRename() },
                    onDeleteModel = { modelsViewModel.showDeleteConfirm = true },
                    // ── ModelView multi-selection ──
                    modelViewOnSelectAll = modelViewSelectAll,
                    modelViewOnInvertSelection = modelViewInvertSelection,
                    modelViewOnDeselectAll = modelViewDeselectAll,
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                ModelListTab(
                    loadedModelId = loadedModelId,
                    loadedModelType = loadedModelType,
                    isModelLoaded = isModelLoaded,
                    isUpscaleModelLoaded = isUpscaleModelLoaded,
                    onLoadModel = loadModel,
                    modelRepository = modelsViewModel.modelRepository,
                    refreshVersion = modelsViewModel.modelRefreshVersion,
                    importingModels = modelsViewModel.importingModels,
                    onLoadUpscaleModel = loadUpscaleModel,
                    onUnloadUpscaleModel = unloadUpscaleModel,
                    persistedUpscalerId = persistedUpscalerId,
                    // ── Multi-selection ──
                    modelViewSelectedModelIds = modelsViewModel.modelViewSelectedModelIds,
                    modelViewOnToggleModelSelection = { modelsViewModel.modelViewToggleModelSelection(it) },
                )
            }
        }
    }
}
