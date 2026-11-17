package ru.uvw.dshnc.logic

/**
 * How many times being pushed out of the channel is our own doing.
 *
 * The server keeps one session per node, and it closes the older socket with `4001 replaced`
 * whenever a newer connection arrives. Our own reconnect can be that newer connection: a socket
 * we closed a moment ago sometimes finishes its handshake after the next one is already current,
 * the server then holds the late one and drops the current one — and the node, reading that as
 * "another instance took over", used to sleep until a person opened the app.
 *
 * A real second instance and our own churn look identical in a single close, so the rule counts:
 * a channel that opened resets the count, and only a streak of pushes means somebody else is
 * genuinely holding the node. Kept pure and apart from the service because this is the decision
 * that put the phone to sleep for hours.
 */
object Eviction {
    /** How many pushes in a row are still treated as our own churn. */
    const val CHURN_LIMIT = 3

    /**
     * @param streak how many `4001 replaced` closes arrived in a row since the last open channel
     * @returns true when the node has to stay down until a person acts
     */
    fun sleep (streak: Int): Boolean = streak > CHURN_LIMIT

    /** A channel opened: whoever pushed us out before is no longer relevant. */
    fun onOpen (): Int = 0

    /** One more push, from whatever caused it. */
    fun onReplaced (streak: Int): Int = streak + 1
}
