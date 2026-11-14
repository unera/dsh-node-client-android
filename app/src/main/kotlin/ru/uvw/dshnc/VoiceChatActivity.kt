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
import ru.uvw.dshnc.logic.VoiceBubble
import ru.uvw.dshnc.logic.VoiceChat
import ru.uvw.dshnc.logic.VoiceChatUi
import ru.uvw.dshnc.logic.VoiceMenu
import ru.uvw.dshnc.logic.VoiceSession
import ru.uvw.dshnc.logic.VoiceStatus
import ru.uvw.dshnc.logic.VoiceTimer
import ru.uvw.dshnc.logic.VoiceChatBus
import ru.uvw.dshnc.logic.VoiceChatLog
import ru.uvw.dshnc.logic.VoiceFailure
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
    /** One line for the model's current step: it replaces the previous step, it does not pile up. */
    private lateinit var progressLine: TextView
    /** The exchange whose steps are being shown: its answer clears the line. */
    private var progressExchange = ""
    private lateinit var scroller: ScrollView
    private lateinit var meter: ProgressBar
    private lateinit var hint: TextView
    private lateinit var talk: TextView
    /**
     * The record button's background.
     *
     * One place decides its shape: the button is a rounded rectangle, and it was drawn as an oval
     * under the finger because two of the three places that painted it kept the old circle. Idle is
     * blue, a running take is red; nothing else changes.
     */
    private fun voiceButton (color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 24 * resources.displayMetrics.density
        setColor(color)
    }

    /** The clock on the record button: `0:07 / 0:30`. */
    private lateinit var timerLine: TextView
    /** The screen title: it carries the assistant's name once the host says who answers. */
    private lateinit var title: TextView
    /** Who answers, as the host last said: the header uses it once there is a line to introduce. */
    private var assistantWho: VoiceChat.Whom? = null
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
    /** The last outcome, shown in the fixed status line until the person does something new. */
    private var status: String? = null

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
        title = header.getChildAt(header.childCount - 1) as TextView
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
            background = voiceButton(Color.parseColor("#2563eb"))
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
        status = null
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
            addLine(VoiceSession.NO_ANSWER)
            return
        }
        Thread({
            val outcome = VoiceEvents(base, token).newSession()
            runOnUiThread {
                when (outcome) {
                    is VoiceSession.Result.Accepted -> {
                        VoiceInbox.clear()
                        VoiceChatBus.clear()
                        hideProgress()
                        addLine("новая сессия")
                    }
                    // the server's own words, not a shrug: the reason has to be readable on screen
                    is VoiceSession.Result.Failed -> addLine(outcome.reason)
                }
            }
        }, "dshnc-voice-session").start()
    }

    /**
     * The line above the button: the habit, the clock, or the last outcome.
     *
     * A status («принято хостом», «слишком коротко») stays until the person starts a new take or
     * picks another habit: replacing it at once with the habit hint made it flash and vanish.
     */
    /**
     * Redraw the header.
     *
     * An empty conversation shows the name of the screen; once there is a line and the host has said
     * who answers, the header becomes that assistant — an emoji and a name, large.
     */
    private fun refreshTitle () {
        title.text = VoiceChat.title(assistantWho, VoiceChatBus.snapshot().isNotEmpty())
    }

    private fun refreshHint (seconds: Double) {
        if (recorder.isRecording) {
            hint.text = ui.hint(recording = true, seconds = seconds)
            return
        }
        if (status != null) return
        hint.text = ui.hint(recording = false)
    }

    /**
     * The status, in the one place it lives: the fixed line above the level bar.
     *
     * It used to be a view inside the conversation, so it wandered with the text and could appear
     * after the answer instead of above the button. There is exactly one such line now, and it is
     * outside the scrolling area.
     */
    private fun setState (text: String) {
        status = text
        hint.text = text
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
        status = null
        talk.background = voiceButton(Color.parseColor("#dc2626"))
        refreshHint(0.0)
        setState("слушаю")
        show("говорите")
        // one clock per take: a second press must not leave the previous ticker running
        ticker.removeCallbacksAndMessages(null)
        tick(0)
    }

    private fun stopRecording () {
        recording = false
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
                        val caption = VoiceStatus.accepted(outcome.seconds ?: take.seconds)
                        val shown = VoiceChatBus.note(id, caption)
                        setState(if (shown) "отправлено" else "готов")
                        renderChat()
                        // the path is for the log, never for the screen
                        store.addEvent("voice принято", VoiceStatus.logDetails(caption, outcome.path))
                        show(caption)
                    }
                    is VoiceUpload.Result.Failed -> refuse(take, outcome.reason, outcome.status)
                }
            }
        }, "dshnc-voice-send").start()
    }

    /**
     * The take did not land: keep it where the host can still pick it up, and say why in words the
     * person can act on.
     *
     * A refused request gets the server's own sentence back; a broken connection gets «плохая
     * связь» and a quiet second line saying the recording is still on the phone.
     */
    private fun refuse (take: Recorder.Result, reason: String, status: Int? = null) {
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
        // the path stays in the log: on screen it read like a fault
        store.addEvent("voice не принято", VoiceStatus.logDetails("$status $reason", parked.absolutePath))
        addLine(VoiceFailure.line(status, reason))
        VoiceFailure.note(status, reason)?.let { note -> addLine(note) }
        show(VoiceFailure.line(status, reason))
    }

    private fun renderIdle () {
        meter.progress = 0
        talk.background = voiceButton(Color.parseColor("#2563eb"))
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
                    VoiceChatBus.putQuiet(item.id, item.role, item.text, item.who?.name)
                    item.who?.let { who ->
                        if (who != assistantWho) {
                            assistantWho = who
                            refreshTitle()
                        }
                    }
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
        var previousId: String? = null
        for (entry in VoiceChatBus.snapshot()) {
            chat.addView(view(entry, previousId))
            previousId = entry.id
        }
        if (progressLine.visibility == View.VISIBLE) {
            (progressLine.parent as? android.view.ViewGroup)?.removeView(progressLine)
            chat.addView(progressLine)
        }
        refreshTitle()
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * One line of the conversation: a blue bubble on the right for the person, a dark one on the
     * left for the answer, and nothing but small grey text for the screen's own notes.
     */
    private fun view (entry: VoiceChatLog.Entry, previousId: String?): View {
        val density = resources.displayMetrics.density
        val bubbleMaxWidth = VoiceBubble.maxWidthPx(resources.displayMetrics.widthPixels)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = when (VoiceBubble.alignment(entry.role)) {
                "end" -> Gravity.END
                else -> Gravity.START
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (VoiceBubble.gapDp(previousId, entry.id) * density).toInt() }
        }

        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (entry.role != VoiceChat.Role.SYSTEM) {
                setPadding(
                    (VoiceBubble.PADDING_H_DP * density).toInt(),
                    (VoiceBubble.PADDING_V_DP * density).toInt(),
                    (VoiceBubble.PADDING_H_DP * density).toInt(),
                    (VoiceBubble.PADDING_V_DP * density).toInt(),
                )
                VoiceBubble.background(entry.role)?.let { color ->
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = VoiceBubble.RADIUS_DP * density
                        setColor(Color.parseColor(color))
                    }
                }
            } else {
                setPadding(0, 0, 0, 0)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val size = if (entry.role == VoiceChat.Role.SYSTEM) 13f else 18f
        val color = Color.parseColor(VoiceBubble.textColor(entry.role))
        // prose is drawn as it came; fenced runs are monospaced on a field darker than the bubble
        for (segment in VoiceChat.segments(entry.text)) {
            bubble.addView(TextView(this).apply {
                text = segment.text
                textSize = size
                setTextColor(color)
                maxWidth = bubbleMaxWidth
                if (segment.code) {
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size - 3f)
                    setBackgroundColor(Color.parseColor(VoiceBubble.CODE_BACKGROUND))
                    setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
                }
            })
        }
        // captions live inside the bubble: they belong to the remark above them
        entry.note?.let { note -> bubble.addView(caption(note)) }
        entry.steps?.takeIf { it > 0 }?.let { count -> bubble.addView(caption("шагов: $count")) }

        row.addView(bubble)
        return row
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
        status = text
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
