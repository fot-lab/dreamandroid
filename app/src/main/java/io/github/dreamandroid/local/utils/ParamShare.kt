package io.github.dreamandroid.local.utils

import io.github.dreamandroid.local.data.GenerationMode
import io.github.dreamandroid.local.data.formatCfgScale
import io.github.dreamandroid.local.ui.screens.run.GenerationParameters
import java.util.Base64
import org.json.JSONObject

enum class ParamShareField {
    PROMPT,
    NEGATIVE_PROMPT,
    STEPS,
    CFG_SCALE,
    SEED,
    SAMPLER,
    SCHEDULER,
    DENOISING_STRENGTH,
    MODE,

    // ═══════════════════════════════════════════════════════════════
    // Canonical preview — use these instead of hand-rolled when blocks
    // ═══════════════════════════════════════════════════════════════
    ;

    fun preview(params: GenerationParameters): String? = when (this) {
        PROMPT -> params.prompt
        NEGATIVE_PROMPT -> params.negativePrompt
        STEPS -> params.steps.toString()
        CFG_SCALE -> formatCfgScale(params.cfgScale)
        SEED -> params.seed?.toString()
        SAMPLER -> samplerDisplayName(params.sampler)
        SCHEDULER -> params.scheduler
        DENOISING_STRENGTH -> "%.2f".format(params.denoisingStrength)
        MODE -> null
    }

    fun preview(imported: ImportedParams): String? = when (this) {
        PROMPT -> imported.prompt
        NEGATIVE_PROMPT -> imported.negativePrompt
        STEPS -> imported.steps?.toString()
        CFG_SCALE -> imported.cfgScale?.let { formatCfgScale(it) }
        SEED -> imported.seed?.toString()
        SAMPLER -> samplerDisplayName(imported.sampler)
        SCHEDULER -> imported.scheduler
        DENOISING_STRENGTH -> imported.denoisingStrength?.let { "%.2f".format(it) }
        MODE -> imported.mode?.name?.lowercase()
    }
}

/**
 * Fields eligible for sharing/reproducing from a [GenerationParameters].
 * PROMPT / NEGATIVE_PROMPT / STEPS / CFG_SCALE / SAMPLER are always present;
 * SEED, SCHEDULER, DENOISING_STRENGTH are conditional.
 */
fun GenerationParameters.availableShareFields(): List<ParamShareField> = buildList {
    add(ParamShareField.PROMPT)
    add(ParamShareField.NEGATIVE_PROMPT)
    add(ParamShareField.STEPS)
    add(ParamShareField.CFG_SCALE)
    if (seed != null) add(ParamShareField.SEED)
    add(ParamShareField.SAMPLER)
    if (scheduler.isNotEmpty()) add(ParamShareField.SCHEDULER)
    if (mode != GenerationMode.UNKNOWN && mode != GenerationMode.TXT2IMG) {
        add(ParamShareField.DENOISING_STRENGTH)
    }
}

data class ImportedParams(
    val prompt: String? = null,
    val negativePrompt: String? = null,
    val steps: Int? = null,
    val cfgScale: Float? = null,
    val seed: Long? = null,
    val sampler: String? = null,
    val scheduler: String? = null,
    val denoisingStrength: Float? = null,
    val mode: GenerationMode? = null,
) {
    fun availableFields(): Set<ParamShareField> {
        // Switching mode requires user interaction (tab switching, source image
        // selection, etc.), so MODE is preserved in the JSON for context but is
        // not surfaced as an applicable field here.
        val set = mutableSetOf<ParamShareField>()
        if (prompt != null) set += ParamShareField.PROMPT
        if (negativePrompt != null) set += ParamShareField.NEGATIVE_PROMPT
        if (steps != null) set += ParamShareField.STEPS
        if (cfgScale != null) set += ParamShareField.CFG_SCALE
        if (seed != null) set += ParamShareField.SEED
        if (sampler != null) set += ParamShareField.SAMPLER
        if (scheduler != null) set += ParamShareField.SCHEDULER
        if (denoisingStrength != null) set += ParamShareField.DENOISING_STRENGTH
        return set
    }
}

object ParamShare {
    private const val MARKER_PREFIX = "LDPARAMS:"
    private const val IDENTITY_KEY = "_dreamandroid_params"
    private const val SCHEMA_VERSION = 1

    fun buildJson(params: GenerationParameters, modelId: String?, fields: Set<ParamShareField>): String {
        val json = JSONObject()
        json.put(IDENTITY_KEY, true)
        json.put("v", SCHEMA_VERSION)
        if (!modelId.isNullOrBlank()) json.put("model_id", modelId)
        if (ParamShareField.PROMPT in fields) json.put("prompt", params.prompt)
        if (ParamShareField.NEGATIVE_PROMPT in fields) {
            json.put("negative_prompt", params.negativePrompt)
        }
        if (ParamShareField.STEPS in fields) json.put("steps", params.steps)
        if (ParamShareField.CFG_SCALE in fields) json.put("cfg_scale", params.cfgScale.toDouble())
        if (ParamShareField.SEED in fields) {
            params.seed?.let { json.put("seed", it) }
        }
        if (ParamShareField.SAMPLER in fields) json.put("sampler", params.sampler)
        if (ParamShareField.SCHEDULER in fields) json.put("scheduler", params.scheduler)
        if (ParamShareField.DENOISING_STRENGTH in fields) {
            json.put("denoising_strength", params.denoisingStrength.toDouble())
        }
        // Mode is included as metadata (not a user-selectable field) when known.
        if (params.mode != GenerationMode.UNKNOWN) json.put("mode", params.mode.name)
        return json.toString()
    }

    fun encodeForClipboard(jsonStr: String, useBase64: Boolean): String {
        if (!useBase64) return jsonStr
        val b64 = Base64.getEncoder()
            .encodeToString(jsonStr.toByteArray(Charsets.UTF_8))
        return "$MARKER_PREFIX$b64"
    }

    fun tryDecode(raw: String?): ImportedParams? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val jsonStr = when {
            trimmed.startsWith(MARKER_PREFIX) -> {
                val payload = trimmed.removePrefix(MARKER_PREFIX).trim()
                runCatching {
                    String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
                }.getOrNull() ?: return null
            }

            trimmed.startsWith("{") -> trimmed

            else -> return null
        }
        return runCatching {
            val json = JSONObject(jsonStr)
            if (!json.optBoolean(IDENTITY_KEY, false)) return null
            ImportedParams(
                prompt = if (json.has("prompt")) json.optString("prompt") else null,
                negativePrompt = if (json.has("negative_prompt")) {
                    json.optString("negative_prompt")
                } else {
                    null
                },
                steps = if (json.has("steps")) json.optInt("steps") else null,
                cfgScale = if (json.has("cfg_scale")) {
                    json.optDouble("cfg_scale").toFloat()
                } else if (json.has("cfg")) {
                    // legacy key: tolerate "cfg" for backward compat
                    json.optDouble("cfg").toFloat()
                } else null,
                seed = if (json.has("seed")) {
                    when (val v = json.opt("seed")) {
                        is Number -> v.toLong()
                        is String -> v.toLongOrNull()
                        else -> null
                    }
                } else {
                    null
                },
                sampler = if (json.has("sampler")) json.optString("sampler") else null,
                scheduler = if (json.has("scheduler")) json.optString("scheduler") else null,
                denoisingStrength = if (json.has("denoising_strength")) {
                    json.optDouble("denoising_strength").toFloat()
                } else if (json.has("denoise_strength")) {
                    // legacy key: tolerate "denoise_strength" for backward compat
                    json.optDouble("denoise_strength").toFloat()
                } else {
                    null
                },
                mode = if (json.has("mode")) {
                    runCatching {
                        GenerationMode.valueOf(json.optString("mode"))
                    }.getOrNull()
                } else {
                    null
                },
            )
        }.getOrNull()
    }
}
