package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.uvw.dshnc.logic.ChannelState
import ru.uvw.dshnc.logic.Link

/**
 * The three words in the notification are the whole conversation with the person about the
 * link: the labels follow the channel, and no other event may borrow them.
 */
class ChannelStateTest {
    @Test
    fun `at a start with no channel the label is connecting`() {
        assertEquals(Link.CONNECTING, ChannelState.of(channelOpen = false, everOpen = false))
    }

    @Test
    fun `an open channel is connected`() {
        assertEquals(Link.CONNECTED, ChannelState.of(channelOpen = true, everOpen = false))
        assertEquals(Link.CONNECTED, ChannelState.of(channelOpen = true, everOpen = true))
    }

    @Test
    fun `a lost channel that once existed is restoring, never connecting again`() {
        assertEquals(Link.RESTORING, ChannelState.of(channelOpen = false, everOpen = true))
    }
}
