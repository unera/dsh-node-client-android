package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.uvw.dshnc.logic.Commandno

/**
 * The number the node claims, as the protocol defines it.
 *
 * The loop this guards against is not in the frames: it is the claim. A node that restarts
 * its process claims zero while the server has already spent two numbers, and every `state`
 * frame then comes back with another one — the server pushes whenever its number is higher.
 */
class CommandnoTest {
    @Test
    fun `the number the server names is accepted`() {
        assertEquals(2L, Commandno.adopt(0L, 2L))
        // the same number twice changes nothing
        assertEquals(2L, Commandno.adopt(2L, 2L))
    }

    @Test
    fun `the claim never goes backwards`() {
        assertEquals(9L, Commandno.adopt(9L, 2L))
    }

    @Test
    fun `a server that names nothing changes nothing`() {
        assertEquals(0L, Commandno.adopt(0L, null))
        assertEquals(4L, Commandno.adopt(4L, null))
    }

    @Test
    fun `the whole loop the panel showed us`() {
        // the phone: a fresh process claims zero, the server has already spent two numbers
        var claimed = 0L
        repeat(3) { claimed = Commandno.adopt(claimed, 2L) }
        assertEquals(2L, claimed)
        // claim equals the server's number now: no push follows, the loop is over
        // then a command number 3 arrives and is executed
        claimed = maxOf(claimed, 3L)
        assertEquals(3L, claimed)
        // an older server number cannot pull the claim back
        assertEquals(3L, Commandno.adopt(claimed, 1L))
    }
}
