package ru.uvw.dshnc.api

import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Выполнение команды оболочки на телефоне.
 *
 * Android разрешает приложению запускать `/system/bin/sh` и утилиты toybox —
 * с правами самого приложения. Значит доступно то же, что и файловому API
 * (разрешённые каталоги, выданные разрешения), и ничего сверх: root-команд,
 * чужих данных и установки пакетов здесь нет.
 *
 * Отдельная тонкость: фильтровать произвольную команду по путям невозможно —
 * `sh -c` обойдёт любую проверку строки. Поэтому рабочий каталог проверяем
 * обязательно, а саму команду считаем тем, чем она является: кодом, который
 * запускает человек или модель.
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
        // как на десктопе: `timeout` в секундах; `timeoutMs` оставлен для явности
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
            // читаем оба потока параллельно: иначе полный буфер одного вешает другой
            val out = StringBuilder()
            val errText = StringBuilder()
            val reader = Thread { drain(process.inputStream, out, LIMIT) }.apply { start() }
            val errReader = Thread { drain(process.errorStream, errText, ERR_LIMIT) }.apply { start() }
            // stdin подаём заранее: скрипт может ждать данные
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
            // отказ при ненулевом коде — так же, как десктопный shell.exec:
            // модель должна видеть «упало», а не «успех с числом»
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

    /** Читать поток до предела: вывод гигантской команды не должен съесть память. */
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

    /** Рабочий каталог должен быть внутри разрешённых: это проверяемо и проверяем. */
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
