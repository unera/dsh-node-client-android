package ru.uvw.dshnc.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Транспорт узла: те же запросы, что делает `dshnc`, только на Kotlin.
 *
 * Ничего своего не придумываем: `pair` обменивает одноразовый код на токен,
 * `announce` объявляет возможности, `commands` забирает пачку, `result`
 * возвращает ответ. Так узел виден в админке ровно так же, как ноутбук.
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

    /** Текст отказа: сервер присылает его словами, их и показываем человеку. */
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

    fun announce(token: String, apis: JSONArray, uptimeSeconds: Long): JSONObject = call(
        "PUT", "/v1/announce", token, JSONObject()
            .put("apis", apis)
            .put("proto", 1)
            .put("clientVersion", VERSION)
            .put("uptimeSeconds", uptimeSeconds)
            .put("allowEscalation", false)
    )

    /** Событие устройства: нажатие кнопки, готовность, что угодно. */
    fun sendEvent (token: String, body: JSONObject): JSONObject =
        call("POST", "/v1/events", token = token, body = body)

    fun commands(token: String, limit: Int = 5): JSONObject =
        call("GET", "/v1/commands?limit=$limit", token)

    fun result(token: String, seq: Long, payload: JSONObject): JSONObject =
        call("POST", "/v1/commands/$seq/result", token, payload)

    companion object {
        const val VERSION = "android-0.7.1"
    }
}

class ApiException(val status: Int, override val message: String) : Exception(message)
