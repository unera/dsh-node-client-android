package ru.uvw.dshnc.logic

/**
 * Parsing of the connection link.
 *
 * Taken out of the activity so that tests can check it: this is exactly where it is
 * easy to get things wrong (encoding, stray spaces, someone else's address), and a
 * mistake costs "it will not connect, and it is not clear why".
 */
object LinkParser {
    /** What was pulled out of the link: the server address and the code. */
    data class Parsed(val base: String, val code: String)

    /**
     * @param raw the string from a QR code or from the input field
     * @return the parsed value, or null if this is not a connection link
     */
    fun parse (raw: String?): Parsed? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = Regex("[?&]v=([^&]+)").find(text) ?: return null
        val base = text.substringBefore("/pair").trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) return null
        val code = try {
            java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
        } catch (error: Throwable) {
            match.groupValues[1]
        }
        if (code.isBlank()) return null
        return Parsed(base, code)
    }
}
