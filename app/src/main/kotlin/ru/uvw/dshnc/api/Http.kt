package ru.uvw.dshnc.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The node's transport: the same requests `dshnc` makes, only in Kotlin.
 *
 * We invent nothing of our own: `pair` exchanges a one-time code for a token,
 * `announce` declares the capabilities, `commands` picks up a batch, `result` sends the
 * answer back. That way the node is visible in the admin panel exactly like a laptop.
 */
class NodeApi(private val base: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    private fun call(method: String, path: String, token: String? = null, body: JSONObject? = null): JSONObject {
        val builder = Request.Builder().url(base.trimEnd('/') + path)
        token?.let { builder.header("authorization", "Bearer $it") }
        val payload = body?.toString()?.toRequestBody(json)
        when (method) {
            "GET" -> builder.get()
            "PUT" -> builder.put(payload ?: "{}".toRequestBody(json))
            else -> builder.post(payload ?: "{}".toRequestBody(json))
        }
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(response.code, extractMessage(text, response.code))
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    /** The rejection text: the server sends it in words, and we show those to the person. */
    private fun extractMessage(text: String, status: Int): String = try {
        JSONObject(text).optString("message").ifBlank { "HTTP $status" }
    } catch (_: Exception) {
        "HTTP $status"
    }

    fun pair(code: String, uuid: String, name: String): JSONObject = call(
        "POST", "/v1/pair", body = JSONObject()
            .put("code", code)
            .put("uuid", uuid)
            .put("name", name)
            .put("aliases", JSONArray())
            .put("proto", 1)
            .put("clientVersion", VERSION)
    )

    /**
     * What we are and what we can do.
     *
     * The field set is the desktop one, and the values say the truth about the phone:
     * there is no sandbox here, so the cap and the start mode are both `full`; there is
     * no approval dialog, so `approver` is `none` and escalation is off. `applyRules`
     * carries the rules the node binds (`SessionApi.rules`).
     */
    fun announce(
        token: String,
        apis: JSONArray,
        uptimeSeconds: Long,
        workdir: String?,
        applyRules: JSONArray = JSONArray(),
        access: String = "full",
        allowEscalation: Boolean = false,
        approver: String = "none",
        base: String = "full",
    ): JSONObject = call(
        "PUT", "/v1/announce", token, JSONObject()
            .put("apis", apis)
            .put("applyRules", applyRules)
            .put("proto", 1)
            .put("clientVersion", VERSION)
            .put("workdir", workdir ?: JSONObject.NULL)
            .put("access", access)
            .put("allowEscalation", allowEscalation)
            .put("uptimeSeconds", uptimeSeconds)
            .put("approver", approver)
            .put("base", base)
    )

    /** A device event: a button press, readiness, anything. */
    fun sendEvent (token: String, body: JSONObject): JSONObject =
        call("POST", "/v1/events", token = token, body = body)

    fun commands(token: String, limit: Int = 5): JSONObject =
        call("GET", "/v1/commands?limit=$limit", token)

    fun result(token: String, seq: Long, payload: JSONObject): JSONObject =
        call("POST", "/v1/commands/$seq/result", token, payload)

    companion object {
        const val VERSION = "android-0.9.4"
    }
}

class ApiException(val status: Int, override val message: String) : Exception(message)
