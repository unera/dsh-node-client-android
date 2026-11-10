package ru.uvw.dshnc.api

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The node's channel: a WebSocket with exactly two frames, `state` and `ping`.
 *
 * `{t:'state', epoch, client_epoch, max_commandno}` is "I am waiting": my generation, my
 * connection counter and my own last command number. It replaces the old `hello`,
 * `check_state` and the `known` cursor — there is no handshake frame and no cursor any
 * more. `{t:'ping', no}` checks that the server is alive.
 *
 * The numbers are read at the moment of sending, not captured at connect time: a snapshot
 * taken once would make the node look lagging forever.
 *
 * The token travels as a subprotocol, in the `Sec-WebSocket-Protocol` header — never in the
 * URL, where proxies and history would keep it.
 */
class Socket(
    private val base: String,
    private val token: String,
    /** The server generation this node knows, or null when it knows none. */
    private val epoch: () -> Long? = { null },
    /** The persistent per-connection counter (`client_epoch` on the wire). */
    private val clientEpoch: () -> Long? = { null },
    /** The highest command number this process executed. */
    private val maxCommandno: () -> Long = { 0L },
    /** A `state` frame from the server: the generation and the number it named. */
    private val onState: (Long?, Long?) -> Unit = { _, _ -> },
    private val onOpen: () -> Unit = {},
    /**
     * The channel closed. The code and the reason are the server's words: `4409` and
     * `4001 client-epoch` mean the reset, `4001 replaced` means this instance is the older
     * one and has to stop.
     */
    private val onClose: (Int, String) -> Unit = { _, _ -> },
    /** The answer to our ping: the delay in milliseconds, 0 when it cannot be computed. */
    private val onPong: (Long) -> Unit = {},
) {
    private val client = OkHttpClient.Builder()
        // Protocol-level ping: OkHttp sends ping frames itself and drops
        // the connection if no pong came. Without it a dead socket counts as
        // alive until our watchdog counts up to two stretched intervals —
        // and those are minutes during which the node counts as connected and does not answer.
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null

    /** Time of the last answer to a ping: the channel is alive regardless of commands. */
    @Volatile var lastPong: Long = 0L

    /** When each ping went out: used to compute the answer delay. */
    private val sentAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    fun connect() {
        val url = base.replaceFirst("http", "ws").trimEnd('/') + "/v1/ws"
        val request = Request.Builder().url(url).header("sec-websocket-protocol", token).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // The very first frame is "I am waiting": the server sends nothing to a node
                // that has not declared its state, otherwise the messages would pile up on
                // its side. The announce has already settled the generation, so the number
                // in this frame is the current one, not a stale copy.
                sendState(webSocket)
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = try {
                    JSONObject(text)
                } catch (_: Throwable) {
                    return
                }
                when (frame.optString("t")) {
                    "state" -> {
                        // the fields may be absent: an absent generation is "not named",
                        // not zero, and the difference decides between adopt and reset
                        val seen = if (frame.has("epoch") && !frame.isNull("epoch")) frame.optLong("epoch") else null
                        val number =
                            if (frame.has("max_commandno") && !frame.isNull("max_commandno")) frame.optLong("max_commandno") else null
                        onState(seen, number)
                    }
                    "pong" -> {
                        // the number came back the same one that went out, so RTT is
                        // computed from our own send
                        val now = System.currentTimeMillis()
                        lastPong = now
                        val sent = sentAt.remove(frame.optInt("no"))
                        onPong(if (sent == null) 0 else now - sent)
                    }
                    // Nothing else exists on this socket: `state` and `pong` are the whole
                    // server vocabulary, and an unknown frame is not worth inventing an
                    // answer for
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onClose(CLOSE_FAILURE, "сбой: ${t.message ?: t.javaClass.simpleName}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClose(code, reason)
            }
        })
    }

    /**
     * Say "I am waiting" again — after a batch is processed, or when the loop is about to
     * sleep. The epoch and the counter are read right here, at the moment of sending.
     */
    fun sendState() {
        socket?.let { sendState(it) }
    }

    private fun sendState(target: WebSocket) {
        target.send(JSONObject()
            .put("t", "state")
            .put("epoch", epoch() ?: JSONObject.NULL)
            .put("client_epoch", clientEpoch() ?: JSONObject.NULL)
            .put("max_commandno", maxCommandno())
            .toString())
    }

    fun ping(no: Int) {
        sentAt[no] = System.currentTimeMillis()
        socket?.send(JSONObject().put("t", "ping").put("no", no).toString())
    }

    fun close() {
        socket?.close(1000, "bye")
        socket = null
    }

    companion object {
        /** Not a protocol code: our own mark for "the socket broke without the server's word". */
        const val CLOSE_FAILURE = 0
    }
}
