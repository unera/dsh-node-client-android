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
import ru.uvw.dshnc.service.NodeService
import ru.uvw.dshnc.store.TokenStore

/**
 * The screen of a single menu section: a title with "back" and the rows of that section.
 */
class SectionActivity : AppCompatActivity() {
    /** How many permission rows the section counts; the summary line says "N из этого". */
    private val permissionRows = 5

    /** The schemes the node may open, each with a sample link used to ask Android who handles it. */
    private companion object {
        /** Where the scroll position travels across a rebuild of the same screen. */
        const val KEY_SCROLL = "scrollY"

        val INTENT_SAMPLES = listOf(
            "geo" to "geo:0,0?q=Москва",
            "tel" to "tel:+70000000000",
            "https" to "https://example.com",
            "mailto" to "mailto:a@b.c",
            "sms" to "sms:+70000000000",
        )
    }

    /** The first entry must not rebuild the screen: otherwise a flicker on opening. */
    private var built = false

    /** The camera request result: after the answer we rebuild the screen. */
    private val cameraRequest = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) recreate() else status.text = getString(R.string.help_camera_settings)
    }
    /** The scan result comes here: the contents of the QR code — the same link. */
    /** Choosing a folder with the system dialog: the path comes already prepared. */
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
    /** The list itself: a switch must not move the reader, so the position is saved and restored. */
    private lateinit var scroller: android.widget.ScrollView

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
            "intents" -> "Запуск приложений"
            "perm" -> "Интеграция с андроид"
            "grants" -> "Разрешения"
            else -> getString(R.string.menu_net)
        }
        // the same screen and the same style as the menu: the third level must not
        // look like a different app
        root = Ui.screen(this, title) { finish() }

        status = TextView(this).apply {
            textSize = 13f
            alpha = 0.7f
            setPadding(0, pad, 0, 0)
        }

        when (section) {
            "auth" -> buildAuth(pad)
            "android" -> { buildAndroid(pad); buildDebug(pad) }
            "roots" -> buildRoots()
            "apps" -> buildApps(pad)
            "intents" -> buildIntents(pad)
            "perm" -> buildPermissions(pad)
            "grants" -> buildGrants(pad)
            else -> buildNet(pad)
        }
        scroller = android.widget.ScrollView(this).apply { addView(root) }
        setContentView(scroller)
        // a toggle rebuilds the screen (recreate); without this the person lands at the top and
        // has to find the switch again
        val keep = savedInstanceState?.getInt(KEY_SCROLL) ?: 0
        if (keep > 0) scroller.post { scroller.scrollTo(0, keep) }
    }

    override fun onSaveInstanceState (outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::scroller.isInitialized) outState.putInt(KEY_SCROLL, scroller.scrollY)
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

    /** The system folder picker: we do not type the path by hand, Android gives it itself. */
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

    /** We turn an SAF tree into an ordinary path: the file API works with paths. */
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

    /** A label and a value on two lines: a long address must not break in the middle. */
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

    /** Forgetting is irreversible: the token cannot be restored, only a new code obtained. */
    private fun confirmForget () {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(getString(R.string.menu_forget_confirm))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.menu_forget)) { _, _ ->
                store.clear()
                store.enabled = false
                // the store is only half of it: the running service holds the old token in
                // memory and would keep knocking with it — that is exactly how a forgotten
                // node went on answering 401 every five seconds for hours
                NodeService.stop(this)
                status.text = getString(R.string.menu_not_connected)
                startActivity(Intent(this@SectionActivity, MainActivity::class.java))
                finish()
            }
            .show()
    }

    /** Camera and recognition: the permission is requested at the moment of the press. */
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
        // the camera was granted — we open the scanner at once, a refusal — we say so in words
        if (requestCode == 7) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) startScanner()
            else status.text = getString(R.string.menu_scan_no_camera)
        }
    }

    /**
     * The list of the phone's capabilities.
     *
     * Each item: the name, the state and a short explanation of why. A tap leads to
     * where that is changed — into the system dialog, or to our own screen if the
     * setting consists of several parts.
     */
    private fun buildAndroid (pad: Int) {
        // an OEM build may refuse the call, and a settings screen must survive that
        val allFiles = runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)
        push(Ui.itemEx(
            this,
            getString(R.string.menu_fs_access),
            stateLine(allFiles),
            Ui.stateColor(allFiles),
            getString(R.string.help_all_files),
            { openAllFiles() },
        ))
        push(Ui.divider(this))
        push(Ui.itemEx(
            this,
            getString(R.string.menu_apps),
            "${stateLine(true)} · ${visibleApps()}",
            Ui.stateColor(true),
            getString(R.string.help_apps),
            { open("apps") },
        ))
        push(Ui.divider(this))
        push(Ui.itemEx(
            this,
            getString(R.string.menu_roots),
            dirsPhrase(store.roots.size),
            Ui.stateColor(store.roots.isNotEmpty()),
            getString(R.string.help_roots),
            { open("roots") },
        ))
        push(Ui.divider(this))
        // the permissions moved to a screen of their own: a person looking for "what may this
        // node do" should not read about directories and app lists on the way
        push(Ui.itemEx(
            this,
            "Разрешения",
            "${permissionsReady()} из $permissionRows",
            Ui.stateColor(permissionsReady() == permissionRows),
            "Камера, фон, будильники, запуск приложений — что узел уже может и чего ему не хватает",
            { open("perm") },
        ))
    }

    override fun onResume () {
        super.onResume()
        // permissions are changed in the system screens: coming back, we rebuild the list,
        // otherwise we show a stale state
        if (built) recreate() else built = true
    }

    /** One state line: "state: on" or "state: off". */
    private fun stateLine (on: Boolean): String =
        "${getString(R.string.state_word)}: ${getString(if (on) R.string.state_on else R.string.state_off)}"

    /** "1 directory set" / "2 directories set" / "5 directories set". */
    private fun dirsPhrase (count: Int): String {
        if (count == 0) return getString(R.string.roots_none_phrase)
        val word = when {
            count % 10 == 1 && count % 100 != 11 -> getString(R.string.roots_one)
            count % 10 in 2..4 && count % 100 !in 12..14 -> getString(R.string.roots_few)
            else -> getString(R.string.roots_many)
        }
        return "$count $word ${getString(R.string.roots_where)}"
    }

    /** The system "access to all files" screen: on and off can be switched right there. */
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

    /**
     * Camera: first the system dialog — that is the shortest way to "allow".
     * If Android no longer shows the dialog (a refusal with "do not ask"),
     * we open the app screen where the permissions are visible.
     */
    private fun askCamera () {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            openAppSettings()
            return
        }
        val canAsk = shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA)
        cameraRequest.launch(android.Manifest.permission.CAMERA)
        if (!canAsk) {
            // the dialog may have been closed for good: we hint where to look
            status.text = getString(R.string.help_camera_settings)
        }
    }

    /** Our app's permission screen in the system settings. */
    private fun openAppSettings () {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (error: Throwable) {
            status.text = error.message ?: getString(R.string.menu_no)
        }
    }

    /** The system "over other apps" access screen: no screen of our own is needed. */
    private fun openOverlaySettings () {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (error: Throwable) {
            status.text = error.message ?: getString(R.string.menu_no)
        }
    }

    /** Working directories: adding through the system dialog and the list. */
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

    /** How many apps are visible right now: without the permission noticeably fewer. */
    private fun visibleApps (): Int {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0).size
    }

    /** What exactly the node sees: the packages and the names. */
    /**
     * The debug switch: `session.debug_info` is off unless the owner allows it, and the only place
     * that used to grant it was the settings screen nobody could reach.
     */
    private fun buildDebug (pad: Int) {
        val debug = android.widget.CheckBox(this).apply {
            text = getString(R.string.menu_debug_info)
            isChecked = store.debugInfo
            setPadding(0, pad, 0, 0)
        }
        debug.setOnCheckedChangeListener { _, checked -> store.debugInfo = checked }
        root.addView(Ui.divider(this))
        root.addView(debug)
    }


    /**
     * What the phone can be asked to open.
     *
     * The list is not written here: it is what Android answers when asked who handles `geo:`,
     * `tel:` and the rest. The same query is what `android.intent_query` returns over the node
     * API, so the screen and the model see one truth.
     */

    /**
     * What the node is allowed to do, one row per permission.
     *
     * A row that leads to the system screen is always live: a permission can be granted AND
     * revoked there, and a one-way row would lock the person out of their own decision.
     */
    /**
     * The Android side: what the system granted, and nothing else.
     *
     * Only permissions a person can grant by tapping a system screen live here — the camera and
     * the alarms are ours to switch on the "Разрешения" screen, not the system's, and a list of
     * applications is not a permission at all.
     */
    private fun buildPermissions (pad: Int) {
        val allFiles = runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)
        push(permissionRow(getString(R.string.menu_fs_access), allFiles, getString(R.string.help_all_files)) { openAllFiles() })

        val overlay = android.provider.Settings.canDrawOverlays(this)
        push(permissionRow(getString(R.string.menu_overlay), overlay, getString(R.string.help_overlay)) { openOverlaySettings() })

        push(permissionRow(
            "Батарея без ограничений", batteryFree(),
            "Иначе Android душит фоновую работу и узел пропадает со связи на час-другой",
        ) { askBattery() })

        val popup = PopupPermission.allowed(this)
        push(permissionRow(
            getString(R.string.menu_popup), popup,
            getString(if (popup) R.string.popup_allowed else R.string.popup_denied),
        ) { PopupPermission.open(this) })
    }

    /**
     * Our own switches: what the node will answer for.
     *
     * A different question from the one above — the system says whether the app *may*, this
     * says whether the node *will*. The person behind each is different too: the phone's owner
     * grants Android permissions, the node's owner decides what a token is worth.
     */
    private fun buildGrants (pad: Int) {
        push(Ui.item(this, "Что это", "Здесь можно ограничить перечень действий, доступных DSH по управлению данным устройством", null))
        push(Ui.divider(this))

        for (grant in ru.uvw.dshnc.logic.Grants.Grant.entries) {
            val allowed = store.grant(grant.key)
            push(Ui.itemEx(
                this,
                grant.title,
                if (allowed) "включено" else "выключено",
                Ui.stateColor(allowed),
                grantHint(grant),
                {
                    store.setGrant(grant.key, !allowed)
                    // rebuilt from the store — one truth, no local copies — and the scroll
                    // position rides along through onSaveInstanceState
                    recreate()
                },
            ))
            push(Ui.divider(this))
        }

        push(Ui.itemEx(
            this,
            "Доступ к каталогам",
            dirsPhrase(store.roots.size),
            Ui.stateColor(store.roots.isNotEmpty()),
            getString(R.string.help_roots),
            { open("roots") },
        ))
        push(Ui.divider(this))
    }

    /** One sentence per switch: what a person loses by turning it off. */
    private fun grantHint (grant: ru.uvw.dshnc.logic.Grants.Grant): String = when (grant) {
        ru.uvw.dshnc.logic.Grants.Grant.SHELL -> "Выключено — exec отвечает отказом, команды на телефоне не выполняются"
        ru.uvw.dshnc.logic.Grants.Grant.ALARMS -> "Выключено — управление будильниками отвечает ошибкой"
        ru.uvw.dshnc.logic.Grants.Grant.INTENTS -> "Выключено — открыть навигатор или браузер по ссылке нельзя"
        ru.uvw.dshnc.logic.Grants.Grant.FILES -> "Выключено — файлы и каталоги недоступны, остаётся только оболочка"
        ru.uvw.dshnc.logic.Grants.Grant.DEBUG -> "Выключено — список приложений и отладочные сведения не отдаются"
        ru.uvw.dshnc.logic.Grants.Grant.VOICE -> "Выключено — голосовой чат и синтез не работают"
        ru.uvw.dshnc.logic.Grants.Grant.CAMERA -> "Выключено — снимок и список фотографий отвечают отказом"
        ru.uvw.dshnc.logic.Grants.Grant.NOTIFY -> "Выключено — уведомления и вопросы на телефоне не показываются"
    }

    /** One permission line: a title, a state, and a sentence about what happens without it. */
    private fun permissionRow (title: String, granted: Boolean, help: String, tap: () -> Unit): android.view.View =
        Ui.itemEx(this, title, stateLine(granted), Ui.stateColor(granted), help, tap)

    /** Android 12 and later may refuse an exact alarm; before that the question does not exist. */
    private fun exactAlarms (): Boolean = runCatching {
        android.os.Build.VERSION.SDK_INT < 31 ||
            getSystemService(android.app.AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }.getOrDefault(false)

    /** Whether the system is told to leave this app alone in the background. */
    private fun batteryFree (): Boolean = runCatching {
        getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
    }.getOrDefault(false)

    /** How many of the permissions are already granted — the number the section shows. */
    private fun permissionsReady (): Int {
        var ready = 0
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) ready += 1
        if (android.provider.Settings.canDrawOverlays(this)) ready += 1
        if (exactAlarms()) ready += 1
        if (batteryFree()) ready += 1
        if (PopupPermission.allowed(this)) ready += 1
        return ready
    }

    private fun openExactAlarmSettings () {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        } catch (error: Throwable) {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        }
    }

    private fun askBattery () {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (error: Throwable) {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun buildIntents (pad: Int) {
        val overlay = android.provider.Settings.canDrawOverlays(this)
        push(Ui.itemEx(
            this,
            "Запуск из фона",
            stateLine(overlay),
            Ui.stateColor(overlay),
            "Без этого доступа Android молча не откроет чужое приложение: ни навигатор, ни почту",
            { openOverlaySettings() },
        ))
        push(Ui.divider(this))
        for ((scheme, sample) in INTENT_SAMPLES) {
            val names = resolvers(sample)
            push(Ui.itemEx(
                this,
                "$scheme:",
                if (names.isEmpty()) "некому открыть" else names.take(3).joinToString(", "),
                Ui.stateColor(names.isNotEmpty()),
                if (names.size > 3) "ещё ${names.size - 3} — полный список спросите у узла" else "кто ответит на такую ссылку",
                null,
            ))
            push(Ui.divider(this))
        }
    }

    /** The apps that would answer this link, by the label a person knows them by. */
    private fun resolvers (sample: String): List<String> {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(sample))
        return packageManager.queryIntentActivities(intent, 0)
            .map { it.loadLabel(packageManager).toString() }
            .distinct()
    }

    /** One line for the section: how many schemes have somebody to open them. */
    private fun schemeCount (): String {
        val ready = INTENT_SAMPLES.count { resolvers(it.second).isNotEmpty() }
        return "$ready из ${INTENT_SAMPLES.size}"
    }

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
        val poll = EditText(this).apply { setText(store.pollFallback.toString()) }
        root.addView(TextView(this).apply { text = getString(R.string.menu_ping_hint); setPadding(0, pad, 0, 0) })
        root.addView(ping)
        root.addView(TextView(this).apply { text = getString(R.string.menu_stretch_hint); setPadding(0, pad, 0, 0) })
        root.addView(factor)
        // the fallback poll was only reachable on the old settings screen, which had no entry
        root.addView(TextView(this).apply { text = getString(R.string.menu_poll_hint); setPadding(0, pad, 0, 0) })
        root.addView(poll)
        root.addView(Ui.button(this, getString(R.string.menu_save)) {
            store.pingInterval = ping.text.toString().toIntOrNull() ?: 15
            store.pingFactor = factor.text.toString().toIntOrNull() ?: 10
            store.pollFallback = poll.text.toString().toIntOrNull() ?: 0
            status.text = getString(R.string.menu_saved)
        })
        root.addView(status)
    }

    /** Connecting by a link: an exchange of the code for a token. */
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
