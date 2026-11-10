package ru.uvw.dshnc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.net.Uri
import android.provider.Settings
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
 * The node's main screen.
 *
 * On top a panel with the menu, below it one big toggle: on — the node works and pings,
 * off — there is no network activity at all, the app only lets one go into the settings
 * and prepare the connection.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var store: TokenStore
    private lateinit var status: TextView
    private lateinit var counters: TextView
    private lateinit var toggle: TextView
    private lateinit var details: TextView
    private lateinit var linkRow: LinearLayout
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())

    /** The scan result: the contents of the QR code — the same link as from the admin panel. */
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

        // the panel: the title and the way into the menu
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

        // what has been happening: commands, ping, delay, version
        details = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            alpha = 0.7f
        }
        root.addView(details)

        // until connected — scanning only: entering the link lives in the menu,
        // so that the main screen stays two buttons and a counter
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

    /** Turning the node on and off: off means no network activity. */
    private fun applyEnabled (on: Boolean) {
        store.enabled = on
        if (on) {
            // a press on "on" is the one intent that revives a displaced node: the flag is
            // cleared here and nowhere else, because a timer waking the node up is not a
            // person — that is exactly how two instances ended up ping-ponging every ten
            // minutes, each reviving the one the other had displaced
            store.replaced = false
            if (store.token != null) NodeService.start(this) else linkRow.visibility = View.VISIBLE
        } else {
            NodeService.stop(this)
        }
        render()
    }

    /**
     * Whether a question may pop up over the screen.
     *
     * Since Android 14 the system decides, and the person grants it in a separate setting.
     * Without it a question still arrives — as a notification in the shade — but it no
     * longer pops up, and that looked like a lottery: sometimes yes, sometimes no.
     */
    private fun popupAllowed (): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.canUseFullScreenIntent()
    }

    private fun openPopupSettings () {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")),
            )
        }
    }

    private fun renderDetails () {
        val state = TokenStore(this)
        val now = System.currentTimeMillis()
        fun ago (at: Long): String = if (at <= 0L) "—" else "${(now - at) / 1000} с назад"
        val allowed = popupAllowed()
        details.text = listOf(
            "последняя команда: ${ago(state.heartbeat)}",
            "последний пинг: ${ago(state.lastPong)}" + (if (state.lastRtt > 0) " (${state.lastRtt} мс)" else ""),
            "версия: ${BuildConfig.VERSION_NAME}",
            getString(if (allowed) R.string.popup_allowed else R.string.popup_denied),
        ).joinToString("\n")
        // a tap on the denied line opens the exact system screen that grants it
        details.setOnClickListener(if (allowed) null else View.OnClickListener { openPopupSettings() })
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

    /** Camera: the permission is requested at the moment of the press. */
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

    /** Connecting by a link from the admin panel: an exchange of the code for a token. */
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
                // a fresh pairing is a person connecting this node: the old "displaced" mark
                // belongs to a previous life and would otherwise keep the new token silent
                store.replaced = false
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
        // they connected through the menu — the service has to come up without the manual toggle
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
