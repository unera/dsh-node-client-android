package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.uvw.dshnc.store.NodeLog

/**
 * The log has a size cap, and the only decision in it is what to throw away when the cap is
 * reached. That decision is checked here: dropping the wrong half would either lose the
 * recent story or keep a file that grows forever.
 */
class NodeLogTest {
    @Test
    fun `a trim keeps the newer half`() {
        val lines = (1..10).map { "строка $it" }
        val kept = NodeLog.rotated(lines)
        assertEquals(5, kept.size)
        assertEquals("строка 6", kept.first())
        assertEquals("строка 10", kept.last())
    }

    @Test
    fun `an odd number of lines does not lose the newest one`() {
        val kept = NodeLog.rotated((1..7).map { "строка $it" })
        assertEquals("строка 7", kept.last())
        assertTrue(kept.size >= 3)
    }

    @Test
    fun `even the shortest log keeps its newest line`() {
        // rotation is only ever called when the cap is reached, so a two-line log is a
        // degenerate case, not a normal one — but the newest line must survive it too
        assertEquals(listOf("две"), NodeLog.rotated(listOf("одна", "две")))
    }
}
