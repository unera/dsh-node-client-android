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
            "apps_pick" -> "Добавить приложение"
            "handlers" -> (intent.getStringExtra("scheme") ?: "Схема")
            "overlay" -> getString(R.string.menu_overlay)
            "intents" -> "Запуск приложений"
            "perm" -> "Интеграция с андроид"
            "grants" -> "Разрешения"
            "update" -> "Автоматическое обновление программы"
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
            "roots" -> buildRoots()
            "apps" -> buildApps(pad)
            "intents" -> buildIntents(pad)
            "perm" -> buildPermissions(pad)
            "grants" -> buildGrants(pad)
            "update" -> buildUpdate(pad)
            "apps_pick" -> buildAppPicker(pad)
            "handlers" -> buildHandlers(pad)
            else -> buildNet(pad)
        }
        // Фон у самой прокрутки, а не только у содержимого: корень — wrap_content, и на
        // короткой секции ниже него просвечивал цвет темы. Плюс fillViewport, чтобы фон
        // растягивался на весь экран, а не заканчивался там, где кончились строки.
        scroller = android.widget.ScrollView(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#141414"))
            isFillViewport = true
            addView(root)
        }
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

    /**
     * One known client: a switch and nothing else.
     *
     * The command behind it is the client's own plugin protocol, learned once and kept by the
     * node; the person's decision here is only whether this node may use it.
     */
    private fun vpnRow (client: ru.uvw.dshnc.logic.VpnClient): LinearLayout {
        val density = resources.displayMetrics.density
        var allowed = store.grant(client.grantKey())
        val state = TextView(this).apply {
            text = stateWord(allowed)
            textSize = 15f
            setTextColor(Ui.stateColor(allowed))
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        val box = android.widget.CheckBox(this).apply {
            isChecked = allowed
            isClickable = false
            isFocusable = false
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@SectionActivity).apply {
                text = client.title
                textSize = 18f
                setTextColor(android.graphics.Color.WHITE)
            })
            addView(state)
            addView(TextView(this@SectionActivity).apply {
                text = "Выключено — включать и выключать ${client.title} с телефона нельзя"
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#8b8b8b"))
                setPadding(0, (6 * density).toInt(), 0, 0)
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
            isClickable = true
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(box)
            setOnClickListener {
                allowed = !allowed
                store.setGrant(client.grantKey(), allowed)
                box.isChecked = allowed
                state.text = stateWord(allowed)
                state.setTextColor(Ui.stateColor(allowed))
            }
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
            // one directory at a time: "удалить последний" was the only choice before, and a list
            // a person fills in deserves a list they can take entries out of
            for (path in store.roots) {
                push(swipeRow(path, "потяните влево, чтобы удалить", {
                    store.roots = store.roots - path
                    recreate()
                }))
                push(Ui.divider(this))
            }
        }
        push(Ui.button(this, getString(R.string.menu_roots_add)) { pickDirectory() })
        push(status)
    }

    /** How many apps are visible right now: without the permission noticeably fewer. */
    private fun visibleApps (): Int {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0).size
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

        // Installing is a system dialog, but the system asks first whether this app may even
        // offer a package. That switch lives in Settings, so the row exists to lead there.
        val canInstall = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ||
            packageManager.canRequestPackageInstalls()
        push(permissionRow(
            "Установка приложений", canInstall,
            if (canInstall) {
                "Узел может предложить APK системному установщику; ставит всё равно человек, в диалоге"
            } else {
                "Выключено — узел не может показать диалог установки"
            },
        ) { openInstallSettings() })
    }

    /** The system screen where "install unknown apps" is allowed for this app alone. */
    private fun openInstallSettings () {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(intent) }.onFailure { openAppSettings() }
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

        // Первым: самое частое желание после установки — чтобы узел возвращался сам, а не
        // после каждого ребута ждать, пока кто-нибудь откроет приложение.
        push(switchRow(
            "Автоматически запускать при старте",
            store.autostart,
            "Включено — после загрузки телефона узел поднимется сам, если он был включён. " +
                "Выключено — после ребута нужно открыть приложение или нажать «Включить» в шторке",
        ) { on -> store.autostart = on })
        push(Ui.divider(this))

        for (grant in ru.uvw.dshnc.logic.Grants.Grant.entries) {
            push(grantRow(grant))
            push(Ui.divider(this))
        }

        push(Ui.item(this, "Известные VPN", "Клиенты, которые узел умеет включать и выключать своим запросом", null))
        push(Ui.divider(this))
        for (client in ru.uvw.dshnc.logic.VpnClient.KNOWN) {
            push(vpnRow(client))
            push(Ui.divider(this))
        }

        push(Ui.itemEx(
            this,
            "Кому можно открываться",
            if (store.apps.isEmpty()) "никому нельзя" else "${store.apps.size} шт.",
            Ui.stateColor(true),
            "Список приложений, которым разрешено открывать ссылки по запросу извне; пусто — никому нельзя",
            { open("intents") },
        ))
        push(Ui.divider(this))

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


    /**
     * One switch: a title, a state, a sentence about what is lost, and a checkbox.
     *
     * The row updates itself instead of rebuilding the screen. Rebuilding kept the scroll
     * position but repainted everything, and a screen that flashes on every tap reads as a
     * fault rather than as an answer.
     */
    private fun grantRow (grant: ru.uvw.dshnc.logic.Grants.Grant): LinearLayout {
        val density = resources.displayMetrics.density
        var allowed = store.grant(grant.key)
        val state = TextView(this).apply {
            text = stateWord(allowed)
            textSize = 15f
            setTextColor(Ui.stateColor(allowed))
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        val box = android.widget.CheckBox(this).apply {
            isChecked = allowed
            // the row owns the tap: a checkbox that also reacts would toggle twice
            isClickable = false
            isFocusable = false
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@SectionActivity).apply {
                text = grant.title
                textSize = 18f
                setTextColor(android.graphics.Color.WHITE)
            })
            addView(state)
            addView(TextView(this@SectionActivity).apply {
                text = grantHint(grant)
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#8b8b8b"))
                setPadding(0, (6 * density).toInt(), 0, 0)
            })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
            isClickable = true
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(box)
            setOnClickListener {
                allowed = !allowed
                store.setGrant(grant.key, allowed)
                box.isChecked = allowed
                state.text = stateWord(allowed)
                state.setTextColor(Ui.stateColor(allowed))
            }
        }
        return row
    }

    /** The word the switch shows — short, because it sits under a title. */
    private fun stateWord (allowed: Boolean): String = if (allowed) "включено" else "выключено"

    /** One sentence per switch: what a person loses by turning it off. */
    private fun grantHint (grant: ru.uvw.dshnc.logic.Grants.Grant): String = when (grant) {
        ru.uvw.dshnc.logic.Grants.Grant.SHELL -> "Выключено — exec отвечает отказом, команды на телефоне не выполняются"
        ru.uvw.dshnc.logic.Grants.Grant.ALARMS -> "Выключено — управление будильниками отвечает ошибкой"
        ru.uvw.dshnc.logic.Grants.Grant.INTENTS -> "Выключено — открыть навигатор или браузер по ссылке нельзя"
        ru.uvw.dshnc.logic.Grants.Grant.COMMANDS -> "Выключено — молчаливые команды приложениям не отправляются: так переключают плеер, умный дом"
        ru.uvw.dshnc.logic.Grants.Grant.INSTALL -> "Выключено — узел не может предложить APK установщику; ставить можно только руками"
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

    /**
     * Who may be woken by a link.
     *
     * The same shape as the directories screen: a list a person fills and edits, not a list the
     * phone decides. Empty means "any app" — and says so, because a whitelist nobody filled
     * must not look like a refusal.
     */
    private fun buildIntents (pad: Int) {
        push(Ui.item(this, "Кому можно открываться", if (store.apps.isEmpty()) "никому нельзя" else "${store.apps.size} шт.", null))
        push(Ui.item(this, "", "Пусто — не открывается ничего. В список попадают только добавленные приложения: их обычно немного.", null))
        push(Ui.divider(this))

        for (pack in store.apps) {
            push(swipeRow(appLabel(pack), pack) {
                store.apps = store.apps - pack
                recreate()
            })
            push(Ui.divider(this))
        }

        push(Ui.button(this, "Добавить приложение") { open("apps_pick") })
        push(Ui.divider(this))

        // what the phone itself answers: a tap shows who, and lets one of them be allowed
        push(Ui.item(this, "Схемы ссылок", "Тап — кто отвечает на такую ссылку", null))
        push(Ui.divider(this))
        for ((scheme, sample) in INTENT_SAMPLES) {
            val names = resolvers(sample)
            push(Ui.item(
                this,
                "$scheme:",
                if (names.isEmpty()) "некому открыть" else names.take(3).joinToString(", "),
                { startActivity(Intent(this, SectionActivity::class.java).putExtra("section", "handlers").putExtra("sample", sample).putExtra("scheme", scheme)) },
            ))
            push(Ui.divider(this))
        }
        push(status)
    }

    /**
     * Who answers one scheme, and a way to try it.
     *
     * The same list the node would get from `intent_query`, shown to the person: a tap allows
     * that app, the button at the bottom opens the sample so the choice can be seen working.
     */
    private fun buildHandlers (pad: Int) {
        val sample = intent.getStringExtra("sample") ?: return
        val scheme = intent.getStringExtra("scheme") ?: sample.substringBefore(':')
        push(Ui.item(this, "$scheme:", sample, null))
        push(Ui.divider(this))
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(sample))
        for (info in packageManager.queryIntentActivities(intent, 0)) {
            val pack = info.activityInfo.packageName
            val allowed = store.apps.contains(pack)
            push(Ui.itemEx(
                this,
                info.loadLabel(packageManager).toString(),
                if (allowed) "разрешено" else "не в списке",
                Ui.stateColor(allowed),
                pack,
                {
                    if (!allowed) {
                        store.apps = store.apps + pack
                        recreate()
                    }
                },
            ))
            push(Ui.divider(this))
        }
        push(Ui.button(this, "Открыть пример") {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sample))) }
        })
    }

    /** Every app that can be launched: tapping one adds it to the whitelist and goes back. */
    private fun buildAppPicker (pad: Int) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = LinkedHashMap<String, String>()
        for (info in packageManager.queryIntentActivities(intent, 0)) {
            val pack = info.activityInfo.packageName
            if (pack == packageName) continue
            seen.putIfAbsent(pack, info.loadLabel(packageManager).toString())
        }
        push(Ui.item(this, "", "Тап добавляет приложение в список, разрешённый для запуска", null))
        push(Ui.divider(this))
        for ((pack, label) in seen.entries.sortedBy { it.value.lowercase() }) {
            if (store.apps.contains(pack)) continue
            push(Ui.item(this, label, pack, {
                store.apps = store.apps + pack
                finish()
            }))
            push(Ui.divider(this))
        }
    }

    /** The label an app is known by, or the package when nothing is installed under it. */
    private fun appLabel (pack: String): String = runCatching {
        val info = packageManager.getApplicationInfo(pack, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(pack)

    /**
     * A row that reveals "Удалить" when dragged to the left.
     *
     * The list is short and lives in a plain ScrollView, so a recycler is not worth its weight;
     * what a person needs is the ability to drop one entry, and the button behind the row is the
     * idiom they already know from mail clients.
     */
    private fun swipeRow (title: String, subtitle: String?, onDelete: () -> Unit): android.view.View {
        val density = resources.displayMetrics.density
        val buttonWidth = (104 * density).toInt()
        val content = Ui.item(this, title, subtitle, null)
        val remove = TextView(this).apply {
            text = "Удалить"
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#b91c1c"))
            isClickable = true
            // Gone until the row is swiped: a button that is merely covered depends on the z
            // order and on every measurement coming out right, which is exactly what went wrong.
            visibility = android.view.View.GONE
            setOnClickListener { onDelete() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(content, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(remove, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT))
        }

        /** Width of the revealed button: zero when closed, `buttonWidth` when fully open. */
        fun reveal (width: Int) {
            val clamped = width.coerceIn(0, buttonWidth)
            remove.visibility = if (clamped > 0) android.view.View.VISIBLE else android.view.View.GONE
            (remove.layoutParams as LinearLayout.LayoutParams).width = clamped
            remove.requestLayout()
        }

        var downX = 0f
        var startWidth = 0
        content.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    startWidth = (remove.layoutParams as LinearLayout.LayoutParams).width
                    // The list lives in a ScrollView, and a ScrollView takes a drag for itself the
                    // moment it reads one. Telling it not to is what lets the row see the end of a
                    // horizontal gesture instead of losing it half-way.
                    content.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    // The row follows the finger: the button takes width as the drag goes left and
                    // gives it back as the drag returns. Nothing appears only after a pause — a
                    // list that answers on the release alone feels broken.
                    val dx = event.rawX - downX
                    if (kotlin.math.abs(dx) > 16 * density) {
                        content.parent?.requestDisallowInterceptTouchEvent(true)
                        reveal(startWidth - dx.toInt())
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val width = (remove.layoutParams as LinearLayout.LayoutParams).width
                    reveal(if (width > buttonWidth / 2) buttonWidth else 0)
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    reveal(0)
                    true
                }
                else -> false
            }
        }
        return row
    }


    /** The apps that would answer this link, by the label a person knows them by. */
    private fun resolvers (sample: String): List<String> {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(sample))
        return packageManager.queryIntentActivities(intent, 0)
            .map { it.loadLabel(packageManager).toString() }
            .distinct()
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

    /**
     * «Автоматическое обновление программы»: расписание, ручная проверка, установка, разрешение.
     *
     * One screen for one story: how often to look, what came back, what to do with it, and what
     * Android must allow for the last step.
     */
    private fun buildUpdate (pad: Int) {
        val interval = ru.uvw.dshnc.logic.Updater.Interval.of(store.updateInterval)
        push(Ui.itemEx(
            this,
            "Проверять обновления",
            interval.label,
            Ui.stateColor(interval != ru.uvw.dshnc.logic.Updater.Interval.NEVER),
            updateDetail(interval),
            {
                val options = ru.uvw.dshnc.logic.Updater.Interval.entries.map { it.label }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Проверять обновления")
                    .setSingleChoiceItems(
                        options,
                        ru.uvw.dshnc.logic.Updater.Interval.entries.indexOf(interval),
                    ) { dialog, which ->
                        store.updateInterval =
                            ru.uvw.dshnc.logic.Updater.Interval.entries[which].key
                        dialog.dismiss()
                        recreate()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            },
        ))
        push(Ui.divider(this))

        // Проверка руками: то же, что делает запуск, но по нажатию и с ответом на экране
        push(Ui.button(this, "Проверить обновления") { checkNow() })

        val pendingVersion = store.pendingUpdateVersion
        val pendingFile = store.pendingUpdate(this)
        if (pendingFile != null && pendingVersion != null &&
            ru.uvw.dshnc.logic.Updater.newer(pendingVersion, ru.uvw.dshnc.logic.Updater.currentVersion(this))
        ) {
            push(Ui.button(this, "Установить обновление $pendingVersion") {
                UpdateUi.install(this, pendingFile) {
                    store.pendingUpdateVersion = null
                    store.pendingUpdateFile = null
                }
            })
        }

        push(status)

        // Разрешение — последний шаг той же истории: без него установка упирается в системный
        // экран, и узнать об этом лучше здесь, а не в момент обновления.
        val canInstall = ru.uvw.dshnc.logic.Updater.canInstall(this)
        push(Ui.itemEx(
            this,
            "Установка приложений",
            if (canInstall) "разрешено" else "запрещено",
            Ui.stateColor(canInstall),
            "Разрешение Android: без него приложение не может предложить скачанный APK установщику. " +
                "Нажмите, чтобы открыть системный экран",
            { openInstallSettings() },
        ))
        push(Ui.divider(this))
        push(Ui.item(
            this,
            "Установить вручную",
            "Скачанный файл лежит в папке приложения; установить его можно и через API узла",
            null,
        ))
    }

    /** Когда проверяли в прошлый раз — и не ждёт ли установки скачанное. */
    private fun updateDetail (interval: ru.uvw.dshnc.logic.Updater.Interval): String {
        if (interval == ru.uvw.dshnc.logic.Updater.Interval.NEVER) return "Проверка выключена"
        val last = store.lastUpdateCheck
        if (last == 0L) return "Ещё не проверяли"
        val minutes = (System.currentTimeMillis() - last) / 60_000
        val ago = when {
            minutes < 1 -> "только что"
            minutes < 60 -> "$minutes мин назад"
            minutes < 60 * 24 -> "${minutes / 60} ч назад"
            else -> "${minutes / (60 * 24)} дн назад"
        }
        val pending = store.pendingUpdateVersion
        return if (pending != null) "Последняя проверка: $ago · скачано $pending, ждёт установки"
        else "Последняя проверка: $ago"
    }

    /** Один запрос к GitHub по нажатию: скачать и сразу предложить установку. */
    private fun checkNow () {
        status.text = "проверяю…"
        Thread {
            val outcome = ru.uvw.dshnc.logic.Updater.checkAndDownload(this)
            runOnUiThread {
                val file = outcome.file
                val version = outcome.version
                if (file == null || version == null) {
                    status.text = outcome.text
                    return@runOnUiThread
                }
                store.pendingUpdateVersion = version
                store.pendingUpdateFile = file.name
                status.text = "скачано $version"
                // Отказ здесь не отменяет обновление: файл остаётся, и главный экран спросит
                // сам — ровно так, как если бы версию нашла фоновая проверка.
                UpdateUi.offerInstall(
                    this, version, file,
                    onDecline = { status.text = "обновление $version ждёт: спрошу на главном экране" },
                    onStarted = {
                        store.pendingUpdateVersion = null
                        store.pendingUpdateFile = null
                    },
                )
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * A switch row: a title, the state, an explanation, and a checkbox.
     *
     * The same shape as the grant rows, and for the same reason: the row owns the tap and the
     * screen is not rebuilt, so a flick does not move what the person was reading.
     */
    private fun switchRow (title: String, on: Boolean, help: String, set: (Boolean) -> Unit): LinearLayout {
        val density = resources.displayMetrics.density
        var current = on
        val state = TextView(this).apply {
            text = stateWord(current)
            textSize = 15f
            setTextColor(Ui.stateColor(current))
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        val box = android.widget.CheckBox(this).apply {
            isChecked = current
            isClickable = false
            isFocusable = false
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@SectionActivity).apply {
                text = title
                textSize = 18f
                setTextColor(android.graphics.Color.WHITE)
            })
            addView(state)
            addView(TextView(this@SectionActivity).apply {
                text = help
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#8b8b8b"))
                setPadding(0, (6 * density).toInt(), 0, 0)
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
            isClickable = true
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(box)
            setOnClickListener {
                current = !current
                box.isChecked = current
                state.text = stateWord(current)
                state.setTextColor(Ui.stateColor(current))
                set(current)
            }
        }
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
                val answer = ru.uvw.dshnc.api.NodeApi(base).pair(code, deviceId(), ru.uvw.dshnc.logic.Naming.localName(store.name, android.os.Build.MODEL))
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
