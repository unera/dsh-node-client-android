package ru.uvw.dshnc.api

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
) {
    companion object {
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
    fun from (seq: Long, raw: JSONObject, commandSeconds: Double = 0.0): JSONObject {
        val args = try {
            Pipe.args(rawArgs(raw), "from")
        } catch (error: Pipe.BadArgs) {
            return err(error.code, error.message ?: "плохие аргументы")
        }
        val cwd = args.cwd ?: roots.firstOrNull() ?: "/storage/emulated/0"
        if (!allowed(cwd)) return err("path_denied", "каталог $cwd вне разрешённых: ${roots.joinToString(", ")}")
        val readTimeout = readTimeout(raw, commandSeconds, args.timeoutMs)

        val process = start(args.command, cwd) ?: return err("pipe_failed", "не смог запустить команду")
        running.put(seq, process)
        var bytes = 0L
        val stderr = StringBuilder()
        // the pause is enforced while the stream is still flowing: waiting out the command
        // and only then asking "was it too long" would be a check after the fact
        val timedOut = AtomicBoolean(false)
        val watchdog = watchdog(process, args.timeoutMs, timedOut)
        val errDrain = Thread { tail(process.errorStream, stderr) }.apply { start() }
        val connection = try {
            connection("/v1/upload-file", seq, chunked = true)
        } catch (error: Throwable) {
            stop(process)
            running.forget(seq)
            return err("no_server", error.message ?: "адрес сервера неизвестен")
        }
        var status = 0
        var body = ""
        try {
            process.outputStream.close()
            connection.outputStream.use { out -> process.inputStream.use { input -> bytes = pump(input, out) } }
            status = connection.responseCode
            body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
        } catch (error: Throwable) {
            // the process is writing into a pipe nobody reads any more: it would hang forever
            stop(process)
            running.forget(seq)
            watchdog?.interrupt()
            // a command killed by its own pause breaks the stream under our feet: that is the
            // timeout talking, not a broken transfer — and the reader must not hear
            // "transfer_failed" for a command that simply ran too long
            if (timedOut.get()) {
                val verdict = Pipe.outcome(-1, true, stderr.toString())
                return err(verdict.code ?: "timeout", verdict.message ?: "команда не уложилась в срок")
            }
            return err("transfer_failed", "поток не дошёл до сервера: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }

        watchdog?.interrupt()
        val waiting = wait(process)
        running.forget(seq)
        errDrain.join(1000)
        if (status !in 200..299) return err("http_$status", body.take(300).ifBlank { "сервер отказал ($status)" })
        val verdict = Pipe.outcome(waiting, timedOut.get(), stderr.toString())
        if (!verdict.ok) return err(verdict.code ?: "pipe_failed", verdict.message ?: "пайп не удался")
        return ok(JSONObject()
            .put("bytes", bytes)
            .put("exitCode", waiting)
            .put("stderr", Pipe.stderrTail(stderr.toString()))
            .put("hostPath", args.hostPath))
    }

    /** The node takes: the response body goes into the process's stdin. */
    fun to (seq: Long, raw: JSONObject, commandSeconds: Double = 0.0): JSONObject {
        val args = try {
            Pipe.args(rawArgs(raw), "to")
        } catch (error: Pipe.BadArgs) {
            return err(error.code, error.message ?: "плохие аргументы")
        }
        val cwd = args.cwd ?: roots.firstOrNull() ?: "/storage/emulated/0"
        if (!allowed(cwd)) return err("path_denied", "каталог $cwd вне разрешённых: ${roots.joinToString(", ")}")
        val readTimeout = readTimeout(raw, commandSeconds, args.timeoutMs)

        val process = start(args.command, cwd) ?: return err("pipe_failed", "не смог запустить команду")
        running.put(seq, process)
        var bytes = 0L
        val stderr = StringBuilder()
        val timedOut = AtomicBoolean(false)
        val watchdog = watchdog(process, args.timeoutMs, timedOut)
        val errDrain = Thread { tail(process.errorStream, stderr) }.apply { start() }
        val connection = try {
            connection("/v1/download-file", seq, chunked = false)
        } catch (error: Throwable) {
            stop(process)
            running.forget(seq)
            return err("no_server", error.message ?: "адрес сервера неизвестен")
        }
        val status = try {
            val code = connection.responseCode
            if (code in 200..299) {
                connection.inputStream.use { input ->
                    // closing the output gives the script its EOF — a `sh` reading stdin
                    // would otherwise wait forever
                    process.outputStream.use { out -> bytes = pump(input, out) }
                }
            }
            code
        } catch (error: Throwable) {
            // a command killed by its own pause closes stdin under our feet: that is the
            // timeout talking, not a broken transfer
            if (timedOut.get()) {
                stop(process)
                running.forget(seq)
                watchdog?.interrupt()
                val verdict = Pipe.outcome(-1, true, stderr.toString())
                return err(verdict.code ?: "timeout", verdict.message ?: "команда не уложилась в срок")
            }
            stop(process)
            running.forget(seq)
            return err("transfer_failed", "данные не пришли с сервера: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }

        watchdog?.interrupt()
        val waiting = wait(process)
        running.forget(seq)
        errDrain.join(1000)
        if (status !in 200..299) return err("http_$status", "сервер отказал ($status)")
        val verdict = Pipe.outcome(waiting, timedOut.get(), stderr.toString())
        if (!verdict.ok) return err(verdict.code ?: "pipe_failed", verdict.message ?: "пайп не удался")
        return ok(JSONObject()
            .put("bytes", bytes)
            .put("exitCode", waiting)
            .put("stderr", Pipe.stderrTail(stderr.toString()))
            .put("hostPath", args.hostPath))
    }

    /** The JSON arguments as plain values: the decisions themselves do not know about JSON. */
    private fun rawArgs (raw: JSONObject): Pipe.Raw = Pipe.Raw(
        command = raw.optString("command"),
        hostPath = raw.optString("hostPath"),
        cwd = raw.optString("cwd"),
        timeoutMs = if (raw.has("timeoutMs") && !raw.isNull("timeoutMs")) raw.optDouble("timeoutMs", 0.0) else null,
        timeoutSeconds = if (raw.has("timeout") && !raw.isNull("timeout")) raw.optDouble("timeout", 0.0) else null,
    )

    /**
     * The command's own pause, enforced on the running process.
     *
     * The clock has to run next to the stream, not after it: a command that produces
     * nothing for ten minutes would otherwise report success at minute ten and call the
     * pause respected.
     */
    private fun watchdog (process: Process, timeoutMs: Long, timedOut: AtomicBoolean): Thread? {
        if (timeoutMs <= 0) return null
        return Thread {
            try {
                Thread.sleep(timeoutMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (process.isAlive) {
                timedOut.set(true)
                runCatching { process.destroyForcibly() }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun wait (process: Process): Int {
        process.waitFor()
        return process.exitValue()
    }

    private fun start (command: String, cwd: String): Process? = try {
        ProcessBuilder("/system/bin/sh", "-c", command)
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
    private fun connection (route: String, seq: Long, chunked: Boolean): HttpURLConnection {
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
            readTimeout = IDLE_READ_TIMEOUT_MS
        }
    }

    /** The pause of the command, clamped: a pipe may legitimately outlive the default. */
    private fun readTimeout (raw: JSONObject, commandSeconds: Double, timeoutMs: Long): Int {
        val asked = when {
            timeoutMs > 0 -> timeoutMs.toInt()
            else -> Timeouts.resolveMs(raw, commandSeconds) ?: IDLE_READ_TIMEOUT_MS
        }
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

    private fun ok (payload: JSONObject) = JSONObject().put("status", "ok").put("payload", payload)

    private fun err (code: String, message: String) = JSONObject()
        .put("status", "error").put("code", code).put("message", message)
}
