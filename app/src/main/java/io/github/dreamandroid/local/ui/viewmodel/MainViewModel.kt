package io.github.dreamandroid.local.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import io.github.dreamandroid.local.navigation.BottomTab

/**
 * Main orchestrator ViewModel extracted from AppContent God Object (UILA-COMP-0001).
 *
 * Manages:
 * - Navigation tab selection
 * - Global UI flags (warnings, etc.)
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // ── Navigation ────────────────────────────────────────────
    var selectedTab by mutableStateOf(BottomTab.Models)

    // ── Global Warnings ───────────────────────────────────────
    var showNoModelWarning by mutableStateOf(false)
}
