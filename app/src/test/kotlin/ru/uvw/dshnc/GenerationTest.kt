package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.uvw.dshnc.logic.Action
import ru.uvw.dshnc.logic.Generation

/**
 * The recovery rules of the epoch protocol, as a pure decision.
 *
 * The frames themselves are not tested here: a WebSocket harness cannot run offline, but
 * the decision "reset or not" is exactly what used to be wrong, so it lives outside the
 * service and is checked here.
 */
class GenerationTest {
    @Test
    fun `close 4409 is the epoche reset`() {
        assertEquals(Action.RESET, Generation.onClose(4409, "epoch"))
        assertEquals(Action.RESET, Generation.onClose(4409, ""))
    }

    @Test
    fun `close 4001 client-epoch is the same reset`() {
        assertEquals(Action.RESET, Generation.onClose(4001, "client-epoch"))
    }

    @Test
    fun `close 4001 replaced stops the service instead of re-announcing`() {
        assertEquals(Action.STOP, Generation.onClose(4001, "replaced"))
        assertEquals(Action.STOP, Generation.onClose(4001, " Replaced "))
    }

    @Test
    fun `an ordinary close is just a reconnect`() {
        assertEquals(Action.RECONNECT, Generation.onClose(1000, "bye"))
        assertEquals(Action.RECONNECT, Generation.onClose(1006, ""))
        assertEquals(Action.RECONNECT, Generation.onClose(0, "сбой: timeout"))
    }

    @Test
    fun `the first generation is adopted, a foreign one is the reset`() {
        assertEquals(Action.ADOPT, Generation.onServerEpoch(null, 12L))
        assertEquals(Action.IGNORE, Generation.onServerEpoch(12L, 12L))
        assertEquals(Action.RESET, Generation.onServerEpoch(12L, 13L))
        // the field may be absent altogether: nothing to decide
        assertEquals(Action.IGNORE, Generation.onServerEpoch(12L, null))
        assertEquals(Action.IGNORE, Generation.onServerEpoch(null, null))
    }

    @Test
    fun `409 for a generation is the reset, any other refusal is not`() {
        assertEquals(Action.RESET, Generation.onRefusal(409, "epoch-mismatch"))
        assertEquals(Action.RESET, Generation.onRefusal(409, "client-epoch-stale"))
        assertEquals(Action.IGNORE, Generation.onRefusal(409, "already-issued"))
        assertEquals(Action.IGNORE, Generation.onRefusal(400, "bad_request"))
        assertEquals(Action.IGNORE, Generation.onRefusal(401, null))
    }
}
