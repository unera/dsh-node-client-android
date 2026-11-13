package ru.uvw.dshnc.logic

/** What the watchman on the main screen decided to do about the service. */
enum class WatchAction { NONE, START, RESTART }

/**
 * The watchman of the background service, asked every time the person opens the app.
 *
 * Two different failures look alike from the outside and need different answers:
 *
 * - the service is not running at all (Android killed it, it crashed, a start from the
 *   background was refused) — there is nothing to heal, it has to be started;
 * - the service runs but has not reached the server for a long time — it has to be taken
 *   down and started again, because the loop it holds may be stuck on a dead token or a
 *   socket nobody will ever answer.
 *
 * The second case is a guess, so it is bounded twice: by a silence long enough that the
 * normal stretch of the ping cannot be mistaken for it, and by a cooldown, otherwise a
 * phone without network would be restarted every minute.
 *
 * Kept pure and free of Android so that the rule is covered by a unit test.
 */
object Watchdog {
    /** How long after a forced restart the next one is forbidden. */
    const val COOLDOWN_MS = 10 * 60 * 1000L

    /**
     * @param enabled the person turned the node on
     * @param known there is an address and an identity: a token, or a uuid to enrol with
     * @param uptimeSeconds how long the service has been alive, 0 — it is not running
     * @param connected the loop has the channel right now
     * @param silenceMs time since the last successful poll, -1 — never polled
     * @param pingSeconds the configured ping interval
     * @param stretchTimes how many times the ping is allowed to stretch when idle
     * @param sinceRestartMs time since the last forced restart, -1 — never
     */
    fun decide (
        enabled: Boolean,
        known: Boolean,
        uptimeSeconds: Long,
        connected: Boolean,
        silenceMs: Long,
        pingSeconds: Int,
        stretchTimes: Int,
        sinceRestartMs: Long,
    ): WatchAction {
        if (!enabled || !known) return WatchAction.NONE
        if (uptimeSeconds <= 0L) return WatchAction.START
        if (connected) return WatchAction.NONE
        // a service that has just come up has not had the time to poll yet: for it the
        // silence is its own uptime, not a gap in the work
        val silence = when {
            silenceMs >= 0L -> silenceMs
            else -> uptimeSeconds * 1000L
        }
        if (silence < limitSeconds(pingSeconds, stretchTimes) * 1000L) return WatchAction.NONE
        if (sinceRestartMs in 0 until COOLDOWN_MS) return WatchAction.NONE
        return WatchAction.RESTART
    }

    /**
     * Where silence stops being suspicious.
     *
     * The ping stretches gradually up to `stretchTimes` while the node is idle, and a round
     * of reconnect backoff sits on top of that, so the limit is three full stretches plus two
     * minutes — with the default 15 s and 10× that is nine and a half minutes.
     */
    fun limitSeconds (pingSeconds: Int, stretchTimes: Int): Long {
        val ping = if (pingSeconds in 1..3600) pingSeconds.toLong() else 15L
        val stretch = if (stretchTimes in 1..60) stretchTimes.toLong() else 10L
        return ping * stretch * 3 + 120
    }
}
