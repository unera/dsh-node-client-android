package ru.uvw.dshnc

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.uvw.dshnc.api.PipeApi
import ru.uvw.dshnc.logic.Pipe
import ru.uvw.dshnc.logic.Running

/**
 * The transport half of a pipe: a real process, a real HTTP request, a local server.
 *
 * This is the part that was wrong twice in one evening — the pause was checked after the
 * command had already finished, and a killed command was reported as a broken transfer. Both
 * were found on a phone by hand; both are caught here without one.
 *
 * `org.json` is not available to unit tests, so `PipeApi` returns plain results and the
 * conversion to the wire shape lives in `Result.toJson()`.
 */
class PipeApiTest {
    private lateinit var server: StubServer
    private lateinit var home: File
    private var answer: String = "с сервера"

    @Before
    fun setUp () {
        home = Files.createTempDirectory("dshnc-pipe").toFile()
        server = StubServer()
    }

    @After
    fun tearDown () {
        server.close()
        home.deleteRecursively()
    }

    private fun api () = PipeApi(
        base = server.base,
        token = "token",
        roots = listOf(home.path),
        running = Running(),
        shell = "/bin/sh",
    )

    @Test
    fun `the output of a command reaches the server body` () {
        val result = api().from(1, Pipe.Raw(command = "printf 'привет'", hostPath = "${home.path}/out.txt"))
        assertTrue(result.message ?: "ok", result.ok)
        assertEquals("привет", server.uploads.single())
        assertEquals(12L, result.payload["bytes"])
        assertEquals(0, result.payload["exitCode"])
    }

    @Test
    fun `a failing command is a failure with its code, not an empty success` () {
        val result = api().from(2, Pipe.Raw(command = "echo сломалось >&2; exit 3", hostPath = "${home.path}/out.txt"))
        assertFalse(result.ok)
        assertEquals("exit_nonzero", result.code)
        assertTrue(result.message!!.contains("код 3"))
        assertTrue(result.message!!.contains("сломалось"))
    }

    @Test
    fun `the pause kills the command while the stream is still flowing` () {
        val started = System.currentTimeMillis()
        val result = api().from(3, Pipe.Raw(command = "sleep 5", hostPath = "${home.path}/out.txt", timeoutMs = 300.0))
        val spent = System.currentTimeMillis() - started
        assertFalse(result.ok)
        // the word matters: a command that ran too long is a timeout, not a broken transfer
        assertEquals("timeout", result.code)
        assertTrue("обрыв занял ${spent}мс — срок не сработал", spent < 4000)
    }

    @Test
    fun `data from the server becomes the command's stdin` () {
        val target = File(home, "from-server.txt")
        val result = api().to(4, Pipe.Raw(command = "cat > ${target.path}", hostPath = "${home.path}/src.txt"))
        assertTrue(result.message ?: "ok", result.ok)
        assertEquals(answer, target.readText())
        assertEquals(answer.toByteArray().size.toLong(), result.payload["bytes"])
    }

    @Test
    fun `a working directory outside the allowed ones is refused before anything runs` () {
        val result = api().from(5, Pipe.Raw(command = "true", hostPath = "/tmp/x", cwd = "/"))
        assertFalse(result.ok)
        assertEquals("path_denied", result.code)
        assertTrue(server.uploads.isEmpty())
    }

    @Test
    fun `a server that refuses the upload is reported with its status` () {
        server.uploadStatus = 413
        val running = Running()
        val result = PipeApi(server.base, "token", listOf(home.path), running, "/bin/sh")
            .from(7, Pipe.Raw(command = "echo данные", hostPath = "${home.path}/out.txt"))
        assertFalse(result.ok)
        assertEquals("http_413", result.code)
        assertTrue("номер команды не освобождён", running.isEmpty())
    }

    @Test
    fun `an unreachable server does not leave the command or its number behind` () {
        val running = Running()
        // port 9 is the discard service: nothing listens there in a test container
        val api = PipeApi("http://127.0.0.1:9", "token", listOf(home.path), running, "/bin/sh")
        val result = api.from(8, Pipe.Raw(command = "echo данные", hostPath = "${home.path}/out.txt"))
        assertFalse(result.ok)
        // the connection is built lazily: a dead port fails on the first byte, and that is a
        // stream that never reached the server — any of these codes says so honestly
        assertTrue(
            "получили ${result.code}",
            result.code in setOf("no_server", "transfer_failed") || (result.code ?: "").startsWith("http_"),
        )
        assertTrue("номер команды не освобождён", running.isEmpty())
    }

    @Test
    fun `missing arguments are refused with the code the server knows` () {
        val result = api().from(6, Pipe.Raw(command = null, hostPath = "${home.path}/out.txt"))
        assertFalse(result.ok)
        assertEquals("bad_args", result.code)
    }
}
