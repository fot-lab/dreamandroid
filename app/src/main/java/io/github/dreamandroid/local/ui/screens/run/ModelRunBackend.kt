package io.github.dreamandroid.local.ui.screens.run

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.*
import io.github.dreamandroid.local.service.BackendService
import io.github.dreamandroid.local.service.BackgroundGenerationService
import io.github.dreamandroid.local.service.backend.BackendManager
import io.github.dreamandroid.local.utils.LogCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Backend lifecycle helpers extracted from ModelRunScreen.
 * Contains the LEGACY cleanup/exit paths that directly interact with
 * BackendService and BackgroundGenerationService (bypassing BackendManager).
 */

fun cleanupBackend(
    context: Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onStateReset: () -> Unit,
    onScrollToPage0: suspend () -> Unit,
    saveAllJob: kotlinx.coroutines.Job?,
    batchGenerationJob: kotlinx.coroutines.Job?,
) {
    try {
        onStateReset() // clears currentBitmap, generationParams
        context.sendBroadcast(Intent(BackgroundGenerationService.ACTION_STOP))
        val backendServiceIntent = Intent(context, BackendService::class.java)
        context.stopService(backendServiceIntent)
        BackgroundGenerationService.resetState()
        coroutineScope.launch {
            onScrollToPage0()
        }
        saveAllJob?.cancel()
        batchGenerationJob?.cancel()
    } catch (e: Exception) {
        Log.e("ModelRunScreen", "error", e)
    }
}

fun handleExitBackend(
    onCleanup: () -> Unit,
    onNavigateUp: () -> Unit,
) {
    onCleanup()
    BackgroundGenerationService.clearCompleteState()
    onNavigateUp()
}

@Composable
fun BackendLifecycleEffects(
    modelId: String,
    modelRunOnCpu: Boolean?,
    modelIsSdxl: Boolean?,
    hasInitialized: Boolean,
    currentWidth: Int,
    currentHeight: Int,
    useOpenCL: Boolean,
    backendManager: BackendManager,
    onBackendFailed: (String) -> Unit,
    onInitialized: () -> Unit,
    onCleanup: () -> Unit,
) {
    val context = LocalContext.current
    val backendState by BackendService.backendState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Log capture
    DisposableEffect(modelId) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val captureEnabled = prefs.getBoolean("enable_log_capture", false)
        if (captureEnabled) {
            LogCapture.start()
        }
        onDispose {
            if (captureEnabled) {
                LogCapture.stopAndPublish()
            }
            // Safety net for paths that bypass handleExit()
            BackgroundGenerationService.clearCompleteState()
        }
    }

    // Start backend service when initialized
    LaunchedEffect(hasInitialized) {
        if (hasInitialized && backendState !is BackendService.BackendState.Running) {
            val intent = Intent(context, BackendService::class.java).apply {
                putExtra("modelId", modelId)
                putExtra("width", currentWidth)
                putExtra("height", currentHeight)
                putExtra("use_opencl", useOpenCL)
            }
            context.startForegroundService(intent)
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            onCleanup()
        }
    }

    // Lifecycle observer for ON_DESTROY
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> {
                    onCleanup()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onCleanup()
        }
    }
}
