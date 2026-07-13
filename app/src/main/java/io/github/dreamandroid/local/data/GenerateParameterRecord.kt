package io.github.dreamandroid.local.data

import io.github.dreamandroid.local.data.db.TaskEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Format cfg scale for display: 0.1 precision for normal values, 0.01 for fine-step, raw for any. */
fun formatCfgScale(cfg: Float): String = when {
    (cfg * 10f) % 1f == 0f -> "%.1f".format(cfg)
    (cfg * 100f) % 1f == 0f -> "%.2f".format(cfg)
    else -> cfg.toString()
}

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
    val sampler: String,
    val scheduler: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val source: RecordSource,
) {
    /** Summary line shown in the record list: "{modelId} · {steps} steps · CFG {cfg} · {width}×{height}" */
    val paramsSummary: String
        get() = "$modelId · $steps steps · CFG ${formatCfgScale(cfg)} · ${width}×${height}"

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
        put("sampler", sampler)
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
            sampler = obj.optString("sampler", "dpm"),
            scheduler = obj.optString("scheduler", ""),
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

        // ── Room entity mapping ──

        fun fromEntity(e: TaskEntity): GenerateParameterRecord {
            val source = try {
                val tags = JSONObject(e.tags ?: "{}")
                RecordSource.valueOf(tags.optString("source", "QUEUE"))
            } catch (_: Exception) {
                RecordSource.QUEUE
            }
            return GenerateParameterRecord(
                id = e.id,
                prompt = e.prompt,
                negativePrompt = e.negativePrompt,
                modelId = e.modelId,
                steps = e.steps,
                cfg = e.cfg,
                seed = e.seed,
                width = e.width,
                height = e.height,
                sampler = e.sampler,
                scheduler = e.scheduler,
                timestamp = e.timestamp,
                source = source,
            )
        }

        fun listFromEntities(entities: List<TaskEntity>): List<GenerateParameterRecord> =
            entities.map { fromEntity(it) }
    }

    // ── Room entity mapping ──

    fun toEntity(): TaskEntity = TaskEntity(
        id = id,
        taskType = TaskEntity.TYPE_RECORD,
        modelId = modelId,
        prompt = prompt,
        negativePrompt = negativePrompt,
        steps = steps,
        cfg = cfg,
        seed = seed,
        width = width,
        height = height,
        denoiseStrength = null,
        useOpenCL = false,
        sampler = sampler,
        scheduler = scheduler,
        timestamp = timestamp,
        tags = """{"source":"${source.name}"}""",
    )
}
