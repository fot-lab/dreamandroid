package io.github.dreamandroid.local.ui.backend

import io.github.dreamandroid.local.service.backend.BackendManager

// =========== Backend State Helpers ===========
// Derived state computables extracted from AppContent to reduce inline type-check boilerplate.

fun BackendManager.State.isDiffusionLoaded(): Boolean =
    this is BackendManager.State.Running && this.mode == BackendManager.Mode.Diffusion

fun BackendManager.State.isDiffusionLoading(): Boolean =
    this is BackendManager.State.Starting && this.mode == BackendManager.Mode.Diffusion

fun BackendManager.State.isUpscalerLoaded(): Boolean =
    this is BackendManager.State.Running && this.mode == BackendManager.Mode.Upscaler

fun BackendManager.State.activeUpscalerId(): String? =
    (this as? BackendManager.State.Running)?.takeIf { it.mode == BackendManager.Mode.Upscaler }?.modelId
