package io.github.dreamandroid.local.service.backend

import io.github.dreamandroid.local.core.model.GenerateParams
import io.github.dreamandroid.local.service.queue.SseStreamParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Unified HTTP middleware — the single entry point for all UI-to-backend communication.
 *
 * Principles:
 * - UI layer (Screens + ViewModels) MUST only interact with BackendService,
 *   never directly with [BackendManager].
 * - All HTTP calls (tokenize, generate, upscale, healthCheck) are proxied
 *   through this service — providing a uniform error-handling boundary.
 * - Process lifecycle (start/stop C++ backend) is also proxied, since
 *   ViewModels should not manage low-level process state directly.
 *
 * This class is a thin wrapper over [BackendManager] today; its value lies in
 * enforcing the architectural boundary, enabling future middleware concerns
 * (logging, metrics, caching, retry policies) to be added in one place.
 */
class BackendService(private val backendManager: BackendManager) {

    // ── Backend lifecycle state (read-only for UI) ─────────────

    val state: StateFlow<BackendManager.State>
        get() = backendManager.state

    /** True while the backend is actively serving a generation request. */
    val isGenerating: Boolean
        get() = backendManager.isGenerating

    // ── Model Lifecycle ────────────────────────────────────────

    suspend fun startDiffusion(
        modelId: String,
        width: Int,
        height: Int,
        useOpenCL: Boolean,
    ): Result<Unit> = backendManager.startDiffusion(modelId, width, height, useOpenCL)

    suspend fun startUpscaler(upscalerId: String): Result<Unit> =
        backendManager.startUpscaler(upscalerId)

    suspend fun stop() = backendManager.stop()

    // ── Health Check ───────────────────────────────────────────

    suspend fun healthCheck(): Boolean = backendManager.healthCheck()

    suspend fun healthCheckWithRetry(
        maxRetries: Int = io.github.dreamandroid.local.core.model.DreamHubConstants.DEFAULT_HEALTH_CHECK_MAX_FAILURES,
        intervalSeconds: Long = io.github.dreamandroid.local.core.model.DreamHubConstants.DEFAULT_HEALTH_CHECK_RETRY_INTERVAL_S,
    ): Boolean = backendManager.healthCheckWithRetry(maxRetries, intervalSeconds)

    // ── HTTP Endpoints ─────────────────────────────────────────

    fun generate(params: GenerateParams): Flow<SseStreamParser.SseEvent> =
        backendManager.generate(params)

    suspend fun tokenize(prompt: String): BackendManager.TokenizeResult =
        backendManager.tokenize(prompt)

    suspend fun upscale(
        rgbBytes: ByteArray,
        width: Int,
        height: Int,
        upscalerPath: String,
    ): ByteArray = backendManager.upscale(rgbBytes, width, height, upscalerPath)

    // ── Progress Query ──

    /**
     * Query the C++ backend for current generation progress.
     * Returns null if the backend is unreachable, otherwise (currentStep, totalSteps).
     */
    suspend fun queryProgress(): Pair<Int, Int>? = backendManager.queryProgress()
}
