package ru.uvw.dshnc.api

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The node's channel: the WebSocket over which the server says "there is work".
 *
 * Ping here is not for commands but for noticing a break: the server answers `pong`,
 * and that tells whether the channel is alive. Commands are fetched by a request only
 * after a `state` frame — that is, strictly by state, not by a timer.
 *
 * `check_state` is a request saying "I am waiting": the server sends `state` only to
 * those who made it, otherwise the messages would pile up on its side.
 */
class Socket(
    private val base: String,
    private val token: String,
    private val proto: Int = 1,
    private val onWake: () -> Unit,
    private val onOpen: () -> Unit,
    private val onClose: (String) -> Unit,
    private val onPong: (Long) -> Unit = {},
    /** The first allowed root: the server shows it in the panel and keeps session dirs there. */
    private val workdir: String? = null,
    /** The permission ceiling of the node: there is no sandbox on the phone, so `full`. */
    private val access: String = "full",
    /** Whether a human can be asked: no dialog here, so `none`. */
    private val approver: String = "none",
    /** The narrowest mode a command may run in — the same value as in `announce`. */
    private val startMode: String = "full",
    /** How long the node has been running: the server translates it into its own clock. */
    private val uptimeSeconds: () -> Long = { 0L },
    /** The server generation the node remembers; null — it knows none yet. */
    private val epoch: Long? = null,
    /** The server said the generation changed: the epoch reset has to be applied. */
    private val onReconnect: (Long?) -> Unit = {},
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

    /**
     * Whether this process still owes the server the "I restarted" frame.
     *
     * `known: null` means for the server "everything you issued to me died with the
     * process": it closes those commands as `node_restarted`. Said once — while it is
     * repeated in every frame, the batch that has just been handed out is closed too,
     * and a live command dies a hundred milliseconds after it was issued. The flag is
     * per process, not per connection: a reconnect keeps the same process and the same
     * running commands.
     */
    private var fresh = !restartDeclared.get()

    /** Time of the last answer to a ping: the channel is alive regardless of commands. */
    @Volatile var lastPong: Long = 0L

    /** When each ping went out: used to compute the answer delay. */
    private val sentAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun connect (conn: Long? = null, known: Long = 0L) {
        val url = base.replaceFirst("http", "ws").trimEnd('/') + "/v1/ws"
        val request = Request.Builder().url(url).header("sec-websocket-protocol", token).build()
        val cursor = if (fresh) JSONObject.NULL else known
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // The hello carries the same fields as `announce`: the server updates the
                // node card from it before the announcement arrives, otherwise they land
                // one step later and the panel shows a half-truth for a while.
                val hello = JSONObject()
                    .put("t", "hello")
                    .put("proto", proto)
                    .put("known", cursor)
                    .put("conn", conn ?: JSONObject.NULL)
                    .put("epoch", epoch ?: JSONObject.NULL)
                    .put("workdir", workdir ?: JSONObject.NULL)
                    .put("access", access)
                    .put("allowEscalation", false)
                    .put("approver", approver)
                    .put("base", startMode)
                    .put("uptimeSeconds", uptimeSeconds())
                // `null` in `known` is said by the hello frame only: it means "everything
                // you issued to me died with the process". Any later frame carries the
                // cursor, otherwise a live batch would be closed right after being issued.
                fresh = false
                restartDeclared.set(true)
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = try {
                    JSONObject(text)
                } catch (_: Throwable) {
                    return
                }
                when (frame.optString("t")) {
                    "hello-ok" -> {
                        // we declare our wait right away: without it the server has no right
                        // to send the frame, even if there is already work
                        webSocket.send(JSONObject().put("t", "check_state").put("known", known).toString())
                        // the server keeps its generation: a different one means it restarted
                        // while we were away, and the epoch the node remembers is stale
                        if (frame.optBoolean("reconnect", false)) onReconnect(frame.optLong("epoch", 0L))
                        onOpen()
                    }
                    "pong" -> {
                        // answer time and delay: the id came back the same one that went out,
                        // so RTT is computed from our own send
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

    /** Tell the server "I am waiting again" after a batch is processed. */
    fun waiting(known: Long?) {
        socket?.send(JSONObject().put("t", "check_state").put("known", known ?: JSONObject.NULL).toString())
    }

    /**
     * Say "I restarted" again — used when the server generation changed, not on an
     * ordinary start.
     *
     * Everything the old generation had already handed out died together with it, and
     * leaving those commands in `processing` until the timeout is a lie. The flag is
     * raised too: if this connection drops before the server hears us, the next hello
     * repeats the declaration.
     */
    fun declareRestart() {
        fresh = true
        restartDeclared.set(false)
        socket?.send(JSONObject().put("t", "check_state").put("known", JSONObject.NULL).toString())
    }

    fun ping(id: Int) {
        sentAt[id.toString()] = System.currentTimeMillis()
        socket?.send(JSONObject().put("t", "ping").put("id", id).toString())
    }

    fun close() {
        socket?.close(1000, "bye")
        socket = null
    }

    companion object {
        /** Set once the process has declared its restart: a reconnect must not repeat it. */
        private val restartDeclared = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
