package ru.uvw.dshnc.api

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import ru.uvw.dshnc.logic.Pipe
import ru.uvw.dshnc.logic.Running
import ru.uvw.dshnc.logic.Timeouts

/**
 * Pipes: a command's stream instead of a file.
 *
 * `pipeFromDevice` runs a command and sends its stdout into the request body, so the
 * server writes a host file with no temporary file on the phone. `pipeToDevice` takes the
 * body of the server's answer and feeds it to the command's stdin.
 *
 * The difference from `copyFromDevice`/`copyToDevice` is not cosmetic: the body here has
 * no `content-length` — the size of a command's output is unknown until it ends, which is
 * why the server allows a chunked body for exactly this method and for nothing else.
 *
 * The decisions (arguments, verdict) live in `logic/Pipe.kt`; only the socket and the
 * process are here.
 */
class PipeApi (
    private val base: String,
    private val token: String,
    private val roots: List<String>,
    private val running: Running,
    /** The shell to run: `/system/bin/sh` on the phone, a plain `sh` in a JVM test. */
    private val shell: String = "/system/bin/sh",
) {
    /**
     * The outcome of a pipe, without JSON.
     *
     * `org.json` does not exist off the phone (unit tests see Android stubs that throw), and
     * a transport that can only be checked on a device is checked never. The conversion to
     * the wire shape lives in `toJson`, where the service needs it.
     */
    data class Result (val ok: Boolean, val code: String? = null, val message: String? = null, val payload: Map<String, Any> = emptyMap()) {
        fun toJson (): JSONObject = if (ok) {
            JSONObject().put("status", "ok").put("payload", JSONObject(payload as Map<*, *>))
        } else {
            JSONObject().put("status", "error").put("code", code ?: "pipe_failed").put("message", message ?: "пайп не удался")
        }
    }

    companion object {
        /** The JSON arguments as plain values: the transport itself never sees JSON. */
        fun raw (raw: JSONObject): Pipe.Raw = Pipe.Raw(
            command = raw.optString("command"),
            hostPath = raw.optString("hostPath"),
            cwd = raw.optString("cwd"),
            timeoutMs = if (raw.has("timeoutMs") && !raw.isNull("timeoutMs")) raw.optDouble("timeoutMs", 0.0) else null,
            timeoutSeconds = if (raw.has("timeout") && !raw.isNull("timeout")) raw.optDouble("timeout", 0.0) else null,
        )

        /** A pipe of a whole backup is not a failure: the pause must be generous. */
        private const val IDLE_READ_TIMEOUT_MS = 120_000
        private const val MIN_READ_TIMEOUT_MS = 30_000
        private const val MAX_READ_TIMEOUT_MS = 3_600_000
        private const val CHUNK = 64 * 1024

        fun schema (version: String): org.json.JSONArray = org.json.JSONArray()
            .put(JSONObject()
                .put("method", "pipeFromDevice")
                .put("description", "Run a command and stream its output into a file on the host: no temporary file on the device")
                .put("args", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("command", JSONObject().put("type", "string").put("description", "what to run (sh -c)"))
                        .put("hostPath", JSONObject().put("type", "string").put("description", "where on the host to put the output"))
                        .put("cwd", JSONObject().put("type", "string").put("description", "working directory on the device"))
                        .put("timeout", JSONObject().put("type", "number").put("description", "seconds, 0 or absent — no limit"))
                        .put("timeoutMs", JSONObject().put("type", "integer")))
                    .put("required", org.json.JSONArray().put("command").put("hostPath")))
                .put("returns", "object"))
            .put(JSONObject()
                .put("method", "pipeToDevice")
                .put("description", "Feed a file from the host into a command's stdin")
                .put("args", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("command", JSONObject().put("type", "string").put("description", "what to run (sh -c)"))
                        .put("hostPath", JSONObject().put("type", "string").put("description", "where on the host to take the data"))
                        .put("cwd", JSONObject().put("type", "string").put("description", "working directory on the device"))
                        .put("timeout", JSONObject().put("type", "number").put("description", "seconds, 0 or absent — no limit"))
                        .put("timeoutMs", JSONObject().put("type", "integer")))
                    .put("required", org.json.JSONArray().put("command").put("hostPath")))
                .put("returns", "object"))
    }

    /** The node gives: the process's stdout goes into the request body. */
    fun from (seq: Long, raw: Pipe.Raw, commandSeconds: Double = 0.0): Result {
        val args = try {
            Pipe.args(raw, "from")
        } catch (error: Pipe.BadArgs) {
            return err(error.code, error.message ?: "плохие аргументы")
        }
        val cwd = args.cwd ?: roots.firstOrNull() ?: "/storage/emulated/0"
        if (!allowed(cwd)) return err("path_denied", "каталог $cwd вне разрешённых: ${roots.joinToString(", ")}")
        val process = start(args.command, cwd) ?: return err("pipe_failed", "не смог запустить команду")
        running.put(seq, process)
        val stderr = StringBuilder()
        val errDrain = Thread { tail(process.errorStream, stderr) }.apply { start() }
        val connection = try {
            connection("/v1/upload-file", seq, chunked = true, readTimeoutMs = readTimeout(raw, commandSeconds))
        } catch (error: Throwable) {
            stop(process)
            running.forget(seq)
            return err("no_server", error.message ?: "адрес сервера неизвестен")
        }
        try {
            // the command gets no input here: its stdin is closed before anything flows
            runCatching { process.outputStream.close() }
            val flow = flow(process.inputStream, connection.outputStream, process, args.timeoutMs)
            if (flow.timedOut) {
                stop(process)
                return err("timeout", Pipe.outcome(-1, true, stderr.toString()).message ?: "команда не уложилась в срок")
            }
            if (flow.error != null) {
                stop(process)
                return err(
                    "transfer_failed",
                    "поток не дошёл до сервера: ${flow.error.message ?: flow.error.javaClass.simpleName}",
                )
            }
            // the terminating chunk goes out only when the body is closed: without it the
            // server keeps waiting for data that will never come
            runCatching { connection.outputStream.close() }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            val exitCode = wait(process)
            errDrain.join(1000)
            if (status !in 200..299) return err("http_$status", body.take(300).ifBlank { "сервер отказал ($status)" })
            val verdict = Pipe.outcome(exitCode, false, stderr.toString())
            if (!verdict.ok) return err(verdict.code ?: "pipe_failed", verdict.message ?: "пайп не удался")
            return Result(true, payload = mapOf(
                "bytes" to flow.bytes,
                "exitCode" to exitCode,
                "stderr" to Pipe.stderrTail(stderr.toString()),
                "hostPath" to args.hostPath,
            ))
        } catch (error: Throwable) {
            stop(process)
            return err("transfer_failed", "поток не дошёл до сервера: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            running.forget(seq)
            errDrain.join(500)
            connection.disconnect()
        }
    }

    /** The node takes: the response body goes into the process's stdin. */
    fun to (seq: Long, raw: Pipe.Raw, commandSeconds: Double = 0.0): Result {
        val args = try {
            Pipe.args(raw, "to")
        } catch (error: Pipe.BadArgs) {
            return err(error.code, error.message ?: "плохие аргументы")
        }
        val cwd = args.cwd ?: roots.firstOrNull() ?: "/storage/emulated/0"
        if (!allowed(cwd)) return err("path_denied", "каталог $cwd вне разрешённых: ${roots.joinToString(", ")}")
        val process = start(args.command, cwd) ?: return err("pipe_failed", "не смог запустить команду")
        running.put(seq, process)
        val stderr = StringBuilder()
        val errDrain = Thread { tail(process.errorStream, stderr) }.apply { start() }
        val connection = try {
            connection("/v1/download-file", seq, chunked = false, readTimeoutMs = readTimeout(raw, commandSeconds))
        } catch (error: Throwable) {
            stop(process)
            running.forget(seq)
            return err("no_server", error.message ?: "адрес сервера неизвестен")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                stop(process)
                return err("http_$status", "сервер отказал ($status)")
            }
            // closing stdin at the end gives the script its EOF — a `sh` reading stdin would
            // otherwise wait forever for data that will never come
            val flow = flow(connection.inputStream, process.outputStream, process, args.timeoutMs)
            runCatching { process.outputStream.close() }
            if (flow.timedOut) {
                stop(process)
                return err("timeout", Pipe.outcome(-1, true, stderr.toString()).message ?: "команда не уложилась в срок")
            }
            if (flow.error != null) {
                stop(process)
                return err(
                    "transfer_failed",
                    "данные не пришли с сервера: ${flow.error.message ?: flow.error.javaClass.simpleName}",
                )
            }
            val exitCode = wait(process)
            errDrain.join(1000)
            val verdict = Pipe.outcome(exitCode, false, stderr.toString())
            if (!verdict.ok) return err(verdict.code ?: "pipe_failed", verdict.message ?: "пайп не удался")
            return Result(true, payload = mapOf(
                "bytes" to flow.bytes,
                "exitCode" to exitCode,
                "stderr" to Pipe.stderrTail(stderr.toString()),
                "hostPath" to args.hostPath,
            ))
        } catch (error: Throwable) {
            stop(process)
            return err("transfer_failed", "данные не пришли с сервера: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            running.forget(seq)
            errDrain.join(500)
            connection.disconnect()
        }
    }

    private class Flow (val bytes: Long, val timedOut: Boolean, val error: Throwable?)

    /**
     * Move the stream while the clock runs — the pause is about the stream, not about its end.
     *
     * The reading happens in its own thread on purpose. A command is `sh -c ...`, and killing
     * `sh` does not kill its children: `sh -c "echo start; sleep 30"` leaves `sleep` holding
     * the same pipe, so a blocked `read` would not notice the kill at all. A test on a plain
     * Linux box showed exactly that — 5 s of sleep with a 300 ms pause spent the whole 5 s.
     * Here the wait has a deadline, and when it passes the process is killed, the sink is
     * closed and the reader is abandoned: it ends by itself as soon as it touches the closed
     * sink, or when the leftover child finally exits.
     */
    private fun flow (source: InputStream, sink: OutputStream, process: Process, timeoutMs: Long): Flow {
        var bytes = 0L
        var error: Throwable? = null
        val reader = Thread {
            runCatching { bytes = pump(source, sink) }.onFailure { error = it }
        }.apply {
            isDaemon = true
            start()
        }
        reader.join(if (timeoutMs > 0) timeoutMs else 0)
        if (reader.isAlive) {
            runCatching { process.destroyForcibly() }
            runCatching { sink.close() }
            runCatching { source.close() }
            return Flow(bytes, true, null)
        }
        return Flow(bytes, false, error)
    }

    private fun wait (process: Process): Int {
        process.waitFor()
        return process.exitValue()
    }

    private fun start (command: String, cwd: String): Process? = try {
        ProcessBuilder(shell, "-c", command)
            .directory(File(cwd))
            .redirectErrorStream(false)
            .start()
    } catch (_: Throwable) {
        null
    }

    private fun stop (process: Process) {
        runCatching { process.outputStream.close() }
        runCatching { process.destroyForcibly() }
    }

    /**
     * One HTTP request to the transfer route.
     *
     * `chunked = true` is the whole point of `pipeFromDevice`: the body has no
     * `content-length`, because the size is not known before the command ends. The server
     * accepts that exactly for this method and refuses it for a file copy.
     */
    private fun connection (route: String, seq: Long, chunked: Boolean, readTimeoutMs: Int): HttpURLConnection {
        val url = URL(base.trimEnd('/') + route + "?seq=" + seq)
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("authorization", "Bearer $token")
            if (chunked) {
                setRequestProperty("content-type", "application/octet-stream")
                setChunkedStreamingMode(0)
            } else {
                setRequestProperty("content-length", "0")
            }
            doOutput = true
            connectTimeout = 20_000
            // the answer arrives only when the stream ends, so the pause is about silence
            // in the middle of a transfer, not about the transfer as a whole
            readTimeout = readTimeoutMs
        }
    }

    /** The pause of the command, clamped: a pipe may legitimately outlive the default. */
    private fun readTimeout (raw: Pipe.Raw, commandSeconds: Double): Int {
        val asked = Timeouts.resolveMs(raw.timeoutMs, raw.timeoutSeconds, commandSeconds) ?: IDLE_READ_TIMEOUT_MS
        // the socket must not give up before the command's own pause: a pipe of a backup is
        // silent for minutes and that silence is not a broken transfer
        return asked.coerceIn(MIN_READ_TIMEOUT_MS, MAX_READ_TIMEOUT_MS)
    }

    /** Copy until the end, counting the bytes: memory stays bounded, the stream does not. */
    private fun pump (input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(CHUNK)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            total += read
        }
        output.flush()
        return total
    }

    /** stderr is drained in the background: a full buffer would hang the command. */
    private fun tail (input: InputStream, sink: StringBuilder) {
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                synchronized(sink) {
                    sink.append(String(buffer, 0, read))
                    if (sink.length > Pipe.STDERR_TAIL * 2) {
                        val kept = sink.substring(sink.length - Pipe.STDERR_TAIL)
                        sink.setLength(0)
                        sink.append(kept)
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun allowed (path: String): Boolean {
        val target = try {
            File(path).canonicalPath
        } catch (_: Throwable) {
            return false
        }
        return roots.any { root ->
            val base = try {
                File(root).canonicalPath
            } catch (_: Throwable) {
                return@any false
            }
            target == base || target.startsWith("$base/")
        }
    }

    private fun err (code: String, message: String) = Result(false, code, message)
}
