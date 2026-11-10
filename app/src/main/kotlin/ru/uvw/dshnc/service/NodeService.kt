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
import ru.uvw.dshnc.api.Roots
import ru.uvw.dshnc.api.SessionApi
import ru.uvw.dshnc.api.ShellApi
import ru.uvw.dshnc.api.TransferApi
import ru.uvw.dshnc.api.Socket
import ru.uvw.dshnc.logic.Action
import ru.uvw.dshnc.logic.Generation
import ru.uvw.dshnc.logic.Revival
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        // the service is already running and holds the channel — a repeated start means nothing
        if (loop?.isActive == true) {
            runCatching { store.addEvent("start-повтор", "канал уже держится") }
            // the label must not lie: the second launch does not open the channel, but
            // the notification has to show the real state of the first one
            runCatching {
                notify(getString(if (channelOpen) R.string.status_connected else R.string.status_connecting))
            }
            return START_STICKY
        }
        // every service start is a trace: it shows that the process was killed
        store.starts = store.starts + 1
        val base = store.base
        val token = store.token
        // turned off by the person, or displaced by a newer instance of the same node: no
        // network either way. The displaced case is decided by the persisted flag, so nothing
        // else resurrects this instance — not the sticky restart, not an explicit start, not
        // the ten-minute alarm; otherwise the two instances ping-pong, each reviving the one
        // the other displaced. Only a person turning the node on clears it.
        if (!Revival.allowed(store.enabled, token != null, store.replaced)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (base == null || token == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (startedAt == 0L) startedAt = android.os.SystemClock.elapsedRealtime()
        loop?.cancel()
        loop = scope.launch {
            try {
                run(base, token)
            } catch (error: Throwable) {
                // a background service must not fail silently: we write the reason into a
                // file that can later be fetched through files
                CrashLog.write(this@NodeService, error)
                notify(error.message ?: getString(R.string.status_disconnected))
            }
        }
        return START_STICKY
    }

    private suspend fun run(base: String, token: String) {
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
        val transfer = TransferApi(base, token)
        // the generation survives a reconnect but not a reset: a reset nulls it, and the
        // announce that follows names `epoch: null`
        epoch = TokenStore(this).epoch
        while (!stopRequested) {
            try {
                // every announce is a new connection generation: the counter grows, otherwise
                // the server takes this instance for the one it has just dropped
                clientEpoch = TokenStore(this).nextClientEpoch()
                // `epoch: null` is not a gap but a statement: "everything you issued to me is
                // dead". That is what closes the commands of the previous life on the server,
                // and it is why the reset never repairs anything locally
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
                runCatching { TokenStore(this@NodeService).connected = true }
                notify(getString(R.string.status_connected))
                // work arrives as a frame: we bring the channel up and wait
                val channel = Socket(
                    base = base,
                    token = token,
                    // read at the moment of sending: a reset may happen while the socket lives
                    epoch = { epoch },
                    clientEpoch = { clientEpoch },
                    maxCommandno = { maxCommandno },
                    onState = { seen, _ ->
                        // the frame carries the generation too: we adopt it when we know none,
                        // and a different one means the server has started a new life
                        if (onServerState(seen)) {
                            scope.launch {
                                runCatching { harvest(api, token, files, session, transfer, notifications, device, shell) }
                                channelRef?.sendState()
                            }
                        }
                    },
                    onOpen = {
                        runCatching { TokenStore(this@NodeService).addEvent("open") }
                        runCatching { notify(getString(R.string.status_connected)) }
                        channelOpen = true
                        runCatching { TokenStore(this@NodeService).connected = true }
                    },
                    onClose = { code, reason -> onChannelClosed(code, reason) },
                    onPong = { rtt ->
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
                waitForWork(api, token, files, session, transfer, notifications, device, shell)
            } catch (error: ApiException) {
                // the generation or the connection counter is out of date: the server refuses
                // instead of repairing, and starting over is our side of the deal
                if (Generation.onRefusal(error.status, error.code) == Action.RESET) {
                    resetGeneration(error.code ?: "HTTP ${error.status}")
                    continue
                }
                runCatching { TokenStore(this@NodeService).addEvent("error", error.message) }
                runCatching { TokenStore(this@NodeService).connected = false }
                notify(error.message)
                delay(RECONNECT_MS)
            } catch (error: Throwable) {
                runCatching { TokenStore(this@NodeService).addEvent("error", error.message ?: error.javaClass.simpleName) }
                runCatching { TokenStore(this@NodeService).connected = false }
                notify(error.message ?: getString(R.string.status_disconnected))
                delay(RECONNECT_MS)
            }
        }
        // the loop is over only because another instance of this node is newer: it holds the
        // channel from now on, and fighting for it would push it out in a circle
        stopSelf()
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
    private fun onChannelClosed(code: Int, reason: String) {
        val text = if (reason.isBlank()) "код $code" else "код $code $reason"
        runCatching { notify(getString(R.string.status_connecting)) }
        when (Generation.onClose(code, reason)) {
            Action.STOP -> {
                runCatching { TokenStore(this).addEvent("replaced", text) }
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
        epoch = null
        TokenStore(this).epoch = null
        runCatching { channelRef?.close() }
        channelRef = null
        pingJob?.cancel()
        channelOpen = false
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

    private suspend fun waitForWork(api: NodeApi, token: String, files: FilesApi, session: SessionApi, transfer: TransferApi, notifications: NotificationsApi, device: DeviceApi, shell: ShellApi) {
        // Only by the server's frame: there is no polling at all. Silence in the channel
        // is normal, while closing the channel wakes the loop instantly so that we reconnect.
        while (!stopRequested && storeConnected()) {
            kotlinx.coroutines.withTimeoutOrNull(60_000L) { closedSignal.receive() }
            if (stopRequested || !storeConnected()) return
            harvest(api, token, files, session, transfer, notifications, device, shell)
        }
    }

    private suspend fun harvest(api: NodeApi, token: String, files: FilesApi, session: SessionApi, transfer: TransferApi, notifications: NotificationsApi, device: DeviceApi, shell: ShellApi) {
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
                else -> files.handle(method, args)
            }
            // a dialog is answered by the person, not by this loop: the answer arrives from
            // the thread that waits for it, through the same `submitResult` — so the loop
            // stays free and the server's cancel can still reach us
            if (outcome.optString("status") == DialogApi.PENDING) continue
            submitResult(api, token, seq, outcome)
        }
        // the batch is over: from here we wait for a frame instead of spinning the loop
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
        // our own work only: the number grows by what this process really executed, and
        // the server's `max_commandno` is never adopted — that adoption was the deleted
        // cursor
        synchronized(this) { maxCommandno = maxOf(maxCommandno, seq) }
    }

    /** The generation of the server process that answered — if it named one. */
    private fun serverEpoch(answer: JSONObject): Long? =
        if (answer.has("epoch") && !answer.isNull("epoch")) answer.optLong("epoch") else null

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

    private fun notify(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        // the title is the node name, the state underneath it; next to it the turn-off
        // button, so that one does not open the app just to "turn off"
        val name = TokenStore(this).name ?: getString(R.string.app_name)
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
            .addAction(
                android.app.Notification.Action.Builder(
                    null,
                    getString(R.string.notif_stop),
                    stop,
                ).build(),
            )
            .build()
    }

    override fun onDestroy() {
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
            context.startForegroundService(Intent(context, NodeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NodeService::class.java))
        }
    }
}
