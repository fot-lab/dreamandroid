package io.github.dreamandroid.local.utils

fun denoiseCurveDisplayName(id: String?): String = when (id) {
    "scaled_linear" -> "Scaled Linear"
    "linear" -> "Linear"
    "karras" -> "Karras"
    null -> ""
    else -> id
}
