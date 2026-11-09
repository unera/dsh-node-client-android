package ru.uvw.dshnc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import ru.uvw.dshnc.api.NodeApi
import ru.uvw.dshnc.service.Heartbeat
import ru.uvw.dshnc.service.NodeService
import ru.uvw.dshnc.store.TokenStore
import java.util.UUID

/**
 * Главный экран узла.
 *
 * Сверху панель с меню, под ней один большой переключатель: включён — узел
 * работает и пингует, выключен — сетевой активности нет вовсе, приложение
 * только позволяет зайти в настройки и подготовить подключение.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var store: TokenStore
    private lateinit var status: TextView
    private lateinit var counters: TextView
    private lateinit var toggle: TextView
    private lateinit var details: TextView
    private lateinit var linkRow: LinearLayout
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())

    /** Результат сканирования: содержимое QR — та же ссылка из админки. */
    private val scanner = registerForActivityResult(
        com.journeyapps.barcodescanner.ScanContract(),
    ) { result ->
        val text = result.contents
        if (!text.isNullOrBlank()) pair(text.trim()) else status.text = getString(R.string.menu_scan_cancelled)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TokenStore(this)
        Thread.setDefaultUncaughtExceptionHandler { _, error ->
            CrashLog.write(this, error)
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#141414"))
        }

        // панель: название и вход в меню
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.title)
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = "⋮"
                textSize = 26f
                setPadding(pad, 0, 0, 0)
                setOnClickListener {
                    try {
                        startActivity(Intent(this@MainActivity, MenuActivity::class.java))
                    } catch (error: Throwable) {
                        status.text = "меню: ${error.message}"
                    }
                }
            })
        })

        counters = TextView(this).apply {
            textSize = 32f
            gravity = Gravity.CENTER
            setPadding(0, pad * 2, 0, pad)
        }
        root.addView(counters)

        toggle = TextView(this).apply {
            isClickable = true
            isFocusable = true
            textSize = 44f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(pad * 6, pad * 6).apply { gravity = Gravity.CENTER_HORIZONTAL }
            setOnClickListener {
                    try {
                        applyEnabled(!store.enabled)
                    } catch (error: Throwable) {
                        status.text = "переключатель: ${error.message}"
                    }
                }
        }
        root.addView(toggle)

        status = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad)
        }
        root.addView(status)

        // что происходило: команды, пинг, задержка, версия
        details = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            alpha = 0.7f
        }
        root.addView(details)

        // пока не подключено — только сканирование: ввод ссылки живёт в меню,
        // чтобы главный экран оставался двумя кнопками и счётчиком
        linkRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (store.token == null) View.VISIBLE else View.GONE
        }
        linkRow.addView(Ui.button(this, getString(R.string.menu_scan_qr)) { startScanner() })
        root.addView(linkRow)

        setContentView(root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Heartbeat.schedule(this)
        if (store.enabled && store.token != null) NodeService.start(this)
        startTicker()
    }

    /** Включение и выключение узла: выключено — сетевой активности нет. */
    private fun applyEnabled (on: Boolean) {
        store.enabled = on
        if (on) {
            if (store.token != null) NodeService.start(this) else linkRow.visibility = View.VISIBLE
        } else {
            NodeService.stop(this)
        }
        render()
    }

    private fun renderDetails () {
        val state = TokenStore(this)
        val now = System.currentTimeMillis()
        fun ago (at: Long): String = if (at <= 0L) "—" else "${(now - at) / 1000} с назад"
        details.text = listOf(
            "последняя команда: ${ago(state.heartbeat)}",
            "последний пинг: ${ago(state.lastPong)}" + (if (state.lastRtt > 0) " (${state.lastRtt} мс)" else ""),
            "версия: ${BuildConfig.VERSION_NAME}",
        ).joinToString("\n")
    }

    private fun render () {
        val on = store.enabled
        toggle.text = if (on) "❚❚" else "▶"
        toggle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) Color.parseColor("#22c55e") else Color.parseColor("#4b5563"))
        }
        status.text = when {
            !on -> getString(R.string.status_off)
            store.connected -> getString(R.string.status_connected)
            else -> getString(R.string.status_connecting)
        }
        counters.text = formatUptime(NodeService.uptime())
        renderDetails()
    }

    private fun formatUptime (seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun startTicker () {
        ticker.postDelayed(object : Runnable {
            override fun run() {
                val state = TokenStore(this@MainActivity)
                status.text = when {
                    !state.enabled -> getString(R.string.status_off)
                    state.connected -> getString(R.string.status_connected)
                    else -> getString(R.string.status_connecting)
                }
                counters.text = formatUptime(NodeService.uptime())
                renderDetails()
                ticker.postDelayed(this, 1000)
            }
        }, 1000)
    }

    /** Камера: разрешение спрашиваем в момент нажатия. */
    private fun startScanner () {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 7)
            return
        }
        val options = com.journeyapps.barcodescanner.ScanOptions()
            .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
            .setPrompt(getString(R.string.menu_scan_hint))
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        scanner.launch(options)
    }

    override fun onRequestPermissionsResult (requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startScanner()
            else status.text = getString(R.string.menu_scan_no_camera)
        }
    }

    /** Подключение по ссылке из админки: обмен кода на токен. */
    private fun pair (raw: String) {
        val parsed = ru.uvw.dshnc.logic.LinkParser.parse(raw)
        if (parsed == null) {
            status.text = getString(R.string.status_bad_link)
            return
        }
        val base = parsed.base
        val code = parsed.code
        status.text = getString(R.string.status_connecting)
        Thread {
            try {
                val answer = NodeApi(base).pair(code, deviceId(), deviceName())
                val token = answer.optString("token")
                if (token.isBlank()) throw IllegalStateException(getString(R.string.status_no_token))
                store.base = base
                store.token = token
                store.name = deviceName()
                store.enabled = true
                runOnUiThread {
                    linkRow.visibility = View.GONE
                    NodeService.start(this)
                    render()
                }
            } catch (error: Throwable) {
                runOnUiThread { status.text = error.message ?: getString(R.string.status_disconnected) }
            }
        }.start()
    }

    private fun deviceId (): String {
        store.deviceId?.let { return it }
        return UUID.randomUUID().toString().also { store.deviceId = it }
    }

    private fun deviceName (): String = Build.MODEL ?: "android"

    override fun onResume() {
        super.onResume()
        linkRow.visibility = if (store.token == null) View.VISIBLE else View.GONE
        // подключились через меню — служба должна подняться без ручного тумблера
        if (store.enabled && store.token != null) NodeService.start(this)
        render()
    }

    override fun onPause() {
        super.onPause()
        store.addEvent("app-фон")
    }

    override fun onDestroy() {
        ticker.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
