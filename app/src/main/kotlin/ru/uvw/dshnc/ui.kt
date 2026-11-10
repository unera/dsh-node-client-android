package ru.uvw.dshnc

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shared pieces of styling in the manner of the Android system settings: a screen title
 * with a back arrow, group headings and "name + explanation" items.
 *
 * Kept separately so that the main screen, the menu and the sections look the same
 * and so that a style edit happens in one place.
 */
object Ui {
    fun screen (activity: Activity, title: String, onBack: (() -> Unit)?): LinearLayout {
        val density = activity.resources.displayMetrics.density
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
        }
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            if (onBack != null) {
                addView(TextView(activity).apply {
                    text = "←"
                    textSize = 22f
                    setTextColor(Color.WHITE)
                    setPadding(0, 0, (16 * density).toInt(), 0)
                    setOnClickListener { onBack() }
                })
            }
            addView(TextView(activity).apply {
                text = title
                textSize = 21f
                setTextColor(Color.WHITE)
            })
        })
        return root
    }

    /** Group heading: bold, separated by padding on top. */
    fun group (activity: Activity, text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.parseColor("#e5e7eb"))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        val density = activity.resources.displayMetrics.density
        setPadding((20 * density).toInt(), (26 * density).toInt(), (20 * density).toInt(), (10 * density).toInt())
    }

    /** An item: the name large, the explanation small and muted. */
    fun item (activity: Activity, title: String, subtitle: String?, onClick: (() -> Unit)?): LinearLayout {
        val density = activity.resources.displayMetrics.density
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
            addView(TextView(activity).apply {
                text = title
                textSize = 18f
                setTextColor(if (onClick != null) Color.WHITE else Color.parseColor("#8a8a8a"))
            })
            if (!subtitle.isNullOrBlank()) {
                addView(TextView(activity).apply {
                    text = subtitle
                    textSize = 14f
                    setTextColor(Color.parseColor("#9ca3af"))
                    setPadding(0, (2 * density).toInt(), 0, 0)
                })
            }
        }
    }

    /** A button inside an item: dark background and extra height for a finger. */
    fun button (activity: Activity, text: String, onClick: () -> Unit): android.widget.Button {
        val density = activity.resources.displayMetrics.density
        return android.widget.Button(activity).apply {
            this.text = text
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1f1f1f"))
            minHeight = (96 * density).toInt()
            setPadding((20 * density).toInt(), (24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt())
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins((20 * density).toInt(), (6 * density).toInt(), (20 * density).toInt(), (6 * density).toInt())
            }
        }
    }

    /**
     * A three-line item: the name, the state on its own line and a permanent
     * explanation. The explanation does not depend on the state — it explains why the
     * item is needed, not what is currently on.
     */
    fun itemEx (
        activity: Activity,
        title: String,
        state: String,
        stateColor: Int,
        help: String,
        onClick: (() -> Unit)?,
    ): LinearLayout {
        val density = activity.resources.displayMetrics.density
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
            addView(TextView(activity).apply {
                text = title
                textSize = 18f
                setTextColor(Color.WHITE)
            })
            addView(TextView(activity).apply {
                text = state
                textSize = 15f
                setTextColor(stateColor)
                setPadding(0, (4 * density).toInt(), 0, 0)
            })
            if (help.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = help
                    textSize = 13f
                    setTextColor(Color.parseColor("#8b8b8b"))
                    setPadding(0, (6 * density).toInt(), 0, 0)
                })
            }
        }
    }

    /** State colour: on is green, off is red. */
    fun stateColor (on: Boolean): Int = Color.parseColor(if (on) "#86efac" else "#f87171")

    /** A thin line between items. */
    fun divider (activity: Activity): View {
        val density = activity.resources.displayMetrics.density
        return View(activity).apply {
            setBackgroundColor(Color.parseColor("#2a2a2a"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt())
                .apply { leftMargin = (20 * density).toInt() }
        }
    }
}
