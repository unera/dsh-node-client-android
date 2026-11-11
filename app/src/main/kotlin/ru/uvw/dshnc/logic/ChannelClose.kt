package ru.uvw.dshnc.logic

/**
 * What a closed socket means — including the one thing it must never mean.
 *
 * The service opens the next channel before the previous one has finished closing, and the
 * server, which keeps one session per node, drops the old one with `4001 replaced`. That
 * close is our own doing: read as "another instance of this node took over", it persisted the
 * `replaced` flag and the node never came back on its own — the alarm fired and got nothing,
 * silently, for hours.
 *
 * So the identity of the socket comes first and the close code second. Kept pure and apart
 * from the service because this is exactly the decision that went wrong.
 */
object ChannelClose {
    /**
     * @param isCurrent whether the closed socket is the one the loop holds right now
     */
    fun decide (isCurrent: Boolean, code: Int, reason: String): Action =
        if (!isCurrent) Action.IGNORE else Generation.onClose(code, reason)
}
