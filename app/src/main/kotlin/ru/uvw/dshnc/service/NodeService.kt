package ru.uvw.dshnc.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import ru.uvw.dshnc.CrashLog
import ru.uvw.dshnc.R
import ru.uvw.dshnc.api.ApiException
import ru.uvw.dshnc.api.FilesApi
import ru.uvw.dshnc.api.NodeApi
import ru.uvw.dshnc.api.DeviceApi
import ru.uvw.dshnc.api.DialogApi
import ru.uvw.dshnc.api.NotificationsApi
import ru.uvw.dshnc.api.PipeApi
import ru.uvw.dshnc.api.Roots
import ru.uvw.dshnc.api.SessionApi
import ru.uvw.dshnc.api.ShellApi
import ru.uvw.dshnc.api.TransferApi
import ru.uvw.dshnc.api.Socket
import ru.uvw.dshnc.logic.Action
import ru.uvw.dshnc.logic.AuthFailure
import ru.uvw.dshnc.logic.ChannelClose
import ru.uvw.dshnc.logic.ChannelState
import ru.uvw.dshnc.logic.Commandno
import ru.uvw.dshnc.logic.Generation
import ru.uvw.dshnc.logic.Link
import ru.uvw.dshnc.logic.Revival
import ru.uvw.dshnc.store.NodeLog
import ru.uvw.dshnc.store.TokenStore

/**
 * The node in the background.
 *
 * Android kills ordinary processes, so the connection lives in a foreground service with
 * a permanent notification — without it there simply is no node once the screen is off.
 *
 * The protocol is the epoch one: `announce` over HTTP settles the generation, the socket
 * carries exactly two frames (`state` and `ping`), and work arrives as a `state` frame
 * instead of a polling timer. Every recovery goes through the same door — forget the epoch,
 * drop the socket, announce `epoch: null` again; there is no cursor to repair.
 */
class NodeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var channelRef: Socket? = null
    /**
     * The address the running loop was built for.
     *
     * The token is not here on purpose: the loop reads it from the store on every round, so a
     * password that changed while the loop was running is picked up by itself. A loop that
     * keeps the token from its own start is exactly how a revoked token turned into ten hours
     * of refusals: the pairing had put a fresh one in the store, and the loop never looked.
     */
    private var runningBase: String? = null
    // the "channel closed" signal: the wait loop wakes up on it at once instead of
    // a minute later, otherwise the node hangs without a link until the next tick
    private val closedSignal = kotlinx.coroutines.channels.Channel<Unit>(
        kotlinx.coroutines.channels.Channel.CONFLATED,
    )
    private var pingJob: Job? = null
    private var freezeJob: Job? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var startedAt = 0L
    /**
     * The highest command number this process executed, and nothing else.
     *
     * It is an honest report of our own work, never a number adopted from the server: the
     * deleted cursor (`known`) was exactly that adoption, and the server's number says what
     * its queue has spent, not what this process did.
     */
    @Volatile private var maxCommandno: Long = 0L
    /** The `dialog.ask` dispatcher: its state lives in `DialogHub`, this is only the door. */
    private val dialog by lazy { DialogApi(this) }
    /** The server generation we know; null — we know none and the next announce says so. */
    @Volatile private var epoch: Long? = null
    /** The persistent connection counter, bumped by every announce. */
    @Volatile private var clientEpoch: Long? = null
    /** Another instance of this node is newer: this service has nothing left to do. */
    @Volatile private var stopRequested = false
    /**
     * The channel has been open at least once since this service started.
     *
     * It is what tells the first attempt from a reconnect: at a start the notification says
     * "connecting", and every attempt after the first connection says "restoring".
     */
    @Volatile private var everOpen = false


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // the screen goes off — the system tries to put the network to sleep; a partial
        // lock keeps the process and the radio alive, otherwise the ping "disappears" for minutes
        val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "dshnc:node").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        wifiLock = wifi.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "dshnc:node").apply {
            setReferenceCounted(false)
            acquire()
        }
        log("service onCreate: sdk=${Build.VERSION.SDK_INT}, network=${NodeLog.network(this)}, starts=${TokenStore(this).starts}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // every start is a trace: from the background this is the alarm, and a start that
        // never happens leaves its silence in the log instead
        log("start id=$startId flags=$flags loop=${loop?.isActive == true}, network=${NodeLog.network(this)}")
        // Since 34 the service type has to be given on the call as well: declaring it in
        // the manifest is not enough, without it the system throws
        // MissingForegroundServiceTypeException — and that was the cause of "a failure happened again"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification(getString(R.string.status_connecting)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification(getString(R.string.status_connecting)))
        }
        Heartbeat.schedule(this)
        val store = TokenStore(this)
        val base = store.base
        val token = store.token
        // the service is already running and holds the channel — a repeated start means
        // nothing, but only as long as the address is the same: the token is read anew by the
        // loop on every round, while the address is fixed when it starts
        if (loop?.isActive == true && base == runningBase) {
            runCatching { store.addEvent("start-повтор", "канал уже держится") }
            log("start-повтор: канал уже держится, channelOpen=$channelOpen")
            // the label must not lie: the second launch does not open the channel, but
            // the notification has to show the real state of the first one
            refresh()
            return START_STICKY
        }
        // every service start is a trace: it shows that the process was killed
        store.starts = store.starts + 1
        // turned off by the person, or displaced by a newer instance of the same node: no
        // network either way. The displaced case is decided by the persisted flag, so nothing
        // else resurrects this instance — not the sticky restart, not an explicit start, not
        // the ten-minute alarm; otherwise the two instances ping-pong, each reviving the one
        // the other displaced. Only a person turning the node on clears it.
        // A node whose token was revoked has no token but still has a uuid: it may come up and
        // ask to be let in again, which is what the desktop client does.
        val canEnroll = base != null && store.deviceId != null
        if (!Revival.allowed(store.enabled, token != null, store.replaced, canEnroll)) {
            log("start отказ: enabled=${store.enabled}, token=${token != null}, replaced=${store.replaced}, canEnroll=$canEnroll")
            stopSelf()
            return START_NOT_STICKY
        }
        if (base == null) {
            log("start отказ: адреса нет — нужна ссылка из админки")
            stopSelf()
            return START_NOT_STICKY
        }
        if (startedAt == 0L) startedAt = android.os.SystemClock.elapsedRealtime()
        val previous = loop
        loop = scope.launch {
            try {
                // the old loop reads the same stopRequested flag, so it has to be gone before
                // this one clears it; cancellation is cooperative and a socket read may hold it
                // for a while, which is why the wait happens here and not on the main thread
                previous?.cancelAndJoin()
                stopRequested = false
                runningBase = base
                run(base)
            } catch (error: Throwable) {
                // a background service must not fail silently: we write the reason into a
                // file that can later be fetched through files
                CrashLog.write(this@NodeService, error)
                log("loop упал: ${error.javaClass.simpleName}: ${error.message}")
                notifyError(error.message)
            }
        }
        return START_STICKY
    }

    private suspend fun run (base: String) {
        val api = NodeApi(base)
        // what we can do: files inside the allowed directories
        val apis = JSONArray()
            .put(FilesApi.schema(NodeApi.VERSION))
            .put(SessionApi.schema())
            .put(NotificationsApi(this).schema())
            .put(dialog.schema())
            .put(DeviceApi(this).schema())
            .put(ShellApi(TokenStore(this).roots).schema())
        // the list of directories is set in the settings and lives in the node state
        val files = FilesApi(this, TokenStore(this).roots)
        val session = SessionApi(this)
        val notifications = NotificationsApi(this)
        val device = DeviceApi(this)
        val shell = ShellApi(TokenStore(this).roots)
        // the generation survives a reconnect but not a reset: a reset nulls it, and the
        // announce that follows names `epoch: null`
        epoch = TokenStore(this).epoch
        while (!stopRequested) {
            try {
                // The token is read anew on every round, and this is the whole point: a pairing
                // or an approval may put a fresh one in the store while this loop is already
                // running. A loop that keeps the token it started with hammers the server with
                // a dead one — ten hours of refusals is exactly what that costs.
                val token = TokenStore(this).token
                if (token == null) {
                    // no token is not the end: a node that knows its address and its uuid
                    // knocks on the door the way the desktop client does — it asks to be
                    // confirmed, and a person approves the request in the admin panel
                    if (!enroll(base)) break
                    continue
                }
                val transfer = TransferApi(base, token)
                // pipes stream a command's output into the request body and back: the same
                // token, the same allowed directories, the same registry of live processes
                val pipe = PipeApi(base, token, TokenStore(this).roots, shell.running)
                // every announce is a new connection generation: the counter grows, otherwise
                // the server takes this instance for the one it has just dropped
                clientEpoch = TokenStore(this).nextClientEpoch()
                // `epoch: null` is not a gap but a statement: "everything you issued to me is
                // dead". That is what closes the commands of the previous life on the server,
                // and it is why the reset never repairs anything locally
                val announceAt = System.currentTimeMillis()
                log("announce → client_epoch=$clientEpoch, epoch=$epoch")
                val answer = api.announce(
                    token = token,
                    apis = apis,
                    uptimeSeconds = uptimeSeconds(),
                    workdir = TokenStore(this).roots.firstOrNull(),
                    applyRules = SessionApi.rules(),
                    epoch = epoch,
                    clientEpoch = clientEpoch,
                )
                // the answer names the current generation, and we asked with null: an
                // adoption, not a change
                adoptEpoch(serverEpoch(answer))
                log("announce ok за ${System.currentTimeMillis() - announceAt}мс, epoch=$epoch")
                runCatching { TokenStore(this@NodeService).connected = true }
                // the announce only proves the server answers over HTTP: the channel is
                // still closed, so the notification keeps naming the attempt, not the result
                refresh()
                // work arrives as a frame: we bring the channel up and wait
                val channel = Socket(
                    base = base,
                    token = token,
                    // read at the moment of sending: a reset may happen while the socket lives
                    epoch = { epoch },
                    clientEpoch = { clientEpoch },
                    maxCommandno = { maxCommandno },
                    onState = { from, seen, named ->
                        onStateFrame(from, seen, named, api, token, files, session, transfer, notifications, device, shell, pipe)
                    },
                    onOpen = { from ->
                        if (!isCurrent(from)) {
                            // the handshake of a channel we have already replaced finishes
                            // late; marking that one open would show a live link that is not
                            log("чужой канал открылся: client_epoch=$clientEpoch")
                            return@Socket
                        }
                        runCatching { TokenStore(this@NodeService).addEvent("open") }
                        // the channel is up: from now on every attempt is a reconnect
                        channelOpen = true
                        everOpen = true
                        log("ws открыт: client_epoch=$clientEpoch, epoch=$epoch")
                        refresh()
                        runCatching { TokenStore(this@NodeService).connected = true }
                    },
                    onClose = { from, code, reason -> onChannelClosed(isCurrent(from), code, reason) },
                    onPong = { from, rtt ->
                        if (!isCurrent(from)) return@Socket
                        runCatching {
                            TokenStore(this@NodeService).addEvent("pong", if (rtt > 0) "${rtt}мс" else "")
                        }
                        runCatching {
                            val state = TokenStore(this@NodeService)
                            state.lastPong = System.currentTimeMillis()
                            if (rtt > 0) state.lastRtt = rtt
                        }
                    },
                )
                // we close the previous connection if it is still there: two live
                // sockets give "the pings go but the server considers us offline"
                runCatching { channelRef?.takeIf { it !== channel }?.close() }
                channelRef = channel
                channelOpen = false
                runCatching { TokenStore(this@NodeService).addEvent("соединение", "№$clientEpoch") }
                channel.connect()
                // we wait for the opening: otherwise the wait loop exits right away
                delay(500)
                if (!channelOpen) delay(RECONNECT_MS)
                startPing(channel)
                // we wait while the channel is alive; it closed — we go for another round
                // and reconnect, otherwise the node falls silent after a server restart
                waitForWork(api, token, files, session, transfer, notifications, device, shell, pipe)
            } catch (error: ApiException) {
                // the server said the token does not match any node: it was revoked in the admin
                // panel, and no amount of retrying brings it back. The token goes, and the next
                // round asks to be confirmed instead of knocking with a dead password.
                if (AuthFailure.isRevoked(error.status, error.code)) {
                    revokeToken()
                    continue
                }
                // the generation or the connection counter is out of date: the server refuses
                // instead of repairing, and starting over is our side of the deal
                if (Generation.onRefusal(error.status, error.code) == Action.RESET) {
                    resetGeneration(error.code ?: "HTTP ${error.status}")
                    continue
                }
                runCatching { TokenStore(this@NodeService).addEvent("error", error.message) }
                runCatching { TokenStore(this@NodeService).connected = false }
                log("ошибка api ${error.status}/${error.code}: ${error.message}")
                notifyError(error.message)
                delay(RECONNECT_MS)
            } catch (error: Throwable) {
                runCatching { TokenStore(this@NodeService).addEvent("error", error.message ?: error.javaClass.simpleName) }
                runCatching { TokenStore(this@NodeService).connected = false }
                log("ошибка ${error.javaClass.simpleName}: ${error.message}")
                notifyError(error.message)
                delay(RECONNECT_MS)
            }
        }
        // the loop is over only because another instance of this node is newer: it holds the
        // channel from now on, and fighting for it would push it out in a circle
        stopSelf()
    }

    /**
     * A `state` frame arrived: "I am waiting" from the server's side.
     *
     * The frame carries the generation too: we adopt it when we know none, and a different
     * one means the server has started a new life. The number goes in the same frame: "my
     * number is higher than yours, go fetch". Accepting it here is what keeps the claim from
     * being false — a lower one would be answered with one more frame each time.
     *
     * A channel we have already replaced keeps talking for a moment, and its frames are
     * history rather than news: acting on them resets the generation of the channel that is
     * actually alive.
     */
    private fun onStateFrame(
        from: Socket,
        seen: Long?,
        named: Long?,
        api: NodeApi,
        token: String,
        files: FilesApi,
        session: SessionApi,
        transfer: TransferApi,
        notifications: NotificationsApi,
        device: DeviceApi,
        shell: ShellApi,
        pipe: PipeApi,
    ) {
        if (!isCurrent(from)) {
            log("кадр от чужого канала: state $seen/$named")
            return
        }
        synchronized(this) { maxCommandno = Commandno.adopt(maxCommandno, named) }
        if (!onServerState(seen)) return
        scope.launch {
            runCatching { harvest(api, token, files, session, transfer, notifications, device, shell, pipe) }
            channelRef?.sendState()
        }
    }

    /**
     * The generation the server named in a `state` frame.
     *
     * A frame carrying a foreign generation means the server lives in another life, and that
     * is the reset — the same door as `4409` on the socket, not a repair.
     */
    private fun onServerState(seen: Long?): Boolean = when (Generation.onServerEpoch(epoch, seen)) {
        Action.ADOPT -> {
            adoptEpoch(seen)
            true
        }
        Action.RESET -> {
            resetGeneration("epoch-mismatch")
            false
        }
        else -> true
    }

    /**
     * The channel closed: the code and the reason are the server's words, and they decide.
     *
     * `4409` and `4001 client-epoch` end this connection's life; `4001 replaced` ends the
     * service, because the newer instance of the same node owns it now.
     */
    private fun onChannelClosed (isCurrent: Boolean, code: Int, reason: String) {
        val text = if (reason.isBlank()) "код $code" else "код $code $reason"
        // this is the channel we closed ourselves when we made the next one; the server may
        // even name it `replaced`. It must not touch the `replaced` flag, the notification or
        // the wait loop: that flag is what used to keep the node down for hours
        if (ChannelClose.decide(isCurrent, code, reason) == Action.IGNORE) {
            log("чужой канал закрылся: $text")
            return
        }
        channelOpen = false
        // the channel is gone: the wording depends on whether there was a connection to
        // restore — a start that never got through keeps saying "connecting"
        refresh()
        when (Generation.onClose(code, reason)) {
            Action.STOP -> {
                runCatching { TokenStore(this).addEvent("replaced", text) }
                // only a channel we still hold reaches this line: the stale ones are filtered
                // above, and that filter is what keeps our own replacement from switching the
                // node off for hours
                log("вытеснен другим экземпляром: $text — узел спит до ручного включения")
                // persist it before stopping: stopping alone means nothing, the ten-minute
                // alarm revives the process and the two instances ping-pong forever
                runCatching { TokenStore(this).replaced = true }
                stopRequested = true
            }
            Action.RESET -> resetGeneration(text)
            else -> {}
        }
        runCatching {
            val state = TokenStore(this@NodeService)
            val silence = if (state.lastPong > 0) System.currentTimeMillis() - state.lastPong else -1
            state.addEvent("close", if (silence >= 0) "$text, тишина ${silence / 1000}с" else "$text, тишина неизвестна")
        }
        channelOpen = false
        runCatching { TokenStore(this@NodeService).connected = false }
        val state = runCatching { TokenStore(this@NodeService).lastPong }.getOrDefault(0L)
        val silence = if (state > 0) (System.currentTimeMillis() - state) / 1000 else -1
        log("ws закрыт: $text, тишина ${silence}с, сеть ${NodeLog.network(this)}")
        runCatching { closedSignal.trySend(Unit) }
    }

    /**
     * Forget the generation and start over from the announce.
     *
     * This is the whole recovery story: the epoch goes back to `null`, the socket is dropped
     * and the announce is repeated with `epoch: null` — that single statement closes whatever
     * was issued to the previous generation, and the announce bumps the connection counter by
     * itself. There is no cursor to repair.
     */
    private fun resetGeneration(code: String) {
        runCatching { TokenStore(this).addEvent("поколение", code) }
        log("поколение сброшено: $code")
        epoch = null
        TokenStore(this).epoch = null
        runCatching { channelRef?.close() }
        channelRef = null
        pingJob?.cancel()
        channelOpen = false
        // the generation is gone, so there is a connection to restore rather than a first try
        refresh()
        runCatching { TokenStore(this@NodeService).connected = false }
        runCatching { closedSignal.trySend(Unit) }
    }

    /**
     * Remember the generation the server named, when we know none.
     *
     * A change of a known generation is the reset, not an adoption — that is decided by
     * `Generation.onServerEpoch`. Persisted, because after a client restart the announce has
     * to name the generation it still belongs to.
     */
    private fun adoptEpoch(value: Long?) {
        if (value == null || value < 0 || epoch != null) return
        epoch = value
        TokenStore(this).epoch = value
    }

    /**
     * The token is dead: the server no longer knows it.
     *
     * It is dropped from the store rather than kept "just in case". The store is where both the
     * next round and the main screen look, and that is the whole recovery: the round sees no
     * token and enrols, the screen sees no token and shows the scanner again. A dead token left
     * in place is what kept a phone knocking with it every five seconds for ten hours.
     *
     * The generation goes with it: after a revocation the generation this node knew belongs to
     * a life that is over, and the announce that follows must say so.
     */
    private fun revokeToken () {
        val store = TokenStore(this)
        store.token = null
        store.epoch = null
        store.connected = false
        epoch = null
        runCatching { store.addEvent("токен", "отозван сервером") }
        log("токен отозван: 401 unauthorized — заявляюсь заново")
        refresh()
    }

    /**
     * Ask the server to let this node in again — the same door the desktop client knocks on.
     *
     * The request shows up in the admin panel among the nodes waiting for confirmation, and a
     * person approves it there. Until that happens the node waits, asking less and less often:
     * five seconds for the first minute, then fifteen, then once a minute. The request is
     * re-posted now and then because the panel stops showing requests that nobody refreshes.
     *
     * @return true when a token was obtained and the announce may be tried again
     */
    private suspend fun enroll (base: String): Boolean {
        val store = TokenStore(this)
        val uuid = store.deviceId
        if (uuid == null) {
            log("enroll: uuid нет — заявляться нечем, нужна ссылка из админки")
            return false
        }
        val name = store.name ?: Build.MODEL ?: "android"
        val nonce = store.clientNonce()
        val api = NodeApi(base)
        store.enrollPending = true
        runCatching { store.addEvent("enroll", "заявка подана") }
        refresh()
        var attempt = 0
        while (!stopRequested) {
            attempt += 1
            try {
                if (attempt == 1 || attempt % 30 == 0) {
                    val asked = api.enroll(uuid, name, nonce)
                    log("enroll → ${asked.optString("status")}")
                    if (asked.optString("status") == "rejected") return enrollRejected(store)
                }
                val answer = api.enrollStatus(uuid, nonce)
                val token = answer.optString("token")
                if (token.isNotBlank()) {
                    store.token = token
                    store.epoch = null
                    store.enrollPending = false
                    store.connected = false
                    epoch = null
                    val alias = answer.optString("alias")
                    runCatching { store.addEvent("enroll", "подтверждено: узел ${answer.optInt("node")} $alias") }
                    log("enroll: подтверждено, узел ${answer.optInt("node")}, alias=$alias")
                    refresh()
                    return true
                }
                if (answer.optString("status") == "rejected") return enrollRejected(store)
                // pending: nobody has looked at the request yet, and the notification says
                // whose turn it is instead of pretending the node is connecting
                runCatching { refresh() }
            } catch (error: ApiException) {
                // the request is gone (expired, cleared) or its token was already handed out
                // once: the next round posts it again
                if (error.status == 404 || error.status == 409) attempt = 0
                log("enroll ошибка ${error.status}/${error.code}: ${error.message}")
            } catch (error: Throwable) {
                log("enroll ошибка ${error.javaClass.simpleName}: ${error.message}")
            }
            delay(waitForApproval(attempt))
        }
        return false
    }

    private fun enrollRejected (store: TokenStore): Boolean {
        store.enrollPending = false
        runCatching { store.addEvent("enroll", "отклонено") }
        log("enroll: заявку отклонили в админке")
        notifyError(getString(R.string.status_enroll_rejected))
        return false
    }

    /** How long to wait between enrolment polls: 5 s, then 15 s, then a minute. */
    private fun waitForApproval (attempt: Int): Long = when {
        attempt < 12 -> 5_000L
        attempt < 60 -> 15_000L
        else -> 60_000L
    }


    /**
     * Ping on a schedule: it lives separately from the commands and only watches the channel.
     *
     * Neither delivery nor the generation depends on it — `state` frames do that — so a
     * silent channel is merely a dead socket to notice and replace.
     */
    private fun startPing(socket: Socket) {
        pingJob?.cancel()
        val store = TokenStore(this)
        val interval = store.pingInterval
        if (interval <= 0) return
        pingJob = scope.launch {
            // We ping every `interval` seconds. The stretch in the client grows
            // gradually (1×, 2×, 3×…), not "ten times at once": otherwise a node that
            // stays silent for a reason looks dead to the server.
            var seq = 0
            var stretch = 1
            var current = interval
            var answered = System.currentTimeMillis()
            val state = TokenStore(this@NodeService)
            while (true) {
                delay(current * 1000L)
                val silence = System.currentTimeMillis() - answered
                // "the channel is dead" is decided by PingPolicy — the same code that is covered by tests
                if (ru.uvw.dshnc.logic.PingPolicy.isSilent(silence, interval, stretch)) {
                    channelOpen = false
                    socket.close()
                    return@launch
                }
                if (state.lastPong > answered) answered = state.lastPong
                socket.ping(++seq)
                // into the log: the ping number and the pause before it — it is from that
                // pause that we will work out at which idle time they cut us off from outside
                runCatching {
                    TokenStore(this@NodeService).addEvent("ping", "#$seq пауза ${current}с")
                }
                val next = ru.uvw.dshnc.logic.PingPolicy.next(interval, store.pingFactor, stretch, false)
                current = next.first
                stretch = next.second
            }
        }
    }

    private suspend fun waitForWork(api: NodeApi, token: String, files: FilesApi, session: SessionApi, transfer: TransferApi, notifications: NotificationsApi, device: DeviceApi, shell: ShellApi, pipe: PipeApi) {
        // Only by the server's frame: there is no polling at all. Silence in the channel
        // is normal, while closing the channel wakes the loop instantly so that we reconnect.
        while (!stopRequested && storeConnected()) {
            kotlinx.coroutines.withTimeoutOrNull(60_000L) { closedSignal.receive() }
            if (stopRequested || !storeConnected()) return
            harvest(api, token, files, session, transfer, notifications, device, shell, pipe)
        }
    }

    private suspend fun harvest(api: NodeApi, token: String, files: FilesApi, session: SessionApi, transfer: TransferApi, notifications: NotificationsApi, device: DeviceApi, shell: ShellApi, pipe: PipeApi) {
        // the generation and the counter travel with the request: a mismatch is the server's
        // refusal (`409`), and that is the reset — not a break to reconnect through
        val answer = try {
            api.commands(token, COMMANDS_LIMIT, epoch, clientEpoch)
        } catch (error: ApiException) {
            if (Generation.onRefusal(error.status, error.code) == Action.RESET) {
                resetGeneration(error.code ?: "HTTP ${error.status}")
                return
            }
            throw error
        }
        // the liveness mark: the screen will show that the service really works
        runCatching { TokenStore(this@NodeService).heartbeat = System.currentTimeMillis() }
        // the answer may name the generation too: a foreign one means the server has restarted
        // and everything it had issued to this node died with the previous generation
        when (Generation.onServerEpoch(epoch, serverEpoch(answer))) {
            Action.ADOPT -> adoptEpoch(serverEpoch(answer))
            Action.RESET -> {
                resetGeneration("epoch-mismatch")
                return
            }
            else -> {}
        }
        val batch = answer.optJSONArray("commands") ?: JSONArray()
        for (index in 0 until batch.length()) {
            val command = batch.getJSONObject(index)
            val seq = command.optLong("seq")
            // the command's own pause set by the server, in seconds: arguments win over
            // it, but without it a method falls back to its default and the server
            // waits for an answer it has already given up on
            val commandSeconds = if (command.has("timeout")) command.optDouble("timeout", 0.0) else 0.0
            // we have not declared a single API yet, so we honestly answer that the
            // method is unknown: silence would look like a hang
            val method = command.optString("method")
            val args = command.optJSONObject("args") ?: JSONObject()
            // we tell the session.* and files.* methods apart by the prefix and by the
            // known names: the server sends the method name, not "api.method".
            // Service commands go first: they come with `api = -1` and a name starting
            // with `#`, are handled by the client itself and must not fall through to
            // `files`
            val outcome = when {
                // the dialog answers for a cancel that has its number; anything else is the
                // shell registry's business
                method.startsWith("#") -> dialog.control(method, args) ?: shell.control(method, args)
                method in SESSION_METHODS -> session.handle(method, args, command.optString("session").ifBlank { null })
                method == "ask" -> dialog.handle(method, args, seq, commandSeconds) { answer ->
                    submitResult(api, token, seq, answer)
                }
                method == "post" && args.has("id") -> notifications.handle(method, args)
                method.startsWith("alarm_") || method == "apps" || method == "camera_snap" || method == "photos_latest" || method.startsWith("device_") -> device.handle(method, args, commandSeconds)
                method == "exec" -> shell.handle(method, args, seq, commandSeconds)
                method == "copyToDevice" -> transfer.download(seq, args, commandSeconds)
                method == "copyFromDevice" -> transfer.upload(seq, args, commandSeconds)
                method == "pipeFromDevice" -> pipe.from(seq, PipeApi.raw(args), commandSeconds).toJson()
                method == "pipeToDevice" -> pipe.to(seq, PipeApi.raw(args), commandSeconds).toJson()
                else -> files.handle(method, args)
            }
            // a dialog is answered by the person, not by this loop: the answer arrives from
            // the thread that waits for it, through the same `submitResult` — so the loop
            // stays free and the server's cancel can still reach us
            if (outcome.optString("status") == DialogApi.PENDING) continue
            submitResult(api, token, seq, outcome)
        }
        // Numbers spent before this process started are never handed out again, and a spent
        // number stays spent. So the answer's number is accepted, not recomputed from the
        // batch: otherwise the claim stays below the server's and every `state` frame comes
        // back with another one, at three frames per second.
        synchronized(this) { maxCommandno = Commandno.adopt(maxCommandno, commandno(answer)) }
    }

    /**
     * Reply to one command.
     *
     * One place for both paths: the batch loop and the thread that waits for a person's
     * answer. `ok` is a mandatory field — the server decides from it whether this is a
     * success or a rejection, and without it any command looked like "internal error".
     */
    private fun submitResult (api: NodeApi, token: String, seq: Long, outcome: JSONObject) {
        val payload = if (outcome.optString("status") == "ok") {
            JSONObject()
                .put("ok", true)
                .put("status", "ok")
                .put("results", JSONArray().put(JSONObject()
                    .put("type", "text")
                    .put("text", outcome.optJSONObject("payload").toString())))
        } else {
            JSONObject()
                .put("ok", false)
                .put("status", "error")
                .put("code", outcome.optString("code"))
                .put("message", outcome.optString("message"))
        }
        // our own clock in the envelope: the server's `timing` rule computes
        // durationMs and clockSkewMs from it — without `now` its `nodeNow` is null
        // and the rule is half-blind
        payload.put("now", System.currentTimeMillis())
        api.result(token, seq, payload)
        executed.incrementAndGet()
        // our own work: the number grows by what this process really executed. A number the
        // server spent on a command we never received is adopted only from an empty batch,
        // where there is nothing left to fetch — see `Commandno`
        synchronized(this) { maxCommandno = maxOf(maxCommandno, seq) }
    }

    /** The generation of the server process that answered — if it named one. */
    private fun serverEpoch(answer: JSONObject): Long? =
        if (answer.has("epoch") && !answer.isNull("epoch")) answer.optLong("epoch") else null

    /** `max_commandno` — the last number the server has spent for this node — if it named one. */
    private fun commandno(answer: JSONObject): Long? =
        if (answer.has("max_commandno") && !answer.isNull("max_commandno")) answer.optLong("max_commandno") else null

    /**
     * How long the node has been running, not how long the phone has been up.
     *
     * `elapsedRealtime()` counts from the device boot, so the node on a fresh phone
     * reported "21 days". We remember the service start time ourselves.
     */
    /** The channel counts as alive until the service sees the close. */
    @Volatile private var channelOpen = false

    private fun storeConnected(): Boolean = channelOpen

    fun uptimeSeconds(): Long {
        val started = startedAt
        if (started == 0L) return 0
        return ((android.os.SystemClock.elapsedRealtime() - started) / 1000).toLong()
    }

    /** Is this the channel the loop holds right now, or one left over from a previous round? */
    private fun isCurrent(socket: Socket): Boolean = socket === channelRef

    /** One line in the on-disk log: the process may be gone before anyone asks it anything. */
    private fun log(text: String) {
        NodeLog.write(this, text)
    }

    /** The state word the notification carries: it follows the channel, nothing else. */
    private fun stateText(): String = when {
        // waiting for a person to confirm the request: the words must name whose turn it is,
        // otherwise "connecting" runs for hours and looks like a broken network
        runCatching { TokenStore(this).enrollPending }.getOrDefault(false) ->
            getString(R.string.status_awaiting_approval)
        else -> when (ChannelState.of(channelOpen, everOpen)) {
            Link.CONNECTED -> getString(R.string.status_connected)
            Link.RESTORING -> getString(R.string.status_reconnecting)
            Link.CONNECTING -> getString(R.string.status_connecting)
        }
    }

    /** Show the state that is true right now. */
    private fun refresh() {
        runCatching { notify(stateText()) }
    }

    /**
     * Show a failure: without a reason the label keeps saying "restoring" for hours and
     * nobody can tell a dead network from a revoked token.
     */
    private fun notifyError(message: String?) {
        val reason = message?.trim().orEmpty()
        val text = if (reason.isEmpty()) getString(R.string.status_disconnected)
        else getString(R.string.status_disconnected) + ": " + reason
        runCatching { notify(text) }
    }

    private fun notify(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    /**
     * The row in the shade: the node name, the state word, the way out and a tap that opens
     * the app. Internal instead of private so that a Robolectric test can look at what a
     * person actually sees — the tap used to do nothing at all.
     */
    internal fun notification(text: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        // the title is the node name, the state underneath it; next to it the turn-off
        // button, so that one does not open the app just to "turn off"
        val name = TokenStore(this).name ?: getString(R.string.app_name)
        // a tap on the row brings the app's own window: the settings live there, and a
        // notification that does nothing when touched is only a decoration
        val open = android.app.PendingIntent.getActivity(
            this,
            0,
            android.content.Intent(this, ru.uvw.dshnc.MainActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = android.app.PendingIntent.getBroadcast(
            this,
            1,
            android.content.Intent(this, StopReceiver::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(name)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(
                android.app.Notification.Action.Builder(
                    null,
                    getString(R.string.notif_stop),
                    stop,
                ).build(),
            )
            .build()
    }

    /**
     * The system took the task away from the recent list.
     *
     * The service normally outlives it, so a record here is what tells "the person swiped
     * the app away" from "Android killed the process on its own".
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        log("onTaskRemoved: приложение убрано из списка, сеть ${NodeLog.network(this)}")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        log("service onDestroy: channelOpen=$channelOpen, client_epoch=$clientEpoch")
        instance = null
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        wakeLock = null
        wifiLock = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** How many commands were executed: shown on the screen. */
        val executed = java.util.concurrent.atomic.AtomicLong()

        private const val CHANNEL = "node"
        private const val NOTIFICATION_ID = 1
        /** The methods served by `session`. */
        private val SESSION_METHODS = setOf("debug_info", "ping", "cwd")

        /** How many commands one request may take: the server caps it on its side anyway. */
        private const val COMMANDS_LIMIT = 5
        private const val RECONNECT_MS = 5000L

        /** How long the node has been running — needed by the `session.ping` method. */
        fun uptime(): Long = instance?.uptimeSeconds() ?: 0L

        /** The generation this process knows, or null — shown by `session.debug_info`. */
        fun epoch(): Long? = instance?.epoch

        /** The connection counter of the last announce — shown by `session.debug_info`. */
        fun clientEpoch(): Long? = instance?.clientEpoch

        /** The highest command number this process executed — shown by `session.debug_info`. */
        fun maxCommandno(): Long = instance?.maxCommandno ?: 0L

        @Volatile private var instance: NodeService? = null

        fun start(context: Context) {
            // since Android 12 a foreground service may not be started from the background
            // without an exemption, and the thrown exception used to disappear into nowhere:
            // the node then simply never came back, and nothing said why
            runCatching { context.startForegroundService(Intent(context, NodeService::class.java)) }
                .onFailure { error ->
                    NodeLog.write(
                        context,
                        "start из фона отказан: ${error.javaClass.simpleName}: ${error.message}, сеть ${NodeLog.network(context)}",
                    )
                }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NodeService::class.java))
        }

        /**
         * Take the service down and bring it up again.
         *
         * Needed when the loop is alive but stuck: stopping is not enough, the loop inside holds
         * whatever it was busy with, and the start would answer "the channel is already held".
         * The pause before the start is the time the system needs to destroy the old instance —
         * starting at once lands on a service that is still on its way out.
         */
        fun restart(context: Context) {
            stop(context)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                start(context)
            }, 700)
        }
    }
}
