package ru.uvw.dshnc.logic

/**
 * Pipes: a command's stream instead of a file.
 *
 * Only decisions live here — the network and the processes live in `PipeApi`. Kept apart
 * so that they can be tested without an Android runtime: the arithmetic is exactly the
 * part that is easy to get wrong, and the mistake shows up as "the file is there, but it
 * is incomplete".
 *
 * The rules are the desktop ones (`dsh-node-client/lib/pipe.mjs`); two clients of one
 * protocol must not disagree about what a failed pipe is.
 */
object Pipe {
    /** stderr is a hint, not a log: a long one must not take up the whole result. */
    const val STDERR_TAIL = 4096

    /** Bad arguments, with the code the server already knows. */
    class BadArgs (val code: String, message: String) : IllegalArgumentException(message)

    /** The arguments as they came, without the transport: JSON is `PipeApi`'s business. */
    data class Raw (
        val command: String?,
        val hostPath: String?,
        val cwd: String? = null,
        val timeoutMs: Double? = null,
        val timeoutSeconds: Double? = null,
    )

    data class Args (val command: String, val hostPath: String, val cwd: String?, val timeoutMs: Long)

    data class Verdict (val ok: Boolean, val code: String?, val message: String?)

    /**
     * Parse the arguments, refusing before a process or a socket exists.
     *
     * @param direction `from` — the node gives its output, `to` — the node takes the data
     */
    fun args (raw: Raw, direction: String): Args {
        val command = raw.command?.trim().orEmpty()
        val hostPath = raw.hostPath?.trim().orEmpty()
        if (command.isEmpty()) throw BadArgs("bad_args", "нужна команда: что выполнять")
        if (hostPath.isEmpty()) {
            throw BadArgs(
                "bad_args",
                if (direction == "from") "нужен hostPath: куда на сервере положить вывод"
                else "нужен hostPath: откуда на сервере брать данные",
            )
        }
        // zero and garbage mean "no limit"; a pipe often carries a backup and a cap there is
        // pointless — the same rule as on the desktop
        val explicit = raw.timeoutMs
        val seconds = when {
            explicit != null -> explicit
            raw.timeoutSeconds != null -> raw.timeoutSeconds * 1000
            else -> 0.0
        }
        val timeoutMs = if (seconds.isFinite() && seconds > 0) seconds.toLong() else 0L
        val cwd = raw.cwd?.trim().orEmpty().ifEmpty { null }
        return Args(command, hostPath, cwd, timeoutMs)
    }

    /**
     * The verdict on a finished pipe.
     *
     * "The script failed" and "the script produced nothing" are different things: empty
     * output with code 0 is a legitimate result (`grep` with no matches), not an error.
     */
    fun outcome (exitCode: Int, timedOut: Boolean, stderrTail: String): Verdict {
        val tail = stderrTail.trim()
        val detail = if (tail.isEmpty()) "" else "\n[stderr] $tail"
        if (timedOut) return Verdict(false, "timeout", "команда не уложилась в срок$detail")
        if (exitCode != 0) return Verdict(false, "exit_nonzero", "код $exitCode$detail")
        return Verdict(true, null, null)
    }

    /** The tail of stderr, capped. */
    fun stderrTail (text: String, limit: Int = STDERR_TAIL): String =
        if (text.length <= limit) text else text.takeLast(limit)
}
