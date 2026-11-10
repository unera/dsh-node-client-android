package ru.uvw.dshnc.logic

/**
 * The "I restarted" declaration: what goes into `known` in the presence frames.
 *
 * `known: null` means for the server "everything you issued to me died with the process",
 * and it closes such commands as `node_restarted`. The server reads it from `check_state`,
 * not from `hello` — a node that stayed silent in the wait frame kept its stale commands
 * hanging in `processing` until the timeout.
 *
 * That is why the declaration is said twice, both times at the start of ONE connection:
 * in `hello` and in the first `check_state` of that same connection. Everything after
 * that carries the number: repeated in every frame it would close commands that are alive
 * and well, and an `exec` would die a hundred milliseconds after being issued.
 *
 * The state is a property of the process, not of a connection: a reconnect keeps the same
 * process and the same running commands, so the declaration is not repeated there.
 */
class Declaration(pending: Boolean = true) {
    /** The process still owes the server the declaration. */
    private var pending = pending

    /** The `known` value for the `hello` frame. */
    fun hello(known: Long): Long? {
        if (!pending) return known
        return null
    }

    /**
     * The `known` value for a `check_state` frame.
     *
     * The declaration is spent by the first one: from then on the number goes out, and the
     * server hears about the restart exactly once.
     */
    fun checkState(known: Long): Long? {
        if (!pending) return known
        pending = false
        return null
    }

    /** Whether the declaration has already gone out: the first command has to wait for it. */
    fun announced(): Boolean = !pending

    /** The server generation changed: declare the restart again, once. */
    fun restart() {
        pending = true
    }
}
