package ru.uvw.dshnc.store

import android.content.Context
import org.json.JSONObject
import java.io.File
import ru.uvw.dshnc.api.Roots

/**
 * Состояние узла: адрес сервера и токен.
 *
 * Пишем в два места сразу — в обычные настройки и в файл, — и читаем из того,
 * где что-то есть. На этом телефоне по очереди «пропадали» и шифрованное
 * хранилище, и настройки, и файл, поэтому одного места мало: пока не понятно,
 * что именно теряется, дешевле хранить дважды и не терять подключение.
 */
class TokenStore(private val context: Context) {
    private val file = File(context.filesDir, "state.json")
    private val prefs = context.getSharedPreferences("dshnc-state", Context.MODE_PRIVATE)

    /** Последняя ошибка записи: показываем на экране, чтобы не гадать. */
    var lastError: String? = null
        private set

    private fun fromPrefs(key: String): String? = prefs.getString(key, null)?.takeIf { it.isNotBlank() }

    private fun fromFile(key: String): String? = try {
        if (!file.exists()) null else JSONObject(file.readText()).optString(key).takeIf { it.isNotBlank() }
    } catch (error: Throwable) {
        lastError = "чтение файла: ${error.message}"
        null
    }

    private fun read(key: String): String? = fromPrefs(key) ?: fromFile(key)

    private fun write(key: String, value: String?) {
        try {
            if (value == null) prefs.edit().remove(key).commit() else prefs.edit().putString(key, value).commit()
        } catch (error: Throwable) {
            lastError = "настройки: ${error.message}"
        }
        try {
            val current = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            if (value == null) current.remove(key) else current.put(key, value)
            file.writeText(current.toString())
        } catch (error: Throwable) {
            lastError = "файл: ${error.message}"
        }
    }

    var base: String?
        get() = read("base")
        set(value) = write("base", value)

    var token: String?
        get() = read("token")
        set(value) = write("token", value)

    var name: String?
        get() = read("name")
        set(value) = write("name", value)

    /** Постоянный идентификатор устройства: сервер узнаёт по нему узел. */
    var deviceId: String?
        get() = read("uuid")
        set(value) = write("uuid", value)

    /**
     * Отметка «служба жива»: время последнего успешного опроса.
     *
     * Без неё «Нет связи» одинаково выглядит и когда служба не поднялась, и
     * когда она работает, но сервер молчит. По отметке это видно сразу.
     */
    var heartbeat: Long
        get() = read("heartbeat")?.toLongOrNull() ?: 0L
        set(value) = write("heartbeat", value.toString())

    /** Связь установлена: экран показывает «Соединено» вместо «Соединяюсь…». */
    var connected: Boolean
        get() = read("connected") == "1"
        set(value) = write("connected", if (value) "1" else null)

    var pingInterval: Int
        get() = read("ping")?.toIntOrNull() ?: 15
        set(value) = write("ping", value.toString())

    var pingFactor: Int
        get() = read("pingFactor")?.toIntOrNull() ?: 10
        set(value) = write("pingFactor", value.toString())

    /** Запасной опрос: 0 — только по кадру. */
    var pollFallback: Int
        get() = read("poll")?.toIntOrNull() ?: 0
        set(value) = write("poll", value.toString())

    var roots: List<String>
        get() {
            val saved = read("roots")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
            return saved.ifEmpty { Roots.defaults(context) }
        }
        set(value) = write("roots", value.joinToString("\n"))

    /** Разрешён ли `session.debug_info`: по умолчанию нет. */
    var debugInfo: Boolean
        get() = read("debug") == "1"
        set(value) = write("debug", if (value) "1" else null)

    /** Когда пришёл последний `pong`: показывает, жив ли канал сам по себе. */
    var lastPong: Long
        get() = read("lastPong")?.toLongOrNull() ?: 0L
        set(value) = write("lastPong", value.toString())

    /** Задержка последнего пинга в миллисекундах. */
    var lastRtt: Long
        get() = read("lastRtt")?.toLongOrNull() ?: 0L
        set(value) = write("lastRtt", value.toString())

    fun path(): String = file.absolutePath

    /** Что записано — для экрана диагностики. */
    fun dump(): String = "файл: ${file.absolutePath}\n" +
        "адрес: ${base ?: "—"}\n" +
        "токен: ${token?.let { "есть (${it.take(6)}…)" } ?: "—"}\n" +
        "команды: " + (if (heartbeat == 0L) "не запрашивались" else "${(System.currentTimeMillis() - heartbeat) / 1000} с назад") + "\n" +
        "пинг: " + (if (lastPong == 0L) "не отвечал" else "${(System.currentTimeMillis() - lastPong) / 1000} с назад") + "\n" +
        "лаг: " + (if (lastRtt == 0L) "—" else "$lastRtt мс") +
        (lastError?.let { "\nошибка: $it" } ?: "")

    /**
     * Узел включён человеком. Выключено — значит никакой сетевой активности:
     * служба не поднимается, будильник не будит, запросов нет вовсе.
     */
    var enabled: Boolean
        get() = read("enabled") == "1"
        set(value) = write("enabled", if (value) "1" else null)

    fun clear() {
        // адрес и личность устройства остаются: «забыть подключение» — это про
        // токен, а не про то, что телефон стал другим. Иначе на сервере плодятся
        // дубликаты одного и того же устройства.
        val keepBase = base
        val keepId = deviceId
        write("token", null)
        write("name", null)
        write("enabled", null)
        write("base", keepBase)
        write("uuid", keepId)
    }
}
