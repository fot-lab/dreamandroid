package io.github.dreamandroid.local.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Source of a saved parameter record.
 */
enum class RecordSource {
    QUEUE,
    GALLERY,
}

/**
 * A saved snapshot of generation parameters, persisted by [RecordRepository].
 * Records are independent of their source (Queue task / Gallery image) —
 * deleting the source does not delete the record.
 */
data class GenerateParameterRecord(
    val id: String = UUID.randomUUID().toString(),
    val prompt: String,
    val negativePrompt: String,
    val modelId: String,
    val steps: Int,
    val cfg: Float,
    val seed: Long?,
    val width: Int,
    val height: Int,
    val scheduler: String,
    val timestamp: Long = System.currentTimeMillis(),
    val source: RecordSource,
) {
    /** Summary line shown in the record list: "{modelId} · {steps} steps · CFG {cfg} · {width}×{height}" */
    val paramsSummary: String
        get() = "$modelId · $steps steps · CFG ${"%.1f".format(cfg)} · ${width}×${height}"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("prompt", prompt)
        put("negativePrompt", negativePrompt)
        put("modelId", modelId)
        put("steps", steps)
        put("cfg", cfg.toDouble())
        if (seed != null) put("seed", seed)
        put("width", width)
        put("height", height)
        put("scheduler", scheduler)
        put("timestamp", timestamp)
        put("source", source.name)
    }

    companion object {
        fun fromJson(obj: JSONObject): GenerateParameterRecord = GenerateParameterRecord(
            id = obj.getString("id"),
            prompt = obj.getString("prompt"),
            negativePrompt = obj.optString("negativePrompt", ""),
            modelId = obj.getString("modelId"),
            steps = obj.getInt("steps"),
            cfg = obj.getDouble("cfg").toFloat(),
            seed = if (obj.has("seed") && !obj.isNull("seed")) obj.getLong("seed") else null,
            width = obj.getInt("width"),
            height = obj.getInt("height"),
            scheduler = obj.optString("scheduler", "dpm"),
            timestamp = obj.getLong("timestamp"),
            source = try {
                RecordSource.valueOf(obj.getString("source"))
            } catch (_: IllegalArgumentException) {
                RecordSource.QUEUE
            },
        )

        fun listFromJsonArray(jsonArray: JSONArray): List<GenerateParameterRecord> {
            val result = mutableListOf<GenerateParameterRecord>()
            for (i in 0 until jsonArray.length()) {
                result.add(fromJson(jsonArray.getJSONObject(i)))
            }
            return result
        }

        fun listToJsonArray(records: List<GenerateParameterRecord>): JSONArray {
            val arr = JSONArray()
            records.forEach { arr.put(it.toJson()) }
            return arr
        }
    }
}
