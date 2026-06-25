package io.github.dreamandroid.local.ui.orchestrator

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.ui.screens.model.CustomModelDialog
import io.github.dreamandroid.local.ui.screens.model.CustomNpuModelDialog
import io.github.dreamandroid.local.ui.screens.model.CustomUpscaleModelDialog
import io.github.dreamandroid.local.ui.viewmodel.ModelsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Model-import dialog wiring extracted from AppContent.
 * Groups CustomModelDialog, CustomNpuModelDialog, and CustomUpscaleModelDialog.
 */
@Composable
fun AppContentDialogModelsImport(
    modelsViewModel: ModelsViewModel,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    scope: CoroutineScope,
) {
    val context = LocalContext.current

    val msgModelConversionSuccess = stringResource(R.string.model_conversion_success)
    val msgModelConversionFailed = stringResource(R.string.model_conversion_failed)
    val msgNpuModelAddedSuccess = stringResource(R.string.npu_model_added_success)
    val msgNpuModelAddFailed = stringResource(R.string.npu_model_add_failed)

    // Custom (diffusion) model import
    if (modelsViewModel.showCustomModelDialog) {
        CustomModelDialog(
            context,
            onDismiss = { modelsViewModel.showCustomModelDialog = false },
            onModelAdded = { modelName, fileUri, clipSkip, loraFiles ->
                modelsViewModel.showCustomModelDialog = false
                scope.launch {
                    modelsViewModel.importCustomModel(context, modelName, fileUri, clipSkip, loraFiles)
                    snackbarHostState.showSnackbar(msgModelConversionSuccess)
                }
            },
        )
    }

    // Custom NPU model import
    if (modelsViewModel.showCustomNpuModelDialog) {
        CustomNpuModelDialog(
            context,
            onDismiss = { modelsViewModel.showCustomNpuModelDialog = false },
            onModelAdded = { modelName, zipUri ->
                modelsViewModel.showCustomNpuModelDialog = false
                scope.launch {
                    modelsViewModel.importNpuModel(context, modelName, zipUri)
                    snackbarHostState.showSnackbar(msgNpuModelAddedSuccess)
                }
            },
        )
    }

    // Custom upscale model import
    if (modelsViewModel.showCustomUpscaleModelDialog) {
        CustomUpscaleModelDialog(
            context = context,
            onDismiss = { modelsViewModel.showCustomUpscaleModelDialog = false },
            onModelAdded = { modelName, fileUri ->
                modelsViewModel.showCustomUpscaleModelDialog = false
                scope.launch {
                    val success = modelsViewModel.importUpscaleModel(context, modelName, fileUri)
                    snackbarHostState.showSnackbar(
                        if (success) context.getString(R.string.upscale_file_selected)
                        else context.getString(R.string.error_download_failed, "")
                    )
                }
            },
        )
    }
}
