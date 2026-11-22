package ru.uvw.dshnc.logic

/**
 * The node's name and aliases, as the phone keeps them.
 *
 * The phone keeps its own copy and changes it locally: a name typed here changes nothing on the
 * server until the person presses "send". That is why the rules of the local copy live apart
 * from the screen — they are the same rules the screen and the exchange with the server must
 * agree on, and they can be tested without an Android runtime.
 */
object Naming {
    /** How long a name may be. The server's own limit. */
    const val NAME_LIMIT = 64

    /** How many aliases one node may carry, the name included. */
    const val ALIAS_LIMIT = 16

    /**
     * The name this node goes by on the phone.
     *
     * What the person typed wins, then the phone's own model, then a neutral word: the name is
     * sent when the node is paired or enrolled, and an empty one would leave it as `node-ab12cd34`.
     */
    fun localName (stored: String?, model: String?): String =
        stored?.trim()?.takeIf { it.isNotEmpty() }
            ?: model?.trim()?.takeIf { it.isNotEmpty() }
            ?: "android"

    /**
     * Aliases as a person types them: commas or newlines, any spacing.
     *
     * Empty entries are not aliases, a repeat is not a second address, and the list is capped
     * before it is written — the same ceiling the server applies, so that sending a list cannot
     * silently lose its tail here and come back shorter from the server for no visible reason.
     */
    fun parseAliases (text: String): List<String> = text
        .split(',', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .take(ALIAS_LIMIT)
        .map { it.take(NAME_LIMIT) }

    /** The list back into the form the field shows: one line, comma separated. */
    fun formatAliases (aliases: List<String>): String = aliases.joinToString(", ")

    /**
     * Aliases worth sending: the list without the name.
     *
     * The name is not one more alias in the field — it is its own field, and the server adds it
     * back to the list on its side. Showing it twice would make a person edit the same word in
     * two places.
     */
    fun withoutName (aliases: List<String>, name: String): List<String> =
        aliases.filter { it.lowercase() != name.trim().lowercase() }
}
