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
import ru.uvw.dshnc.api.FilesApi
import ru.uvw.dshnc.api.NodeApi
import ru.uvw.dshnc.api.DeviceApi
import ru.uvw.dshnc.api.NotificationsApi
import ru.uvw.dshnc.api.Roots
import ru.uvw.dshnc.api.SessionApi
import ru.uvw.dshnc.api.ShellApi
import ru.uvw.dshnc.api.TransferApi
import ru.uvw.dshnc.api.Socket
import ru.uvw.dshnc.store.TokenStore

/**
 * The node in the background.
 *
 * Android kills ordinary processes, so the connection lives in a foreground service with
 * a permanent notification — without it there simply is no node once the screen is off.
 * So far this was polling: `announce` at startup, then `commands` once a second. We will
 * add the WebSocket when instant delivery is needed; for the first step polling is
 * enough, and it shows up in the admin panel as a live node.
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
    /** The last command number seen: the server cross-checks against it. */
    @Volatile private var known: Long = 0L


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
        // turned off by the person — no network, even if someone tries to bring it up
        if (!store.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        val base = store.base
        val token = store.token
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
            .put(DeviceApi(this).schema())
            .put(ShellApi(TokenStore(this).roots).schema())
        // the list of directories is set in the settings and lives in the node state
        val files = FilesApi(this, TokenStore(this).roots)
        val session = SessionApi(this)
        val notifications = NotificationsApi(this)
        val device = DeviceApi(this)
        val shell = ShellApi(TokenStore(this).roots)
        val transfer = TransferApi(base, token)
        while (true) {
            try {
                api.announce(token, apis, uptimeSeconds(), TokenStore(this).roots.firstOrNull())
                runCatching { TokenStore(this@NodeService).connected = true }
                notify(getString(R.string.status_connected))
                // work arrives as a frame: we bring the channel up and wait
                val channel = Socket(
                    base = base,
                    token = token,
                    onWake = { scope.launch { runCatching { harvest(api, token, files, session, transfer, notifications, device, shell) }; channelRef?.waiting(known) } },
                    onOpen = {
                        runCatching { TokenStore(this@NodeService).addEvent("open") }
                        runCatching { notify(getString(R.string.status_connected)) }
                        channelOpen = true
                        runCatching { TokenStore(this@NodeService).connected = true }
                    },
                    onClose = {
                        runCatching { notify(getString(R.string.status_connecting)) }
                        runCatching { closedSignal.trySend(Unit) }
                        runCatching {
                            val state = TokenStore(this@NodeService)
                            val silence = if (state.lastPong > 0) System.currentTimeMillis() - state.lastPong else -1
                            state.addEvent("close", if (silence >= 0) "тишина ${silence / 1000}с" else "тишина неизвестна")
                        }
                        channelOpen = false
                        runCatching { TokenStore(this@NodeService).connected = false }
                    },
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
                val connNumber = TokenStore(this@NodeService).nextConnection()
                runCatching { TokenStore(this@NodeService).addEvent("соединение", "№$connNumber") }
                channel.connect(connNumber, known)
                // we wait for the opening: otherwise the wait loop exits right away
                delay(500)
                if (!channelOpen) delay(RECONNECT_MS)
                startPing(channel)
                // we wait while the channel is alive; it closed — we go for another round
                // and reconnect, otherwise the node falls silent after a server restart
                waitForWork(channel, api, token, files, session, transfer, notifications, device, shell)
            } catch (error: Throwable) {
                runCatching { TokenStore(this@NodeService).addEvent("error", error.message ?: error.javaClass.simpleName) }
                runCatching { TokenStore(this@NodeService).connected = false }
                notify(error.message ?: getString(R.string.status_disconnected))
                delay(RECONNECT_MS)
            }
        }
    }

    /**
     * We wait for the `state` frame — the server says itself that work has appeared.
     *
     * While there are no frames nothing happens: ping is responsible for noticing a
     * break, not for delivery. Once per `POLL_FALLBACK_MS` we check the queue just in
     * case — that is the fallback if the socket did not come up.
     */
    /** Ping on a schedule: it lives separately from the commands and only watches the channel. */
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

    private suspend fun waitForWork(socket: Socket, api: NodeApi, token: String, files: FilesApi, session: SessionApi, transfer: TransferApi, notifications: NotificationsApi, device: DeviceApi, shell: ShellApi) {
        // Only by the server's frame: there is no polling at all. Silence in the channel
        // is normal, while closing the channel wakes the loop instantly so that we reconnect.
        while (storeConnected()) {
            kotlinx.coroutines.withTimeoutOrNull(60_000L) { closedSignal.receive() }
            harvest(api, token, files, session, transfer, notifications, device, shell)
        }
    }

    private suspend fun harvest(api: NodeApi, token: String, files: FilesApi, session: SessionApi, transfer: TransferApi, notifications: NotificationsApi, device: DeviceApi, shell: ShellApi) {
        while (true) {
            val answer = api.commands(token)
            // the liveness mark: the screen will show that the service really works
            runCatching { TokenStore(this@NodeService).heartbeat = System.currentTimeMillis() }
            val batch = answer.optJSONArray("commands") ?: JSONArray()
            for (index in 0 until batch.length()) {
                val command = batch.getJSONObject(index)
                val seq = command.optLong("seq")
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
                    method.startsWith("#") -> shell.control(method, args)
                    method in SESSION_METHODS -> session.handle(method, args, command.optString("session").ifBlank { null })
                    method == "post" && args.has("id") -> notifications.handle(method, args)
                    method.startsWith("alarm_") || method == "apps" || method == "camera_snap" || method == "photos_latest" || method.startsWith("device_") -> device.handle(method, args)
                    method == "exec" -> shell.handle(method, args, seq)
                    method == "copyToDevice" -> transfer.download(command.optLong("seq"), args)
                    method == "copyFromDevice" -> transfer.upload(command.optLong("seq"), args)
                    else -> files.handle(method, args)
                }
                // `ok` is a mandatory field: the server decides from it whether this is a
                // success or a rejection. Without it any command looked like "internal error".
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
                known = maxOf(known, seq)
            }
            // The queue is exhausted — only now can the server's number be accepted in
            // full. Accepting it earlier is impossible: commands with smaller numbers may
            // still stand in the queue, and "I know up to N" would leave them undelivered.
            if (batch.length() == 0) known = adoptMaxCommandno(known, maxCommandno(answer))
            // the batch is over: from here we wait for a frame instead of spinning the loop
            return
        }
    }

    /** `max_commandno` — the last command number the server has spent — if it sent one. */
    private fun maxCommandno(answer: JSONObject): Long? =
        if (answer.has("max_commandno") && !answer.isNull("max_commandno")) answer.optLong("max_commandno") else null

    /**
     * Accept the number the server named.
     *
     * `known` is not "the maximum of the numbers received" but "up to which number I am
     * aware": the numbers are an honest autoincrement and are never reused, so the
     * server's number also closes the holes — a cancelled command has spent a number and
     * will never come to the node.
     */
    private fun adoptMaxCommandno(current: Long, named: Long?): Long {
        if (named == null) return current
        return maxOf(current, named)
    }

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

        /** A fallback queue check, in case the frame did not come. */
        private const val POLL_FALLBACK_MS = 30_000L
        private const val RECONNECT_MS = 5000L

        /** How long the node has been running — needed by the `session.ping` method. */
        fun uptime(): Long = instance?.uptimeSeconds() ?: 0L

        @Volatile private var instance: NodeService? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, NodeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NodeService::class.java))
        }
    }
}
