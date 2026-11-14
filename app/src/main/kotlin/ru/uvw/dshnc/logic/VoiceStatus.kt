package ru.uvw.dshnc.logic

/**
 * The one line under the conversation, and the one in the device log.
 *
 * A person needs to know what happened to their take — it was accepted, it was refused and why —
 * and nothing about where the file lies: `/home/…/cache/voice/incoming/<uuid>.wav` on the screen is
 * noise that looks like a problem. The path still goes to the device log, where it is the first
 * thing worth knowing when something goes wrong.
 */
object VoiceStatus {
    /** The take reached the host: duration only, no destination. */
    fun accepted (seconds: Double): String = "принято хостом · ${clock(seconds)} с"

    /** The host refused, in its own words — with any path taken out of the sentence. */
    fun refused (reason: String): String {
        val clean = withoutPaths(reason).trim().trimEnd('.').trim()
        return if (clean.isEmpty()) "хост не принял" else "хост не принял: $clean"
    }

    /** What the device log keeps: the path is the point of it. */
    fun logDetails (what: String, path: String?): String =
        if (path.isNullOrBlank()) what else "$what $path"

    /** Drop absolute paths from a sentence the server wrote: they mean nothing to a reader. */
    fun withoutPaths (text: String): String =
        text.replace(Regex("""/[^\s,;:()]+"""), "")
            .replace(Regex("""\s{2,}"""), " ")

    private fun clock (seconds: Double): String {
        val safe = if (seconds.isFinite() && seconds > 0) seconds else 0.0
        return String.format(java.util.Locale.US, "%.1f", safe)
    }
}
