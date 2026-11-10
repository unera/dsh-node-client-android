package ru.uvw.dshnc.logic

/**
 * Ping schedule: the interval grows while the server stays silent and is reset once
 * there was work.
 *
 * The arithmetic lives outside the service because it has to be tested: a mistake here
 * meant "the node looks dead" or "we ping more often than necessary".
 */
object PingPolicy {
    /** The next interval in seconds and the new stretch value. */
    fun next (baseInterval: Int, factor: Int, stretch: Int, hadWork: Boolean): Pair<Int, Int> {
        val safeFactor = factor.coerceAtLeast(1)
        val safeBase = baseInterval.coerceAtLeast(0)
        if (safeBase == 0) return 0 to 1
        if (hadWork) return safeBase to 1
        val nextStretch = (stretch + 1).coerceAtMost(safeFactor)
        return safeBase * nextStretch to nextStretch
    }

    /** Is it time to consider the channel dead: no answer for longer than two intervals. */
    fun isSilent (silenceMs: Long, intervalSeconds: Int, stretch: Int): Boolean {
        val interval = intervalSeconds.coerceAtLeast(1) * stretch.coerceAtLeast(1) * 1000L
        return silenceMs > interval * 2
    }
}
