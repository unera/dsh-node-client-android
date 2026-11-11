package ru.uvw.dshnc.logic

/**
 * The command number the node claims to know.
 *
 * `max_commandno` is the last number the server spent, and numbers are honest: they grow by
 * one, are never reused, and a number spent on a command the node never saw (a cancel, a
 * timeout) stays spent.
 *
 * Hence the rule, written down in `spec/CLIENT-LOOP.md` — the client **accepts the number
 * the server names**, both from the fetch answer and from a `state` frame, instead of
 * counting the maximum over the commands it received. Otherwise the hole in the numbering
 * never closes: the server has 5, the client remembers 4, and every `state` frame comes back
 * with another one.
 *
 * What the node claims in its own `state` frame is a statement, not an identity: a mismatch
 * is only logged by the server with both numbers.
 */
object Commandno {
    /**
     * @param current the number this process knows so far
     * @param named the server's `max_commandno`; `null` when it named none
     * @return the number to claim from now on — never backwards
     */
    fun adopt (current: Long, named: Long?): Long {
        // a server that names nothing is not a server that names zero
        if (named == null) return current
        return maxOf(current, named)
    }
}
