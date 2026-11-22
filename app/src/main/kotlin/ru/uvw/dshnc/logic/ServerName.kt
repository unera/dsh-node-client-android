package ru.uvw.dshnc.logic

/**
 * The server address as a person names it in the shade.
 *
 * The notification title used to be the device name, which says nothing about where the node is
 * connected: two phones show the same word, and an address one cannot see is an address one
 * cannot check. The title is the host now — no scheme, no path, and a port only when it is not
 * the default one, because `https://` and `/dsh-nodes` are noise in a line this short.
 */
object ServerName {
    /**
     * The host of an address, or null when there is nothing to show.
     *
     * Accepts what the store holds: a full URL, a bare host, or a link without a scheme. The
     * parse must never throw — the notification is built on the hot path, and an address a
     * person typed by hand is exactly the input that breaks parsers.
     */
    fun host (base: String?): String? {
        val raw = base?.trim().orEmpty()
        if (raw.isEmpty()) return null
        // `user@host` and a bare `host:port` are both addresses without a scheme
        val withScheme = if (raw.contains("://")) raw else "https://" + raw.substringAfter("://")
        val parsed = runCatching { java.net.URI(withScheme) }.getOrNull() ?: return null
        val host = parsed.host?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val port = parsed.port
        // the default port is not part of the name: it is the same server with or without it
        return if (port > 0 && port != 443 && port != 80) "$host:$port" else host
    }
}
