package ru.uvw.dshnc.logic

/**
 * What the node does when the server says the whole connection is out of date.
 *
 * The epoch protocol has one recovery story: forget the generation, drop the socket, bump
 * the connection counter and announce `epoch: null` again. The old cursor (`known`) and
 * the restart declaration are gone with it, so the decisions are pure and small — they are
 * covered by a unit test instead of a WebSocket harness that cannot run offline.
 *
 * @see dsh-nodes/lib/ws.mjs for the close codes and the server side of the same rules
 */
enum class Action {
    /** Nothing to do: the numbers agree or the event carries no generation at all. */
    IGNORE,

    /** The first generation we ever see: remember it, this is not a change. */
    ADOPT,

    /** Forget the epoch, drop the socket and start over from the announce. */
    RESET,

    /** Another instance of this node is newer: stop the service, do not fight back. */
    STOP,

    /** An ordinary break: reconnect after a pause. */
    RECONNECT,
}

object Generation {
    /** The server's own close codes. 4xxx is the application range of the protocol. */
    const val CLOSE_CLIENT_EPOCH = 4001
    const val CLOSE_EPOCH = 4409

    /**
     * A socket closed: the code and the reason are the server's words.
     *
     * `4409` means the server lives in another generation, `4001 client-epoch` means our
     * connection counter is stale — in both cases this connection is over. `4001 replaced`
     * is different: the newer instance of this very node owns it now, so the only safe
     * answer is to stop instead of pushing it out and raising the counter forever.
     */
    fun onClose(code: Int, reason: String): Action = when {
        code == CLOSE_EPOCH -> Action.RESET
        code == CLOSE_CLIENT_EPOCH && reason.trim().equals("replaced", ignoreCase = true) -> Action.STOP
        code == CLOSE_CLIENT_EPOCH -> Action.RESET
        else -> Action.RECONNECT
    }

    /**
     * A generation named by the server: a `state` frame or a `commands` answer.
     *
     * A generation we know none of is adopted; a foreign one means the server has started
     * another life, and nothing it issued to the previous one is ours any more.
     */
    fun onServerEpoch(current: Long?, seen: Long?): Action = when {
        seen == null || seen < 0 -> Action.IGNORE
        current == null -> Action.ADOPT
        current != seen -> Action.RESET
        else -> Action.IGNORE
    }

    /**
     * An HTTP refusal: `409 epoch-mismatch` and `409 client-epoch-stale` are the same reset.
     *
     * The server refuses instead of repairing, because only the client knows whether it can
     * start over; a 409 for any other reason is not ours to act on.
     */
    fun onRefusal(status: Int, code: String?): Action =
        if (status == 409 && (code == "epoch-mismatch" || code == "client-epoch-stale")) {
            Action.RESET
        } else {
            Action.IGNORE
        }
}
