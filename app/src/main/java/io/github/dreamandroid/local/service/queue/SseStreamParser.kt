package io.github.dreamandroid.local.service.queue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

class SseStreamParser(
    private val inputStream: InputStream
) {
    sealed class SseEvent {
        data class Progress(
            val step: Int,
            val totalSteps: Int,
            val imageBase64: String
        ) : SseEvent()

        data class Complete(
            val imageBase64: String,
            val seed: Long,
            val width: Int,
            val height: Int
        ) : SseEvent()

        data class Error(val message: String) : SseEvent()
    }

    /**
     * Returns a cold Flow of parsed SSE events.
     *
     * Uses [channelFlow] instead of [kotlinx.coroutines.flow.flow] to avoid the
     * `flow{}` + `withContext` anti-pattern (which violates Flow context preservation).
     * [channelFlow] is designed to support internal context switching.
     *
     * The upstream (BufferedReader reads) runs on [Dispatchers.IO].
     * JSON parsing runs on [Dispatchers.Default].
     * Collection respects the downstream collector's context.
     *
     * Cancellation: the loop checks [isActive] on each iteration and the
     * [BufferedReader.close] in [finally] ensures resources are released.
     */
    fun events(): Flow<SseEvent> = channelFlow {
        val reader = BufferedReader(InputStreamReader(inputStream))
        try {
            var currentLine: String? = null
            while (isActive && reader.readLine().also { currentLine = it } != null) {
                val line = currentLine ?: continue
                if (!line.startsWith("data: ")) continue

                val json = line.removePrefix("data: ")
                if (json == "[DONE]") break

                // Parse JSON on Default dispatcher to keep IO thread free for reads
                val event = withContext(Dispatchers.Default) {
                    parseEvent(json)
                }
                send(event)
            }
        } finally {
            // Ensure BufferedReader is closed even on cancellation
            try {
                reader.close()
            } catch (_: Exception) {
                // Best-effort close
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun parseEvent(json: String): SseEvent {
        val obj = JSONObject(json)
        return when (obj.getString("type")) {
            "progress" -> SseEvent.Progress(
                step = obj.getInt("step"),
                totalSteps = obj.getInt("total_steps"),
                imageBase64 = obj.getString("image")
            )
            "complete" -> SseEvent.Complete(
                imageBase64 = obj.getString("image"),
                seed = obj.optLong("seed"),
                width = obj.getInt("width"),
                height = obj.getInt("height")
            )
            "error" -> SseEvent.Error(obj.getString("message"))
            else -> SseEvent.Error("Unknown event type: ${obj.getString("type")}")
        }
    }
}
