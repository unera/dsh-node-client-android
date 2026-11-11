package ru.uvw.dshnc

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.uvw.dshnc.api.Socket

/**
 * The wire itself: what our socket sends and what it is willing to hear.
 *
 * Everything here is a fact the server depends on — the first frame is `state` (the server
 * sends nothing to a node that has not declared itself), `pong` is matched by number, and a
 * close carries the socket it belongs to, which is what keeps a replaced channel from
 * stopping the whole node.
 *
 * A real WebSocket on the JVM (MockWebServer), not a stub: the frames are parsed by the same
 * okhttp the phone uses.
 */
// Robolectric for one reason: the frames are built with `org.json`, which the plain JVM test
// classpath replaces with stubs that throw
@RunWith(RobolectricTestRunner::class)
class SocketTest {
    private lateinit var server: MockWebServer
    private val opened = CountDownLatch(1)

    @Before
    fun setUp () {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown () {
        server.shutdown()
    }

    /** A server side that records what the node sends and can talk back. */
    private inner class Peer : WebSocketListener() {
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val closed = AtomicReference<Pair<Int, String>?>(null)
        val closeLatch = CountDownLatch(1)
        var socket: WebSocket? = null

        override fun onOpen (webSocket: WebSocket, response: Response) {
            socket = webSocket
            opened.countDown()
        }

        override fun onMessage (webSocket: WebSocket, text: String) {
            lines.add(text)
        }

        override fun onClosing (webSocket: WebSocket, code: Int, reason: String) {
            closed.set(code to reason)
            closeLatch.countDown()
            webSocket.close(1000, null)
        }
    }

    private fun serve (peer: Peer) {
        server.enqueue(MockResponse().withWebSocketUpgrade(peer))
    }

    private fun client (
        onOpen: (Socket) -> Unit = {},
        onState: (Socket, Long?, Long?) -> Unit = { _, _, _ -> },
        onClose: (Socket, Int, String) -> Unit = { _, _, _ -> },
        onPong: (Socket, Long) -> Unit = { _, _ -> },
    ) = Socket(
        base = server.url("/").toString(),
        token = "S4mple-Token_123",
        epoch = { 160L },
        clientEpoch = { 1030L },
        maxCommandno = { 31L },
        onState = onState,
        onOpen = onOpen,
        onClose = onClose,
        onPong = onPong,
    )

    @Test
    fun `the first frame is state, with the numbers read at the moment of sending`() {
        val peer = Peer()
        serve(peer)
        val socket = client()
        socket.connect()
        assertTrue("канал не открылся", opened.await(5, TimeUnit.SECONDS))
        val first = JSONObject(peer.lines.first())
        assertEquals("state", first.optString("t"))
        assertEquals(160L, first.optLong("epoch"))
        assertEquals(1030L, first.optLong("client_epoch"))
        assertEquals(31L, first.optLong("max_commandno"))
        socket.close()
    }

    @Test
    fun `a ping goes out as ping and the answer is reported as a pong`() {
        val peer = Peer()
        serve(peer)
        var reported: Long? = null
        val pong = CountDownLatch(1)
        val socket = client(onPong = { _, rtt -> reported = rtt; pong.countDown() })
        socket.connect()
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        socket.ping(7)
        // the server answers with the same number, as the protocol describes
        val answered = CountDownLatch(1)
        Thread {
            while (peer.lines.size < 2) Thread.sleep(10)
            peer.socket?.send("""{"t":"pong","no":7}""")
            answered.countDown()
        }.apply { isDaemon = true; start() }
        assertTrue("pong не вернулся", pong.await(5, TimeUnit.SECONDS))
        assertEquals(7, JSONObject(peer.lines[1]).optInt("no"))
        assertNotNull("rtt не посчитан", reported)
        socket.close()
    }

    @Test
    fun `a state frame from the server is passed on with its generation`() {
        val peer = Peer()
        serve(peer)
        val seen = AtomicReference<Long?>(null)
        val got = CountDownLatch(1)
        val socket = client(onState = { _, epoch, _ -> seen.set(epoch); got.countDown() })
        socket.connect()
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        peer.socket?.send("""{"t":"state","epoch":161,"max_commandno":32}""")
        assertTrue(got.await(5, TimeUnit.SECONDS))
        assertEquals(161L, seen.get())
        socket.close()
    }

    @Test
    fun `a close tells the owner which socket closed`() {
        val peer = Peer()
        serve(peer)
        val who = AtomicReference<Socket?>(null)
        val code = AtomicReference<Int?>(null)
        val closedLatch = CountDownLatch(1)
        val socket = client(onClose = { from, codeSeen, _ -> who.set(from); code.set(codeSeen); closedLatch.countDown() })
        socket.connect()
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        peer.socket?.close(4001, "replaced")
        assertTrue("закрытие не дошло", closedLatch.await(5, TimeUnit.SECONDS))
        // the identity is the point: the service decides by it whether to stop the node
        assertSame(socket, who.get())
        assertEquals(4001, code.get())
        socket.close()
    }
}
