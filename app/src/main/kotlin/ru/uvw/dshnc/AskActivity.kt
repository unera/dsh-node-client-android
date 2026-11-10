package ru.uvw.dshnc

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.uvw.dshnc.api.AskPending
import ru.uvw.dshnc.api.DialogHub
import ru.uvw.dshnc.logic.DialogStyle

/**
 * The question a `dialog.ask` command shows.
 *
 * A window of its own, not a `Dialog`: the answer must survive a rotated screen and has to
 * be reachable while the app is in the background, which a dialog owned by another activity
 * cannot promise. The window is transparent, keeps no history and stays out of the recent
 * list — it is not a screen of the app, it is a question.
 *
 * A press answers with the button's action. Closing the window — back, a tap on the dimmed
 * area, the system gesture — answers nothing: the question stays open and comes back from
 * its notification or from the list of active questions.
 */
class AskActivity : AppCompatActivity() {
    private var open: AskPending? = null
    private var answered = false

    override fun onCreate (state: Bundle?) {
        super.onCreate(state)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        bind(intent.getLongExtra(EXTRA_SEQ, -1L))
    }

    /**
     * Another question was asked while this window is already on top.
     *
     * `singleTop` turns the tap on a notification of a second question into this call, so a
     * window must bind to the question it was opened for — otherwise it would keep showing
     * the one it was built with.
     */
    override fun onNewIntent (intent: android.content.Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        bind(intent.getLongExtra(EXTRA_SEQ, -1L))
    }

    /** Show the question with this number, or close the window when there is none. */
    private fun bind (seq: Long) {
        val previous = open
        if (previous != null && previous.seq != seq) DialogHub.detach(previous.seq)
        val current = DialogHub.current(seq)
        if (current == null) {
            // the question is already answered or cancelled: there is nothing to show
            finish()
            return
        }
        open = current
        answered = false
        // the waiting thread takes the window down when the question ends from the outside
        DialogHub.attach(seq) { runOnUiThread { finish() } }
        setContentView(build(current))
    }

    override fun onDestroy () {
        open?.let { DialogHub.detach(it.seq) }
        super.onDestroy()
    }

    @Deprecated("the window is closed by the system back gesture as well")
    override fun onBackPressed () {
        hide()
    }

    /**
     * Back, or a tap on the dimmed area: the window goes away and nothing else happens.
     *
     * The question stays open — its notification stays in the shade and, when there are
     * several, its row stays in the list. Only the command's timeout or the server's
     * `#INTERNAL.CANCEL` answers `cancel`.
     */
    private fun hide () {
        finish()
    }

    /** A button press: its `action` is the answer. */
    private fun press (index: Int) {
        if (answered) return
        answered = true
        open?.let { DialogHub.press(it.seq, index) }
        finish()
    }

    private fun build (open: AskPending): View {
        val density = resources.displayMetrics.density
        fun dp (value: Int): Int = (value * density).toInt()
        val request = open.request

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#b3000000"))
            setOnClickListener { hide() }
        }
        // the overall style is the fill of the card, not a border: the whole question is
        // grey, blue or red at a glance
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(DialogHub.styleColor(request.style))
                cornerRadius = dp(18).toFloat()
            }
            setPadding(dp(20), dp(18), dp(20), dp(14))
            // the card swallows taps: only the area around it dismisses the question
            isClickable = true
        }
        if (request.title.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = request.title
                textSize = 20f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dp(10))
            })
        }
        val body = TextView(this).apply {
            this.text = request.content
            textSize = 16f
            setTextColor(Color.WHITE)
            setLineSpacing(dp(3).toFloat(), 1f)
            movementMethod = ScrollingMovementMethod()
        }
        // long text scrolls inside the card instead of pushing the buttons off the screen
        val scroll = CappedScroll(this, (resources.displayMetrics.heightPixels * 0.55).toInt()).apply {
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        request.buttons.forEachIndexed { index, button ->
            val label = if (button.icon.isBlank()) button.title else "${button.icon} ${button.title}"
            buttons.addView(Button(this).apply {
                text = label
                textSize = 16f
                isAllCaps = false
                minHeight = dp(52)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setTextColor(buttonInk)
                // a button is filled by its own style; a dark border separates it from the
                // card, and the dark label keeps the contrast on a light fill
                background = GradientDrawable().apply {
                    setColor(styleFill(button.style))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), Color.parseColor("#33000000"))
                }
                setOnClickListener { press(index) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, dp(4), 0, dp(4)) }
            })
        }
        card.addView(buttons)
        root.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(10), dp(10), dp(10), dp(10))
        })
        return root
    }

    /** The button fill by its style: grey, blue or red, the same set the card uses. */
    /** Buttons are light against the dark card, so their labels are dark, not white. */
    private fun styleFill (style: DialogStyle): Int = when (style) {
        DialogStyle.WARNING -> Color.parseColor("#93c5fd")
        DialogStyle.DANGER -> Color.parseColor("#fca5a5")
        DialogStyle.NORMAL -> Color.parseColor("#d1d5db")
    }

    /** Dark ink for the light buttons: white on #93c5fd is unreadable. */
    private val buttonInk = Color.parseColor("#111827")

    /** A scroll view that never grows past its cap: the buttons stay where a thumb expects them. */
    private class CappedScroll (context: Context, private val maxPx: Int) : ScrollView(context) {
        override fun onMeasure (widthSpec: Int, heightSpec: Int) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
        }
    }

    companion object {
        const val EXTRA_SEQ = "seq"
    }
}
