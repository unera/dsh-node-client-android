package ru.uvw.dshnc.api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Файловый API узла.
 *
 * Работает только внутри разрешённых каталогов, и первым методом отдаёт их
 * список (`roots`): модели не нужно угадывать, куда можно, — она спрашивает и
 * получает ответ. Так же это устроено в консольном клиенте, поэтому один и тот
 * же список путей годится для обеих платформ.
 */
class FilesApi(private val context: Context, private val roots: List<String>) {

    companion object {
        /** Что объявляем серверу: имена и аргументы совпадают с десктопным клиентом. */
        fun schema(version: String): JSONObject = JSONObject()
            .put("name", "files")
            .put("version", version)
            .put("description", "Files on the phone inside the directories allowed by the owner")
            .put("methods", JSONArray()
                .put(method("roots", "Which directories this node may touch"))
                .put(method("list", "List a directory", "path"))
                .put(method("stat", "File information", "path"))
                .put(method("read", "Read a text file", "path", "offset", "limit"))
                .put(method("write", "Write a text file", "path", "content"))
                .put(method("edit", "Replace text in a file", "path", "old", "new"))
                .put(method("mkdir", "Create a directory", "path", "name", "mode"))
                .put(method("copyToDevice", "Copy a file from this node to the host", "path", "hostPath"))
                .put(method("copyFromDevice", "Copy a file from the host to this node", "path", "hostPath"))
                .put(method("sha256", "File hash", "path")))
            .put("clientVersion", version)

        private fun method(name: String, description: String, vararg args: String): JSONObject {
            val props = JSONObject()
            for (arg in args) props.put(arg, JSONObject().put("type", "string"))
            return JSONObject()
                .put("method", name)
                .put("description", description)
                .put("args", JSONObject().put("type", "object").put("properties", props))
                .put("returns", "object")
        }
    }

    /** Куда путь попал: null означает «вне разрешённых каталогов». */
    private fun resolve(raw: String?): File? {
        val file = File(raw ?: return null).canonicalFile
        val ok = roots.any { root ->
            val base = File(root).canonicalFile
            file == base || file.path.startsWith(base.path + File.separator)
        }
        return if (ok) file else null
    }

    fun handle(method: String, args: JSONObject): JSONObject {
        return try {
        when (method) {
            "roots" -> ok(JSONObject().put("roots", JSONArray(roots)))
            else -> {
                val file = resolve(args.optString("path"))
                    ?: return deny(args.optString("path"))
                when (method) {
                    "list" -> ok(JSONObject().put("entries", JSONArray().apply {
                        file.listFiles()?.sortedBy { it.name }?.forEach { child ->
                            put(JSONObject()
                                .put("name", child.name)
                                .put("type", if (child.isDirectory) "dir" else "file")
                                .put("size", child.length())
                                .put("mtime", child.lastModified()))
                        }
                    }))
                    "stat" -> ok(JSONObject()
                        .put("exists", file.exists())
                        .put("type", if (file.isDirectory) "dir" else "file")
                        .put("size", file.length())
                        .put("mtime", file.lastModified()))
                    "read" -> {
                        val offset = args.optInt("offset", 0)
                        val limit = args.optInt("limit", 256 * 1024)
                        val bytes = file.readBytes()
                        val slice = bytes.copyOfRange(offset.coerceIn(0, bytes.size),
                            (offset + limit).coerceIn(0, bytes.size))
                        ok(JSONObject().put("text", String(slice)).put("size", bytes.size))
                    }
                    "write" -> {
                        file.parentFile?.mkdirs()
                        file.writeText(args.optString("content"))
                        ok(JSONObject().put("path", file.path).put("size", file.length()))
                    }
                    "edit" -> {
                        val old = args.optString("old")
                        val text = file.readText()
                        val index = text.indexOf(old)
                        if (index < 0) return err("not_found", "строки нет в файле")
                        val replaced = text.removeRange(index, index + old.length).let {
                            it.substring(0, index) + args.optString("new") + it.substring(index)
                        }
                        file.writeText(replaced)
                        ok(JSONObject().put("path", file.path).put("replaced", 1))
                    }
                    "readChunk" -> {
                        val bytes = file.readBytes()
                        val offset = args.optInt("offset", 0).coerceIn(0, bytes.size)
                        val size = args.optInt("size", 256 * 1024)
                        val slice = bytes.copyOfRange(offset, (offset + size).coerceIn(0, bytes.size))
                        ok(JSONObject()
                            .put("data", android.util.Base64.encodeToString(slice, android.util.Base64.NO_WRAP))
                            .put("size", slice.size)
                            .put("eof", offset + slice.size >= bytes.size))
                    }
                    "mkdir" -> {
                        val name = args.optString("name").ifBlank { null }
                        val dir = if (name != null) File(file, name) else file
                        dir.mkdirs()
                        ok(JSONObject().put("path", dir.path).put("type", "dir"))
                    }
                    "sha256" -> {
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        file.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                digest.update(buffer, 0, read)
                            }
                        }
                        ok(JSONObject()
                            .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                            .put("size", file.length()))
                    }
                    else -> err("unknown_method", "нет такого метода: $method")
                }
            }
        }
        } catch (error: Throwable) {
            err("io_error", error.message ?: "не смог прочитать файл")
        }
    }

    private fun ok(payload: JSONObject) = JSONObject().put("status", "ok").put("payload", payload)

    private fun err(code: String, message: String) = JSONObject()
        .put("status", "error").put("code", code).put("message", message)

    /** Отказ с подсказкой: модель должна понять, что нужно просить доступ. */
    private fun deny(path: String) = err(
        "path_denied",
        "путь $path вне разрешённых каталогов: " + (roots.joinToString(", ").ifBlank { "список пуст" }),
    )
}
