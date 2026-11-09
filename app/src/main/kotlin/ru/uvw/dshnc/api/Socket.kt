package ru.uvw.dshnc.api

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Канал узла: WebSocket, по которому сервер говорит «есть работа».
 *
 * Пинг здесь нужен не для команд, а чтобы заметить обрыв: сервер отвечает
 * `pong`, и по нему понятно, жив ли канал. Команды забираются запросом только
 * после кадра `state` — то есть строго по состоянию, а не по таймеру.
 *
 * `check_state` — это заявка «я жду»: сервер присылает `state` только тем, кто
 * её сделал, иначе сообщения копились бы у него.
 */
class Socket(
    private val base: String,
    private val token: String,
    private val proto: Int = 1,
    private val onWake: () -> Unit,
    private val onOpen: () -> Unit,
    private val onClose: (String) -> Unit,
    private val onPong: (Long) -> Unit = {},
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null

    /** Время последнего ответа на пинг: канал жив независимо от команд. */
    @Volatile var lastPong: Long = 0L

    /** Когда ушёл каждый пинг: по нему считаем задержку ответа. */
    private val sentAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun connect() {
        val url = base.replaceFirst("http", "ws").trimEnd('/') + "/v1/ws"
        val request = Request.Builder().url(url).header("sec-websocket-protocol", token).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("t", "hello").put("proto", proto).put("known", JSONObject.NULL).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = try {
                    JSONObject(text)
                } catch (_: Throwable) {
                    return
                }
                when (frame.optString("t")) {
                    "hello-ok" -> {
                        // заявляем ожидание сразу: без этого сервер не имеет права
                        // присылать кадр, даже если работа уже есть
                        webSocket.send(JSONObject().put("t", "check_state").put("known", JSONObject.NULL).toString())
                        onOpen()
                    }
                    "pong" -> {
                        // время ответа и задержка: id пришёл тот же, что ушёл,
                        // поэтому RTT считается по своей же отправке
                        val now = System.currentTimeMillis()
                        lastPong = now
                        val sent = sentAt.remove(frame.opt("id")?.toString())
                        onPong(if (sent == null) 0 else now - sent)
                    }
                    "state" -> onWake()
                    "ping" -> webSocket.send(JSONObject().put("t", "pong").put("id", frame.opt("id")).toString())
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onClose("сбой: ${t.message ?: t.javaClass.simpleName}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClose("код $code ${reason.ifBlank { "без причины" }}")
            }
        })
    }

    /** Сказать серверу «я снова жду» после обработки пачки. */
    fun waiting(known: Long?) {
        socket?.send(JSONObject().put("t", "check_state").put("known", known ?: JSONObject.NULL).toString())
    }

    fun ping(id: Int) {
        sentAt[id.toString()] = System.currentTimeMillis()
        socket?.send(JSONObject().put("t", "ping").put("id", id).toString())
    }

    fun close() {
        socket?.close(1000, "bye")
        socket = null
    }
}
