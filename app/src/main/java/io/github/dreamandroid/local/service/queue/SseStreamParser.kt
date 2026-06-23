package io.github.dreamandroid.local.service.queue

import android.util.Log
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
            val progress: Float,
            val imageBase64: String
        ) : SseEvent()

        data class Complete(
            val imageBase64: String,
            val seed: Long,
            val width: Int,
            val height: Int,
            val finishReason: String = "SUCCESS"
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

                // .trim() matches the pre-migration BackgroundGenerationService
                // behaviour: line.substring(6).trim().  Strips trailing \r
                // that may survive BufferedReader.readLine() on some platforms.
                val json = line.removePrefix("data: ").trim()
                if (json == "[DONE]") break

                // Parse JSON on Default dispatcher to keep IO thread free for reads.
                // Skip unrecognised / malformed events silently — lenient parsing
                // prevents false failure detection when the backend sends
                // non-standard content.
                val event = withContext(Dispatchers.Default) {
                    parseEvent(json)
                } ?: continue
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

    /**
     * Parse a single SSE data line into an [SseEvent].
     *
     * Uses [JSONObject.optString]/[JSONObject.optInt] throughout so that
     * missing or unexpected fields do NOT crash the SSE stream.  Returns
     * `null` for unrecognised event types or unparseable JSON, which the
     * caller silently skips.  This matches the lenient behaviour of the
     * original [io.github.dreamandroid.local.service.BackgroundGenerationService]
     * (pre-migration) that simply ignored lines it could not parse.
     */
    private fun parseEvent(json: String): SseEvent? {
        return try {
            val obj = JSONObject(json)
            val eventType = obj.optString("type", "")
            when {
                eventType == "progress" -> SseEvent.Progress(
                    step = obj.optInt("step", 0),
                    totalSteps = obj.optInt("total_steps", 0),
                    progress = obj.optDouble("progress", 0.0).toFloat(),
                    imageBase64 = obj.optString("image", "")
                )
                eventType == "complete" -> SseEvent.Complete(
                    imageBase64 = obj.optString("image", ""),
                    seed = obj.optLong("seed", -1L),
                    width = obj.optInt("width", 512),
                    height = obj.optInt("height", 512),
                    finishReason = obj.optString("finish_reason", "SUCCESS")
                )
                eventType == "error" -> {
                    val msg = obj.optString("message",
                        if (obj.has("errors")) {
                            val arr = obj.getJSONArray("errors")
                            if (arr.length() > 0) arr.getString(0) else "Unknown error"
                        } else "Unknown error"
                    )
                    SseEvent.Error(msg)
                }
                // ── Fallback: Stability-AI error envelope without "type" field ──
                // BKND-PROC-0008 refactoring changed sseErrorDone to produce
                // {"id":"...","name":"...","errors":[...]} without "type":"error".
                // Parsing "errors" as a fallback ensures these errors are surfaced
                // even if the C++ side omits the type discriminator.
                //
                // GUARD: Do NOT match if eventType is "progress" or "complete".
                // Those branches are above (when{} short-circuits on first match),
                // but this guard is an explicit safety measure against accidental
                // branch reordering — a progress/complete event with a spurious
                // "errors" key must never be misclassified as an error.
                eventType != "progress" && eventType != "complete" && obj.has("errors") -> {
                    val arr = obj.getJSONArray("errors")
                    val msg = if (arr.length() > 0) arr.getString(0) else "Unknown error"
                    SseEvent.Error(msg)
                }
                // Unrecognised type → skip silently (don't crash the SSE stream)
                else -> null
            }
        } catch (e: Exception) {
            // Malformed JSON → skip silently
            Log.w("SseStreamParser", "Skipping unparseable SSE event: ${json.take(120)}", e)
            null
        }
    }
}
