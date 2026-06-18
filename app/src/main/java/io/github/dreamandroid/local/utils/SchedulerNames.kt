package io.github.dreamandroid.local.utils

fun samplerDisplayName(id: String?): String = when (id) {
    "dpm" -> "DPM++ 2M"
    "euler_a" -> "Euler A"
    "eulera" -> "Euler A"
    "lcm" -> "LCM"
    "euler" -> "Euler"
    "dpm_sde" -> "DPM++ 2M SDE"
    null -> ""
    else -> id
}

fun denoiseCurveDisplayName(id: String?): String = when (id) {
    "scaled_linear" -> "Scaled Linear"
    "linear" -> "Linear"
    "karras" -> "Karras"
    null -> ""
    else -> id
}
