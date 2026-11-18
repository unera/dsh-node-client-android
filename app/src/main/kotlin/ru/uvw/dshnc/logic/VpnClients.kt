package ru.uvw.dshnc.logic

/**
 * The VPN clients this node knows how to switch.
 *
 * Android has no system call that turns a VPN on or off: a tunnel belongs to the app that made
 * it. What a client can offer is a plugin command — a broadcast, a receiver name and one profile
 * id — and every client names them differently. So the list is written down here, one entry per
 * client, instead of being guessed by the caller: a node that accepts "some package and some
 * action" is a node that can be asked to broadcast anything.
 */
data class VpnClient (
    /** The name a caller uses: stable, short, ours. */
    val type: String,
    /** What a person sees on the screen. */
    val title: String,
    val packageName: String,
    /** The receiver that takes the command, inside that package. */
    val receiver: String,
) {
    /** The profile id is per person, so it lives in the store, not here. */
    fun grantKey (): String = "vpn-$type"

    companion object {
        val KNOWN = listOf(
            VpnClient(
                type = "v2raytun",
                title = "v2RayTun",
                packageName = "com.v2raytun.android",
                receiver = "com.v2raytun.android.receiver.TaskerReceiver",
            ),
        )

        /** @returns the client by type, or null when nobody knows it. */
        fun byType (type: String): VpnClient? =
            KNOWN.firstOrNull { it.type.equals(type.trim(), ignoreCase = true) }

        /** @returns the types a caller may name, for the refusal to list them. */
        fun types (): String = KNOWN.joinToString(", ") { it.type }
    }
}
