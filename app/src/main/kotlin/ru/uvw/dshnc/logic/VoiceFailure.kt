package ru.uvw.dshnc.logic

/**
 * What to tell a person when a take does not reach the host.
 *
 * Two very different things were saying the same sentence. A refused request has a reason, and the
 * server's own words are the answer to it; a broken connection has no reason at all, and telling
 * someone «хост не принял» only invites them to look for a mistake they did not make. No retries:
 * the take is kept, the sentence is short, and a second quiet line says the recording is still here.
 */
object VoiceFailure {
    /** Transport and gateway: nothing was decided about the request, it simply did not arrive. */
    const val NO_CONNECTION = "плохая связь, попробуйте ещё раз"

    /** The second, quieter line: the words are not lost while the person tries again. */
    const val SAVED_LOCALLY = "запись сохранена на телефоне"

    /** The gate answered instead of the application: a proxy or a timeout, not a refusal. */
    private val TRANSPORT_STATUSES = setOf(502, 503, 504)

    /** Exception names that are about the wire, never about the request. */
    private val TRANSPORT_ERRORS = listOf(
        "SocketTimeout",
        "Timeout",
        "UnknownHost",
        "ConnectException",
        "ConnectTimeout",
        "NoRouteToHost",
        "SSLException",
        "EOFException",
        "ClosedChannel",
    )

    /**
     * Is this a transport failure?
     *
     * @param status the HTTP status, or null when no answer came at all.
     * @param reason the text that came with it — an exception message carries its class name.
     */
    fun isTransport (status: Int?, reason: String): Boolean {
        // no answer at all: the request never reached anything that could judge it
        if (status == null) return true
        if (status in TRANSPORT_STATUSES) return true
        // a 4xx is the host speaking for itself; 5xx other than the gate still counts as the host
        return TRANSPORT_ERRORS.any { reason.contains(it, ignoreCase = true) }
    }

    /** The main line of the status bar for a failed take. */
    fun line (status: Int?, reason: String): String =
        if (isTransport(status, reason)) NO_CONNECTION else VoiceStatus.refused(reason)

    /** The quiet second line, shown when the take stayed on the phone. */
    fun note (status: Int?, reason: String): String? =
        if (isTransport(status, reason)) SAVED_LOCALLY else null
}
