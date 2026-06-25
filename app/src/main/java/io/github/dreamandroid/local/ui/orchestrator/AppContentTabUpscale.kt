package io.github.dreamandroid.local.ui.orchestrator

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.service.backend.BackendManager
import io.github.dreamandroid.local.ui.frontend.UpscaleTopBar
import io.github.dreamandroid.local.ui.screens.UpscaleScreen
import kotlinx.coroutines.launch

/**
 * Upscale tab: ModalNavigationDrawer (empty) + Scaffold + UpscaleTopBar + UpscaleScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContentTabUpscale(
    drawerState: DrawerState,
    snackbarHostState: SnackbarHostState,
    loadedModelId: String?,
    loadedModelType: BackendManager.Mode?,
) {
    val scope = rememberCoroutineScope()
    val isUpscaleModelLoaded = loadedModelType == BackendManager.Mode.Upscaler && loadedModelId != null

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
                    verticalAlignment = Alignment.CenterVertically,
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
            }
        },
    ) {
        Scaffold(
            topBar = {
                UpscaleTopBar(
                    drawerState = drawerState,
                    loadedModelId = loadedModelId,
                    loadedModelType = loadedModelType,
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                UpscaleScreen()
            }
        }
    }
}
