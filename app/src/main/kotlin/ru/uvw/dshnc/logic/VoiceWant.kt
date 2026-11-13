package ru.uvw.dshnc.logic

import org.json.JSONObject

/**
 * The knock on the host's door: "a person is about to speak".
 *
 * The screen records on its own, but the host learns about a take only when it calls `record` —
 * which is backwards for a radio: the button is pressed first, and the host is the one who has to
 * start listening. So a press sends one event with no session and no text, only the intent, and the
 * server answers 202 with the command that follows.
 *
 * Nothing here decides whether the recording happens: a refused or unreachable host must not cost
 * the person their words, so the announcement is a hint and the local take is the fact.
 */
object VoiceWant {
    /** What the server is told: the intent, and which button habit produced it. */
    fun body (mode: VoiceMode): JSONObject = JSONObject()
        .put("voice", JSONObject()
            .put("want", WANT)
            .put("mode", mode.wire))

    /** What became of the announcement, as the screen has to tell the person. */
    sealed class Result {
        /** The host took the hint; a `record` command is on its way. */
        data class Accepted(val text: String? = null) : Result()

        /** The host did not take it: the take stays local and the person is told so. */
        data class Failed(val reason: String) : Result()
    }

    /**
     * Read the host's answer.
     *
     * 2xx (the agreed code is 202) is acceptance; anything else is a refusal the person should see
     * in their own words, and the take is kept either way.
     */
    fun interpret (status: Int, text: String?): Result {
        val payload = try {
            if (text.isNullOrBlank()) JSONObject() else JSONObject(text)
        } catch (error: Throwable) {
            JSONObject()
        }
        if (status in 200..299) {
            val note = payload.optString("message").takeIf { it.isNotBlank() }
            return Result.Accepted(note)
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

    /** The line the conversation list shows for an announcement that did not land. */
    const val REFUSED_LINE = "хост не ответил, запись сохранена локально"

    /** The intent name on the wire. */
    const val WANT = "record"
}
