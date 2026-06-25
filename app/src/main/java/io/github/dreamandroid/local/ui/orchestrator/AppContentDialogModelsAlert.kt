package io.github.dreamandroid.local.ui.orchestrator

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.ui.viewmodel.MainViewModel
import io.github.dreamandroid.local.ui.viewmodel.ModelsViewModel
import kotlinx.coroutines.launch

/**
 * Shared alert/confirmation dialogs extracted from AppContent.
 * - No-model-loaded warning
 * - Delete-model confirmation
 */
@Composable
fun AppContentDialogModelsAlert(
    mainViewModel: MainViewModel,
    modelsViewModel: ModelsViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // No model warning
    if (mainViewModel.showNoModelWarning) {
        AlertDialog(
            onDismissRequest = { mainViewModel.showNoModelWarning = false },
            title = { Text(stringResource(R.string.no_model_loaded)) },
            text = { Text(stringResource(R.string.no_model_loaded_hint)) },
            confirmButton = {
                TextButton(onClick = { mainViewModel.showNoModelWarning = false }) {
                    Text(stringResource(R.string.got_it))
                }
            },
        )
    }

    // Delete model confirmation dialog
    if (modelsViewModel.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { modelsViewModel.showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_model)) },
            text = { Text(stringResource(R.string.delete_model_confirm_single)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val success = modelsViewModel.deleteModel(context)
                            snackbarHostState.showSnackbar(
                                if (success) context.getString(R.string.delete_success)
                                else context.getString(R.string.delete_failed)
                            )
                        }
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { modelsViewModel.showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
