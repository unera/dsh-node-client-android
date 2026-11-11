package ru.uvw.dshnc

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A toy HTTP server for the pipe tests.
 *
 * Hand-written instead of a library: the phone's unit tests compile against `android.jar`,
 * where `com.sun.net.httpserver` does not exist, and a network test that cannot be compiled
 * is a test that will never fail. What the pipes need is small — read a body honestly
 * (chunked, because that is exactly what `pipeFromDevice` sends) and answer with a fixed
 * string.
 */
class StubServer : Closeable {
    private val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
    private val thread = Thread { loop() }.apply { isDaemon = true; start() }

    /** Bodies of the requests that arrived on the upload route, in order. */
    val uploads = CopyOnWriteArrayList<String>()

    /** What the download route answers. */
    @Volatile var download: String = "с сервера"

    /** Status the upload route answers with. */
    @Volatile var uploadStatus: Int = 200

    val base: String get() = "http://127.0.0.1:${server.localPort}"

    override fun close () {
        runCatching { server.close() }
        thread.interrupt()
    }

    private fun loop () {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: Throwable) {
                return
            }
            runCatching { handle(client) }
            runCatching { client.close() }
        }
    }

    private fun handle (client: Socket) {
        val input = client.getInputStream()
        val requestLine = readLine(input) ?: return
        val path = requestLine.split(' ').getOrNull(1) ?: return
        var length = -1
        var chunked = false
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val lower = line.lowercase()
            if (lower.startsWith("content-length:")) length = lower.substringAfter(':').trim().toIntOrNull() ?: -1
            if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) chunked = true
        }
        val body = when {
            chunked -> readChunked(input)
            length > 0 -> String(readExactly(input, length))
            else -> ""
        }
        if (path.startsWith("/v1/upload-file")) {
            uploads.add(body)
            respond(client.getOutputStream(), uploadStatus, """{"code":"stored"}""")
        } else {
            respond(client.getOutputStream(), 200, download)
        }
    }

    private fun respond (output: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray()
        val head = "HTTP/1.1 $status OK\r\ncontent-type: application/json\r\n" +
            "content-length: ${bytes.size}\r\nconnection: close\r\n\r\n"
        output.write(head.toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun readLine (input: InputStream): String? {
        val text = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (text.isEmpty()) null else text.toString()
            if (byte == '\n'.code) return text.toString().trimEnd('\r')
            text.append(byte.toChar())
        }
    }

    private fun readExactly (input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = input.read(buffer, read, size - read)
            if (count < 0) break
            read += count
        }
        return buffer
    }

    /** `pipeFromDevice` sends a chunked body: the size is unknown before the command ends. */
    private fun readChunked (input: InputStream): String {
        val body = StringBuilder()
        while (true) {
            val header = readLine(input) ?: break
            val size = header.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                readLine(input)
                break
            }
            body.append(String(readExactly(input, size)))
            readLine(input)
        }
        return body.toString()
    }
}
