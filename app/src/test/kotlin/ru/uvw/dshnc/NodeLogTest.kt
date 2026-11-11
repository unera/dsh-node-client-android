package ru.uvw.dshnc

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.uvw.dshnc.store.NodeLog

/**
 * The log has a size cap, and the only decision in it is what to throw away when the cap is
 * reached — dropping the wrong half would either lose the recent story or keep a file that
 * grows forever. The file path is checked on a real temporary file: the trimming is I/O, and
 * a test of `List.drop` would prove nothing about it.
 */
class NodeLogTest {
    private lateinit var dir: File

    @Before
    fun setUp () {
        dir = Files.createTempDirectory("dshnc-log").toFile()
    }

    @After
    fun tearDown () {
        dir.deleteRecursively()
    }

    @Test
    fun `what is written comes back, newest last` () {
        val target = File(dir, "node.log")
        NodeLog.append(target, "первая")
        NodeLog.append(target, "вторая")
        val lines = NodeLog.tail(target, 10)
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("  первая"))
        assertTrue(lines[1].endsWith("  вторая"))
        // the full date is the point of this file: the old event list had only the clock
        assertTrue(lines[0].matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+первая""")))
    }

    @Test
    fun `the file is trimmed when it outgrows its cap, and the newest lines survive` () {
        val target = File(dir, "node.log")
        val many = (1..500).map { "строка $it" }
        many.forEach { NodeLog.append(target, it, limit = 2000) }
        assertTrue("файл ${target.length()} байт не обрезан", target.length() <= 2000 + 200)
        val lines = NodeLog.tail(target, 1000)
        assertTrue(lines.last().endsWith("строка 500"))
        // a trim throws away the older half, so the beginning must be gone
        assertTrue(lines.none { it.endsWith("строка 1") })
    }

    @Test
    fun `the tail never returns more lines than asked` () {
        val target = File(dir, "node.log")
        (1..20).forEach { NodeLog.append(target, "строка $it") }
        val lines = NodeLog.tail(target, 5)
        assertEquals(5, lines.size)
        assertEquals("строка 20", lines.last().substringAfter("  "))
    }
}
