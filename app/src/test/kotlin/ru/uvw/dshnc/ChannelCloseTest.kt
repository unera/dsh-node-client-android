package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.uvw.dshnc.logic.Action
import ru.uvw.dshnc.logic.ChannelClose

/**
 * The regression that cost the most: a close from a channel the service had already replaced,
 * named `4001 replaced` by the server, was read as "another instance of this node took over".
 * The node then went to sleep until a human opened the app — two hours and twenty minutes in
 * the log, with the alarm firing and getting nothing.
 */
class ChannelCloseTest {
    @Test
    fun `a close from a channel we already replaced decides nothing`() {
        assertEquals(Action.IGNORE, ChannelClose.decide(isCurrent = false, code = 4001, reason = "replaced"))
        assertEquals(Action.IGNORE, ChannelClose.decide(isCurrent = false, code = 4409, reason = ""))
        assertEquals(Action.IGNORE, ChannelClose.decide(isCurrent = false, code = 1000, reason = "bye"))
    }

    @Test
    fun `only a channel we really hold can be displaced by another instance`() {
        assertEquals(Action.STOP, ChannelClose.decide(isCurrent = true, code = 4001, reason = "replaced"))
    }

    @Test
    fun `the ordinary reasons still work on the current channel`() {
        assertEquals(Action.RESET, ChannelClose.decide(isCurrent = true, code = 4409, reason = ""))
        assertEquals(Action.RESET, ChannelClose.decide(isCurrent = true, code = 4001, reason = "client-epoch"))
        assertEquals(Action.RECONNECT, ChannelClose.decide(isCurrent = true, code = 1000, reason = "bye"))
    }
}
