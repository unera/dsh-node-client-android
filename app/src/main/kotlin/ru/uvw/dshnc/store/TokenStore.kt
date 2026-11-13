package ru.uvw.dshnc.store

import android.content.Context
import org.json.JSONObject
import java.io.File
import ru.uvw.dshnc.api.Roots

/**
 * Node state: the server address and the token.
 *
 * We write to two places at once — ordinary settings and a file — and read from
 * whichever has something. On this phone the encrypted store, the settings and the file
 * "disappeared" one after another, so one place is not enough: until it is clear what
 * exactly is being lost, it is cheaper to store twice than to lose the connection.
 */
class TokenStore(private val context: Context) {
    private val file = File(context.filesDir, "state.json")
    private val prefs = context.getSharedPreferences("dshnc-state", Context.MODE_PRIVATE)

    /** The last write error: shown on the screen so that we do not have to guess. */
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

    /** Permanent device identifier: the server recognises the node by it. */
    var deviceId: String?
        get() = read("uuid")
        set(value) = write("uuid", value)

    /**
     * The "service is alive" mark: the time of the last successful poll.
     *
     * Without it "No connection" looks the same both when the service did not come up
     * and when it runs but the server is silent. The mark makes that visible at once.
     */
    var heartbeat: Long
        get() = read("heartbeat")?.toLongOrNull() ?: 0L
        set(value) = write("heartbeat", value.toString())

    /** The link is established: the screen shows "Connected" instead of "Connecting…". */
    var connected: Boolean
        get() = read("connected") == "1"
        set(value) = write("connected", if (value) "1" else null)

    var pingInterval: Int
        get() = read("ping")?.toIntOrNull() ?: 15
        set(value) = write("ping", value.toString())

    var pingFactor: Int
        get() = read("pingFactor")?.toIntOrNull() ?: 10
        set(value) = write("pingFactor", value.toString())

    /** Fallback polling: 0 — frame-driven only. */
    var pollFallback: Int
        get() = read("poll")?.toIntOrNull() ?: 0
        set(value) = write("poll", value.toString())

    var roots: List<String>
        get() {
            val saved = read("roots")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
            return saved.ifEmpty { Roots.defaults(context) }
        }
        set(value) = write("roots", value.joinToString("\n"))

    /** Whether `session.debug_info` is allowed: off by default. */
    var debugInfo: Boolean
        get() = read("debug") == "1"
        set(value) = write("debug", if (value) "1" else null)

    /** When the last `pong` came: shows whether the channel is alive on its own. */
    var lastPong: Long
        get() = read("lastPong")?.toLongOrNull() ?: 0L
        set(value) = write("lastPong", value.toString())

    /** The delay of the last ping in milliseconds. */
    var lastRtt: Long
        get() = read("lastRtt")?.toLongOrNull() ?: 0L
        set(value) = write("lastRtt", value.toString())

    /**
     * The server generation this node knows.
     *
     * `null` means "I know none": a fresh install, or a reset after the server named a
     * generation that is not ours. `null` is also how the announce says "everything you
     * issued to me is dead" — that is the only statement that closes in-flight commands on
     * the server, so forgetting is an action, not a loss.
     */
    var epoch: Long?
        get() = read("epoch")?.toLongOrNull()?.takeIf { it >= 0 }
        set(value) = write("epoch", value?.toString())

    fun path(): String = file.absolutePath

    /** What is stored — for the diagnostics screen. */
    fun dump(): String = "файл: ${file.absolutePath}\n" +
        "адрес: ${base ?: "—"}\n" +
        "токен: ${token?.let { "есть (${it.take(6)}…)" } ?: "—"}\n" +
        "команды: " + (if (heartbeat == 0L) "не запрашивались" else "${(System.currentTimeMillis() - heartbeat) / 1000} с назад") + "\n" +
        "пинг: " + (if (lastPong == 0L) "не отвечал" else "${(System.currentTimeMillis() - lastPong) / 1000} с назад") + "\n" +
        "лаг: " + (if (lastRtt == 0L) "—" else "$lastRtt мс") +
        (lastError?.let { "\nошибка: $it" } ?: "")

    /**
     * The node was turned on by the person. Off means no network activity at all:
     * the service does not come up, the alarm does not wake anything, there are no
     * requests whatsoever.
     */
    var enabled: Boolean
        get() = read("enabled") == "1"
        set(value) = write("enabled", if (value) "1" else null)

    /**
     * A newer instance of this node took the channel (`4001 replaced`) and this one stopped.
     *
     * The flag exists because stopping is not enough: the ten-minute alarm used to bring the
     * service back, the revived instance announced a bigger `client_epoch`, pushed out the
     * instance that had replaced it — and that one's own alarm revived it in turn. Two
     * instances, ping-ponging every ten minutes, each sure it is the real one.
     *
     * Persisted, and cleared only by a person turning the node on: a press is intent, a timer
     * is not.
     */
    var replaced: Boolean
        get() = read("replaced") == "1"
        set(value) = write("replaced", if (value) "1" else null)


    /** How many times the service was woken from scratch: shows that the process died. */
    var starts: Int
        get() = read("starts")?.toIntOrNull() ?: 0
        set(value) = write("starts", value.toString())

    /**
     * Freezes: the system put the process to sleep and then woke it up.
     *
     * We keep the latest records so that "why did the node disappear" can be answered
     * with facts rather than guesses. Record format: `<when>:<how many ms>`.
     */
    var freezes: List<String>
        get() = read("freezes")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = write("freezes", value.takeLast(10).joinToString(","))

    /**
     * The node's event log: connected, disconnected, an error, went to the background.
     *
     * We keep the latest records — they show what exactly happened, instead of guesses
     * like "probably fell asleep" or "probably the network".
     */
    var events: List<String>
        get() = read("events")?.split('|')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = write("events", value.takeLast(60).joinToString("|"))

    /** Record an event: its kind and a short detail. */
    fun addEvent (kind: String, detail: String = "") {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        events = events + (if (detail.isBlank()) "$stamp $kind" else "$stamp $kind: $detail")
    }

    /**
     * The press number: a persistent counter, not a uuid.
     *
     * The server uses it to tell a retry of the same send from a new press. It always
     * grows and survives an app restart.
     */
    fun nextPress (): Long {
        val value = (read("press")?.toLongOrNull() ?: 0L) + 1
        write("press", value.toString())
        return value
    }

    /**
     * The connection counter (`client_epoch` on the wire): incremented before every announce.
     *
     * The server uses it to decide which instance of the node to keep, so it must only grow —
     * also across an upgrade. The value lives under the `conn` key, where the counter used to
     * be: pointing it at a fresh key would restart the numbering at one, and a server that
     * already knows a bigger number would refuse every announce with `client-epoch-stale`
     * until the counter climbed back.
     */
    fun nextClientEpoch (): Long {
        val value = (read("conn")?.toLongOrNull() ?: 0L) + 1
        write("conn", value.toString())
        return value
    }

    /** Record a freeze lasting `ms`. */
    fun addFreeze (ms: Long) {
        if (ms < 1000) return
        freezes = freezes + "${System.currentTimeMillis()}:$ms"
    }

    /**
     * The secret that makes an enrolment ours.
     *
     * A uuid is not a secret — it sits in the state file and shows up in logs — so the
     * server hands the token of an approved request only to whoever knows this nonce. It is
     * generated once, kept next to the token in both places, and never leaves the phone
     * except in the two requests that need it.
     */
    fun clientNonce (): String {
        read("nonce")?.let { return it }
        val value = java.util.UUID.randomUUID().toString()
        write("nonce", value)
        return value
    }

    /**
     * The node asked to be let in and waits for a person to confirm it in the admin panel.
     *
     * The screen shows it instead of "connecting", and the notification says it in words:
     * without this word a node waiting for approval is indistinguishable from one that
     * cannot reach the server at all.
     */
    var enrollPending: Boolean
        get() = read("enroll") == "1"
        set(value) = write("enroll", if (value) "1" else null)

    /** When the watchman last restarted the service: the cooldown is counted from here. */
    var watchdogAt: Long
        get() = read("watchdog")?.toLongOrNull() ?: 0L
        set(value) = write("watchdog", value.toString())

    fun clear() {
        // the address and the device identity stay: "forget the connection" is about
        // the token, not about the phone having become another phone. Otherwise
        // duplicates of the same device pile up on the server.
        val keepBase = base
        val keepId = deviceId
        write("token", null)
        write("name", null)
        write("enabled", null)
        // the request that was waiting for approval belongs to the connection being forgotten
        write("enroll", null)
        write("base", keepBase)
        write("uuid", keepId)
    }
}
