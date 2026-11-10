package ru.uvw.dshnc.logic

import org.json.JSONObject

/**
 * How long a command may wait.
 *
 * The order is the desktop one and it is not arbitrary: a person or a model asked for a
 * pause explicitly, then comes the pause asked for in the arguments, then the one the
 * server set for the command as a whole, and only then the default of the method.
 *
 * The arithmetic lives here, apart from the transport, so that it can be tested without
 * an Android runtime — the same way as the ping policy.
 */
object Timeouts {
    /**
     * The pause in milliseconds, or null when nobody named one.
     *
     * @param explicitMs `args.timeoutMs` — the most explicit form
     * @param seconds `args.timeout` — seconds inside the arguments
     * @param commandSeconds the command's own `timeout` set by the server, in seconds
     */
    fun resolveMs (explicitMs: Double?, seconds: Double?, commandSeconds: Double?): Int? {
        if (explicitMs != null && explicitMs > 0) return explicitMs.toInt()
        if (seconds != null && seconds > 0) return (seconds * 1000).toInt()
        if (commandSeconds != null && commandSeconds > 0) return (commandSeconds * 1000).toInt()
        return null
    }

    /** The same, taken from the command arguments and the command's own timeout. */
    fun resolveMs (args: JSONObject?, commandSeconds: Double): Int? = resolveMs(
        explicitMs = if (args != null && args.has("timeoutMs") && !args.isNull("timeoutMs")) args.optDouble("timeoutMs", 0.0) else null,
        seconds = if (args != null && args.has("timeout") && !args.isNull("timeout")) args.optDouble("timeout", 0.0) else null,
        commandSeconds = commandSeconds,
    )
}
