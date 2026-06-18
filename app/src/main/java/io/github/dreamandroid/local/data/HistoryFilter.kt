package io.github.dreamandroid.local.data

import androidx.compose.runtime.Immutable
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

enum class GenerationMode {
    TXT2IMG,
    IMG2IMG,
    INPAINT,
    UNKNOWN,
    ;

    companion object {
        fun fromString(s: String?): GenerationMode = when (s) {
            "TXT2IMG" -> TXT2IMG
            "IMG2IMG" -> IMG2IMG
            "INPAINT" -> INPAINT
            else -> UNKNOWN
        }
    }
}

enum class DeviceFilter { NPU, CPU, GPU }

@Immutable
data class HistoryFilter(
    val modelIds: Set<String>? = null,
    val modes: Set<GenerationMode>? = null,
    val from: Long? = null,
    val to: Long? = null,
    val sizes: Set<String>? = null,
    val samplers: Set<String>? = null,
    val devices: Set<DeviceFilter>? = null,
    val promptSubstring: String? = null,
    val descending: Boolean = true,
) {
    fun toSqlQuery(): SupportSQLiteQuery {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()

        // Mandatory: only history tasks
        where += "task_type = 'HISTORY'"

        if (!modelIds.isNullOrEmpty()) {
            where += "model_id IN (${modelIds.joinToString(",") { "?" }})"
            args.addAll(modelIds)
        }
        if (!modes.isNullOrEmpty()) {
            // Selecting TXT2IMG also matches UNKNOWN: legacy migrated rows have no mode
            // recorded, and from the user's perspective anything that's not img2img/inpaint
            // is effectively txt2img.
            val expanded =
                if (GenerationMode.TXT2IMG in modes) modes + GenerationMode.UNKNOWN else modes
            where += "mode IN (${expanded.joinToString(",") { "?" }})"
            args.addAll(expanded.map { it.name })
        }
        if (from != null) {
            where += "timestamp >= ?"
            args += from
        }
        if (to != null) {
            where += "timestamp <= ?"
            args += to
        }
        if (!sizes.isNullOrEmpty()) {
            where += "(width || 'x' || height) IN (${sizes.joinToString(",") { "?" }})"
            args.addAll(sizes)
        }
        if (!samplers.isNullOrEmpty()) {
            where += "scheduler IN (${samplers.joinToString(",") { "?" }})"
            args.addAll(samplers)
        }
        if (!devices.isNullOrEmpty()) {
            val parts = mutableListOf<String>()
            // run_on_cpu=0 → NPU; run_on_cpu=1 && use_opencl=0 → CPU; run_on_cpu=1 && use_opencl=1 → GPU
            if (DeviceFilter.NPU in devices) parts += "run_on_cpu = 0"
            if (DeviceFilter.CPU in devices) parts += "(run_on_cpu = 1 AND use_opencl = 0)"
            if (DeviceFilter.GPU in devices) parts += "(run_on_cpu = 1 AND use_opencl = 1)"
            if (parts.isNotEmpty()) {
                where += "(${parts.joinToString(" OR ")})"
            }
        }
        if (!promptSubstring.isNullOrBlank()) {
            where += "(INSTR(prompt, ?) > 0 OR INSTR(negative_prompt, ?) > 0)"
            args += promptSubstring
            args += promptSubstring
        }

        val whereClause = "WHERE ${where.joinToString(" AND ")}"
        val direction = if (descending) "DESC" else "ASC"
        val orderClause = "ORDER BY timestamp $direction, id $direction"

        val sql = "SELECT * FROM tasks $whereClause $orderClause"

        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }
}
