package ru.uvw.dshnc.api

import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

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
    fun schema (): JSONObject = JSONObject()
        .put("name", "shell")
        .put("version", NodeApi.VERSION)
        .put("methods", org.json.JSONArray().put(JSONObject()
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
            .put("returns", "object")))

    fun handle (method: String, args: JSONObject): JSONObject {
        if (method != "exec") return err("unknown_method", "нет такого метода: $method")
        val command = args.optString("command").trim()
        if (command.isEmpty()) return err("bad_args", "нужна команда")
        val cwd = args.optString("cwd").ifBlank { roots.firstOrNull() ?: "/storage/emulated/0" }
        if (!allowed(cwd)) return err("path_denied", "каталог $cwd вне разрешённых: ${roots.joinToString(", ")}")
        // same as on the desktop: `timeout` is in seconds; `timeoutMs` is kept for explicitness
        val timeout = if (args.has("timeoutMs")) {
            args.optInt("timeoutMs", 15_000)
        } else {
            (args.optDouble("timeout", 15.0) * 1000).toInt()
        }.coerceIn(1_000, 120_000)
        val started = System.currentTimeMillis()
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(File(cwd))
                .redirectErrorStream(false)
                .start()
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
        } catch (error: Throwable) {
            err("exec_failed", error.message ?: error.javaClass.simpleName)
        }
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
    }
}
