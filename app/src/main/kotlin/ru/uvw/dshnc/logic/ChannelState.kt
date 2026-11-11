package ru.uvw.dshnc.logic

/**
 * What the notification is allowed to say.
 *
 * The three words are the whole conversation with the person about the link, so the
 * decision is kept here as arithmetic instead of being scattered over the service: a
 * label computed in five places drifts, and the drift is what people notice first.
 *
 * The rule is what the owner asked for: "connecting" belongs to the start, "restoring"
 * to a channel that already existed and was lost, "connected" to a live channel — and
 * nothing else. A switch of the connection counter, an announce over HTTP or an error
 * text are not states of the channel and must not be named as if they were.
 */
enum class Link {
    /** No channel of this service start has ever opened: the first attempt is running. */
    CONNECTING,

    /** The channel is open. */
    CONNECTED,

    /** A channel was open before and is being rebuilt. */
    RESTORING,
}

object ChannelState {
    fun of (channelOpen: Boolean, everOpen: Boolean): Link = when {
        channelOpen -> Link.CONNECTED
        everOpen -> Link.RESTORING
        else -> Link.CONNECTING
    }
}
