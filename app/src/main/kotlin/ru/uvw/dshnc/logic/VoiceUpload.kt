package ru.uvw.dshnc.logic

import org.json.JSONObject

/**
 * Handing one take to the host in a single request.
 *
 * The take is an event, not an answer to a command: the phone records when a person speaks, and
 * the host has not necessarily asked for anything at that moment. So the request carries no
 * `seq` — `/v1/upload-file` accepts a device event in its own right — and the destination path is
 * the server's business, not the phone's. That removes three round trips (want → record → copy)
 * and every question about which directory on the phone the host may read.
 *
 * The `id` is minted on the device for one press of the button: a retry of the same press must
 * not become a second turn in the conversation.
 */
object VoiceUpload {
    /** The intent name in the query: `/v1/upload-file?type=voice…`. */
    const val TYPE = "voice"

    /** The container this client produces, and the only one it sends. */
    const val FORMAT = "wav"

    /**
     * The request address for one take.
     *
     * @param base the host root, with or without a trailing slash.
     * @param id the press identity, minted by [newId].
     * @returns the full URL, query included.
     */
    fun url (base: String, id: String): String =
        base.trimEnd('/') + "/v1/upload-file?type=" + TYPE + "&filetype=" + FORMAT + "&id=" + encode(id)

    /**
     * The headers besides the token and the length, which the transport sets itself.
     *
     * @param seconds how long the take lasts.
     * @returns the header values as they go on the wire.
     */
    fun headers (seconds: Double): Map<String, String> = mapOf(
        "x-seconds" to format(seconds),
    )

    /** A duration as the wire wants it: one decimal place, never `4,8` and never `NaN`. */
    fun format (seconds: Double): String {
        val safe = if (seconds.isFinite() && seconds > 0) seconds else 0.0
        return String.format(java.util.Locale.US, "%.1f", safe)
    }

    /** What became of the take, as the conversation list has to tell the person. */
    sealed class Result {
        /** The host took it: the file on the phone is no longer needed. */
        data class Accepted(
            val path: String?,
            val bytes: Long?,
            val seconds: Double?,
        ) : Result()

        /** The host did not take it: the take stays on the phone for the older route. */
        data class Failed(val reason: String) : Result()
    }

    /**
     * Read the host's answer.
     *
     * 202 (and any other 2xx, should the server grow a variant) means the take is accepted. A
     * refusal keeps the server's own words, because that sentence is what the person reads.
     */
    fun interpret (status: Int, text: String?): Result {
        val payload = try {
            if (text.isNullOrBlank()) JSONObject() else JSONObject(text)
        } catch (error: Throwable) {
            JSONObject()
        }
        if (status in 200..299) {
            return Result.Accepted(
                path = payload.optString("path").takeIf { it.isNotBlank() },
                bytes = if (payload.has("bytes")) payload.optLong("bytes") else null,
                seconds = if (payload.has("seconds")) payload.optDouble("seconds") else null,
            )
        }
        val code = payload.optString("code").takeIf { it.isNotBlank() }
        val message = payload.optString("message").takeIf { it.isNotBlank() }
        val reason = when {
            message != null && code != null -> "$message ($code)"
            message != null -> message
            code != null -> code
            else -> "HTTP $status"
        }
        return Result.Failed(reason)
    }

    /** One press of the button, named so that a retry stays the same turn. */
    fun newId (): String = java.util.UUID.randomUUID().toString()

    /** The line the conversation shows when the host refused the take. */
    const val REFUSED_LINE = "хост не принял, сохранено локально"

    private fun encode (value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}
