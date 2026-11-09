package ru.uvw.dshnc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.uvw.dshnc.store.TokenStore

/**
 * Экран одного раздела меню: заголовок с «назад» и строки этого раздела.
 */
class SectionActivity : AppCompatActivity() {
    /** Результат сканирования приходит сюда: содержимое QR — та же ссылка. */
    /** Выбор папки системным диалогом: путь приходит уже готовым. */
    private val picker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        val path = uri?.let { treeToPath(it) }
        if (path != null) {
            store.roots = (store.roots + path).distinct()
            recreate()
        } else if (uri != null) {
            status.text = getString(R.string.menu_roots_unsupported)
        }
    }

    private val scanner = registerForActivityResult(
        com.journeyapps.barcodescanner.ScanContract(),
    ) { result ->
        val text = result.contents
        if (!text.isNullOrBlank()) connect(text.trim()) else status.text = getString(R.string.menu_scan_cancelled)
    }

    private lateinit var store: TokenStore
    private lateinit var root: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TokenStore(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val section = intent.getStringExtra("section") ?: "auth"
        val title = when (section) {
            "auth" -> getString(R.string.menu_auth)
            "android" -> getString(R.string.menu_android)
            "roots" -> getString(R.string.menu_roots)
            "apps" -> getString(R.string.menu_apps)
            "overlay" -> getString(R.string.menu_overlay)
            else -> getString(R.string.menu_net)
        }
        // тот же экран и тот же стиль, что у меню: третий уровень не должен
        // выглядеть как другое приложение
        root = Ui.screen(this, title) { finish() }

        status = TextView(this).apply {
            textSize = 13f
            alpha = 0.7f
            setPadding(0, pad, 0, 0)
        }

        when (section) {
            "auth" -> buildAuth(pad)
            "android" -> buildAndroid(pad)
            "roots" -> buildRoots()
            "apps" -> buildApps(pad)
            else -> buildNet(pad)
        }
        setContentView(root)
    }

    private fun buildAuth (pad: Int) {
        root.addView(block(getString(R.string.menu_server), store.base ?: getString(R.string.menu_none)))
        if (store.token != null) {
            root.addView(row(getString(R.string.menu_status), getString(R.string.menu_connected)))
            root.addView(Ui.button(this, getString(R.string.menu_forget)) { confirmForget() })
            return
        }
        val link = EditText(this).apply { hint = getString(R.string.link_hint) }
        root.addView(link)
        root.addView(Ui.button(this, getString(R.string.menu_connect_link)) { connect(link.text.toString().trim()) })
        root.addView(Ui.button(this, getString(R.string.menu_scan_qr)) { startScanner() })
        root.addView(status)
    }

    /** Системный выбор папки: путь не набираем руками, его даёт сам Android. */
    private fun pickDirectory () {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        try {
            picker.launch(intent)
        } catch (error: Throwable) {
            status.text = error.message ?: getString(R.string.menu_roots_no_picker)
        }
    }

    /** Дерево SAF превращаем в обычный путь: файловый API работает с путями. */
    private fun treeToPath (uri: android.net.Uri): String? {
        val docId = try {
            android.provider.DocumentsContract.getTreeDocumentId(uri)
        } catch (error: Throwable) {
            null
        } ?: return null
        val parts = docId.split(':', limit = 2)
        val volume = parts[0]
        val tail = parts.getOrNull(1) ?: ""
        val base = when (volume) {
            "primary" -> android.os.Environment.getExternalStorageDirectory().absolutePath
            else -> "/storage/$volume"
        }
        return if (tail.isBlank()) base else "$base/$tail"
    }

    /** Подпись и значение в две строки: длинный адрес не должен разрываться посередине. */
    private fun block (label: String, value: String): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            addView(TextView(this@SectionActivity).apply {
                text = label
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#9ca3af"))
            })
            addView(TextView(this@SectionActivity).apply {
                text = value
                textSize = 15f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(0, (2 * density).toInt(), 0, 0)
            })
        }
    }

    /** Забывание необратимо: токен не восстановить, только получить новый код. */
    private fun confirmForget () {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(getString(R.string.menu_forget_confirm))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.menu_forget)) { _, _ ->
                store.clear()
                store.enabled = false
                status.text = getString(R.string.menu_not_connected)
                startActivity(Intent(this@SectionActivity, MainActivity::class.java))
                finish()
            }
            .show()
    }

    /** Камера и распознавание: разрешение спрашиваем в момент нажатия. */
    private fun startScanner () {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 7)
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
        // камеру выдали — сразу открываем сканер, отказ — говорим об этом словами
        if (requestCode == 7) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) startScanner()
            else status.text = getString(R.string.menu_scan_no_camera)
        }
    }

    /**
     * Список возможностей телефона.
     *
     * Каждый пункт: название, состояние и короткое объяснение зачем. Тап ведёт
     * туда, где это меняется — в системный диалог либо на свой экран, если
     * настройка состоит из нескольких частей.
     */
    private fun buildAndroid (pad: Int) {
        push(Ui.itemEx(
            this,
            getString(R.string.menu_fs_access),
            "${getString(R.string.state_word)}: ${if (android.os.Environment.isExternalStorageManager()) getString(R.string.state_on) else getString(R.string.state_off)}",
            getString(R.string.help_all_files),
            { openAllFiles() },
        ))
        push(Ui.divider(this))
        push(Ui.itemEx(
            this,
            getString(R.string.menu_apps),
            "${getString(R.string.state_word)}: ${getString(R.string.state_on)} · ${visibleApps()}",
            getString(R.string.help_apps),
            { open("apps") },
        ))
        push(Ui.divider(this))
        push(Ui.itemEx(
            this,
            getString(R.string.menu_roots),
            dirsPhrase(store.roots.size),
            getString(R.string.help_roots),
            { open("roots") },
        ))
        push(Ui.divider(this))
        push(Ui.itemEx(
            this,
            getString(R.string.menu_overlay),
            "${getString(R.string.state_word)}: ${if (android.provider.Settings.canDrawOverlays(this)) getString(R.string.state_on) else getString(R.string.state_off)}",
            getString(R.string.help_overlay),
            { openOverlaySettings() },
        ))
    }

    /** «указан 1 каталог» / «указано 2 каталога» / «указано 5 каталогов». */
    private fun dirsPhrase (count: Int): String {
        if (count == 0) return getString(R.string.roots_none_phrase)
        val word = when {
            count % 10 == 1 && count % 100 != 11 -> getString(R.string.roots_one)
            count % 10 in 2..4 && count % 100 !in 12..14 -> getString(R.string.roots_few)
            else -> getString(R.string.roots_many)
        }
        return "$count $word ${getString(R.string.roots_where)}"
    }

    /** Системный экран «доступ ко всем файлам»: включить и выключить можно там же. */
    private fun openAllFiles () {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } catch (error: Throwable) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (inner: Throwable) {
                status.text = inner.message ?: getString(R.string.menu_no)
            }
        }
    }

    /** Системный экран доступа «поверх других приложений»: своего экрана не надо. */
    private fun openOverlaySettings () {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (error: Throwable) {
            status.text = error.message ?: getString(R.string.menu_no)
        }
    }

    /** Рабочие каталоги: добавление системным диалогом и список. */
    private fun buildRoots () {
        if (store.roots.isEmpty()) {
            push(Ui.item(this, getString(R.string.menu_roots_none), null, null))
        } else {
            for (path in store.roots) push(Ui.item(this, path, null, null))
        }
        push(Ui.button(this, getString(R.string.menu_roots_add)) { pickDirectory() })
        push(Ui.button(this, getString(R.string.menu_roots_drop)) {
            store.roots = store.roots.dropLast(1)
            recreate()
        })
        push(status)
    }

    /** Сколько приложений видно прямо сейчас: без разрешения их заметно меньше. */
    private fun visibleApps (): Int {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0).size
    }

    /** Что именно видит узел: пакеты и названия. */
    private fun buildApps (pad: Int) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val items = packageManager.queryIntentActivities(intent, 0)
        push(Ui.item(this, getString(R.string.menu_apps_visible), items.size.toString(), null))
        push(Ui.item(this, getString(R.string.menu_apps_note), null, null))
        for (info in items.take(60)) {
            push(Ui.item(this, info.loadLabel(packageManager).toString(), info.activityInfo.packageName, null))
        }
        push(status)
    }

    private fun push (view: android.view.View) {
        root.addView(view)
    }

    private fun open (key: String) {
        startActivity(Intent(this, SectionActivity::class.java).putExtra("section", key))
    }

    private fun buildNet (pad: Int) {
        val ping = EditText(this).apply { setText(store.pingInterval.toString()) }
        val factor = EditText(this).apply { setText(store.pingFactor.toString()) }
        root.addView(TextView(this).apply { text = getString(R.string.menu_ping_hint); setPadding(0, pad, 0, 0) })
        root.addView(ping)
        root.addView(TextView(this).apply { text = getString(R.string.menu_stretch_hint); setPadding(0, pad, 0, 0) })
        root.addView(factor)
        root.addView(Ui.button(this, getString(R.string.menu_save)) {
            store.pingInterval = ping.text.toString().toIntOrNull() ?: 15
            store.pingFactor = factor.text.toString().toIntOrNull() ?: 10
            status.text = getString(R.string.menu_saved)
        })
        root.addView(status)
    }

    /** Подключение по ссылке: обмен кода на токен. */
    private fun connect (raw: String) {
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
                val answer = ru.uvw.dshnc.api.NodeApi(base).pair(code, deviceId(), android.os.Build.MODEL ?: "android")
                val token = answer.optString("token")
                if (token.isBlank()) throw IllegalStateException(getString(R.string.status_no_token))
                store.base = base
                store.token = token
                store.enabled = true
                runOnUiThread {
                    status.text = getString(R.string.menu_connected)
                    startActivity(Intent(this@SectionActivity, MainActivity::class.java))
                    finish()
                }
            } catch (error: Throwable) {
                runOnUiThread { status.text = error.message ?: getString(R.string.status_disconnected) }
            }
        }.start()
    }

    private fun deviceId (): String {
        store.deviceId?.let { return it }
        return java.util.UUID.randomUUID().toString().also { store.deviceId = it }
    }

    private fun row (what: String, value: String) = TextView(this).apply {
        text = "$what: $value"
        textSize = 14f
        setPadding(0, 8, 0, 8)
    }
}
