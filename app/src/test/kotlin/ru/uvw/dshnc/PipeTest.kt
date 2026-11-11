package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.uvw.dshnc.logic.Pipe

/** The pipe decisions: they are arithmetic and wording, so they are checked without Android. */
class PipeTest {
    @Test
    fun `arguments are read with the desktop rules`() {
        val args = Pipe.args(Pipe.Raw(
            command = "  tar -c /sdcard/DCIM  ",
            hostPath = " /tmp/arch.tar ",
            timeoutSeconds = 30.0,
        ), "from")
        assertEquals("tar -c /sdcard/DCIM", args.command)
        assertEquals("/tmp/arch.tar", args.hostPath)
        // seconds in the arguments become milliseconds, exactly as on the desktop
        assertEquals(30_000L, args.timeoutMs)
        assertNull(args.cwd)
    }

    @Test
    fun `an explicit timeoutMs wins over seconds`() {
        val args = Pipe.args(Pipe.Raw(
            command = "true",
            hostPath = "/tmp/x",
            timeoutMs = 1500.0,
            timeoutSeconds = 30.0,
        ), "from")
        assertEquals(1500L, args.timeoutMs)
    }

    @Test
    fun `zero timeout means no limit, not zero seconds`() {
        val args = Pipe.args(Pipe.Raw(command = "true", hostPath = "/tmp/x", timeoutSeconds = 0.0), "from")
        assertEquals(0L, args.timeoutMs)
    }

    @Test
    fun `a command without hostPath is refused before anything starts`() {
        val error = runCatching { Pipe.args(Pipe.Raw(command = "true", hostPath = null), "to") }.exceptionOrNull()
        assertTrue(error is Pipe.BadArgs)
        assertEquals("bad_args", (error as Pipe.BadArgs).code)
        // the two directions complain about different things: one wants a place to put the
        // output, the other a place to take the data from
        assertTrue(error.message!!.contains("откуда"))
    }

    @Test
    fun `empty output with code zero is a success`() {
        val verdict = Pipe.outcome(exitCode = 0, timedOut = false, stderrTail = "")
        assertTrue(verdict.ok)
        assertNull(verdict.code)
    }

    @Test
    fun `a non-zero code is a failure and carries its stderr`() {
        val verdict = Pipe.outcome(exitCode = 2, timedOut = false, stderrTail = "tar: нет файла\n")
        assertTrue(!verdict.ok)
        assertEquals("exit_nonzero", verdict.code)
        assertTrue(verdict.message!!.contains("код 2"))
        assertTrue(verdict.message!!.contains("tar: нет файла"))
    }

    @Test
    fun `a timeout is named as a timeout even when the code looks like a kill`() {
        val verdict = Pipe.outcome(exitCode = -1, timedOut = true, stderrTail = "")
        assertEquals("timeout", verdict.code)
    }

    @Test
    fun `stderr is cut from the head, keeping the tail`() {
        val long = "a".repeat(5000) + "ХВОСТ"
        val cut = Pipe.stderrTail(long)
        assertEquals(Pipe.STDERR_TAIL, cut.length)
        assertTrue(cut.endsWith("ХВОСТ"))
    }
}
