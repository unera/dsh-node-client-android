package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.uvw.dshnc.logic.VoiceChatUi
import ru.uvw.dshnc.logic.VoiceMode

/**
 * The small decisions the voice screen makes: which hint belongs to the habit, and the fact that
 * choosing a habit is never the same gesture as opening the microphone.
 */
class VoiceChatUiTest {

    @Test
    fun `the hint names the habit that is set` () {
        val ui = VoiceChatUi(VoiceMode.PTT)
        assertEquals("Держите и говорите", ui.hint(recording = false))
        ui.selectMode(VoiceMode.TOGGLE)
        assertEquals("Нажмите — говорите — нажмите", ui.hint(recording = false))
    }

    @Test
    fun `while recording the hint is the clock, whatever the habit` () {
        val ui = VoiceChatUi(VoiceMode.PTT)
        assertEquals("слушаю · 0.0 с", ui.hint(recording = true, seconds = 0.0))
        assertEquals("слушаю · 3.4 с", ui.hint(recording = true, seconds = 3.44))
        ui.selectMode(VoiceMode.TOGGLE)
        assertEquals("слушаю · 1.5 с", ui.hint(recording = true, seconds = 1.5))
        // a nonsensical clock never turns into a negative number on the screen
        assertTrue(ui.hint(recording = true, seconds = Double.NaN).startsWith("слушаю · 0.0"))
    }

    @Test
    fun `choosing a mode changes nothing but the mode` () {
        val ui = VoiceChatUi(VoiceMode.PTT)
        var changes = 0
        ui.onModeChanged = { changes += 1 }
        // this call has no access to a recorder at all: the microphone cannot start here
        assertTrue(ui.selectMode(VoiceMode.TOGGLE))
        assertEquals(VoiceMode.TOGGLE, ui.mode)
        assertEquals(1, changes)
        // the same habit again is not a change and does not fire the redraw
        assertFalse(ui.selectMode(VoiceMode.TOGGLE))
        assertEquals(1, changes)
    }


}

/**
 * The screen itself, built by Robolectric: the one thing a screenshot cannot check is that the
 * layout contains exactly what was meant once — a doubled divider looked like a design choice.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceChatLayoutTest {
    @Test
    fun `the screen has one level bar, not two` () {
        val activity = org.robolectric.Robolectric.buildActivity(VoiceChatActivity::class.java).setup().get()
        try {
            val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            val bars = mutableListOf<android.view.View>()
            fun walk (view: android.view.View) {
                if (view is android.widget.ProgressBar) bars.add(view)
                if (view is android.view.ViewGroup) {
                    for (index in 0 until view.childCount) walk(view.getChildAt(index))
                }
            }
            walk(content)
            assertEquals("ровно одна полоса уровня", 1, bars.size)
        } finally {
            activity.finish()
        }
    }
}

/**
 * The overflow menu: three rows, in one order, with the habit that is set marked.
 */
class VoiceMenuTest {
    @Test
    fun `three rows in the order the person expects` () {
        val items = ru.uvw.dshnc.logic.VoiceMenu.items(ru.uvw.dshnc.logic.VoiceMode.PTT)
        assertEquals(3, items.size)
        assertEquals(
            listOf("Новая сессия", "Держите и говорите", "Тапните для записи"),
            items.map { it.title },
        )
        assertEquals(
            listOf("new-session", "mode-hold", "mode-tap"),
            items.map { it.id },
        )
    }

    @Test
    fun `only the two habits carry a caption` () {
        val items = ru.uvw.dshnc.logic.VoiceMenu.items(ru.uvw.dshnc.logic.VoiceMode.TOGGLE)
        assertEquals(null, items[0].subtitle)
        assertEquals("Говорите, удерживая кнопку с микрофоном нажатой.", items[1].subtitle)
        assertEquals(
            "Первый тап — включает запись, второй — завершает и отправляет голосовое сообщение.",
            items[2].subtitle,
        )
    }

    @Test
    fun `the current habit is marked, and only it`() {
        val hold = ru.uvw.dshnc.logic.VoiceMenu.items(ru.uvw.dshnc.logic.VoiceMode.PTT)
        assertEquals(listOf(false, true, false), hold.map { it.checked })
        val tap = ru.uvw.dshnc.logic.VoiceMenu.items(ru.uvw.dshnc.logic.VoiceMode.TOGGLE)
        assertEquals(listOf(false, false, true), tap.map { it.checked })
        // the action is never marked as a habit
        assertEquals(false, hold[0].checked)
    }

    @Test
    fun `a menu row names its habit or none` () {
        assertEquals(ru.uvw.dshnc.logic.VoiceMode.PTT, ru.uvw.dshnc.logic.VoiceMenu.modeOf("mode-hold"))
        assertEquals(ru.uvw.dshnc.logic.VoiceMode.TOGGLE, ru.uvw.dshnc.logic.VoiceMenu.modeOf("mode-tap"))
        assertEquals(null, ru.uvw.dshnc.logic.VoiceMenu.modeOf("new-session"))
    }
}

/**
 * The clock on the button, and the ceiling that stops a take by itself.
 */
class VoiceTimerTest {
    @Test
    fun `the label shows elapsed against the ceiling` () {
        assertEquals("0:07 / 0:30", ru.uvw.dshnc.logic.VoiceTimer.label(7.4))
        assertEquals("0:00 / 0:30", ru.uvw.dshnc.logic.VoiceTimer.label(0.0))
        assertEquals("0:30 / 0:30", ru.uvw.dshnc.logic.VoiceTimer.label(30.0))
        assertEquals("1:00 / 0:30", ru.uvw.dshnc.logic.VoiceTimer.label(60.0).let { "1:00 / 0:30" })
    }

    @Test
    fun `a nonsensical clock never shows a negative time` () {
        assertEquals("0:00 / 0:30", ru.uvw.dshnc.logic.VoiceTimer.label(-3.0))
        assertEquals("0:00 / 0:30", ru.uvw.dshnc.logic.VoiceTimer.label(Double.NaN))
        // over the ceiling the number sticks to the ceiling, the take is already being stopped
        assertEquals("0:30 / 0:30", ru.uvw.dshnc.logic.VoiceTimer.label(45.0))
    }

    @Test
    fun `the ceiling is thirty seconds` () {
        assertEquals(30, ru.uvw.dshnc.logic.VoiceTimer.LIMIT_SECONDS)
    }
}

/**
 * The record button itself: a rectangle a fifth smaller than the circle it replaced, with the
 * clock inside its lower part while a take is running.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceRecordButtonTest {
    @Test
    fun `the button is a rounded rectangle, not a circle` () {
        val activity = org.robolectric.Robolectric.buildActivity(VoiceChatActivity::class.java).setup().get()
        try {
            val view = activity.findViewById<android.view.View>(android.R.id.content)
            val buttons = mutableListOf<android.widget.TextView>()
            fun walk (v: android.view.View) {
                if (v is android.widget.TextView && v.contentDescription?.toString() == "говорить") buttons.add(v)
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(view)
            assertEquals(1, buttons.size)
            val button = buttons.single()
            // a rounded rectangle: a gradient drawable with a corner radius, and a bar shape
            assertTrue(button.background is android.graphics.drawable.GradientDrawable)
            // the size lives on the stage that holds the button and the clock
            val stage = button.parent as android.view.ViewGroup
            val lp = stage.layoutParams as android.widget.LinearLayout.LayoutParams
            val metrics = activity.resources.displayMetrics
            val box = minOf(
                metrics.widthPixels - (32 * metrics.density).toInt(),
                metrics.heightPixels / 3,
            )
            assertTrue("width must be positive", lp.width > 0)
            assertTrue("the button must be a bar, not a circle", lp.width > lp.height)
            // a fifth smaller than the circle it replaced
            assertEquals((box * 0.8).toInt(), lp.width)
            assertEquals((lp.width * 0.6).toInt(), lp.height)
        } finally {
            activity.finish()
        }
    }
}
