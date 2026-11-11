package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.uvw.dshnc.logic.Running

/**
 * The registry of live processes: `#INTERNAL.CANCEL` finds a process by its number, and a
 * number that is already released must not point at a dead process.
 */
class RunningTest {
    private class FakeProcess : Process() {
        override fun getOutputStream () = throw UnsupportedOperationException()
        override fun getInputStream () = throw UnsupportedOperationException()
        override fun getErrorStream () = throw UnsupportedOperationException()
        override fun waitFor () = 0
        override fun waitFor (timeout: Long, unit: java.util.concurrent.TimeUnit) = true
        override fun exitValue () = 0
        override fun destroy () {}
    }

    @Test
    fun `a process is found by its number and only once`() {
        val running = Running()
        val process = FakeProcess()
        running.put(7, process)
        assertEquals(1, running.count())
        assertSame(process, running.take(7))
        // the second cancel finds nothing: one cancel wins the process, the other is late
        assertNull(running.take(7))
        assertTrue(running.isEmpty())
    }

    @Test
    fun `forgetting a finished command frees its number`() {
        val running = Running()
        running.put(9, FakeProcess())
        running.forget(9)
        assertTrue(running.isEmpty())
        assertNull(running.take(9))
    }
}
