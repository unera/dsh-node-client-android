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
import ru.uvw.dshnc.logic.VoiceOutbox
import ru.uvw.dshnc.store.TokenStore

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

    private val permissionRequest = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startRecording() else show("без разрешения на микрофон говорить не получится")
    }

    override fun onCreate (savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TokenStore(this)
        recorder = Recorder(cacheDir)
        mode = VoiceMode.of(store.voiceMode)
        setContentView(build())
        VoiceOutbox.onEvent = { event, recording -> runOnUiThread { onOutbox(event, recording?.seconds) } }
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
        talk.setBackgroundColor(Color.parseColor("#dc2626"))
        cancel.visibility = View.VISIBLE
        show(if (VoiceOutbox.isWaiting()) "говорите — хост слушает" else "говорите")
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
        addLine("вы · ${"%.1f".format(take.seconds)} с")
        VoiceOutbox.offer(VoiceOutbox.Recording(take.file, take.seconds, mode.wire))
        renderIdle()
        show(if (VoiceOutbox.isWaiting()) "отправляю…" else "записано — жду запроса от хоста")
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

    /** A line in the conversation: the person's take, or later the host's answer. */
    private fun addLine (text: String) {
        chat.addView(TextView(this).apply {
            this.text = text
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
        })
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

    private fun show (text: String) {
        hint.text = text
    }

    private fun onOutbox (event: VoiceOutbox.Event, seconds: Double?) {
        when (event) {
            VoiceOutbox.Event.RECORDED -> show("записано ${"%.1f".format(seconds ?: 0.0)} с — жду запроса от хоста")
            VoiceOutbox.Event.TAKEN -> {
                if (seconds != null) addLine("→ отправлено ${"%.1f".format(seconds)} с")
                show("отправлено")
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
        show("говорите · ${"%.1f".format(seconds)} с")
        if (mode == VoiceMode.TOGGLE) renderModeHeaderOnly()
        ticker.postDelayed({ tick(elapsed + 1) }, 100)
    }

    override fun onPause () {
        super.onPause()
        // a screen that goes away must not keep the microphone: the take is finished and parked
        holding = false
        if (recorder.isRecording) stopRecording()
        VoiceOutbox.onEvent = null
    }

    override fun onDestroy () {
        super.onDestroy()
        ticker.removeCallbacksAndMessages(null)
        recorder.cancel()
    }
}
