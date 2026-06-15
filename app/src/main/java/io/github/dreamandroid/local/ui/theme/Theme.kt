package io.github.dreamandroid.local.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.dreamandroid.local.data.DarkModePreference
import io.github.dreamandroid.local.data.ThemePreferences
import io.github.dreamandroid.local.data.ThemeState

/**
 * Holds the live theme preferences plus a setter that also persists. Provided
 * via [LocalThemeController] so settings UIs can read and mutate without
 * threading state down through every screen.
 */
class ThemeController(initial: ThemeState, private val onPersist: (ThemeState) -> Unit) {
    var state: ThemeState by mutableStateOf(initial)
        private set

    fun update(transform: (ThemeState) -> ThemeState) {
        val next = transform(state)
        if (next != state) {
            state = next
            onPersist(next)
        }
    }
}

val LocalThemeController = compositionLocalOf<ThemeController> {
    error("ThemeController not provided")
}

@Composable
fun rememberThemeController(): ThemeController {
    val context = LocalContext.current
    val prefs = remember { ThemePreferences(context) }
    return remember { ThemeController(prefs.read(), prefs::write) }
}

private inline val ThemeState.systemFollowsDark: Boolean
    @Composable get() = when (darkMode) {
        DarkModePreference.SYSTEM -> isSystemInDarkTheme()
        DarkModePreference.LIGHT -> false
        DarkModePreference.DARK -> true
    }

@Composable
fun DreamHubTheme(themeState: ThemeState, content: @Composable () -> Unit) {
    val darkTheme = themeState.systemFollowsDark
    val colorScheme = when {
        themeState.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        else -> themeState.preset.scheme(darkTheme)
    }

    val finalColorScheme = if (themeState.oledBlack && darkTheme) {
        colorScheme.copy(
            surface = androidx.compose.ui.graphics.Color(0xFF000000),
            background = androidx.compose.ui.graphics.Color(0xFF000000),
            surfaceContainer = androidx.compose.ui.graphics.Color(0xFF0A0A0A),
            surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF000000),
            surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF111111),
            surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF1A1A1A),
        )
    } else {
        colorScheme
    }

    MaterialTheme(
        colorScheme = finalColorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content,
    )
}
