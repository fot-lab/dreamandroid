package io.github.dreamandroid.local.data

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import io.github.dreamandroid.local.ui.theme.ThemePreset

enum class DarkModePreference { SYSTEM, LIGHT, DARK }

data class ThemeState(
    val dynamicColor: Boolean = true,
    val preset: ThemePreset = ThemePreset.TANGERINE,
    val darkMode: DarkModePreference = DarkModePreference.SYSTEM,
    val oledBlack: Boolean = false,
)

class ThemePreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): ThemeState {
        val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val dynamic = supportsDynamic && prefs.getBoolean(KEY_DYNAMIC, supportsDynamic)
        val preset = prefs.getString(KEY_PRESET, null)
            ?.let { name -> runCatching { ThemePreset.valueOf(name) }.getOrNull() }
            ?: ThemePreset.TANGERINE
        val darkMode = prefs.getString(KEY_DARK_MODE, null)
            ?.let { name -> runCatching { DarkModePreference.valueOf(name) }.getOrNull() }
            ?: DarkModePreference.SYSTEM
        val oledBlack = prefs.getBoolean(KEY_OLED_BLACK, false)
        return ThemeState(dynamic, preset, darkMode, oledBlack)
    }

    fun write(state: ThemeState) {
        prefs.edit {
            putBoolean(KEY_DYNAMIC, state.dynamicColor)
            putString(KEY_PRESET, state.preset.name)
            putString(KEY_DARK_MODE, state.darkMode.name)
            putBoolean(KEY_OLED_BLACK, state.oledBlack)
        }
    }

    companion object {
        private const val PREFS_NAME = "theme_prefs"
        private const val KEY_DYNAMIC = "dynamic_color"
        private const val KEY_PRESET = "preset"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_OLED_BLACK = "oled_black"
    }
}
