package ru.uvw.dshnc

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.uvw.dshnc.api.AskPending
import ru.uvw.dshnc.api.DialogHub

/**
 * The active questions, when there is more than one.
 *
 * Two windows stacked on each other hide one question behind the other, so from the second
 * question onwards the phone shows a list instead: every open question is one row, a tap
 * opens that very question, and the list disappears once the last one is answered. A single
 * question is still shown directly as a dialog — a list of one would be a detour.
 */
class DialogListActivity : AppCompatActivity() {
    override fun onCreate (state: Bundle?) {
        super.onCreate(state)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        DialogHub.attachList { runOnUiThread { render() } }
        render()
    }

    /** A question arrived (or was answered) while the list is on top: show the new registry. */
    override fun onNewIntent (intent: android.content.Intent?) {
        super.onNewIntent(intent)
        render()
    }

    override fun onDestroy () {
        DialogHub.detachList()
        super.onDestroy()
    }

    /** The list follows the registry: an answered question leaves it, an empty list closes. */
    private fun render () {
        val items = DialogHub.active()
        if (items.isEmpty()) {
            finish()
            return
        }
        setContentView(build(items))
    }

    private fun build (items: List<AskPending>): FrameLayout {
        val density = resources.displayMetrics.density
        fun dp (value: Int): Int = (value * density).toInt()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#b3000000"))
            setOnClickListener { finish() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1c1c1c"))
                cornerRadius = dp(18).toFloat()
            }
            setPadding(dp(18), dp(16), dp(18), dp(12))
            isClickable = true
        }
        card.addView(TextView(this).apply {
            text = getString(R.string.dialog_list_title)
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        val scroll = ScrollView(this)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (item in items) {
            rows.addView(row(item) { dp(it) })
        }
        scroll.addView(rows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(10), dp(10), dp(10), dp(10))
        })
        return root
    }

    /** One question: the style colour as a stripe, its title and the beginning of the text. */
    private fun row (item: AskPending, dp: (Int) -> Int): LinearLayout {
        val request = item.request
        val first = request.content.lineSequence().firstOrNull().orEmpty()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isClickable = true
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { open(item.seq) }
            addView(android.view.View(this@DialogListActivity).apply {
                setBackgroundColor(DialogHub.styleColor(request.style))
                layoutParams = LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT)
            })
            addView(LinearLayout(this@DialogListActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(8), dp(8))
                addView(TextView(this@DialogListActivity).apply {
                    text = request.title.ifBlank { first.ifBlank { getString(R.string.dialog_default_title) } }
                    textSize = 17f
                    setTextColor(Color.WHITE)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(this@DialogListActivity).apply {
                    text = first
                    textSize = 14f
                    setTextColor(Color.parseColor("#9ca3af"))
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@DialogListActivity).apply {
                text = "\u203A"
                textSize = 22f
                setTextColor(Color.parseColor("#9ca3af"))
                setPadding(dp(8), 0, dp(4), 0)
            })
        }
    }

    /** Open exactly this question; the list stays behind and follows the registry. */
    private fun open (seq: Long) {
        startActivity(android.content.Intent(this, AskActivity::class.java).putExtra(AskActivity.EXTRA_SEQ, seq))
    }
}
