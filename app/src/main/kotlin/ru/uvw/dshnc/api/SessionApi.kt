package ru.uvw.dshnc.api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.uvw.dshnc.BuildConfig
import ru.uvw.dshnc.service.NodeService
import ru.uvw.dshnc.store.TokenStore

/**
 * `session`: the node's service state.
 *
 * It exists for debugging: instead of "send me a screenshot" it is enough to ask for
 * `session.debug_info` and see the version, the link state, the directories and the
 * counters. That is why the method is gated by a setting: it is off by default, and the
 * phone owner decides whether to expose such things at all.
 */
class SessionApi(private val context: Context) {
    companion object {
        /**
         * The special rules this API binds (`announce.applyRules`).
         *
         * `ping` is measured by the server itself: the duration by its own marks, the clock
         * skew by `now` from the result envelope. Without a binding the numbers are
         * computed into nowhere, and `now` is worth nothing (spec/SPECIAL-RULES.md).
         */
        fun rules(): JSONArray = JSONArray().put(JSONObject()
            .put("api", "session")
            .put("method", "ping")
            .put("rule", "timing")
            .put("rulesOpts", JSONObject().put("retNames", JSONObject()
                .put("durationMs", "durationMs")
                .put("clockSkewMs", "clockSkewMs"))))

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
            // the server computes rtt itself: it knows when it sent the command.
            // The node hands over only its own time and uptime.
            val now = System.currentTimeMillis()
            return JSONObject().put("status", "ok").put("payload", JSONObject()
                // `pong` — the node's own clock: the server uses it to compute the delay
                // and the clock skew, the same way as with the desktop client
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
            // the epoch protocol's three numbers: a wrong one is what the server refuses, so
            // they are the first thing to look at when the node "reconnects for no reason"
            .put("epoch", NodeService.epoch() ?: JSONObject.NULL)
            .put("clientEpoch", NodeService.clientEpoch() ?: JSONObject.NULL)
            .put("maxCommandno", NodeService.maxCommandno())
            .put("roots", JSONArray(store.roots))
            .put("pingInterval", store.pingInterval)
            .put("pingFactor", store.pingFactor)
            .put("allFiles", android.os.Environment.isExternalStorageManager())
            .put("starts", store.starts)
            .put("events", org.json.JSONArray(store.events))
            .put("freezes", org.json.JSONArray(store.freezes.mapNotNull { entry ->
                val parts = entry.split(":")
                if (parts.size != 2) null
                else org.json.JSONObject().put("at", parts[0].toLongOrNull() ?: 0L).put("ms", parts[1].toLongOrNull() ?: 0L)
            }))
            .put("batteryUnrestricted",
                (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                    .isIgnoringBatteryOptimizations(context.packageName))
            .put("lastError", store.lastError ?: JSONObject.NULL)
        return JSONObject().put("status", "ok").put("payload", payload)
    }
}
