package io.github.dreamandroid.local.ui.orchestrator

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.ui.screens.download.DownloadManagerScreen

/**
 * Downloads screen: reachable via the expandable bottom bar (Download icon).
 *
 * Wraps [DownloadManagerScreen] with a full Scaffold + TopAppBar + back navigation,
 * making it a peer-level screen alongside Queue / Generate / Browse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContentTabDownloads(
    onBack: () -> Unit,
    onModelStateChanged: (String) -> Unit = {},
) {
    DownloadManagerScreen(
        onClose = onBack,
        onModelStateChanged = onModelStateChanged,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
    )
}
