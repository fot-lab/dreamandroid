package io.github.dreamandroid.local.ui.orchestrator

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.DarkModePreference
import io.github.dreamandroid.local.ui.frontend.ChipSetting
import io.github.dreamandroid.local.ui.frontend.SectionHeader
import io.github.dreamandroid.local.ui.frontend.SwitchSetting
import io.github.dreamandroid.local.ui.theme.LocalThemeController
import io.github.dreamandroid.local.ui.theme.ThemePreset

/**
 * General Settings screen: reachable via the expandable bottom bar (Settings icon).
 *
 * Contains Appearance (Dynamic Color / Dark Mode / OLED / Theme Preset) and
 * Debug (debug_queue / debug_model) sections, migrated from the Models tab drawer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContentTabSettings(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val themeController = LocalThemeController.current
    val appPrefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_expand_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // ────── Appearance ──────
            SectionHeader(stringResource(R.string.appearance))

            // Dynamic Color
            var dynamicColor by remember { mutableStateOf(themeController.state.dynamicColor) }
            SwitchSetting(
                title = stringResource(R.string.dynamic_color),
                hint = stringResource(R.string.dynamic_color_hint),
                checked = dynamicColor,
                onCheckedChange = { checked ->
                    dynamicColor = checked
                    themeController.update { it.copy(dynamicColor = checked) }
                },
            )

            // Dark Mode
            var darkMode by remember { mutableStateOf(themeController.state.darkMode) }
            ChipSetting(
                title = stringResource(R.string.dark_mode),
                options = listOf(
                    DarkModePreference.SYSTEM to stringResource(R.string.dark_mode_system),
                    DarkModePreference.LIGHT to stringResource(R.string.dark_mode_light),
                    DarkModePreference.DARK to stringResource(R.string.dark_mode_dark),
                ),
                selected = darkMode,
                onSelect = { mode ->
                    darkMode = mode
                    themeController.update { it.copy(darkMode = mode) }
                },
            )

            // OLED Pure Black (only visible when not forced light)
            if (darkMode != DarkModePreference.LIGHT) {
                var oledBlack by remember { mutableStateOf(themeController.state.oledBlack) }
                SwitchSetting(
                    title = stringResource(R.string.oled_black),
                    hint = stringResource(R.string.oled_black_hint),
                    checked = oledBlack,
                    onCheckedChange = { checked ->
                        oledBlack = checked
                        themeController.update { it.copy(oledBlack = checked) }
                    },
                )
            }

            // Theme Preset
            var themePreset by remember { mutableStateOf(themeController.state.preset) }
            ChipSetting(
                title = stringResource(R.string.theme_preset),
                hint = stringResource(R.string.theme_preset_hint),
                options = ThemePreset.entries.map { it to stringResource(it.nameRes) },
                selected = themePreset,
                onSelect = { preset ->
                    themePreset = preset
                    themeController.update { it.copy(preset = preset) }
                },
            )

            Spacer(Modifier.height(4.dp))
            HorizontalDivider()

            // ────── Debug ──────
            SectionHeader(stringResource(R.string.debug_section))

            var debugQueue by remember { mutableStateOf(appPrefs.getBoolean("debug_queue", false)) }
            SwitchSetting(
                title = stringResource(R.string.debug_queue),
                hint = stringResource(R.string.debug_queue_hint),
                checked = debugQueue,
                onCheckedChange = { checked ->
                    debugQueue = checked
                    appPrefs.edit { putBoolean("debug_queue", checked) }
                },
            )

            var debugModel by remember { mutableStateOf(appPrefs.getBoolean("debug_model", false)) }
            SwitchSetting(
                title = stringResource(R.string.debug_model),
                hint = stringResource(R.string.debug_model_hint),
                checked = debugModel,
                onCheckedChange = { checked ->
                    debugModel = checked
                    appPrefs.edit { putBoolean("debug_model", checked) }
                },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}
