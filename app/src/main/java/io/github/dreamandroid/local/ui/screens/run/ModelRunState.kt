package io.github.dreamandroid.local.ui.screens.run

/*
 * NOTE: This file is no longer in use.
 *
 * ModelRunState was the centralized state holder for the original ModelRunScreen.
 * Since ModelRunScreen has been replaced by tab-based UI (Generate / Upscale tabs),
 * this state class is kept solely as reference.
 *
 * Do NOT instantiate ModelRunState in new code.
 */

import android.graphics.Bitmap
import android.graphics.Rect as AndroidRect
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.dreamandroid.local.data.GenerationMode
import io.github.dreamandroid.local.data.HistoryFilter
import io.github.dreamandroid.local.data.HistoryItem
import io.github.dreamandroid.local.data.Resolution
import io.github.dreamandroid.local.data.TagSuggestion
import io.github.dreamandroid.local.ui.screens.PathData
import io.github.dreamandroid.local.utils.ImportedParams
import kotlinx.coroutines.Job

/**
 * Centralized mutable state holder for ModelRunScreen.
 *
 * Groups all mutable state previously declared inline in the composable body,
 * enabling extraction of page composables and dialog blocks into separate files.
 *
 * This is a pure-Compose @Stable class (not an AndroidX ViewModel) — instantiated
 * via remember { ModelRunState() } in the main composable.
 */

// Alias for path pen data used by InpaintScreen
typealias PathData = Any

@Stable
class ModelRunState {

    // ── Generation ────────────────────────────────────────────
    var currentBitmap by mutableStateOf<Bitmap?>(null)
    var intermediateBitmap by mutableStateOf<Bitmap?>(null)
    var imageVersion by mutableIntStateOf(0)
    var generationParams by mutableStateOf<GenerationParameters?>(null)
    var generationParamsModelId by mutableStateOf("")
    var generationParamsTmp by mutableStateOf(
        GenerationParameters(
            steps = 0, cfgScale = 0f, seed = 0, prompt = "", negativePrompt = "",
            generationTime = "", width = 512, height = 512, runOnCpu = false,
        ),
    )
    var returnedSeed by mutableStateOf<Long?>(null)
    var isRunning by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var errorMessage by mutableStateOf<String?>(null)
    var isCheckingBackend by mutableStateOf(true)
    var generationStartTime by mutableStateOf<Long?>(null)
    var hasInitialized by mutableStateOf(false)
    var currentBatchGroupId by mutableStateOf<String?>(null)

    // ── Prompt ────────────────────────────────────────────────
    var prompt by mutableStateOf("")
    var negativePrompt by mutableStateOf("")
    var promptFieldValue by mutableStateOf(TextFieldValue(""))
    var negativePromptFieldValue by mutableStateOf(TextFieldValue(""))
    var promptSuggestions by mutableStateOf<List<TagSuggestion>>(emptyList())
    var negativePromptSuggestions by mutableStateOf<List<TagSuggestion>>(emptyList())
    var promptActiveQuery by mutableStateOf<String?>(null)
    var negativePromptActiveQuery by mutableStateOf<String?>(null)
    var isPromptFocused by mutableStateOf(false)
    var isNegativePromptFocused by mutableStateOf(false)
    var promptUndoStack by mutableStateOf<List<String>>(emptyList())
    var promptRedoStack by mutableStateOf<List<String>>(emptyList())
    var promptHistoryAt by mutableStateOf(0L)
    var negativePromptUndoStack by mutableStateOf<List<String>>(emptyList())
    var negativePromptRedoStack by mutableStateOf<List<String>>(emptyList())
    var negativePromptHistoryAt by mutableStateOf(0L)
    var promptPopupDismissed by mutableStateOf(false)
    var negativePromptPopupDismissed by mutableStateOf(false)
    var promptTokenCount by mutableIntStateOf(2)
    var negativePromptTokenCount by mutableIntStateOf(2)
    var promptTokenMax by mutableIntStateOf(77)
    var negativePromptTokenMax by mutableIntStateOf(77)
    var promptOverflowOffset by mutableIntStateOf(-1)
    var negativePromptOverflowOffset by mutableIntStateOf(-1)
    var promptSuggestJob by mutableStateOf<Job?>(null)
    var negativePromptSuggestJob by mutableStateOf<Job?>(null)

    // ── Parameters ────────────────────────────────────────────
    var cfg by mutableFloatStateOf(7f)
    var steps by mutableFloatStateOf(20f)
    var seed by mutableStateOf("")
    var denoiseStrength by mutableFloatStateOf(0.6f)
    var useOpenCL by mutableStateOf(false)
    var batchCounts by mutableIntStateOf(1)
    var sampler by mutableStateOf("dpm")
    var denoiseCurve by mutableStateOf("scaled_linear")
    var aspectRatio by mutableStateOf("1:1")
    var currentWidth by mutableIntStateOf(512)
    var currentHeight by mutableIntStateOf(512)
    var availableResolutions by mutableStateOf<List<Resolution>>(emptyList())

    // ── Dialogs visibility ────────────────────────────────────
    var showExitDialog by mutableStateOf(false)
    var showResetConfirmDialog by mutableStateOf(false)
    var showOpenCLWarningDialog by mutableStateOf(false)
    var showCustomAspectRatioDialog by mutableStateOf(false)
    var showResolutionChangeDialog by mutableStateOf(false)
    var showAdvancedSettings by mutableStateOf(false)
    var showReportDialog by mutableStateOf(false)
    var showParametersDialog by mutableStateOf(false)
    var showUpscalerDialog by mutableStateOf(false)
    var showHistoryFilterSheet by mutableStateOf(false)
    var showHistoryDetailDialog by mutableStateOf(false)
    var showHistoryParametersDialog by mutableStateOf(false)
    var showDeleteHistoryDialog by mutableStateOf(false)
    var showReproduceParamsDialog by mutableStateOf(false)
    var showBatchDeleteDialog by mutableStateOf(false)
    var showBatchSaveDialog by mutableStateOf(false)
    var showCropScreen by mutableStateOf(false)
    var showInpaintScreen by mutableStateOf(false)
    var isPreviewMode by mutableStateOf(false)

    // ── Resolution / Restart ──────────────────────────────────
    var pendingResolution by mutableStateOf<Resolution?>(null)
    var backendRestartTrigger by mutableIntStateOf(0)

    // ── Image / Inpaint ───────────────────────────────────────
    var selectedImageUri by mutableStateOf<Uri?>(null)
    var base64EncodeDone by mutableStateOf(false)
    var croppedBitmap by mutableStateOf<Bitmap?>(null)
    var imageUriForCrop by mutableStateOf<Uri?>(null)
    var maskBitmap by mutableStateOf<Bitmap?>(null)
    var isInpaintMode by mutableStateOf(false)
    var savedPathHistory by mutableStateOf<List<PathData>?>(null)
    var cropRect by mutableStateOf<AndroidRect?>(null)
    var hasOriginalImageForStitch by mutableStateOf(false)
    var snapshotIsInpaintMode by mutableStateOf(false)
    var snapshotSelectedImageUri by mutableStateOf<Uri?>(null)
    var snapshotCropRect by mutableStateOf<AndroidRect?>(null)
    var snapshotHasOriginalImage by mutableStateOf(false)
    var stitchableHistoryIds by mutableStateOf<Set<Long>>(emptySet())
    var currentDisplayedHistoryId by mutableStateOf<Long?>(null)
    var isUpscaling by mutableStateOf(false)

    // ── History ───────────────────────────────────────────────
    var historyFilter by mutableStateOf(HistoryFilter())
    var selectedHistoryItem by mutableStateOf<HistoryItem?>(null)
    var pendingReproduceParams by mutableStateOf<GenerationParameters?>(null)
    var isSelectionMode by mutableStateOf(false)
    val selectedItems = mutableStateListOf<HistoryItem>()
    var isBatchSaving by mutableStateOf(false)
    var batchSaveTotal by mutableIntStateOf(0)
    var batchSaveCurrent by mutableIntStateOf(0)
    var batchSaveFailed by mutableIntStateOf(0)

    // ── Share / Import ────────────────────────────────────────
    var shareSourceParams by mutableStateOf<GenerationParameters?>(null)
    var shareSourceModelId by mutableStateOf<String?>(null)
    var pendingImport by mutableStateOf<ImportedParams?>(null)
    var clipboardImportChecked by mutableStateOf(false)

    // ── Embeddings ────────────────────────────────────────────
    var embeddingNames by mutableStateOf<List<String>>(emptyList())

    // ── Jobs ──────────────────────────────────────────────────
    var saveAllJob by mutableStateOf<Job?>(null)

    // ── Helpers ───────────────────────────────────────────────
    fun clearImg2imgState() {
        selectedImageUri = null
        croppedBitmap = null
        maskBitmap = null
        isInpaintMode = false
        cropRect = null
        savedPathHistory = null
        base64EncodeDone = false
        hasOriginalImageForStitch = false
    }
}
