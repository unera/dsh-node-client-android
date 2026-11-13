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
import ru.uvw.dshnc.logic.Recorder
import ru.uvw.dshnc.logic.VoiceMode
import ru.uvw.dshnc.api.VoiceEvents
import ru.uvw.dshnc.logic.VoiceChat
import ru.uvw.dshnc.logic.VoiceChatBus
import ru.uvw.dshnc.logic.VoiceChatLog
import ru.uvw.dshnc.logic.VoiceFiles
import ru.uvw.dshnc.logic.VoiceOutbox
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
    private lateinit var scroller: ScrollView
    private lateinit var meter: ProgressBar
    private lateinit var hint: TextView
    private lateinit var talk: TextView
    private lateinit var cancel: TextView
    private lateinit var modePtt: TextView
    private lateinit var modeToggle: TextView

    private val ticker = Handler(Looper.getMainLooper())
    private var mode = VoiceMode.PTT
    /** True while the person's finger is down in the radio mode: a hold, not a press. */
    private var holding = false
    /** When the microphone opened, for the diagnostics on the wire: 0 means "not known". */
    private var recordStartedAt = 0L

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
        mode = VoiceMode.of(store.voiceMode)
        setContentView(build())
        VoiceOutbox.onEvent = { event, recording -> runOnUiThread { onOutbox(event, recording?.seconds) } }
        VoiceChatBus.onChanged = { runOnUiThread { renderChat() } }
        renderChat()
        renderMode()
        show("готов")
        tick(0)
    }

    /** The whole screen, built the way the rest of the app is: plain views, no XML. */
    private fun build (): View {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val root = Ui.screen(this, "Голосовой чат") { finish() }
        root.setPadding(0, 0, 0, 0)

        // the two habits of the button, as a pair of chips
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(pad, 0, pad, (8 * density).toInt())
            modePtt = chip("держать")
            modeToggle = chip("переключение")
            addView(modePtt)
            addView(modeToggle)
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
        scroller = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(chat)
        }
        root.addView(scroller)

        hint = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9ca3af"))
            setPadding(pad, (4 * density).toInt(), pad, (4 * density).toInt())
        }
        root.addView(hint)

        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (6 * density).toInt())
                .apply { setMargins(pad, 0, pad, 0) }
        }
        root.addView(meter)

        // the bottom third: one button, large enough to hit without looking
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels / 3),
            )
            setPadding(pad, (8 * density).toInt(), pad, pad)
        }
        talk = TextView(this).apply {
            text = "🎤"
            textSize = 56f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 32 * density
                setColor(Color.parseColor("#2563eb"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
            contentDescription = "говорить"
        }
        bottom.addView(talk)
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
        bottom.addView(cancel)
        root.addView(bottom)

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

    /** One mode chip: the chosen one is filled, the other is a quiet outline. */
    private fun chip (title: String): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = title
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding((18 * density).toInt(), (8 * density).toInt(), (18 * density).toInt(), (8 * density).toInt())
            setOnClickListener {
                mode = if (title == VoiceMode.PTT.title) VoiceMode.PTT else VoiceMode.TOGGLE
                store.voiceMode = mode.wire
                renderMode()
            }
        }
    }

    private fun renderMode () {
        fun paint (view: TextView, active: Boolean) {
            view.setTextColor(if (active) Color.WHITE else Color.parseColor("#9ca3af"))
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24 * resources.displayMetrics.density
                setColor(if (active) Color.parseColor("#374151") else Color.TRANSPARENT)
            }
        }
        paint(modePtt, mode == VoiceMode.PTT)
        paint(modeToggle, mode == VoiceMode.TOGGLE)
        talk.text = if (mode == VoiceMode.PTT) "🎤" else if (recorder.isRecording) "⏹" else "🎤"
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
        recorder.maxSeconds = VoiceOutbox.maxSeconds
        val error = recorder.start()
        if (error != null) {
            show(error)
            return
        }
        recordStartedAt = System.currentTimeMillis()
        talk.setBackgroundColor(Color.parseColor("#dc2626"))
        cancel.visibility = View.VISIBLE
        setState("слушаю")
        show("говорите")
        // one clock per take: a second press must not leave the previous ticker running
        ticker.removeCallbacksAndMessages(null)
        tick(0)
    }

    private fun stopRecording () {
        cancel.visibility = View.GONE
        val take = recorder.stop()
        if (take == null) {
            renderIdle()
            show("слишком коротко, не отправлено")
            return
        }
        val stoppedAt = System.currentTimeMillis()
        val exchange = VoiceUpload.newId()
        // the take appears at once and carries its exchange id: the transcript that arrives later
        // replaces this very line instead of standing next to it
        VoiceChatBus.recording(exchange, "вы · ${"%.1f".format(take.seconds)} с")
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
                        setState("принято хостом · ${"%.1f".format(outcome.seconds ?: take.seconds)} с")
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
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 32 * resources.displayMetrics.density
            setColor(Color.parseColor("#2563eb"))
        }
        renderModeHeaderOnly()
    }

    /** The icon depends on the mode and on whether the microphone is open right now. */
    private fun renderModeHeaderOnly () {
        talk.text = if (mode == VoiceMode.TOGGLE && recorder.isRecording) "⏹" else "🎤"
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
        if (stateLine.parent == null) chat.addView(stateLine)
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

    /** One line: the person flush left, the answer indented, service notes small and grey. */
    private fun view (entry: VoiceChatLog.Entry): TextView {
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
        return TextView(this).apply {
            text = entry.text
            textSize = size
            setTextColor(color)
            setPadding(indent, (4 * density).toInt(), 0, (4 * density).toInt())
        }
    }

    /** A note of the screen's own: small and grey, never mistaken for a remark. */
    private fun addLine (text: String) {
        VoiceChatBus.system(text)
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
            meter.progress = 0
            return
        }
        meter.progress = (recorder.level * 100).toInt().coerceIn(0, 100)
        val seconds = elapsed / 10.0
        show("слушаю · ${"%.1f".format(seconds)} с")
        if (mode == VoiceMode.TOGGLE) renderModeHeaderOnly()
        ticker.postDelayed({ tick(elapsed + 1) }, 100)
    }

    override fun onPause () {
        super.onPause()
        // a screen that goes away must not keep the microphone: the take is finished and parked
        holding = false
        if (recorder.isRecording) stopRecording()
        VoiceOutbox.onEvent = null
        VoiceChatBus.onChanged = null
    }

    override fun onDestroy () {
        super.onDestroy()
        ticker.removeCallbacksAndMessages(null)
        recorder.cancel()
    }
}
