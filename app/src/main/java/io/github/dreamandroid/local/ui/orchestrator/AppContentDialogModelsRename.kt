package io.github.dreamandroid.local.ui.orchestrator

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.ui.viewmodel.ModelsViewModel
import kotlinx.coroutines.launch

/**
 * Rename-model dialog extracted from AppContent to keep the orchestrator thin.
 */
@Composable
fun AppContentDialogModelsRename(
    modelsViewModel: ModelsViewModel,
    context: Context,
    snackbarHostState: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    val newName = modelsViewModel.renameText.trim()
    val isDuplicate = newName.isNotEmpty() && modelsViewModel.isRenameDuplicate(newName)

    AlertDialog(
        onDismissRequest = { modelsViewModel.showRenameDialog = false },
        title = { Text(stringResource(R.string.rename_model)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = modelsViewModel.renameText,
                    onValueChange = { modelsViewModel.renameText = it },
                    label = { Text(stringResource(R.string.custom_model_name)) },
                    singleLine = true,
                    isError = isDuplicate,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isDuplicate) {
                    Text(
                        text = stringResource(R.string.rename_duplicate_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        val success = modelsViewModel.renameModel(context, newName)
                        snackbarHostState.showSnackbar(
                            if (success) context.getString(R.string.rename_success)
                            else context.getString(R.string.rename_failed, "directory error")
                        )
                    }
                },
                enabled = newName.isNotEmpty() && !isDuplicate,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = { modelsViewModel.showRenameDialog = false }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
