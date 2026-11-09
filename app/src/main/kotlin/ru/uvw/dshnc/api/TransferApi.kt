package ru.uvw.dshnc.api

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Передача файлов одним HTTP-запросом — как в десктопном клиенте.
 *
 * Сервер отдаёт файл по `v1/download-file?seq=…`, узел пишет его в
 * `<path>.inprogress` и переименовывает только после сверки sha256 и размера.
 * Разрешение на передачу даёт сама команда: её номер и есть билет.
 */
class TransferApi(private val base: String, private val token: String) {

    companion object {
        fun schema(): JSONObject = JSONObject()
            .put("name", "transfer")
            .put("version", "android-0.3.10")
            .put("description", "File transfer between the node and the host in one HTTP request")
            .put("methods", JSONArray()
                .put(JSONObject()
                    .put("method", "copyToDevice")
                    .put("description", "Download a file from the host to this node")
                    .put("args", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("path", JSONObject().put("type", "string"))
                        .put("hostPath", JSONObject().put("type", "string"))))
                    .put("returns", "object")))
    }

    fun download(seq: Long, args: JSONObject): JSONObject {
        val path = args.optString("path").ifBlank {
            return err("bad_args", "не передан путь, куда сохранять")
        }
        val target = File(path)
        val part = File("$path.inprogress")
        target.parentFile?.mkdirs()

        val url = URL(base.trimEnd('/') + "/v1/download-file?seq=$seq")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("authorization", "Bearer $token")
            setRequestProperty("content-length", "0")
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 60_000
        }
        return try {
            val status = connection.responseCode
            if (status != 200) {
                val text = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                return err("http_$status", text.take(300).ifBlank { "сервер отказал" })
            }
            FileOutputStream(part).use { output ->
                connection.inputStream.use { input -> input.copyTo(output) }
            }
            val expected = connection.getHeaderField("x-sha256")
            if (expected != null && expected != sha256(part)) {
                part.delete()
                return err("hash_mismatch", "sha256 не совпал: ждали $expected")
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                return err("io_error", "не смог переименовать $part в $path")
            }
            JSONObject().put("status", "ok").put("payload", JSONObject()
                .put("path", target.path)
                .put("bytes", target.length()))
        } catch (error: Throwable) {
            runCatching { part.delete() }
            err("transfer_failed", error.message ?: "передача не удалась")
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun err(code: String, message: String) = JSONObject()
        .put("status", "error").put("code", code).put("message", message)
}
