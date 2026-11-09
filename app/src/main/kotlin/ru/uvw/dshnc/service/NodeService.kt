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
import ru.uvw.dshnc.api.Roots
import ru.uvw.dshnc.api.SessionApi
import ru.uvw.dshnc.api.TransferApi
import ru.uvw.dshnc.api.Socket
import ru.uvw.dshnc.store.TokenStore

/**
 * Узел в фоне.
 *
 * Android убивает обычные процессы, поэтому соединение живёт в foreground
 * service с постоянным уведомлением — без него узла просто нет, когда экран
 * погашен. Пока это опрос: `announce` при старте, затем `commands` раз в секунду.
 * WebSocket добавим, когда понадобится мгновенная доставка; для первого шага
 * опроса достаточно, и он виден в админке как живой узел.
 */
class NodeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var channelRef: Socket? = null
    private var pingJob: Job? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var startedAt = 0L
    /** Последний увиденный номер команды: сервер сверяет по нему. */
    @Volatile private var known: Long = 0L


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // экран гаснет — система норовит усыпить сеть; частичная блокировка
        // держит процесс и радио живыми, иначе пинг «пропадает» на минуты
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
        // С 34 тип службы надо указывать и в вызове: объявления в манифесте мало,
        // без него система бросает MissingForegroundServiceTypeException — это и
        // было причиной «снова произошёл сбой»
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
        // выключено человеком — никакой сети, даже если кто-то пытается поднять
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
                // фоновая служба не должна падать молча: пишем причину в файл,
                // который потом можно забрать через files
                CrashLog.write(this@NodeService, error)
                notify(error.message ?: getString(R.string.status_disconnected))
            }
        }
        return START_STICKY
    }

    private suspend fun run(base: String, token: String) {
        val api = NodeApi(base)
        // что умеем: файлы внутри разрешённых каталогов
        val apis = JSONArray()
            .put(FilesApi.schema(NodeApi.VERSION))
            .put(SessionApi.schema())
        // список каталогов задаётся в настройках и живёт в состоянии узла
        val files = FilesApi(this, TokenStore(this).roots)
        val session = SessionApi(this)
        val transfer = TransferApi(base, token)
        while (true) {
            try {
                api.announce(token, apis, uptimeSeconds())
                runCatching { TokenStore(this@NodeService).connected = true }
                notify(getString(R.string.status_connected))
                // работа приходит кадром: поднимаем канал и ждём
                val channel = Socket(
                    base = base,
                    token = token,
                    onWake = { scope.launch { runCatching { harvest(api, token, files, session, transfer) }; channelRef?.waiting(known) } },
                    onOpen = {
                        channelOpen = true
                        runCatching { TokenStore(this@NodeService).connected = true }
                    },
                    onClose = {
                        channelOpen = false
                        runCatching { TokenStore(this@NodeService).connected = false }
                    },
                    onPong = { rtt ->
                        runCatching {
                            val state = TokenStore(this@NodeService)
                            state.lastPong = System.currentTimeMillis()
                            if (rtt > 0) state.lastRtt = rtt
                        }
                    },
                )
                channelRef = channel
                channelOpen = false
                channel.connect()
                // ждём открытия: иначе цикл ожидания выйдет сразу
                delay(500)
                if (!channelOpen) delay(RECONNECT_MS)
                startPing(channel)
                // ждём, пока канал жив; закрылся — уходим на новый круг и
                // переподключаемся, иначе после рестарта сервера узел замолкает
                waitForWork(channel, api, token, files, session, transfer)
            } catch (error: Throwable) {
                runCatching { TokenStore(this@NodeService).connected = false }
                notify(error.message ?: getString(R.string.status_disconnected))
                delay(RECONNECT_MS)
            }
        }
    }

    /**
     * Ждём кадр `state` — сервер сам скажет, что появилась работа.
     *
     * Пока кадров нет, ничего не происходит: пинг отвечает за то, чтобы заметить
     * обрыв, а не за доставку. Раз в `POLL_FALLBACK_MS` на всякий случай
     * проверяем очередь — это запас, если сокет не поднялся.
     */
    /** Пинг по расписанию: живёт отдельно от команд и только следит за каналом. */
    private fun startPing(socket: Socket) {
        pingJob?.cancel()
        val store = TokenStore(this)
        val interval = store.pingInterval
        if (interval <= 0) return
        pingJob = scope.launch {
            // Пингуем каждые `interval` секунд. Растяжение в клиенте растёт
            // постепенно (1×, 2×, 3×…), а не «сразу в 10 раз»: иначе узел,
            // который молчит по делу, выглядит мёртвым для сервера.
            var seq = 0
            var stretch = 1
            var answered = System.currentTimeMillis()
            val state = TokenStore(this@NodeService)
            while (true) {
                delay(interval * 1000L * stretch)
                // ответа нет дольше интервала с учётом растяжения — это разрыв,
                // а не «медленно»: закрываем канал и переподключаемся сразу
                val silence = System.currentTimeMillis() - answered
                if (silence > interval * 1000L * stretch * 2) {
                    channelOpen = false
                    socket.close()
                    return@launch
                }
                if (state.lastPong > answered) answered = state.lastPong
                socket.ping(++seq)
                val factor = store.pingFactor.coerceAtLeast(1)
                if (stretch < factor) stretch += 1
            }
        }
    }

    private suspend fun waitForWork(socket: Socket, api: NodeApi, token: String, files: FilesApi, session: SessionApi, transfer: TransferApi) {
        while (storeConnected()) {
            val fallback = TokenStore(this@NodeService).pollFallback
            if (fallback <= 0) {
                // 0 — только по кадру: тишина в канале это норма, а не простой
                delay(60_000L)
                continue
            }
            delay(fallback * 1000L)
            harvest(api, token, files, session, transfer)
            socket.waiting(known)
        }
    }

    private suspend fun harvest(api: NodeApi, token: String, files: FilesApi, session: SessionApi, transfer: TransferApi) {
        while (true) {
            val answer = api.commands(token)
            // отметка жизни: экран покажет, что служба действительно работает
            runCatching { TokenStore(this@NodeService).heartbeat = System.currentTimeMillis() }
            val batch = answer.optJSONArray("commands") ?: JSONArray()
            for (index in 0 until batch.length()) {
                val command = batch.getJSONObject(index)
                // ни одного API мы пока не объявили, поэтому честно отвечаем,
                // что метод неизвестен: молчание выглядело бы как зависание
                val method = command.optString("method")
                val args = command.optJSONObject("args") ?: JSONObject()
                // методы session.* и files.* различаем по префиксу и по известным
                // именам: сервер присылает имя метода, а не «api.метод»
                val outcome = when {
                    method in SESSION_METHODS -> session.handle(method, args, command.optString("session").ifBlank { null })
                    method == "copyToDevice" -> transfer.download(command.optLong("seq"), args)
                    else -> files.handle(method, args)
                }
                // `ok` — обязательное поле: по нему сервер решает, успех это или
                // отказ. Без него любая команда выглядела как «internal error».
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
                api.result(token, command.optLong("seq"), payload)
                executed.incrementAndGet()
                known = maxOf(known, command.optLong("seq"))
            }
            // пачка кончилась: дальше ждём кадр, а не крутим цикл
            return
        }
    }

    /**
     * Сколько работает узел, а не сколько живёт телефон.
     *
     * `elapsedRealtime()` считает с загрузки устройства, поэтому узел на свежем
     * телефоне рапортовал «21 день». Время старта службы помним сами.
     */
    /** Канал считается живым, пока служба не увидела закрытие. */
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
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
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
        /** Сколько команд исполнено: показываем на экране. */
        val executed = java.util.concurrent.atomic.AtomicLong()

        private const val CHANNEL = "node"
        private const val NOTIFICATION_ID = 1
        /** Методы, которые обслуживает `session`. */
        private val SESSION_METHODS = setOf("debug_info", "ping", "cwd")

        /** Запасная проверка очереди, если кадр не пришёл. */
        private const val POLL_FALLBACK_MS = 30_000L
        private const val RECONNECT_MS = 5000L

        /** Сколько работает узел — нужно методу `session.ping`. */
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
