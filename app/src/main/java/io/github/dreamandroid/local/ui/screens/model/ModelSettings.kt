package io.github.dreamandroid.local.ui.screens.model

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.DarkModePreference
import io.github.dreamandroid.local.ui.theme.LocalThemeController
import io.github.dreamandroid.local.ui.theme.ThemePreset
import io.github.dreamandroid.local.ui.theme.scheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingNavCard(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SwitchSettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceSection() {
    val themeController = LocalThemeController.current
    val state = themeController.state
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val isDark = when (state.darkMode) {
        DarkModePreference.SYSTEM -> isSystemInDarkTheme()
        DarkModePreference.LIGHT -> false
        DarkModePreference.DARK -> true
    }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Palette,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.appearance),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            if (dynamicColorSupported) {
                SwitchSettingRow(
                    title = stringResource(R.string.dynamic_color),
                    description = stringResource(R.string.dynamic_color_hint),
                    checked = state.dynamicColor,
                    onCheckedChange = { value ->
                        themeController.update { it.copy(dynamicColor = value) }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.theme_preset),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.theme_preset_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ThemePreset.entries.forEach { preset ->
                        ThemeSwatch(
                            preset = preset,
                            isDark = isDark,
                            selected = preset == state.preset && !state.dynamicColor,
                            enabled = !state.dynamicColor,
                            onClick = {
                                themeController.update { it.copy(preset = preset) }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.dark_mode),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val modes = DarkModePreference.entries
                    modes.forEach { mode ->
                        FilterChip(
                            selected = mode == state.darkMode,
                            onClick = { themeController.update { it.copy(darkMode = mode) } },
                            label = {
                                Text(
                                    text = stringResource(
                                        when (mode) {
                                            DarkModePreference.SYSTEM -> R.string.dark_mode_system
                                            DarkModePreference.LIGHT -> R.string.dark_mode_light
                                            DarkModePreference.DARK -> R.string.dark_mode_dark
                                        },
                                    ),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSwatch(
    preset: ThemePreset,
    isDark: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = preset.scheme(isDark)
    val alpha = if (enabled) 1f else 0.45f
    val description = stringResource(preset.nameRes)
    val shape = when (preset) {
        ThemePreset.TANGERINE -> RoundedCornerShape(16.dp)
        ThemePreset.FOREST -> CircleShape
        ThemePreset.OCEAN -> RoundedCornerShape(8.dp)
        ThemePreset.AMBER -> RoundedCornerShape(4.dp)
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            color = scheme.primary.copy(alpha = alpha),
            border = if (selected) {
                BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
            } else {
                null
            },
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = description },
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
