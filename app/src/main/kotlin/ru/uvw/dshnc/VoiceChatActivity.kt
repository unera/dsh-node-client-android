package ru.uvw.dshnc

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import ru.uvw.dshnc.api.NodeApi
import ru.uvw.dshnc.logic.Recorder
import ru.uvw.dshnc.logic.VoiceMode
import ru.uvw.dshnc.api.VoiceEvents
import ru.uvw.dshnc.logic.VoiceChat
import ru.uvw.dshnc.logic.VoiceChatUi
import ru.uvw.dshnc.logic.VoiceMenu
import ru.uvw.dshnc.logic.VoiceTimer
import ru.uvw.dshnc.logic.VoiceChatBus
import ru.uvw.dshnc.logic.VoiceChatLog
import ru.uvw.dshnc.logic.VoiceFiles
import ru.uvw.dshnc.logic.VoiceInbox
import ru.uvw.dshnc.logic.VoiceOutbox
import ru.uvw.dshnc.logic.VoiceTake
import ru.uvw.dshnc.logic.VoiceUpload
import ru.uvw.dshnc.store.TokenStore
import java.io.File

/**
 * The spoken chat: a conversation in the top third, one big button in the bottom third.
 *
 * The screen owns the microphone; the host owns the transfer. A take is recorded here and parked
 * in [VoiceOutbox], because `/v1/upload-file` is accepted only inside a command the server
 * issued — so the button may be pressed before the host asks, and the next `voice.record` picks
 * the take up at once. That is the ordinary case in a car: people speak when they have something
 * to say, not when a command arrives.
 *
 * The button has two habits, both worth having: hold it while speaking (radio), or press once to
 * start and once to stop (gloves, cold, a mounted phone).
 */
class VoiceChatActivity : AppCompatActivity() {
    private lateinit var store: TokenStore
    private lateinit var recorder: Recorder
    private lateinit var chat: LinearLayout
    /** The line that says what the microphone is doing: «слушаю» then «отправлено хосту». */
    private lateinit var stateLine: TextView
    /** One line for the model's current step: it replaces the previous step, it does not pile up. */
    private lateinit var progressLine: TextView
    /** The exchange whose steps are being shown: its answer clears the line. */
    private var progressExchange = ""
    private lateinit var scroller: ScrollView
    private lateinit var meter: ProgressBar
    private lateinit var hint: TextView
    private lateinit var talk: TextView
    private lateinit var cancel: TextView
    /** The clock on the record button: `0:07 / 0:30`. */
    private lateinit var timerLine: TextView
    /** The menu, while it is open: dismissed from the rows themselves. */
    private var popup: android.widget.PopupWindow? = null
    /** The screen's own small decisions: the hint and the mode, testable without a device. */
    private lateinit var ui: VoiceChatUi

    private val ticker = Handler(Looper.getMainLooper())
    private var mode = VoiceMode.PTT
    /** True while the person's finger is down in the radio mode: a hold, not a press. */
    private var holding = false
    /** When the microphone opened, for the diagnostics on the wire: 0 means "not known". */
    private var recordStartedAt = 0L
    /** True while the screen considers a take to be running, including the auto-stop moment. */
    private var recording = false

    private val permissionRequest = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startRecording() else show("без разрешения на микрофон говорить не получится")
    }

    override fun onCreate (savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TokenStore(this)
        // a take lives in the cache: the ordinary path uploads it and deletes it at once, so
        // nothing of ours should accumulate in the person's shared storage
        recorder = Recorder(cacheDir)
        ui = VoiceChatUi(VoiceMode.of(store.voiceMode))
        mode = ui.mode
        setContentView(build())
        VoiceOutbox.onEvent = { event, recording -> runOnUiThread { onOutbox(event, recording?.seconds) } }
        VoiceInbox.onPosted = { runOnUiThread { drainInbox() } }
        drainInbox()
        refreshHint(0.0)
        show("готов")
        tick(0)
    }

    /** The whole screen, built the way the rest of the app is: plain views, no XML. */
    private fun build (): View {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val root = Ui.screen(this, "Голосовой чат") { finish() }
        root.setPadding(0, 0, 0, 0)
        // the habits live in this menu now: on the screen the squares were in the way
        val header = root.getChildAt(0) as LinearLayout
        header.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        header.addView(TextView(this).apply {
            text = "⋮"
            textSize = 24f
            gravity = Gravity.CENTER
            contentDescription = "меню голосового чата"
            val size = (24 * density).toInt()
            val margin = (16 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(margin, 0, margin, 0) }
            isClickable = true
            isFocusable = true
            setOnClickListener { openMenu(it) }
        })

        // the conversation: everything spoken so far, newest at the bottom
        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, (8 * density).toInt())
        }
        stateLine = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.parseColor("#93c5fd"))
            setPadding(0, (6 * density).toInt(), 0, 0)
            visibility = View.GONE
        }
        progressLine = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#9ca3af"))
            setPadding(0, (6 * density).toInt(), 0, 0)
            visibility = View.GONE
        }
        scroller = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(chat)
        }
        root.addView(scroller)

        // the hint stands above the big button and is always there: a person should not have to
        // remember which habit the button has. Small on purpose: it is a hint, not a title.
        hint = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9ca3af"))
            setPadding(pad, (4 * density).toInt(), pad, (4 * density).toInt())
        }
        root.addView(hint)

        val width = resources.displayMetrics.widthPixels
        // a rectangle, not a circle, and a fifth smaller than the round button it replaces:
        // 80% of min(width - 32 dp, height/3), with a 0.6 aspect so it reads as a bar
        val box = minOf(
            width - pad * 2,
            resources.displayMetrics.heightPixels / 3,
        )
        val buttonWidth = (box * 0.8).toInt()
        val buttonHeight = (buttonWidth * 0.6).toInt()

        // one divider, not two: the level bar is the only line between the conversation and the
        // button, and it sits right above the button with a small gap
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (6 * density).toInt())
                .apply { setMargins(pad, (4 * density).toInt(), pad, (8 * density).toInt()) }
        }
        root.addView(meter)

        val stage = android.widget.FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(buttonWidth, buttonHeight).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, (16 * density).toInt())
            }
        }

        talk = TextView(this).apply {
            text = "🎤"
            textSize = 44f
            gravity = Gravity.CENTER
            contentDescription = "говорить"
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24 * density
                setColor(Color.parseColor("#2563eb"))
            }
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        stage.addView(talk)

        // the clock runs along the bottom of the button: how long it lasts and how long it may
        timerLine = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            visibility = View.GONE
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (8 * density).toInt()
            }
        }
        stage.addView(timerLine)
        root.addView(stage)

        cancel = TextView(this).apply {
            text = "отмена"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#f87171"))
            visibility = View.GONE
            setOnClickListener {
                holding = false
                recorder.cancel()
                renderIdle()
                show("запись отменена")
            }
        }
        root.addView(cancel)

        talk.setOnTouchListener { _, event ->
            when (mode) {
                VoiceMode.PTT -> {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            holding = true
                            startRecording()
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (holding) {
                                holding = false
                                stopRecording()
                            }
                        }
                    }
                    true
                }
                VoiceMode.TOGGLE -> {
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        if (recorder.isRecording) stopRecording() else startRecording()
                    }
                    true
                }
            }
        }
        return root
    }

    /** Choose the habit: it changes the mode and the picture, never the microphone. */
    private fun selectMode (next: VoiceMode) {
        if (!ui.selectMode(next)) return
        mode = ui.mode
        store.voiceMode = mode.wire
        refreshHint(0.0)
    }

    /**
     * The overflow menu: the two habits, and starting a new conversation.
     *
     * A plain `PopupMenu` cannot carry the small grey captions the modes need, so the rows are
     * built here; the list itself comes from [VoiceMenu], which is where its order and marks are
     * tested.
     */
    private fun openMenu (anchor: View) {
        val density = resources.displayMetrics.density
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1f2937"))
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }
        for (item in VoiceMenu.items(mode)) {
            rows.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
                isClickable = true
                setOnClickListener {
                    popup?.dismiss()
                    onMenuItem(item.id)
                }
                addView(LinearLayout(this@VoiceChatActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@VoiceChatActivity).apply {
                        text = item.title
                        textSize = 16f
                        setTextColor(Color.WHITE)
                    })
                    item.subtitle?.let { subtitle ->
                        addView(TextView(this@VoiceChatActivity).apply {
                            text = subtitle
                            textSize = 12f
                            setTextColor(Color.parseColor("#9ca3af"))
                            setPadding(0, (2 * density).toInt(), 0, 0)
                        })
                    }
                })
                // the habit that is set right now is marked, the action is not
                if (item.checked) {
                    addView(TextView(this@VoiceChatActivity).apply {
                        text = "✓"
                        textSize = 18f
                        setTextColor(Color.parseColor("#2563eb"))
                        setPadding((12 * density).toInt(), 0, 0, 0)
                    })
                }
            })
        }
        val popup = android.widget.PopupWindow(
            rows,
            (280 * density).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            elevation = 8 * density
        }
        this.popup = popup
        popup.showAsDropDown(anchor, 0, (4 * density).toInt())
    }

    /** Act on a menu row: switch the habit, or start a new conversation on the host. */
    private fun onMenuItem (id: String) {
        VoiceMenu.modeOf(id)?.let { next ->
            selectMode(next)
            return
        }
        if (id == VoiceMenu.NEW_SESSION) newSession()
    }

    /**
     * Start a new conversation on the host.
     *
     * The history is cleared only when the host accepted: a cleared screen with the old session
     * still on the other side would be a lie about what the next remark does.
     */
    private fun newSession () {
        val base = store.base
        val token = store.token
        if (base == null || token == null) {
            addLine("хост не ответил")
            return
        }
        Thread({
            val ok = runCatching {
                NodeApi(base).sendEvent(token, org.json.JSONObject()
                    .put("type", "voice")
                    .put("action", "new-session"))
                true
            }.getOrDefault(false)
            runOnUiThread {
                if (!ok) {
                    addLine("хост не ответил")
                    return@runOnUiThread
                }
                VoiceInbox.clear()
                VoiceChatBus.clear()
                hideProgress()
                addLine("новая сессия")
            }
        }, "dshnc-voice-session").start()
    }

    /** The line above the button: the habit, or the clock while a take is running. */
    private fun refreshHint (seconds: Double) {
        hint.text = ui.hint(recorder.isRecording, seconds)
    }

    /** One line in the conversation for the microphone's state; the newest state wins. */
    private fun setState (text: String) {
        stateLine.text = text
        stateLine.visibility = View.VISIBLE
        // the state line always sits at the bottom of the conversation: remove and re-add
        (stateLine.parent as? LinearLayout)?.removeView(stateLine)
        chat.addView(stateLine)
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

    private fun startRecording () {
        if (recorder.isRecording) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionRequest.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        // the ceiling is the screen's: thirty seconds, whatever the host would allow
        recorder.maxSeconds = VoiceTimer.LIMIT_SECONDS.toDouble()
        val error = recorder.start()
        if (error != null) {
            show(error)
            return
        }
        recordStartedAt = System.currentTimeMillis()
        recording = true
        talk.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#dc2626"))
        }
        cancel.visibility = View.VISIBLE
        refreshHint(0.0)
        setState("слушаю")
        show("говорите")
        // one clock per take: a second press must not leave the previous ticker running
        ticker.removeCallbacksAndMessages(null)
        tick(0)
    }

    private fun stopRecording () {
        recording = false
        cancel.visibility = View.GONE
        timerLine.visibility = View.GONE
        val take = recorder.stop()
        if (take == null) {
            renderIdle()
            show("слишком коротко, не отправлено")
            return
        }
        // a mistaken tap must not become a turn: the recognizer hears something in any noise, and
        // an answer to a question nobody asked is worse than no answer at all
        val refuseShort = VoiceTake.rejectReason(take.seconds, take.file.length())
        if (refuseShort != null) {
            runCatching { take.file.delete() }
            renderIdle()
            setState("готов")
            show(refuseShort)
            return
        }
        val stoppedAt = System.currentTimeMillis()
        val exchange = VoiceUpload.newId()
        // the take appears at once and carries its exchange id: the transcript that arrives later
        // replaces this very line instead of standing next to it
        VoiceChatBus.recording(exchange, "вы · ${"%.1f".format(take.seconds)} с")
        renderChat()
        setState("отправлено")
        sendTake(take, recordStartedAt, stoppedAt, exchange)
        renderIdle()
    }

    /**
     * Hand one take to the host in a single request.
     *
     * The press identity is minted here, once per press: the host may retry, and a retry must not
     * turn into a second remark in the conversation. On refusal the take is not thrown away — it
     * moves into a directory the host may read and waits for the older `record` route.
     */
    private fun sendTake (take: Recorder.Result, startedAt: Long, stoppedAt: Long, id: String) {
        val base = store.base
        val token = store.token
        if (base == null || token == null) {
            refuse(take, "узел не подключён к хосту")
            return
        }
        Thread({
            // the three marks travel with the take so the host can measure the whole path: a
            // delay a person notices spans from the button to the answer, not just the upload
            val times = VoiceUpload.Times(
                recordStart = startedAt.takeIf { it > 0 },
                recordStop = stoppedAt.takeIf { it > 0 },
                sentAt = System.currentTimeMillis(),
            )
            val outcome = VoiceEvents(base, token)
                .sendVoice(take.file, take.seconds, id, times, store.voiceSynth)
            runOnUiThread {
                when (outcome) {
                    is VoiceUpload.Result.Accepted -> {
                        runCatching { take.file.delete() }
                        // the receipt belongs to the take: a caption under «вы», and it disappears
                        // as soon as the transcript makes it pointless
                        val caption = "принято хостом · ${"%.1f".format(outcome.seconds ?: take.seconds)} с"
                        val shown = VoiceChatBus.note(id, caption)
                        setState(if (shown) "отправлено" else "готов")
                        renderChat()
                        show(outcome.path?.let { "на хосте: $it" } ?: "принято")
                    }
                    is VoiceUpload.Result.Failed -> refuse(take, outcome.reason)
                }
            }
        }, "dshnc-voice-send").start()
    }

    /** The take did not land: keep it where the host can still pick it up, and say so. */
    private fun refuse (take: Recorder.Result, reason: String) {
        val directory = VoiceFiles.directory(store.roots, filesDir)
        val target = File(directory, take.file.name)
        val parked = runCatching {
            if (target.absolutePath != take.file.absolutePath) {
                take.file.copyTo(target, overwrite = true)
                take.file.delete()
            }
            target
        }.getOrElse { take.file }
        VoiceOutbox.offer(VoiceOutbox.Recording(parked, take.seconds, mode.wire))
        addLine(VoiceUpload.REFUSED_LINE)
        show("$reason · ${parked.absolutePath}")
        setState("хост не принял, сохранено локально")
    }

    private fun renderIdle () {
        meter.progress = 0
        talk.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#2563eb"))
        }
        refreshHint(0.0)
    }

    /**
     * Take everything the host has sent and show it.
     *
     * This runs on the main thread and only here: the command handlers put items in the queue and
     * answer at once, so neither a slow draw nor a closed screen can hold up the node.
     */
    private fun drainInbox () {
        val items = VoiceInbox.drain()
        if (items.isEmpty()) {
            renderChat()
            return
        }
        for (item in items) {
            when (item) {
                is VoiceInbox.Item.Chat -> {
                    VoiceChatBus.putQuiet(item.id, item.role, item.text)
                    // the answer ends the work: the progress line has nothing left to report
                    if (item.role == VoiceChat.Role.ASSISTANT && (item.id.isEmpty() || item.id == progressExchange)) {
                        hideProgress()
                    }
                }
                is VoiceInbox.Item.Activity -> {
                    VoiceChatBus.countStep(item.id)
                    progressExchange = item.id.ifEmpty { progressExchange }
                    progressLine.text = "${item.n}. ${item.name.ifEmpty { "…" }}"
                    progressLine.visibility = View.VISIBLE
                }
            }
        }
        renderChat()
    }

    /** The step is over: the line goes away rather than turning into history. */
    private fun hideProgress () {
        progressLine.visibility = View.GONE
        progressLine.text = ""
    }

    /**
     * Draw the whole conversation from the shared log.
     *
     * Redrawing everything is cheap at two hundred lines and it removes a whole class of bugs: a
     * transcript replacing its placeholder, an answer arriving before the screen was opened, and a
     * rotate in the middle of an exchange all end up in the same code path.
     */
    private fun renderChat () {
        chat.removeAllViews()
        for (entry in VoiceChatBus.snapshot()) chat.addView(view(entry))
        if (progressLine.visibility == View.VISIBLE) {
            (progressLine.parent as? android.view.ViewGroup)?.removeView(progressLine)
            chat.addView(progressLine)
        }
        (stateLine.parent as? android.view.ViewGroup)?.removeView(stateLine)
        chat.addView(stateLine)
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

    /** One line: the person flush left, the answer indented, captions small and grey. */
    private fun view (entry: VoiceChatLog.Entry): View {
        val density = resources.displayMetrics.density
        val indent = when (entry.role) {
            // the answer sits as the other speaker: indented, slightly quieter
            VoiceChat.Role.ASSISTANT -> (28 * density).toInt()
            else -> 0
        }
        val size = when (entry.role) {
            VoiceChat.Role.SYSTEM -> 13f
            else -> 20f
        }
        val color = when (entry.role) {
            VoiceChat.Role.USER -> Color.WHITE
            VoiceChat.Role.ASSISTANT -> Color.parseColor("#e5e7eb")
            VoiceChat.Role.SYSTEM -> Color.parseColor("#6b7280")
        }
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(indent, (4 * density).toInt(), 0, (4 * density).toInt())
        }
        // prose is drawn as it came; fenced runs are monospaced on a grey field, markers gone
        for (segment in VoiceChat.segments(entry.text)) {
            block.addView(TextView(this).apply {
                text = segment.text
                textSize = size
                setTextColor(color)
                if (segment.code) {
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size - 3f)
                    setBackgroundColor(Color.parseColor("#1f2937"))
                    setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
                }
            })
        }
        // a caption under the person's line: how the host took the take
        entry.note?.let { note -> block.addView(caption(note)) }
        // and one under the answer: how much work it took, so a passing cadence is at least counted
        entry.steps?.takeIf { it > 0 }?.let { count -> block.addView(caption("шагов: $count")) }
        return block
    }

    /** A small grey line under a remark: a caption, never a remark of its own. */
    private fun caption (text: String): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor("#6b7280"))
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
    }

    /** A note of the screen's own: small and grey, never mistaken for a remark. */
    private fun addLine (text: String) {
        VoiceChatBus.system(text)
        renderChat()
    }

    private fun show (text: String) {
        hint.text = text
    }

    private fun onOutbox (event: VoiceOutbox.Event, seconds: Double?) {
        when (event) {
            VoiceOutbox.Event.RECORDED -> show("запись сохранена (${"%.1f".format(seconds ?: 0.0)} с)")
            VoiceOutbox.Event.TAKEN -> {
                setState(if (seconds != null) "отправлено хосту · ${"%.1f".format(seconds)} с" else "отправлено хосту")
                show("хост забрал запись")
            }
            VoiceOutbox.Event.WAITING ->
                show(if (VoiceOutbox.isWaiting()) "хост слушает" else "готов")
        }
    }

    /** While a take is running the meter and the clock are refreshed ten times a second. */
    private fun tick (elapsed: Int) {
        if (!recorder.isRecording) {
            // the recorder stops by itself at the ceiling: that is the automatic stop, and the
            // take is sent as if the person had pressed the button a second time
            if (elapsed > 0 && recording) finishAtLimit()
            meter.progress = 0
            return
        }
        meter.progress = (recorder.level * 100).toInt().coerceIn(0, 100)
        val seconds = elapsed / 10.0
        // the hint over the button is where the person looks: the clock belongs there
        refreshHint(seconds)
        // and the button itself counts: how long it lasts and how long it may
        timerLine.text = VoiceTimer.label(seconds)
        timerLine.visibility = View.VISIBLE
        ticker.postDelayed({ tick(elapsed + 1) }, 100)
    }

    /** Thirty seconds are over: stop, say so, and send what was said. */
    private fun finishAtLimit () {
        recording = false
        addLine("лимит ${VoiceTimer.LIMIT_SECONDS} с, отправлено")
        stopRecording()
    }

    override fun onPause () {
        super.onPause()
        // a screen that goes away must not keep the microphone: the take is finished and parked
        holding = false
        if (recorder.isRecording) stopRecording()
        VoiceOutbox.onEvent = null
    }

    override fun onResume () {
        super.onResume()
        // coming back to a screen that was in the background: the queue kept the lines, drain them
        VoiceInbox.onPosted = { runOnUiThread { drainInbox() } }
        drainInbox()
    }

    override fun onDestroy () {
        super.onDestroy()
        ticker.removeCallbacksAndMessages(null)
        recorder.cancel()
        VoiceInbox.onPosted = null
    }
}
