package ru.uvw.dshnc.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.uvw.dshnc.BuildConfig
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
                throw ApiException(response.code, extractMessage(text, response.code), extractCode(text))
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

    /**
     * The machine-readable refusal: `epoch-mismatch` and `client-epoch-stale` are the codes
     * that decide whether the whole connection is out of date, and the text in words is no
     * way to tell them apart.
     */
    private fun extractCode(text: String): String? = try {
        JSONObject(text).optString("code").ifBlank { null }
    } catch (_: Exception) {
        null
    }

    fun pair(code: String, uuid: String, name: String): JSONObject = call(
        "POST", "/v1/pair", body = JSONObject()
            .put("code", code)
            .put("uuid", uuid)
            .put("name", name)
            .put("aliases", JSONArray())
            .put("clientVersion", VERSION)
            .put("platform", PLATFORM)
    )

    /**
     * What we are and what we can do.
     *
     * The field set is the desktop one, and the values say the truth about the phone:
     * there is no sandbox here, so the cap and the start mode are both `full`; there is
     * no approval dialog, so `approver` is `none` and escalation is off. `applyRules`
     * carries the rules the node binds (`SessionApi.rules`).
     *
     * `epoch` is the server generation this node knows, or `null` when it knows none —
     * `null` is also the statement "everything you issued to me is dead", and that is what
     * closes in-flight commands on the server. `client_epoch` is the persistent connection
     * counter: it lets the server keep one instance of a node out of two.
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
        epoch: Long? = null,
        clientEpoch: Long? = null,
    ): JSONObject = call(
        "PUT", "/v1/announce", token, JSONObject()
            .put("apis", apis)
            .put("applyRules", applyRules)
            .put("clientVersion", VERSION)
            .put("platform", PLATFORM)
            .put("epoch", epoch ?: JSONObject.NULL)
            .put("client_epoch", clientEpoch ?: JSONObject.NULL)
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

    /**
     * Take the next batch.
     *
     * The generation and the connection counter travel with the request, so that the server
     * refuses a client from another life instead of handing it work it does not belong to:
     * `409 epoch-mismatch` or `409 client-epoch-stale` mean the reset.
     */
    fun commands(token: String, limit: Int = 5, epoch: Long? = null, clientEpoch: Long? = null): JSONObject {
        val query = StringBuilder("/v1/commands?limit=$limit")
        if (epoch != null) query.append("&epoch=$epoch")
        if (clientEpoch != null) query.append("&client_epoch=$clientEpoch")
        return call("GET", query.toString(), token)
    }

    fun result(token: String, seq: Long, payload: JSONObject): JSONObject =
        call("POST", "/v1/commands/$seq/result", token, payload)

    companion object {
        // Taken from the build, never written by hand: a hand-kept copy stayed at 0.10.4
        // through two releases, so the panel showed a version nobody was running
        val VERSION = "android-" + BuildConfig.VERSION_NAME

        /** The platform this client runs on: the server shows it in the panel. */
        const val PLATFORM = "android"
    }
}

class ApiException(
    val status: Int,
    override val message: String,
    /** The server's machine-readable refusal, for example `epoch-mismatch`. */
    val code: String? = null,
) : Exception(message)
