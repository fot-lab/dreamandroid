package io.github.dreamandroid.local.ui.orchestrator

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.dreamandroid.local.ui.frontend.UpscaleTopBar
import io.github.dreamandroid.local.ui.screens.UpscaleScreen

/**
 * Upscale tab: ModalNavigationDrawer (empty) + Scaffold + UpscaleTopBar + UpscaleScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContentTabUpscale(
    drawerState: DrawerState,
    snackbarHostState: SnackbarHostState,
    isUpscaleModelLoaded: Boolean,
    loadedUpscalerId: String?,
) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(2f / 3f)) {
                // Upscale tab: drawer opens empty
            }
        },
    ) {
        Scaffold(
            topBar = {
                UpscaleTopBar(
                    drawerState = drawerState,
                    isUpscaleModelLoaded = isUpscaleModelLoaded,
                    upscalerId = loadedUpscalerId,
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
