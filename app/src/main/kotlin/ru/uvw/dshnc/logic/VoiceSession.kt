package ru.uvw.dshnc.logic

import org.json.JSONObject

/**
 * Starting a new conversation on the host.
 *
 * The event names what happened, nothing more: `/v1/events` takes a `type`, and `voice` there is
 * refused — an audio upload has its own route (`/v1/upload-file`), and a session boundary is not
 * an audio. The old body (`{"type":"voice","action":"new-session"}`) came back as
 * `400 bad_type`, and the screen turned that into the useless «хост не ответил».
 */
object VoiceSession {
    /** The event type that means "from here on, a new conversation". */
    const val TYPE = "new-session"

    /** The body, exactly as it goes on the wire. */
    fun body (): JSONObject = JSONObject().put("type", TYPE)

    /** What became of the request. */
    sealed class Result {
        /** The host started a new conversation: the local history may be dropped. */
        object Accepted : Result()

        /** The host refused or was unreachable, in its own words when it gave any. */
        data class Failed(val reason: String) : Result()
    }

    /**
     * Read the host's answer.
     *
     * A refusal is shown to the person with the server's text: a generic "no answer" hid the one
     * sentence that explained what was wrong with the request.
     */
    fun interpret (status: Int, text: String?): Result {
        if (status in 200..299) return Result.Accepted
        val payload = try {
            if (text.isNullOrBlank()) JSONObject() else JSONObject(text)
        } catch (error: Throwable) {
            JSONObject()
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

    /** The line shown while nothing came back at all: only for a transport failure. */
    const val NO_ANSWER = "хост не ответил"
}
