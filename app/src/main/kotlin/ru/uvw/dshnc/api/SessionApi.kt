package ru.uvw.dshnc.api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.uvw.dshnc.BuildConfig
import ru.uvw.dshnc.service.NodeService
import ru.uvw.dshnc.store.TokenStore

/**
 * `session`: служебное состояние узла.
 *
 * Живёт ради отладки: вместо «пришли скриншот» достаточно попросить
 * `session.debug_info` и увидеть версию, состояние связи, каталоги и счётчики.
 * Поэтому метод закрыт настройкой: по умолчанию выключен, и владелец телефона
 * сам решает, отдавать ли такое наружу.
 */
class SessionApi(private val context: Context) {
    companion object {
        fun schema(): JSONObject = JSONObject()
            .put("name", "session")
            .put("version", NodeApi.VERSION)
            .put("description", "Node state for debugging (disabled unless the owner allows it)")
            .put("methods", JSONArray()
                .put(JSONObject()
                    .put("method", "id")
                    .put("description", "Session id this command belongs to (for device events)")
                    .put("args", JSONObject().put("type", "object").put("properties", JSONObject()))
                    .put("returns", "object"))
                .put(JSONObject()
                    .put("method", "ping")
                    .put("description", "Answer with the phone clock and uptime; the server measures the round trip")
                    .put("args", JSONObject().put("type", "object").put("properties", JSONObject()))
                    .put("returns", "object"))
                .put(JSONObject()
                    .put("method", "debug_info")
                .put("description", "Version, connection state, allowed roots and counters")
                .put("args", JSONObject().put("type", "object").put("properties", JSONObject()))
                .put("returns", "object")))
    }

    fun handle(method: String, args: JSONObject, session: String? = null): JSONObject {
        if (method == "id") {
            return JSONObject().put("status", "ok").put("payload", JSONObject()
                .put("session", session ?: JSONObject.NULL))
        }
        if (method == "ping") {
            // сервер считает rtt сам: он знает, когда отправил команду.
            // Узел отдаёт только своё время и аптайм.
            val now = System.currentTimeMillis()
            return JSONObject().put("status", "ok").put("payload", JSONObject()
                // `pong` — собственные часы узла: сервер считает по ним задержку
                // и расхождение часов, как у десктопного клиента
                .put("pong", now)
                .put("sentAt", now)
                .put("upSeconds", NodeService.uptime())
                .put("version", NodeApi.VERSION)
                .put("platform", "android"))
        }
        if (method != "debug_info") {
            return JSONObject().put("status", "error")
                .put("code", "unknown_method").put("message", "нет такого метода: $method")
        }
        val store = TokenStore(context)
        if (!store.debugInfo) {
            return JSONObject().put("status", "error").put("code", "disabled").put(
                "message",
                "debug_info выключен владельцем телефона: включи его в настройках приложения",
            )
        }
        val heartbeat = store.heartbeat
        val payload = JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("build", BuildConfig.VERSION_CODE)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("model", android.os.Build.MODEL)
            .put("connected", store.connected)
            .put("enabled", store.enabled)
            .put("lastCommandsAgoMs", if (heartbeat == 0L) -1 else System.currentTimeMillis() - heartbeat)
            .put("lastPongAgoMs", if (store.lastPong == 0L) -1 else System.currentTimeMillis() - store.lastPong)
            .put("lastRttMs", store.lastRtt)
            .put("commandsExecuted", NodeService.executed.get())
            .put("roots", JSONArray(store.roots))
            .put("pingInterval", store.pingInterval)
            .put("pingFactor", store.pingFactor)
            .put("pollFallback", store.pollFallback)
            .put("allFiles", android.os.Environment.isExternalStorageManager())
            .put("batteryUnrestricted",
                (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                    .isIgnoringBatteryOptimizations(context.packageName))
            .put("lastError", store.lastError ?: JSONObject.NULL)
        return JSONObject().put("status", "ok").put("payload", payload)
    }
}
