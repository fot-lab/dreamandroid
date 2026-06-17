package io.github.dreamandroid.local.core.error

import org.json.JSONException
import java.io.IOException

sealed class AppError(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message) {

    data class Network(
        override val message: String,
        val code: Int? = null,
        override val cause: Throwable? = null
    ) : AppError(message, cause)

    data class Backend(
        override val message: String
    ) : AppError(message)

    /**
     * BKND-PROC-0008: Backend rejected the request because it is already
     * processing another generation.  Callers should retry after [retryAfterMs].
     */
    data class BackendBusy(
        override val message: String,
        val retryAfterMs: Long = 3000L,
    ) : AppError(message)

    data class Parse(
        override val message: String,
        override val cause: Throwable? = null
    ) : AppError(message, cause)

    data class Storage(
        override val message: String,
        override val cause: Throwable? = null
    ) : AppError(message, cause)

    companion object {
        fun from(e: Throwable): AppError = when (e) {
            is AppError -> e
            is IOException -> Network(e.message ?: "IO Error", cause = e)
            is JSONException -> Parse(e.message ?: "Parse Error", cause = e)
            else -> Backend(e.message ?: "Unknown Error")
        }
    }
}
