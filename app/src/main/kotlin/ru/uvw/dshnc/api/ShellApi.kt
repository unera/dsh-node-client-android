package ru.uvw.dshnc.api

import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import ru.uvw.dshnc.logic.Running
import ru.uvw.dshnc.logic.Timeouts

/**
 * Running a shell command on the phone.
 *
 * Android lets an app launch `/system/bin/sh` and the toybox utilities — with the app's
 * own permissions. So what is available is exactly what the file API has (the allowed
 * directories, the permissions granted), and nothing beyond: no root commands, no
 * other people's data and no package installation here.
 *
 * One separate subtlety: filtering an arbitrary command by paths is impossible —
 * `sh -c` will get around any string check. That is why the working directory is
 * checked without fail, while the command itself is taken for what it is: code that a
 * person or a model runs.
 */
class ShellApi (private val roots: List<String>) {
    /**
     * Live processes by command number — shared with the pipes.
     *
     * `#INTERNAL.CANCEL` reaches the node with a number, not with a word, and a cancel does
     * not care which method started the process.
     */
    val running = Running()

    /** How many commands are running right now — for the FLUSH barrier and diagnostics. */
    fun runningCount (): Int = running.count()

    fun schema (): JSONObject {
        val methods = org.json.JSONArray().put(JSONObject()
            .put("method", "exec")
            .put("description", "Run a shell command; returns stdout and stderr. Note: no sandbox on Android — the command runs with the app's own permissions")
            .put("args", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("command", JSONObject().put("type", "string"))
                    .put("cwd", JSONObject().put("type", "string"))
                    .put("timeout", JSONObject().put("type", "number").put("description", "seconds"))
                    .put("timeoutMs", JSONObject().put("type", "integer"))
                    .put("stdin", JSONObject().put("type", "string")))
                .put("required", org.json.JSONArray().put("command")))
            .put("returns", "object"))
        // pipes belong to the same API: they are the same `sh -c`, only the result travels
        // as a stream instead of text
        val pipes = PipeApi.schema(NodeApi.VERSION)
        for (index in 0 until pipes.length()) methods.put(pipes.get(index))
        return JSONObject()
            .put("name", "shell")
            .put("version", NodeApi.VERSION)
            .put("methods", methods)
    }

    fun handle (method: String, args: JSONObject, seq: Long = 0L, commandSeconds: Double = 0.0): JSONObject {
        if (method != "exec") return err("unknown_method", "нет такого метода: $method")
        val command = args.optString("command").trim()
        if (command.isEmpty()) return err("bad_args", "нужна команда")
        val cwd = args.optString("cwd").ifBlank { roots.firstOrNull() ?: "/storage/emulated/0" }
        if (!allowed(cwd)) return err("path_denied", "каталог $cwd вне разрешённых: ${roots.joinToString(", ")}")
        // like on the desktop: `timeoutMs`, then `timeout` in seconds from the arguments,
        // then the `timeout` the server set for the command, then our own default
        val timeout = (Timeouts.resolveMs(args, commandSeconds) ?: Timeouts.DEFAULT_MS)
            .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val started = System.currentTimeMillis()
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(File(cwd))
                .redirectErrorStream(false)
                .start()
            // from now on the command can be cancelled by its number
            running.put(seq, process)
            try {
                // we read both streams in parallel: otherwise a full buffer of one hangs the other
                val out = StringBuilder()
                val errText = StringBuilder()
                val reader = Thread { drain(process.inputStream, out, LIMIT) }.apply { start() }
                val errReader = Thread { drain(process.errorStream, errText, ERR_LIMIT) }.apply { start() }
                // stdin is fed in advance: the script may be waiting for data
                val input = args.optString("stdin")
                if (input.isNotEmpty()) {
                    runCatching {
                        process.outputStream.use { it.write(input.toByteArray()) }
                    }
                } else {
                    runCatching { process.outputStream.close() }
                }
                val done = process.waitFor(timeout.toLong(), TimeUnit.MILLISECONDS)
                if (!done) process.destroyForcibly()
                reader.join(2000)
                errReader.join(2000)
                val code = if (done) process.exitValue() else -1
                val spent = System.currentTimeMillis() - started
                // a rejection on a non-zero code — exactly like the desktop shell.exec:
                // the model has to see "it failed", not "success with a number"
                if (!done) {
                    return err("timeout", "команда не уложилась в ${timeout / 1000} с")
                }
                if (code != 0) {
                    val detail = buildString {
                        append("код $code")
                        if (errText.isNotEmpty()) append("\n[stderr] ").append(errText)
                        if (out.isNotEmpty()) append("\n[stdout] ").append(out)
                    }
                    return err("exit_nonzero", detail)
                }
                JSONObject().put("status", "ok").put("payload", JSONObject()
                    .put("exitCode", 0)
                    .put("stdout", out.toString())
                    .put("stderr", errText.toString())
                    .put("ms", spent))
            } finally {
                // the number is free again: a late cancel must find nothing, not a dead process
                running.forget(seq)
            }
        } catch (error: Throwable) {
            err("exec_failed", error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Service commands (`#INTERNAL.*`).
     *
     * They are not declared in any API and arrive with `api = -1`: the server knows what
     * they are by itself. They are handled before the name-based dispatch, otherwise they
     * would fall through to `files` and be answered with `unknown_method`.
     */
    fun control (method: String, args: JSONObject): JSONObject = when (method) {
        "#INTERNAL.CANCEL" -> cancel(args.optLong("seq", -1L))
        "#INTERNAL.FLUSH" -> flush()
        "#INTERNAL.APPROVE" -> err("no_approver", "на телефоне нет диалога подтверждения: человек не может ответить")
        else -> err("unknown_method", "неизвестная служебная команда: $method")
    }

    /**
     * `#INTERNAL.CANCEL`: stop the command with this number.
     *
     * `destroy()` first and `destroyForcibly()` if it did not die: a process that ignores
     * SIGTERM must not keep the slot.
     */
    private fun cancel (seq: Long): JSONObject {
        val process = running.take(seq)
            ?: return err("no_such_process", "команда $seq не выполняется")
        return try {
            process.destroy()
            if (!process.waitFor(CANCEL_WAIT_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            JSONObject().put("status", "ok").put("payload", JSONObject().put("cancelled", true))
        } catch (error: Throwable) {
            err("cancel_failed", "не смог остановить процесс $seq: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /**
     * `#INTERNAL.FLUSH`: a barrier meaning "everything before me has already finished".
     *
     * The harvest loop is sequential: every command, `exec` included, is awaited before the
     * next one starts, so by the time the barrier itself is reached nothing from the same
     * batch is running. The wait below is only a guard on that invariant.
     */
    private fun flush (): JSONObject {
        val deadline = System.currentTimeMillis() + FLUSH_WAIT_MS
        while (!running.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        if (!running.isEmpty()) {
            return err("still_running", "после FLUSH ещё выполняются команды: ${running.count()}")
        }
        return JSONObject().put("status", "ok").put("payload", JSONObject().put("flushed", true))
    }

    /** Read a stream up to a limit: the output of a gigantic command must not eat memory. */
    private fun drain (input: java.io.InputStream, sink: StringBuilder, limit: Int) {
        val buffer = ByteArray(8192)
        var total = 0
        try {
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                if (total < limit) {
                    val keep = minOf(read, limit - total)
                    sink.append(String(buffer, 0, keep))
                    total += keep
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** The working directory has to be inside the allowed ones: that is checkable and does get checked. */
    private fun allowed (path: String): Boolean {
        val target = try {
            File(path).canonicalPath
        } catch (error: Throwable) {
            return false
        }
        return roots.any { root ->
            val base = try {
                File(root).canonicalPath
            } catch (error: Throwable) {
                return@any false
            }
            target == base || target.startsWith("$base/")
        }
    }

    private fun err (code: String, message: String) = JSONObject()
        .put("status", "error").put("code", code).put("message", message)

    companion object {
        private const val LIMIT = 64 * 1024
        private const val ERR_LIMIT = 16 * 1024
        private const val MIN_TIMEOUT_MS = 1_000
        private const val MAX_TIMEOUT_MS = 120_000
        /** How long a cancelled process is given to die on SIGTERM. */
        private const val CANCEL_WAIT_MS = 2000L
        /** How long the FLUSH barrier waits for the registry to drain. */
        private const val FLUSH_WAIT_MS = 5000L
    }
}
