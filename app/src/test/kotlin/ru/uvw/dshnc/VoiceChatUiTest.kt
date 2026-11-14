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


    @Test
    fun `both mode buttons stand above the circle, symmetric about its centre` () {
        val width = 1080
        val diameter = 720
        val size = 198   // 66 dp at 3x
        val overlap = 78 // 26 dp at 3x
        val boxes = VoiceChatUi(ru.uvw.dshnc.logic.VoiceMode.PTT).modeButtonBoxes(width, diameter, size, overlap)
        assertEquals(2, boxes.size)
        val left = boxes[0]
        val right = boxes[1]
        // the same line, hanging over the circle by the overlap
        assertEquals(overlap, left.top)
        assertEquals(overlap, right.top)
        // each square is centred on one edge of the circle, not on its centre
        assertEquals(width / 2 - diameter / 2, left.left + size / 2)
        assertEquals(width / 2 + diameter / 2, right.left + size / 2)
        // and the pair is symmetric about the middle of the screen
        assertEquals(width, left.left + right.left + size)
        // they do not sit on top of each other
        assertTrue(right.left > left.left + size)
        // and they hang over the circle: the squares reach further down than its top edge
        assertTrue("the buttons must overlap the circle", overlap > size / 4)
        assertTrue(left.left + size > 0)
        assertTrue(right.left + size <= width)
    }

    @Test
    fun `the mode buttons go quiet while a take is running` () {
        val ui = VoiceChatUi()
        assertTrue(ui.modeButtonsEnabled(recording = false))
        assertEquals(1f, ui.modeButtonsAlpha(recording = false), 0.001f)
        assertFalse(ui.modeButtonsEnabled(recording = true))
        assertEquals(0.4f, ui.modeButtonsAlpha(recording = true), 0.001f)
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
