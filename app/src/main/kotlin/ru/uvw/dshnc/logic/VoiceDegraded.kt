package ru.uvw.dshnc.logic

import org.json.JSONObject

/**
 * When the host could not synthesize the voice and used the phone's engine instead.
 *
 * Falling back silently is the wrong kind of politeness: the answer sounds different, and nobody
 * knows why. So the reason travels with the answer, the screen says it out loud in a line of its
 * own, and a person can send it back to the host with one tap — with the exchange id, so the other
 * side knows which answer is meant.
 */
object VoiceDegraded {
    /** What the host says when its own voice failed. */
    data class Report (val kind: String, val message: String)

    /** The line under the bubble: what happened to the voice. */
    const val LINE = "синтез на сервере не сработал — озвучено голосом телефона"

    /** After a successful report the button is gone and only this stays. */
    const val SENT = "отчёт отправлен"

    /** A refusal shows the server's own words. */
    fun failed (reason: String): String = "не удалось отправить отчёт: $reason"

    /**
     * Read the `degraded` field of an answer.
     *
     * An object with a kind and a message; anything else is ignored rather than half-read, so a
     * malformed field cannot put an empty line on the screen.
     */
    fun parse (value: Any?): Report? {
        val payload = value as? JSONObject ?: return null
        val kind = payload.optString("kind").trim()
        val message = payload.optString("message").trim()
        if (kind.isEmpty()) return null
        return Report(kind, message)
    }

    /**
     * The body of the bug report.
     *
     * @param id the exchange the answer belongs to.
     * @param report what the host said went wrong.
     * @param app the client version, so the reporter knows what built it.
     */
    fun body (id: String, report: Report, app: String): JSONObject = JSONObject()
        .put("type", "bug-report")
        .put("id", id)
        .put("kind", report.kind)
        .put("text", report.message)
        .put("app", app)
}
