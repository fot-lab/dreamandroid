package io.github.dreamandroid.local.ui.frontend

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import io.github.dreamandroid.local.R
import io.github.dreamandroid.local.data.GenerationPreferences
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// =========== Settings Drawer ===========

@Composable
fun ColumnScope.AppSettingsDrawerContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appPrefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val genPrefs = remember { GenerationPreferences(context) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ────── Backend ──────
        SectionHeader(stringResource(R.string.backend_settings))

        var listenAll by remember { mutableStateOf(appPrefs.getBoolean("listen_on_all_addresses", false)) }
        SwitchSetting(
            title = stringResource(R.string.listen_on_all_addresses),
            hint = stringResource(R.string.listen_on_all_addresses_hint),
            checked = listenAll,
            onCheckedChange = { checked ->
                listenAll = checked
                appPrefs.edit { putBoolean("listen_on_all_addresses", checked) }
            },
        )

        var sdxlLowram by remember { mutableStateOf(appPrefs.getBoolean("sdxl_lowram", true)) }
        SwitchSetting(
            title = stringResource(R.string.sdxl_lowram),
            hint = stringResource(R.string.sdxl_lowram_hint),
            checked = sdxlLowram,
            onCheckedChange = { checked ->
                sdxlLowram = checked
                appPrefs.edit { putBoolean("sdxl_lowram", checked) }
            },
        )

        var enableLogCapture by remember { mutableStateOf(appPrefs.getBoolean("enable_log_capture", false)) }
        SwitchSetting(
            title = stringResource(R.string.capture_logs),
            hint = stringResource(R.string.capture_logs_hint),
            checked = enableLogCapture,
            onCheckedChange = { checked ->
                enableLogCapture = checked
                appPrefs.edit { putBoolean("enable_log_capture", checked) }
            },
        )

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()

        // ────── Downloads ──────
        SectionHeader(stringResource(R.string.download_settings_section))

        var selectedSource by remember { mutableStateOf("huggingface") }
        var customUrl by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            selectedSource = genPrefs.getSelectedSource()
            customUrl = if (selectedSource == "custom") genPrefs.getBaseUrl() else ""
        }

        ChipSetting(
            title = stringResource(R.string.download_from),
            options = listOf(
                "huggingface" to stringResource(R.string.source_huggingface),
                "hf-mirror" to stringResource(R.string.source_hf_mirror),
                "custom" to stringResource(R.string.source_custom),
            ),
            selected = selectedSource,
            onSelect = { src ->
                selectedSource = src
                scope.launch {
                    genPrefs.saveSelectedSource(src)
                    when (src) {
                        "huggingface" -> genPrefs.saveBaseUrl("https://huggingface.co/")
                        "hf-mirror" -> genPrefs.saveBaseUrl("https://hf-mirror.com/")
                        "custom" -> { /* keep existing custom URL */ }
                    }
                }
            },
        )

        if (selectedSource == "custom") {
            OutlinedTextField(
                value = customUrl,
                onValueChange = { customUrl = it },
                label = { Text("URL") },
                placeholder = { Text("https://hf-mirror.com/") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = {
                scope.launch {
                    genPrefs.saveBaseUrl(customUrl.trim().ifBlank { "https://hf-mirror.com/" })
                }
            }) {
                Text(stringResource(R.string.save))
            }
        }


        Spacer(Modifier.height(4.dp))
    }
    Spacer(Modifier.height(16.dp))
}

// =========== Queue Settings Drawer ===========

/**
 * Queue tab drawer: Generation process + Health check settings.
 */
@Composable
fun ColumnScope.QueueSettingsDrawerContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appPrefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ────── Generation ──────
        SectionHeader(stringResource(R.string.generation_settings))

        var showDiffusion by remember { mutableStateOf(appPrefs.getBoolean("show_diffusion_process", false)) }
        SwitchSetting(
            title = stringResource(R.string.show_process),
            hint = stringResource(R.string.show_process_hint),
            checked = showDiffusion,
            onCheckedChange = { checked ->
                showDiffusion = checked
                appPrefs.edit { putBoolean("show_diffusion_process", checked) }
            },
        )

        if (showDiffusion) {
            var diffusionStride by remember {
                mutableIntStateOf(appPrefs.getInt("show_diffusion_stride", 1).coerceIn(1, 10))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.preview_stride))
                    Text(
                        stringResource(R.plurals.preview_stride_hint, diffusionStride, diffusionStride),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = diffusionStride.toFloat(),
                    onValueChange = { diffusionStride = it.roundToInt().coerceIn(1, 10) },
                    onValueChangeFinished = {
                        appPrefs.edit { putInt("show_diffusion_stride", diffusionStride) }
                    },
                    valueRange = 1f..10f,
                    steps = 8,
                    modifier = Modifier.width(120.dp),
                )
                Text(
                    "$diffusionStride",
                    modifier = Modifier.width(28.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        var genTimeout by remember {
            mutableIntStateOf(appPrefs.getInt("generation_timeout_s", 60).coerceIn(15, 600))
        }
        SliderSetting(
            title = stringResource(R.string.generation_timeout),
            hint = stringResource(R.string.generation_timeout_hint, genTimeout),
            value = genTimeout,
            valueRange = 15..600,
            steps = 38,
            suffix = "s",
            onValueChangeFinished = {
                appPrefs.edit { putInt("generation_timeout_s", genTimeout) }
            },
        ) { genTimeout = it }

        var bitmapTimeout by remember {
            mutableIntStateOf(appPrefs.getInt("bitmap_consumed_timeout_s", 30).coerceIn(5, 120))
        }
        SliderSetting(
            title = stringResource(R.string.bitmap_consumed_timeout),
            hint = stringResource(R.string.bitmap_consumed_timeout_hint, bitmapTimeout),
            value = bitmapTimeout,
            valueRange = 5..120,
            steps = 22,
            suffix = "s",
            onValueChangeFinished = {
                appPrefs.edit { putInt("bitmap_consumed_timeout_s", bitmapTimeout) }
            },
        ) { bitmapTimeout = it }

        var cooldownTime by remember {
            mutableIntStateOf(appPrefs.getInt("queue_cooldown_time_s", 0).coerceIn(0, 120))
        }
        SliderSetting(
            title = stringResource(R.string.queue_cooldown_time),
            hint = stringResource(R.string.queue_cooldown_time_hint, cooldownTime),
            value = cooldownTime,
            valueRange = 0..120,
            steps = 23,
            suffix = "s",
            onValueChangeFinished = {
                appPrefs.edit { putInt("queue_cooldown_time_s", cooldownTime) }
            },
        ) { cooldownTime = it }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()

        // ────── Health Check ──────
        SectionHeader(stringResource(R.string.health_check_settings))

        var healthCheckInterval by remember {
            mutableIntStateOf(appPrefs.getInt("health_check_retry_interval_s", 20).coerceIn(5, 120))
        }
        SliderSetting(
            title = stringResource(R.string.health_check_retry_interval),
            hint = stringResource(R.string.health_check_retry_interval_hint, healthCheckInterval),
            value = healthCheckInterval,
            valueRange = 5..120,
            steps = 22,
            suffix = "s",
            onValueChangeFinished = {
                appPrefs.edit { putInt("health_check_retry_interval_s", healthCheckInterval) }
            },
        ) { healthCheckInterval = it }

        var healthCheckMaxFails by remember {
            mutableIntStateOf(appPrefs.getInt("health_check_max_failures", 4).coerceIn(1, 20))
        }
        SliderSetting(
            title = stringResource(R.string.health_check_max_failures),
            hint = stringResource(R.string.health_check_max_failures_hint, healthCheckMaxFails),
            value = healthCheckMaxFails,
            valueRange = 1..20,
            steps = 18,
            suffix = "",
            onValueChangeFinished = {
                appPrefs.edit { putInt("health_check_max_failures", healthCheckMaxFails) }
            },
        ) { healthCheckMaxFails = it }
    }
    Spacer(Modifier.height(16.dp))
}
