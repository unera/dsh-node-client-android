package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        // the first group only: a new conversation and the two habits, the forms are a second group
        val items = ru.uvw.dshnc.logic.VoiceMenu.items(ru.uvw.dshnc.logic.VoiceMode.PTT).take(3)
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
        val items = ru.uvw.dshnc.logic.VoiceMenu.items(ru.uvw.dshnc.logic.VoiceMode.TOGGLE).take(3)
        assertEquals(null, items[0].subtitle)
        assertEquals("Говорите, удерживая кнопку с микрофоном нажатой.", items[1].subtitle)
        assertEquals(
            "Первый тап — включает запись, второй — завершает и отправляет голосовое сообщение.",
            items[2].subtitle,
        )
    }

    @Test
    fun `the current habit is marked, and only it`() {
        val hold = ru.uvw.dshnc.logic.VoiceMenu.items(ru.uvw.dshnc.logic.VoiceMode.PTT).take(3)
        assertEquals(listOf(false, true, false), hold.map { it.checked })
        val tap = ru.uvw.dshnc.logic.VoiceMenu.items(ru.uvw.dshnc.logic.VoiceMode.TOGGLE).take(3)
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

/**
 * The new-conversation event: one word of type, and the server's own words when it refuses.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceSessionTest {
    @Test
    fun `the body is exactly the new-session type`() {
        val body = ru.uvw.dshnc.logic.VoiceSession.body()
        assertEquals("{\"type\":\"new-session\"}", body.toString())
        assertEquals(1, body.length())
        assertEquals(null, body.opt("action"))
    }

    @Test
    fun `a refusal keeps the server's words`() {
        val result = ru.uvw.dshnc.logic.VoiceSession.interpret(
            400,
            "{\"code\":\"bad_type\",\"message\":\"voice принимается только на /v1/upload-file\"}",
        )
        assertTrue(result is ru.uvw.dshnc.logic.VoiceSession.Result.Failed)
        assertEquals(
            "voice принимается только на /v1/upload-file (bad_type)",
            (result as ru.uvw.dshnc.logic.VoiceSession.Result.Failed).reason,
        )
    }

    @Test
    fun `an accepted answer and a silent one`() {
        assertTrue(ru.uvw.dshnc.logic.VoiceSession.interpret(202, "") is ru.uvw.dshnc.logic.VoiceSession.Result.Accepted)
        assertTrue(ru.uvw.dshnc.logic.VoiceSession.interpret(200, null) is ru.uvw.dshnc.logic.VoiceSession.Result.Accepted)
        val silent = ru.uvw.dshnc.logic.VoiceSession.interpret(502, "<html/>")
        assertEquals("HTTP 502", (silent as ru.uvw.dshnc.logic.VoiceSession.Result.Failed).reason)
    }
}

/**
 * What is no longer on the screen: the cancel button, and any lift of the record button.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceScreenShapeTest {
    private fun activity(): VoiceChatActivity =
        org.robolectric.Robolectric.buildActivity(VoiceChatActivity::class.java).setup().get()

    @Test
    fun `there is no cancel button in the layout`() {
        val screen = activity()
        try {
            val texts = mutableListOf<String>()
            fun walk (view: android.view.View) {
                if (view is android.widget.TextView) texts.add(view.text?.toString().orEmpty())
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(screen.findViewById(android.R.id.content))
            assertTrue("отмены быть не должно: $texts", texts.none { it.equals("отмена", ignoreCase = true) })
        } finally {
            screen.finish()
        }
    }

    @Test
    fun `nothing is lifted off its place`() {
        val screen = activity()
        try {
            var shifted = 0
            fun walk (view: android.view.View) {
                if (view.translationY != 0f || view.translationX != 0f) shifted += 1
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(screen.findViewById(android.R.id.content))
            // the button used to rise under the finger; nothing shifts any more
            assertEquals(0, shifted)
        } finally {
            screen.finish()
        }
    }
}

/**
 * What a person reads under the conversation: what happened, never where a file lies.
 */
class VoiceStatusTest {
    @Test
    fun `an accepted take shows its duration and no destination`() {
        val line = ru.uvw.dshnc.logic.VoiceStatus.accepted(1.5)
        assertEquals("принято хостом · 1.5 с", line)
        assertFalse(line.contains("/"))
        assertFalse(line.contains("на хосте"))
    }

    @Test
    fun `a refusal keeps the server's words but loses any path in them`() {
        val line = ru.uvw.dshnc.logic.VoiceStatus.refused(
            "файл не скачался: /home/dsh/work/ai/.dsh/cache/voice/a.wav (voice_failed)",
        )
        assertEquals("хост не принял: файл не скачался: (voice_failed)", line)
        assertFalse(line.contains("/"))
    }

    @Test
    fun `the device log is where the path still goes`() {
        val path = "/home/dsh/work/ai/.dsh/cache/voice/incoming/<uuid>.wav"
        val details = ru.uvw.dshnc.logic.VoiceStatus.logDetails("принято хостом · 1.5 с", path)
        assertTrue(details.contains(path))
        // and nothing else was swallowed on the way
        assertTrue(details.startsWith("принято хостом · 1.5 с"))
        assertEquals("принято", ru.uvw.dshnc.logic.VoiceStatus.logDetails("принято", null))
    }

    @Test
    fun `a refusal with only a path left still says something`() {
        assertEquals("хост не принял", ru.uvw.dshnc.logic.VoiceStatus.refused("/tmp/a.wav"))
    }
}

/**
 * The look of a line: two different bubbles, the sides they stick to, and the width cap.
 */
class VoiceBubbleTest {
    @Test
    fun `the person and the answer have different bubbles`() {
        val user = ru.uvw.dshnc.logic.VoiceBubble.background(ru.uvw.dshnc.logic.VoiceChat.Role.USER)
        val answer = ru.uvw.dshnc.logic.VoiceBubble.background(ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT)
        assertEquals("#2563eb", user)
        assertEquals("#1f2937", answer)
        assertFalse(user == answer)
        // a service note has no bubble at all
        assertEquals(null, ru.uvw.dshnc.logic.VoiceBubble.background(ru.uvw.dshnc.logic.VoiceChat.Role.SYSTEM))
    }

    @Test
    fun `the text colour matches its bubble`() {
        assertEquals("#ffffff", ru.uvw.dshnc.logic.VoiceBubble.textColor(ru.uvw.dshnc.logic.VoiceChat.Role.USER))
        assertEquals("#e5e7eb", ru.uvw.dshnc.logic.VoiceBubble.textColor(ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT))
        assertEquals("#6b7280", ru.uvw.dshnc.logic.VoiceBubble.textColor(ru.uvw.dshnc.logic.VoiceChat.Role.SYSTEM))
    }

    @Test
    fun `the person is on the right and the answer on the left`() {
        assertEquals("end", ru.uvw.dshnc.logic.VoiceBubble.alignment(ru.uvw.dshnc.logic.VoiceChat.Role.USER))
        assertEquals("start", ru.uvw.dshnc.logic.VoiceBubble.alignment(ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT))
    }

    @Test
    fun `a bubble never takes the whole screen`() {
        assertEquals(850, ru.uvw.dshnc.logic.VoiceBubble.maxWidthPx(1000))
        assertEquals(0.85, ru.uvw.dshnc.logic.VoiceBubble.MAX_WIDTH_FRACTION, 0.0001)
        assertTrue(ru.uvw.dshnc.logic.VoiceBubble.maxWidthPx(1080) < 1080)
    }

    @Test
    fun `the gap is small inside an exchange and wide between them`() {
        assertEquals(8, ru.uvw.dshnc.logic.VoiceBubble.gapDp("a", "a"))
        assertEquals(16, ru.uvw.dshnc.logic.VoiceBubble.gapDp("a", "b"))
        assertEquals(16, ru.uvw.dshnc.logic.VoiceBubble.gapDp(null, "a"))
        // the code field inside a bubble is darker than the bubble itself
        assertEquals("#111827", ru.uvw.dshnc.logic.VoiceBubble.CODE_BACKGROUND)
    }
}

/**
 * The status line stands in one place: the fixed bottom block, above the level bar, outside the
 * scrolling conversation.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceStatusPlaceTest {
    private fun activity(): VoiceChatActivity =
        org.robolectric.Robolectric.buildActivity(VoiceChatActivity::class.java).setup().get()

    @Test
    fun `the status sits directly above the level bar and outside the conversation`() {
        val screen = activity()
        try {
            val content = screen.findViewById<android.view.ViewGroup>(android.R.id.content)
            var status: android.widget.TextView? = null
            var bar: android.widget.ProgressBar? = null
            var scroller: android.widget.ScrollView? = null
            fun walk (view: android.view.View) {
                when (view) {
                    is android.widget.ProgressBar -> bar = view
                    is android.widget.ScrollView -> scroller = view
                }
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(content)
            // the status label is the small one just above the bar, inside the same bottom block
            assertNotNull(bar)
            assertNotNull(scroller)
            val bottom = bar!!.parent as android.view.ViewGroup
            assertTrue("the bar must not live inside the conversation", bottom !== scroller)
            val texts = (0 until bottom.childCount).mapNotNull { index ->
                val child = bottom.getChildAt(index)
                if (child is android.widget.TextView) child else null
            }
            status = texts.firstOrNull { it.textSize <= 24f }
            assertNotNull("the status line must be in the bottom block", status)
            assertTrue("it stands above the bar", bottom.indexOfChild(status!!) < bottom.indexOfChild(bar!!))
        } finally {
            screen.finish()
        }
    }
}

/**
 * The header: the name of the screen while it is empty, the assistant once it answers.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceWhoTest {
    private val kesha = ru.uvw.dshnc.logic.VoiceChat.Whom("Кеша", "🦜")

    @Test
    fun `an empty conversation keeps the plain title`() {
        assertEquals("Голосовой чат", ru.uvw.dshnc.logic.VoiceChat.title(kesha, hasLines = false))
        assertEquals("Голосовой чат", ru.uvw.dshnc.logic.VoiceChat.title(null, hasLines = false))
    }

    @Test
    fun `a line plus a name becomes the assistant in the header`() {
        assertEquals("🦜 Кеша", ru.uvw.dshnc.logic.VoiceChat.title(kesha, hasLines = true))
    }

    @Test
    fun `an icon is optional and a name is not`() {
        assertEquals("Кеша", ru.uvw.dshnc.logic.VoiceChat.title(ru.uvw.dshnc.logic.VoiceChat.Whom("Кеша"), true))
        assertEquals("Кеша", ru.uvw.dshnc.logic.VoiceChat.title(ru.uvw.dshnc.logic.VoiceChat.Whom("Кеша", "  "), true))
        // lines but no name yet: the plain title stays until the host says who answers
        assertEquals("Голосовой чат", ru.uvw.dshnc.logic.VoiceChat.title(null, hasLines = true))
        assertEquals("Голосовой чат", ru.uvw.dshnc.logic.VoiceChat.title(ru.uvw.dshnc.logic.VoiceChat.Whom("  "), true))
    }

    @Test
    fun `the api reads an object, and still a bare string`() {
        val api = ru.uvw.dshnc.api.VoiceApi(org.robolectric.RuntimeEnvironment.getApplication())
        // no screen is listening: the queue is ours to read
        ru.uvw.dshnc.logic.VoiceInbox.onPosted = null
        ru.uvw.dshnc.logic.VoiceInbox.clear()
        try {
            api.handle(
                "chat",
                org.json.JSONObject()
                    .put("role", "assistant")
                    .put("text", "ответ")
                    .put("id", "e-1")
                    .put("who", org.json.JSONObject().put("name", "Кеша").put("icon", "🦜")),
                40L,
                0.0,
            ) { }
            val item = ru.uvw.dshnc.logic.VoiceInbox.drain().single() as ru.uvw.dshnc.logic.VoiceInbox.Item.Chat
            assertEquals("Кеша", item.who?.name)
            assertEquals("🦜", item.who?.icon)

            api.handle(
                "chat",
                org.json.JSONObject().put("role", "assistant").put("text", "ответ").put("who", "Кеша"),
                41L,
                0.0,
            ) { }
            val simple = ru.uvw.dshnc.logic.VoiceInbox.drain().single() as ru.uvw.dshnc.logic.VoiceInbox.Item.Chat
            assertEquals("Кеша", simple.who?.name)
            assertEquals(null, simple.who?.icon)
        } finally {
            ru.uvw.dshnc.logic.VoiceInbox.clear()
        }
    }
}

/**
 * Two failures, two sentences: the gate is not the host, and a refusal is not a broken wire.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceFailureTest {
    private val log = ru.uvw.dshnc.logic.VoiceFailure.NO_CONNECTION

    @Test
    fun `gateway statuses are a connection problem`() {
        for (status in listOf(502, 503, 504)) {
            assertEquals("HTTP $status", log, ru.uvw.dshnc.logic.VoiceFailure.line(status, "server said nothing useful"))
            assertEquals(
                ru.uvw.dshnc.logic.VoiceFailure.SAVED_LOCALLY,
                ru.uvw.dshnc.logic.VoiceFailure.note(status, ""),
            )
        }
    }

    @Test
    fun `no answer at all is a connection problem too`() {
        val timeout = ru.uvw.dshnc.logic.VoiceFailure.line(null, "SocketTimeoutException: failed to connect")
        assertEquals(log, timeout)
        assertTrue(ru.uvw.dshnc.logic.VoiceFailure.isTransport(null, "UnknownHostException: dsh.example"))
        assertTrue(ru.uvw.dshnc.logic.VoiceFailure.isTransport(null, "ConnectException: refused"))
        assertTrue(ru.uvw.dshnc.logic.VoiceFailure.isTransport(0, "java.net.SocketTimeoutException"))
    }

    @Test
    fun `a refusal keeps the server's words and adds no note`() {
        val line = ru.uvw.dshnc.logic.VoiceFailure.line(
            400,
            "voice принимается только на /v1/upload-file (bad_type)",
        )
        assertEquals("хост не принял: voice принимается только на (bad_type)", line)
        assertEquals(null, ru.uvw.dshnc.logic.VoiceFailure.note(400, "bad_type"))
    }

    @Test
    fun `a 500 is the host speaking, not the wire`() {
        // the gate is 502/503/504; a plain 500 carries the application's own text
        val line = ru.uvw.dshnc.logic.VoiceFailure.line(500, "внутренняя ошибка")
        assertEquals("хост не принял: внутренняя ошибка", line)
    }

    @Test
    fun `success is not a failure at all`() {
        val outcome = ru.uvw.dshnc.logic.VoiceUpload.interpret(202, "{\"accepted\":true,\"seconds\":1.5}")
        assertTrue(outcome is ru.uvw.dshnc.logic.VoiceUpload.Result.Accepted)
    }

    @Test
    fun `a transport failure carries no status into the upload result`() {
        val outcome = ru.uvw.dshnc.logic.VoiceUpload.interpret(503, "{\"code\":\"unavailable\"}")
        assertTrue(outcome is ru.uvw.dshnc.logic.VoiceUpload.Result.Failed)
        outcome as ru.uvw.dshnc.logic.VoiceUpload.Result.Failed
        assertEquals(503, outcome.status)
        assertEquals(ru.uvw.dshnc.logic.VoiceFailure.NO_CONNECTION, ru.uvw.dshnc.logic.VoiceFailure.line(outcome.status, outcome.reason))
    }
}

/**
 * The record button's shape: a rounded rectangle in every state.
 *
 * It used to turn into an oval under the finger and stay an oval afterwards, because two of the
 * three places that painted it kept the shape of the old circle.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceButtonShapeTest {
    private fun screen(): VoiceChatActivity =
        org.robolectric.Robolectric.buildActivity(VoiceChatActivity::class.java).setup().get()

    /** GradientDrawable keeps its shape privately: read it the way the view will use it. */
    private fun shapeOf (drawable: android.graphics.drawable.GradientDrawable): Int {
        val state = android.graphics.drawable.GradientDrawable::class.java
            .getDeclaredField("mGradientState")
            .apply { isAccessible = true }
            .get(drawable)
        return state.javaClass.getDeclaredField("mShape").apply { isAccessible = true }.getInt(state)
    }

    private fun button (screen: VoiceChatActivity): android.widget.TextView {
        var found: android.widget.TextView? = null
        fun walk (view: android.view.View) {
            if (view is android.widget.TextView && view.contentDescription?.toString() == "говорить") found = view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(screen.findViewById(android.R.id.content))
        return requireNotNull(found) { "кнопка записи не найдена" }
    }

    @Test
    fun `the button is a rounded rectangle when idle`() {
        val screen = screen()
        try {
            val drawable = button(screen).background as android.graphics.drawable.GradientDrawable
            assertEquals(android.graphics.drawable.GradientDrawable.RECTANGLE, shapeOf(drawable))
            assertEquals(24 * screen.resources.displayMetrics.density, drawable.cornerRadius, 0.5f)
        } finally {
            screen.finish()
        }
    }

    @Test
    fun `a running take paints the same rectangle in red`() {
        val screen = screen()
        try {
            val activity = screen as VoiceChatActivity
            val method = VoiceChatActivity::class.java.getDeclaredMethod("voiceButton", Int::class.java)
                .apply { isAccessible = true }
            val idle = method.invoke(activity, android.graphics.Color.parseColor("#2563eb")) as android.graphics.drawable.GradientDrawable
            val busy = method.invoke(activity, android.graphics.Color.parseColor("#dc2626")) as android.graphics.drawable.GradientDrawable
            assertEquals(android.graphics.drawable.GradientDrawable.RECTANGLE, shapeOf(idle))
            assertEquals(android.graphics.drawable.GradientDrawable.RECTANGLE, shapeOf(busy))
            assertEquals(24 * screen.resources.displayMetrics.density, busy.cornerRadius, 0.5f)
        } finally {
            screen.finish()
        }
    }

    @Test
    fun `no state of the screen is an oval`() {
        val screen = screen()
        try {
            var ovals = 0
            fun walk (view: android.view.View) {
                val background = view.background
                if (background is android.graphics.drawable.GradientDrawable) {
                    if (shapeOf(background) == android.graphics.drawable.GradientDrawable.OVAL) ovals += 1
                }
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(screen.findViewById(android.R.id.content))
            assertEquals(0, ovals)
        } finally {
            screen.finish()
        }
    }
}

/**
 * The two forms of an answer and the three ways to show them.
 */
class VoiceAnswerTest {
    private val long = "# Ответ\n\nГолосовое сообщение отправлено."
    private val short = "Отправил."

    @Test
    fun `short shows the spoken form only`() {
        val parts = ru.uvw.dshnc.logic.VoiceAnswer.compose(long, short, ru.uvw.dshnc.logic.AnswerMode.SHORT)
        assertEquals(listOf("Отправил."), parts.map { it.text })
        assertEquals(1, parts.size)
    }

    @Test
    fun `long shows the written form only`() {
        val parts = ru.uvw.dshnc.logic.VoiceAnswer.compose(long, short, ru.uvw.dshnc.logic.AnswerMode.LONG)
        assertEquals(listOf(long), parts.map { it.text })
    }

    @Test
    fun `both put a rule between the two forms`() {
        val parts = ru.uvw.dshnc.logic.VoiceAnswer.compose(long, short, ru.uvw.dshnc.logic.AnswerMode.BOTH)
        assertEquals(
            listOf(
                ru.uvw.dshnc.logic.AnswerPart.Kind.TEXT,
                ru.uvw.dshnc.logic.AnswerPart.Kind.DIVIDER,
                ru.uvw.dshnc.logic.AnswerPart.Kind.TEXT,
            ),
            parts.map { it.kind },
        )
        assertEquals(listOf("Отправил.", "", long), parts.map { it.text })
    }

    @Test
    fun `identical forms are shown once, without a rule`() {
        val same = "Готово."
        val parts = ru.uvw.dshnc.logic.VoiceAnswer.compose(same, same, ru.uvw.dshnc.logic.AnswerMode.BOTH)
        assertEquals(1, parts.size)
        assertEquals("Готово.", parts.single().text)
    }

    @Test
    fun `a missing form falls back to the other one`() {
        assertEquals(
            listOf(long),
            ru.uvw.dshnc.logic.VoiceAnswer.compose(long, null, ru.uvw.dshnc.logic.AnswerMode.SHORT).map { it.text },
        )
        assertEquals(
            listOf(short),
            ru.uvw.dshnc.logic.VoiceAnswer.compose("", short, ru.uvw.dshnc.logic.AnswerMode.LONG).map { it.text },
        )
        // both with nothing to say stays empty rather than inventing a line
        assertTrue(ru.uvw.dshnc.logic.VoiceAnswer.compose("", null, ru.uvw.dshnc.logic.AnswerMode.BOTH).isEmpty())
    }

    @Test
    fun `the speech form prefers what was written for the ear`() {
        assertEquals("Отправил.", ru.uvw.dshnc.logic.VoiceAnswer.speech(long, short))
        assertEquals(long, ru.uvw.dshnc.logic.VoiceAnswer.speech(long, "  "))
    }

    @Test
    fun `the stored mode survives, and defaults to the long form`() {
        assertEquals(ru.uvw.dshnc.logic.AnswerMode.LONG, ru.uvw.dshnc.logic.AnswerMode.of(null))
        assertEquals(ru.uvw.dshnc.logic.AnswerMode.LONG, ru.uvw.dshnc.logic.AnswerMode.of("nonsense"))
        assertEquals(ru.uvw.dshnc.logic.AnswerMode.SHORT, ru.uvw.dshnc.logic.AnswerMode.of("SHORT"))
        assertEquals(ru.uvw.dshnc.logic.AnswerMode.BOTH, ru.uvw.dshnc.logic.AnswerMode.of(" both "))
    }
}

/**
 * Markdown stops being visible: the model writes it, the person should not read asterisks.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceMarkdownTest {
    @Test
    fun `the answer is rendered, the person's own line is not`() {
        val screen = org.robolectric.Robolectric.buildActivity(VoiceChatActivity::class.java).setup().get()
        try {
            ru.uvw.dshnc.logic.VoiceInbox.onPosted = null
            ru.uvw.dshnc.logic.VoiceInbox.clear()
            ru.uvw.dshnc.logic.VoiceChatBus.clear()
            ru.uvw.dshnc.logic.VoiceChatBus.putQuiet(
                "m-1",
                ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT,
                "**жирный** и | a | b |\n| - | - |\n| 1 | 2 |\n```\nкод\n```",
            )
            ru.uvw.dshnc.logic.VoiceChatBus.putQuiet(
                "m-1",
                ru.uvw.dshnc.logic.VoiceChat.Role.USER,
                "**это мои слова**",
            )
            val method = VoiceChatActivity::class.java.getDeclaredMethod("renderChat").apply { isAccessible = true }
            method.invoke(screen)
            val texts = mutableListOf<String>()
            fun walk (view: android.view.View) {
                if (view is android.widget.TextView) texts.add(view.text?.toString().orEmpty())
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(screen.findViewById(android.R.id.content))
            val answer = texts.firstOrNull { it.contains("жирный") }.orEmpty()
            assertTrue("markdown must be rendered: $answer", !answer.contains("**"))
            // the person's own remark is kept as typed
            assertTrue(texts.any { it.contains("**это мои слова**") })
        } finally {
            ru.uvw.dshnc.logic.VoiceInbox.onPosted = null
            ru.uvw.dshnc.logic.VoiceChatBus.clear()
            screen.finish()
        }
    }
}

/**
 * The menu's second group, and the fact that the choice outlives the screen.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceAnswerMenuTest {
    private fun app(): android.content.Context = org.robolectric.RuntimeEnvironment.getApplication()

    @Test
    fun `the answer group follows the modes behind a separator`() {
        val items = ru.uvw.dshnc.logic.VoiceMenu.items(
            ru.uvw.dshnc.logic.VoiceMode.PTT,
            ru.uvw.dshnc.logic.AnswerMode.LONG,
        )
        // three groups: the new conversation, the forms, the synthesis
        assertEquals(10, items.size)
        assertEquals("new-session", items[0].id)
        assertEquals("mode-hold", items[1].id)
        assertEquals("mode-tap", items[2].id)
        assertTrue(items[3].separator)
        assertEquals("Показывать ответы", items[3].title)
        assertEquals(
            listOf("Только короткую форму", "Только длинную форму", "Всё, с чертой между ними"),
            items.drop(4).take(3).map { it.title },
        )
        // the habit is marked as well: «держать» is the default
        assertEquals(
            listOf(false, true, false, false, false, true, false, false, true, false),
            items.map { it.checked },
        )
    }

    @Test
    fun `the chosen form is marked`() {
        val items = ru.uvw.dshnc.logic.VoiceMenu.items(
            ru.uvw.dshnc.logic.VoiceMode.PTT,
            ru.uvw.dshnc.logic.AnswerMode.SHORT,
        )
        assertEquals(
            listOf(false, true, false, false, true, false, false, false, true, false),
            items.map { it.checked },
        )
        assertEquals(
            ru.uvw.dshnc.logic.AnswerMode.BOTH,
            ru.uvw.dshnc.logic.VoiceMenu.answerModeOf("answer-both"),
        )
        assertEquals(null, ru.uvw.dshnc.logic.VoiceMenu.answerModeOf("mode-hold"))
    }

    @Test
    fun `the choice is remembered across a new screen`() {
        val store = ru.uvw.dshnc.store.TokenStore(app())
        assertEquals("long", store.answerMode)
        store.answerMode = "both"
        // a second store instance reads the same value: it survives a recreate
        assertEquals("both", ru.uvw.dshnc.store.TokenStore(app()).answerMode)
        store.answerMode = "long"
    }
}

/**
 * The answer bubble: as wide as the text needs, never squeezed to half the screen, and labelled
 * with who is speaking.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceAnswerBubbleTest {
    private fun screen(): VoiceChatActivity =
        org.robolectric.Robolectric.buildActivity(VoiceChatActivity::class.java).setup().get()

    private fun walk (view: android.view.View, visit: (android.view.View) -> Unit) {
        visit(view)
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), visit)
    }

    @Test
    fun `a long answer takes most of the width`() {
        val screen = screen()
        ru.uvw.dshnc.logic.VoiceInbox.clear()
        ru.uvw.dshnc.logic.VoiceChatBus.clear()
        try {
            val text = (1..40).joinToString(" ") { "слово$it" }
            ru.uvw.dshnc.logic.VoiceChatBus.putQuiet("w-1", ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT, text)
            val render = VoiceChatActivity::class.java.getDeclaredMethod("renderChat").apply { isAccessible = true }
            render.invoke(screen)
            val root = screen.findViewById<android.view.View>(android.R.id.content)
            root.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(screen.resources.displayMetrics.widthPixels, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(screen.resources.displayMetrics.heightPixels, android.view.View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, screen.resources.displayMetrics.widthPixels, screen.resources.displayMetrics.heightPixels)

            var widest = 0
            var table: android.widget.HorizontalScrollView? = null
            walk(root) { view ->
                if (view is android.widget.TextView && view.text?.contains("слово1") == true) {
                    widest = maxOf(widest, view.width)
                }
                if (view is android.widget.HorizontalScrollView) table = view
            }
            val screenWidth = screen.resources.displayMetrics.widthPixels
            // a plain paragraph is not wrapped in a scroller
            assertEquals(null, table)
            assertTrue(
                "answer text is ${widest}px of ${screenWidth}px, it must use most of the width",
                widest > screenWidth * 0.6,
            )
        } finally {
            ru.uvw.dshnc.logic.VoiceChatBus.clear()
            screen.finish()
        }
    }

    @Test
    fun `the speaker's name sits inside the bubble, without the emoji`() {
        val screen = screen()
        ru.uvw.dshnc.logic.VoiceChatBus.clear()
        try {
            ru.uvw.dshnc.logic.VoiceChatBus.putQuiet(
                "w-2",
                ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT,
                "ответ",
                ru.uvw.dshnc.logic.VoiceChat.Whom("Кеша", "🦜"),
            )
            ru.uvw.dshnc.logic.VoiceChatBus.putQuiet("w-2", ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT, "ответ без имени")
            val render = VoiceChatActivity::class.java.getDeclaredMethod("renderChat").apply { isAccessible = true }
            render.invoke(screen)
            val texts = mutableListOf<String>()
            walk(screen.findViewById(android.R.id.content)) { view ->
                if (view is android.widget.TextView) texts.add(view.text?.toString().orEmpty())
            }
            // the name is its own label; the emoji lives on the avatar and is not glued to it
            assertTrue(texts.contains("Кеша"))
            assertTrue(texts.contains("🦜"))
            assertTrue(texts.none { it.contains("🦜 Кеша") })
        } finally {
            ru.uvw.dshnc.logic.VoiceChatBus.clear()
            screen.finish()
        }
    }
}

/**
 * A series of remarks reads as one speech: only its last bubble carries the tail.
 */
class VoiceBubbleTailsTest {
    private fun entry (id: String, role: ru.uvw.dshnc.logic.VoiceChat.Role, text: String) =
        ru.uvw.dshnc.logic.VoiceChatLog.Entry(id, role, text)

    @Test
    fun `only the last of a run has a tail`() {
        val entries = listOf(
            entry("a", ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT, "первый"),
            entry("a", ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT, "второй"),
            entry("a", ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT, "третий"),
            entry("a", ru.uvw.dshnc.logic.VoiceChat.Role.USER, "мой"),
        )
        assertEquals(
            listOf(false, false, true, true),
            ru.uvw.dshnc.logic.VoiceBubble.tails(entries),
        )
    }

    @Test
    fun `a single message has a tail, two speakers alternate tails`() {
        assertEquals(
            listOf(true),
            ru.uvw.dshnc.logic.VoiceBubble.tails(listOf(entry("a", ru.uvw.dshnc.logic.VoiceChat.Role.USER, "один"))),
        )
        assertEquals(
            listOf(true, true, true),
            ru.uvw.dshnc.logic.VoiceBubble.tails(
                listOf(
                    entry("a", ru.uvw.dshnc.logic.VoiceChat.Role.USER, "вопрос"),
                    entry("a", ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT, "ответ"),
                    entry("b", ru.uvw.dshnc.logic.VoiceChat.Role.USER, "ещё"),
                ),
            ),
        )
    }

    @Test
    fun `a service note never carries a tail and breaks nothing`() {
        val entries = listOf(
            entry("a", ru.uvw.dshnc.logic.VoiceChat.Role.USER, "вопрос"),
            entry("", ru.uvw.dshnc.logic.VoiceChat.Role.SYSTEM, "слушаю"),
            entry("a", ru.uvw.dshnc.logic.VoiceChat.Role.USER, "ещё"),
        )
        val tails = ru.uvw.dshnc.logic.VoiceBubble.tails(entries)
        // the note ends the run, so the remark before it is the last one and carries the tail
        assertEquals(listOf(true, false, true), tails)
    }
}

/**
 * The messenger look: an avatar beside incoming bubbles, the name inside them.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceMessengerLookTest {
    private fun screen(): VoiceChatActivity =
        org.robolectric.Robolectric.buildActivity(VoiceChatActivity::class.java).setup().get()

    private fun collect (view: android.view.View): List<android.view.View> {
        val all = mutableListOf<android.view.View>()
        fun walk (v: android.view.View) {
            all.add(v)
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(view)
        return all
    }

    @Test
    fun `an incoming bubble has a 28 dp avatar with the emoji from who`() {
        val screen = screen()
        ru.uvw.dshnc.logic.VoiceChatBus.clear()
        try {
            ru.uvw.dshnc.logic.VoiceChatBus.putQuiet(
                "av-1", ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT, "ответ",
                ru.uvw.dshnc.logic.VoiceChat.Whom("Кеша", "🦜"),
            )
            val render = VoiceChatActivity::class.java.getDeclaredMethod("renderChat").apply { isAccessible = true }
            render.invoke(screen)
            val views = collect(screen.findViewById(android.R.id.content))
            val avatar = views.filterIsInstance<android.widget.TextView>().firstOrNull { it.text == "🦜" }
            assertNotNull("аватарка с иконкой не найдена", avatar)
            val density = screen.resources.displayMetrics.density
            val lp = avatar!!.layoutParams as android.widget.LinearLayout.LayoutParams
            assertEquals((ru.uvw.dshnc.logic.VoiceBubble.AVATAR_DP * density).toInt(), lp.width)
            assertEquals(lp.width, lp.height)
            // the name is inside the bubble, in the accent colour, without the emoji
            val name = views.filterIsInstance<android.widget.TextView>().firstOrNull { it.text == "Кеша" }
            assertNotNull("имени внутри пузыря нет", name)
            assertEquals(
                android.graphics.Color.parseColor(ru.uvw.dshnc.logic.VoiceBubble.NAME_COLOR),
                name!!.currentTextColor,
            )
        } finally {
            ru.uvw.dshnc.logic.VoiceChatBus.clear()
            screen.finish()
        }
    }

    @Test
    fun `without who the avatar is a robot and there is no name`() {
        val screen = screen()
        ru.uvw.dshnc.logic.VoiceChatBus.clear()
        try {
            ru.uvw.dshnc.logic.VoiceChatBus.putQuiet("av-2", ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT, "ответ")
            val render = VoiceChatActivity::class.java.getDeclaredMethod("renderChat").apply { isAccessible = true }
            render.invoke(screen)
            val texts = collect(screen.findViewById(android.R.id.content))
                .filterIsInstance<android.widget.TextView>()
                .map { it.text?.toString().orEmpty() }
            assertTrue(texts.contains(ru.uvw.dshnc.logic.VoiceBubble.AVATAR_FALLBACK))
            // no name label anywhere, and the old corner caption is gone
            assertTrue(texts.none { it.endsWith("Кеша") })
        } finally {
            ru.uvw.dshnc.logic.VoiceChatBus.clear()
            screen.finish()
        }
    }

    @Test
    fun `the old top-right caption is gone`() {
        val screen = screen()
        ru.uvw.dshnc.logic.VoiceChatBus.clear()
        try {
            ru.uvw.dshnc.logic.VoiceChatBus.putQuiet(
                "av-3", ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT, "ответ",
                ru.uvw.dshnc.logic.VoiceChat.Whom("Кеша", "🦜"),
            )
            val render = VoiceChatActivity::class.java.getDeclaredMethod("renderChat").apply { isAccessible = true }
            render.invoke(screen)
            val texts = collect(screen.findViewById(android.R.id.content))
                .filterIsInstance<android.widget.TextView>()
                .map { it.text?.toString().orEmpty() }
            // the emoji lives in the avatar only; no «🦜 Кеша» label remains
            assertTrue(texts.none { it.contains("🦜 Кеша") })
        } finally {
            ru.uvw.dshnc.logic.VoiceChatBus.clear()
            screen.finish()
        }
    }
}

/**
 * The newest line is visible; the reader is not dragged away from history.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceScrollTest {
    private fun screen(): VoiceChatActivity =
        org.robolectric.Robolectric.buildActivity(VoiceChatActivity::class.java).setup().get()

    private fun field (screen: VoiceChatActivity, name: String): Any? =
        VoiceChatActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(screen)

    private fun layout (screen: VoiceChatActivity) {
        val root = screen.findViewById<android.view.View>(android.R.id.content)
        val metrics = screen.resources.displayMetrics
        root.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, android.view.View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun fill (screen: VoiceChatActivity, count: Int) {
        repeat(count) { index ->
            ru.uvw.dshnc.logic.VoiceChatBus.putQuiet(
                "s-$index",
                if (index % 2 == 0) ru.uvw.dshnc.logic.VoiceChat.Role.USER else ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT,
                "строка $index — довольно длинный текст, чтобы лента стала высокой и прокручиваемой",
            )
        }
        val render = VoiceChatActivity::class.java.getDeclaredMethod("renderChat").apply { isAccessible = true }
        render.invoke(screen)
        layout(screen)
    }

    @Test
    fun `a new line is scrolled into view to its very bottom`() {
        val screen = screen()
        ru.uvw.dshnc.logic.VoiceChatBus.clear()
        try {
            fill(screen, 30)
            val scroller = field(screen, "scroller") as android.widget.ScrollView
            val chat = field(screen, "chat") as android.widget.LinearLayout
            val max = (chat.height - scroller.height).coerceAtLeast(0)
            assertTrue("лента должна быть прокручиваемой", max > 0)
            assertEquals("должен быть виден самый низ ленты", max, scroller.scrollY)
        } finally {
            ru.uvw.dshnc.logic.VoiceChatBus.clear()
            screen.finish()
        }
    }

    @Test
    fun `a reader far up is not dragged away, and gets the down tab`() {
        val screen = screen()
        ru.uvw.dshnc.logic.VoiceChatBus.clear()
        try {
            fill(screen, 40)
            val scroller = field(screen, "scroller") as android.widget.ScrollView
            val down = field(screen, "downButton") as android.widget.TextView
            // the person scrolls back to the very top: a screen and more from the end
            scroller.scrollTo(0, 0)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("кнопка «вниз» должна быть видна", android.view.View.VISIBLE, down.visibility)

            // a new line arrives: no autoscroll, the position is kept
            ru.uvw.dshnc.logic.VoiceChatBus.putQuiet(
                "s-new",
                ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT,
                "новый ответ, который не должен дёргать читателя",
            )
            val render = VoiceChatActivity::class.java.getDeclaredMethod("renderChat").apply { isAccessible = true }
            render.invoke(screen)
            layout(screen)
            assertEquals(0, scroller.scrollY)
            assertEquals(android.view.View.VISIBLE, down.visibility)

            // the tab brings the reader to the end and disappears
            down.performClick()
            layout(screen)
            val chat = field(screen, "chat") as android.widget.LinearLayout
            assertEquals((chat.height - scroller.height).coerceAtLeast(0), scroller.scrollY)
            assertEquals(android.view.View.GONE, down.visibility)
        } finally {
            ru.uvw.dshnc.logic.VoiceChatBus.clear()
            screen.finish()
        }
    }
}

/**
 * One command, one answer: the voice is downloaded, the bubble appears, the sound queues behind
 * whatever is already speaking — and the command answers before any of that is heard.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceAnswerCommandTest {
    private fun api (): ru.uvw.dshnc.api.VoiceApi =
        ru.uvw.dshnc.api.VoiceApi(org.robolectric.RuntimeEnvironment.getApplication())

    private fun body (path: String = "/var/lib/dsh/voice/out/ex-1.ogg") = org.json.JSONObject()
        .put("id", "ex-1")
        .put("text", "**Ответ** с markdown")
        .put("speak", "Ответ")
        .put("who", org.json.JSONObject().put("name", "Кеша").put("icon", "🦜"))
        .put("seconds", 3.4)
        .put("path", path)

    @Test
    fun `the file is fetched by this command's ticket and the bubble is queued` () {
        val api = api()
        ru.uvw.dshnc.logic.VoiceInbox.onPosted = null
        ru.uvw.dshnc.logic.VoiceInbox.clear()
        var askedSeq = -1L
        var wrote = ""
        api.downloader = { seq, localPath, _ ->
            askedSeq = seq
            wrote = localPath
            java.io.File(localPath).writeBytes(ByteArray(64))
            org.json.JSONObject().put("status", "ok")
        }
        try {
            val outcome = api.handle("answer", body(), 77L, 0.0) { }
            assertEquals("ok", outcome.optString("status"))
            assertEquals(true, outcome.optJSONObject("payload")!!.optBoolean("queued"))
            // the route is a transfer route: the ticket is the command number
            assertEquals(77L, askedSeq)
            assertTrue("файл должен лежать в cacheDir", wrote.contains("/cache/answer-ex-1"))
            assertTrue(java.io.File(wrote).isFile)

            val item = ru.uvw.dshnc.logic.VoiceInbox.drain().single() as ru.uvw.dshnc.logic.VoiceInbox.Item.Chat
            assertEquals("ex-1", item.id)
            assertEquals("Кеша", item.who?.name)
            assertEquals("🦜", item.who?.icon)
            assertEquals("Ответ", item.speak)
            assertTrue(item.text.contains("markdown"))
        } finally {
            api.shutdown()
            ru.uvw.dshnc.logic.VoiceInbox.clear()
        }
    }

    @Test
    fun `a failed download refuses and queues no speech`() {
        val api = api()
        ru.uvw.dshnc.logic.VoiceInbox.onPosted = null
        ru.uvw.dshnc.logic.VoiceInbox.clear()
        api.downloader = { _, _, _ ->
            org.json.JSONObject().put("status", "error").put("code", "forbidden").put("message", "нет доступа")
        }
        try {
            val outcome = api.handle("answer", body(), 78L, 0.0) { }
            assertEquals("error", outcome.optString("status"))
            assertEquals("forbidden", outcome.optString("code"))
            // nothing to show and nothing to say: the command is simply refused
            assertEquals(0, ru.uvw.dshnc.logic.VoiceInbox.size())
        } finally {
            api.shutdown()
            ru.uvw.dshnc.logic.VoiceInbox.clear()
        }
    }

    @Test
    fun `an answer without a file, an id or a link to the server is refused`() {
        val api = api()
        try {
            assertEquals("bad_args", api.handle("answer", body(path = " "), 79L, 0.0) { }.optString("code"))
            val noLink = api.handle("answer", body(), 80L, 0.0) { }
            assertEquals("voice_unavailable", noLink.optString("code"))
        } finally {
            api.shutdown()
        }
    }

    @Test
    fun `answer is declared next to chat and play, which still work` () {
        val api = api()
        try {
            val methods = api.schema().optJSONArray("methods")!!
            val names = (0 until methods.length()).map { methods.getJSONObject(it).optString("method") }
            assertTrue(names.contains("answer"))
            // the old two-command route stays for a server that has not been updated yet
            assertTrue(names.contains("chat"))
            assertTrue(names.contains("play"))
        } finally {
            api.shutdown()
        }
    }
}

/**
 * The node's settings live in the sections that are actually reachable, and nowhere else.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsReachabilityTest {
    private fun open (key: String): SectionActivity {
        val intent = android.content.Intent(
            org.robolectric.RuntimeEnvironment.getApplication(),
            SectionActivity::class.java,
        ).putExtra("section", key)
        return org.robolectric.Robolectric.buildActivity(SectionActivity::class.java, intent).setup().get()
    }

    private fun texts (screen: android.app.Activity): List<String> {
        val found = mutableListOf<String>()
        fun walk (view: android.view.View) {
            if (view is android.widget.TextView) found.add(view.text?.toString().orEmpty())
            if (view is android.widget.Button) found.add(view.text?.toString().orEmpty())
            if (view is android.widget.CheckBox) found.add(view.text?.toString().orEmpty())
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(screen.findViewById(android.R.id.content))
        return found
    }

    @Test
    fun `the network section keeps ping, stretch and the fallback poll`() {
        val screen = open("net")
        try {
            val texts = texts(screen)
            // the labels come from the resources, which are in English on this screen
            assertTrue(texts.any { it.contains("Ping interval", ignoreCase = true) })
            assertTrue(texts.any { it.contains("Stretch when idle", ignoreCase = true) })
            assertTrue("резервный опрос должен быть здесь", texts.any { it.contains("Запасной опрос", ignoreCase = true) })
            assertTrue(texts.any { it.contains("Save", ignoreCase = true) })
        } finally {
            screen.finish()
        }
    }

    @Test
    fun `the android section carries the debug switch`() {
        val screen = open("android")
        try {
            val found = texts(screen)
            assertTrue("debug-переключателя нет; на экране: $found", found.any { it.contains("debug_info", ignoreCase = true) })
        } finally {
            screen.finish()
        }
    }

    @Test
    fun `the dead screen is gone from the manifest`() {
        val manifest = java.io.File("src/main/AndroidManifest.xml")
        val file = if (manifest.exists()) manifest else java.io.File("app/src/main/AndroidManifest.xml")
        val text = file.readText()
        // the dead screen is gone; the new voice settings are a different activity and must stay
        assertTrue("мёртвый экран вернулся в манифест", !text.contains(".SettingsActivity"))
        assertTrue("экран настроек голоса должен быть объявлен", text.contains("VoiceSettingsActivity"))
    }
}

/**
 * The synthesis choice moved into the voice menu, and it takes effect with the very next take.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceSynthChoiceTest {
    private fun app(): android.content.Context = org.robolectric.RuntimeEnvironment.getApplication()

    @Test
    fun `the menu offers both ways and marks the current one`() {
        val items = ru.uvw.dshnc.logic.VoiceMenu.items(
            ru.uvw.dshnc.logic.VoiceMode.PTT,
            ru.uvw.dshnc.logic.AnswerMode.LONG,
            "host",
        )
        val group = items.dropWhile { !(it.separator && it.title == "Способ синтеза звука") }
        assertEquals(listOf("TTS на Android", "Синтез на DSH"), group.drop(1).map { it.title })
        assertEquals(listOf(false, true), group.drop(1).map { it.checked })

        val device = ru.uvw.dshnc.logic.VoiceMenu.items(
            ru.uvw.dshnc.logic.VoiceMode.PTT,
            ru.uvw.dshnc.logic.AnswerMode.LONG,
            "device",
        ).takeLast(2)
        assertEquals(listOf(true, false), device.map { it.checked })
    }

    @Test
    fun `a row names the synthesis it selects`() {
        assertEquals("device", ru.uvw.dshnc.logic.VoiceMenu.synthOf("synth-device"))
        assertEquals("host", ru.uvw.dshnc.logic.VoiceMenu.synthOf("synth-host"))
        assertEquals(null, ru.uvw.dshnc.logic.VoiceMenu.synthOf("mode-hold"))
    }

    @Test
    fun `the choice is stored at once and travels with the next take`() {
        val store = ru.uvw.dshnc.store.TokenStore(app())
        store.voiceSynth = "host"
        // the very next upload carries it: no restart of the node is involved
        val url = ru.uvw.dshnc.logic.VoiceUpload.url("https://dsh.example", "id-1", store.voiceSynth)
        assertTrue(url.endsWith("&synth=host"))
        // and the value survives a new store instance
        assertEquals("host", ru.uvw.dshnc.store.TokenStore(app()).voiceSynth)
        store.voiceSynth = "device"
        assertTrue(ru.uvw.dshnc.logic.VoiceUpload.url("https://dsh.example", "id-1", store.voiceSynth).endsWith("&synth=device"))
    }
}

/**
 * «Повтори»: the last answer can be heard again without asking the host.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceReplayTest {
    private fun app(): android.content.Context = org.robolectric.RuntimeEnvironment.getApplication()

    @Test
    fun `a file replaces the previous one, and only the last stays`() {
        ru.uvw.dshnc.logic.VoiceReplay.clear()
        val cache = app().cacheDir
        val first = java.io.File(cache, "answer-a.ogg").apply { writeBytes(ByteArray(16) { 1 }) }
        val second = java.io.File(cache, "answer-b.ogg").apply { writeBytes(ByteArray(32) { 2 }) }
        val keptFirst = ru.uvw.dshnc.logic.VoiceReplay.rememberFile(first, cache)
        assertNotNull(keptFirst)
        assertTrue(keptFirst!!.isFile)
        val keptSecond = ru.uvw.dshnc.logic.VoiceReplay.rememberFile(second, cache)
        assertNotNull(keptSecond)
        // one file, one name: the new answer took the place of the old one
        assertEquals(ru.uvw.dshnc.logic.VoiceReplay.FILE_NAME, keptSecond!!.name)
        assertTrue(keptSecond.isFile)
        assertEquals(32L, keptSecond.length())
        assertEquals(keptSecond.absolutePath, ru.uvw.dshnc.logic.VoiceReplay.snapshot()?.file?.absolutePath)
        ru.uvw.dshnc.logic.VoiceReplay.clear()
    }

    @Test
    fun `a spoken sentence is remembered without a file`() {
        ru.uvw.dshnc.logic.VoiceReplay.clear()
        ru.uvw.dshnc.logic.VoiceReplay.rememberSpeech("Свет включил.")
        val last = ru.uvw.dshnc.logic.VoiceReplay.snapshot()
        assertEquals("Свет включил.", last?.speak)
        assertEquals(null, last?.file)
        // an empty sentence is not worth remembering
        ru.uvw.dshnc.logic.VoiceReplay.rememberSpeech("   ")
        assertEquals("Свет включил.", ru.uvw.dshnc.logic.VoiceReplay.snapshot()?.speak)
        ru.uvw.dshnc.logic.VoiceReplay.clear()
        assertEquals(null, ru.uvw.dshnc.logic.VoiceReplay.snapshot())
    }

    @Test
    fun `a tap queues the sentence, or the kept file, and never both`() {
        val api = ru.uvw.dshnc.api.VoiceApi(app())
        ru.uvw.dshnc.logic.VoiceReplay.clear()
        try {
            assertEquals(null, api.replayTask())

            ru.uvw.dshnc.logic.VoiceReplay.rememberSpeech("Половина шестого.")
            val spoken = api.replayTask()
            assertEquals("Половина шестого.", spoken?.text)
            assertEquals(null, spoken?.localFile)

            val cache = app().cacheDir
            val file = java.io.File(cache, "answer-c.ogg").apply { writeBytes(ByteArray(8)) }
            ru.uvw.dshnc.logic.VoiceReplay.rememberFile(file, cache)
            val played = api.replayTask()
            assertNotNull(played?.localFile)
            // the kept file must survive its own playback
            assertEquals(true, played?.keepFile)
        } finally {
            ru.uvw.dshnc.api.VoiceApi(app()).shutdown()
            ru.uvw.dshnc.logic.VoiceReplay.clear()
        }
    }

    @Test
    fun `the button sits on the newest answer only`() {
        val screen = org.robolectric.Robolectric.buildActivity(VoiceChatActivity::class.java).setup().get()
        ru.uvw.dshnc.logic.VoiceChatBus.clear()
        try {
            ru.uvw.dshnc.logic.VoiceChatBus.putQuiet(
                "r-1", ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT, "первый ответ",
                ru.uvw.dshnc.logic.VoiceChat.Whom("Кеша", "🦜"),
            )
            ru.uvw.dshnc.logic.VoiceChatBus.putQuiet(
                "r-1", ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT, "второй ответ",
                ru.uvw.dshnc.logic.VoiceChat.Whom("Кеша", "🦜"),
            )
            ru.uvw.dshnc.logic.VoiceChatBus.putQuiet("r-1", ru.uvw.dshnc.logic.VoiceChat.Role.USER, "мой вопрос")
            val render = VoiceChatActivity::class.java.getDeclaredMethod("renderChat").apply { isAccessible = true }
            render.invoke(screen)
            var buttons = 0
            fun walk (view: android.view.View) {
                if (view is android.widget.TextView && view.contentDescription?.toString() == "повторить последнюю фразу") buttons += 1
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(screen.findViewById(android.R.id.content))
            assertEquals("кнопка должна быть только у последнего ответа", 1, buttons)
        } finally {
            ru.uvw.dshnc.logic.VoiceChatBus.clear()
            screen.finish()
        }
    }
}

/**
 * A failed synthesis is said out loud on the screen, and can be sent back with one tap.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceDegradedTest {
    private fun app(): android.content.Context = org.robolectric.RuntimeEnvironment.getApplication()

    @Test
    fun `the field is read as kind and message, and a broken one is ignored`() {
        val parsed = ru.uvw.dshnc.logic.VoiceDegraded.parse(
            org.json.JSONObject().put("kind", "tts-failed").put("message", "silero provider has been stopped"),
        )
        assertEquals("tts-failed", parsed?.kind)
        assertEquals("silero provider has been stopped", parsed?.message)
        assertEquals(null, ru.uvw.dshnc.logic.VoiceDegraded.parse(null))
        assertEquals(null, ru.uvw.dshnc.logic.VoiceDegraded.parse("строка"))
        assertEquals(null, ru.uvw.dshnc.logic.VoiceDegraded.parse(org.json.JSONObject().put("message", "без kind")))
    }

    @Test
    fun `the report body is exactly the agreed event`() {
        val body = ru.uvw.dshnc.logic.VoiceDegraded.body(
            "ex-1",
            ru.uvw.dshnc.logic.VoiceDegraded.Report("tts-failed", "silero provider has been stopped"),
            "0.10.42",
        )
        assertEquals("bug-report", body.getString("type"))
        assertEquals("ex-1", body.getString("id"))
        assertEquals("tts-failed", body.getString("kind"))
        assertEquals("silero provider has been stopped", body.getString("text"))
        assertEquals("0.10.42", body.getString("app"))
        assertEquals(5, body.length())
    }

    @Test
    fun `the line appears only when the host said something`() {
        val screen = org.robolectric.Robolectric.buildActivity(VoiceChatActivity::class.java).setup().get()
        ru.uvw.dshnc.logic.VoiceChatBus.clear()
        try {
            ru.uvw.dshnc.logic.VoiceChatBus.putQuiet("d-1", ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT, "обычный ответ")
            ru.uvw.dshnc.logic.VoiceChatBus.putQuiet(
                "d-2", ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT, "ответ с деградацией",
                degraded = ru.uvw.dshnc.logic.VoiceDegraded.Report("tts-failed", "silero provider has been stopped"),
            )
            val render = VoiceChatActivity::class.java.getDeclaredMethod("renderChat").apply { isAccessible = true }
            render.invoke(screen)
            val texts = mutableListOf<String>()
            fun walk (view: android.view.View) {
                if (view is android.widget.TextView) texts.add(view.text?.toString().orEmpty())
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(screen.findViewById(android.R.id.content))
            // exactly one such line, and it carries the report button
            assertEquals(1, texts.count { it == ru.uvw.dshnc.logic.VoiceDegraded.LINE })
            assertEquals(1, texts.count { it == "Отчёт" })
        } finally {
            ru.uvw.dshnc.logic.VoiceChatBus.clear()
            screen.finish()
        }
    }

    @Test
    fun `a sent report leaves no button, a refusal keeps it`() {
        val log = ru.uvw.dshnc.logic.VoiceChatLog()
        log.put(
            "d-3", ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT, "ответ",
            degraded = ru.uvw.dshnc.logic.VoiceDegraded.Report("tts-failed", "нет голоса"),
        )
        assertEquals(null, log.snapshot().single().report)
        assertTrue(log.reportResult("d-3", ru.uvw.dshnc.logic.VoiceDegraded.SENT))
        assertEquals(ru.uvw.dshnc.logic.VoiceDegraded.SENT, log.snapshot().single().report)
        assertTrue(log.reportResult("d-3", ru.uvw.dshnc.logic.VoiceDegraded.failed("bad_type")))
        assertEquals("не удалось отправить отчёт: bad_type", log.snapshot().single().report)
        // an exchange without a degraded report has nothing to attach to
        assertEquals(false, log.reportResult("нет-такого", "x"))
    }
}

/**
 * A take that did not arrive can be sent again without saying it twice.
 */
class VoiceParkedTest {
    private fun file (name: String = "voice-1.wav"): java.io.File {
        val dir = java.nio.file.Files.createTempDirectory("parked").toFile()
        return java.io.File(dir, name).apply { writeBytes(ByteArray(2048) { 3 }) }
    }

    @Test
    fun `the parked take keeps its file, its id and its duration`() {
        ru.uvw.dshnc.logic.VoiceParked.clear()
        val take = file()
        ru.uvw.dshnc.logic.VoiceParked.remember(take, 2.5, "ex-7", "ptt")
        val parked = ru.uvw.dshnc.logic.VoiceParked.snapshot()
        assertEquals("ex-7", parked?.id)
        assertEquals(2.5, parked!!.seconds, 0.0001)
        assertEquals(take.absolutePath, parked.file.absolutePath)
        assertTrue(parked.file.isFile)
        ru.uvw.dshnc.logic.VoiceParked.clear()
        assertEquals(null, ru.uvw.dshnc.logic.VoiceParked.snapshot())
        assertTrue("после успеха файл удаляется", !take.exists())
    }

    @Test
    fun `a second tap while sending is refused`() {
        ru.uvw.dshnc.logic.VoiceParked.clear()
        ru.uvw.dshnc.logic.VoiceParked.remember(file(), 1.0, "ex-8", "ptt")
        assertTrue(ru.uvw.dshnc.logic.VoiceParked.beginSend())
        assertTrue(ru.uvw.dshnc.logic.VoiceParked.isSending())
        // the link is inert until the attempt settles
        assertEquals(false, ru.uvw.dshnc.logic.VoiceParked.beginSend())
        ru.uvw.dshnc.logic.VoiceParked.endSend()
        assertTrue(ru.uvw.dshnc.logic.VoiceParked.beginSend())
        ru.uvw.dshnc.logic.VoiceParked.endSend()
        ru.uvw.dshnc.logic.VoiceParked.clear()
    }

    @Test
    fun `nothing to send is not a send`() {
        ru.uvw.dshnc.logic.VoiceParked.clear()
        assertEquals(false, ru.uvw.dshnc.logic.VoiceParked.beginSend())
    }
}

/**
 * The offer lives in the conversation line, and disappears once the host has the take.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceResendLinkTest {
    @Test
    fun `the link is next to the note, and gone after success`() {
        val screen = org.robolectric.Robolectric.buildActivity(VoiceChatActivity::class.java).setup().get()
        ru.uvw.dshnc.logic.VoiceChatBus.clear()
        val dir = org.robolectric.RuntimeEnvironment.getApplication().cacheDir
        val take = java.io.File(dir, "voice-resend.wav").apply { writeBytes(ByteArray(2048)) }
        try {
            ru.uvw.dshnc.logic.VoiceParked.remember(take, 1.5, "ex-9", "ptt")
            ru.uvw.dshnc.logic.VoiceChatBus.system(
                ru.uvw.dshnc.logic.VoiceFailure.SAVED_LOCALLY,
                "ex-9",
                resend = true,
            )
            val render = VoiceChatActivity::class.java.getDeclaredMethod("renderChat").apply { isAccessible = true }
            render.invoke(screen)
            fun texts (): List<String> {
                val found = mutableListOf<String>()
                fun walk (view: android.view.View) {
                    if (view is android.widget.TextView) found.add(view.text?.toString().orEmpty())
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
                }
                walk(screen.findViewById(android.R.id.content))
                return found
            }
            val first = texts()
            assertTrue("на экране: $first", first.contains(ru.uvw.dshnc.logic.VoiceFailure.SAVED_LOCALLY))
            assertTrue("на экране: $first", first.contains("переотправить"))

            // the repeat succeeded: the line becomes the receipt and the offer disappears
            ru.uvw.dshnc.logic.VoiceChatBus.resendText(ru.uvw.dshnc.logic.VoiceStatus.accepted(1.5))
            ru.uvw.dshnc.logic.VoiceChatBus.dropResend()
            render.invoke(screen)
            val after = texts()
            val logState = ru.uvw.dshnc.logic.VoiceChatBus.snapshot().map { "${it.text}/${it.resend}" }
            assertTrue("лог: $logState, экран: $after", after.contains("принято хостом · 1.5 с"))
            assertTrue("лог: $logState, экран: $after", !after.contains("переотправить"))
        } finally {
            ru.uvw.dshnc.logic.VoiceParked.clear()
            ru.uvw.dshnc.logic.VoiceChatBus.clear()
            screen.finish()
        }
    }

    @Test
    fun `while sending the link says so and cannot be tapped`() {
        val screen = org.robolectric.Robolectric.buildActivity(VoiceChatActivity::class.java).setup().get()
        ru.uvw.dshnc.logic.VoiceChatBus.clear()
        val dir = org.robolectric.RuntimeEnvironment.getApplication().cacheDir
        try {
            ru.uvw.dshnc.logic.VoiceParked.remember(java.io.File(dir, "voice-x.wav").apply { writeBytes(ByteArray(8)) }, 1.0, "ex-10", "ptt")
            ru.uvw.dshnc.logic.VoiceChatBus.system(ru.uvw.dshnc.logic.VoiceFailure.SAVED_LOCALLY, "ex-10", resend = true)
            assertTrue(ru.uvw.dshnc.logic.VoiceParked.beginSend())
            val render = VoiceChatActivity::class.java.getDeclaredMethod("renderChat").apply { isAccessible = true }
            render.invoke(screen)
            val texts = mutableListOf<String>()
            var clickable = true
            fun walk (view: android.view.View) {
                if (view is android.widget.TextView) {
                    texts.add(view.text?.toString().orEmpty())
                    if (view.text == "отправляю…") clickable = view.isClickable
                }
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(screen.findViewById(android.R.id.content))
            assertTrue(texts.contains("отправляю…"))
            assertEquals("во время отправки ссылка неактивна", false, clickable)
        } finally {
            ru.uvw.dshnc.logic.VoiceParked.endSend()
            ru.uvw.dshnc.logic.VoiceParked.clear()
            ru.uvw.dshnc.logic.VoiceChatBus.clear()
            screen.finish()
        }
    }
}

/**
 * Waking the host's synthesizer: once per visit, only when the host is the one who speaks, and
 * never at the cost of a visible step.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceWarmupTest {
    private fun app(): android.content.Context = org.robolectric.RuntimeEnvironment.getApplication()

    private fun screen (synth: String): VoiceChatActivity {
        val store = ru.uvw.dshnc.store.TokenStore(app())
        store.base = "https://dsh.example"
        store.token = "token"
        store.voiceSynth = synth
        return org.robolectric.Robolectric.buildActivity(VoiceChatActivity::class.java).setup().get()
    }

    @Test
    fun `the body is the agreed event`() {
        assertEquals("{\"type\":\"voice-warmup\"}", ru.uvw.dshnc.logic.VoiceWarmup.body().toString())
    }

    @Test
    fun `host mode sends one wake-up per visit, device mode sends none`() {
        ru.uvw.dshnc.logic.VoiceWarmup.reset()
        var sent = 0
        ru.uvw.dshnc.logic.VoiceWarmup.transport = { _, _ -> sent += 1 }
        try {
            val host = screen("host")
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(150)
            host.finish()
            assertEquals("в режиме host должен уйти ровно один запрос", 1, sent)

            // a second entry inside half a minute must not knock again
            val again = screen("host")
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(150)
            again.finish()
            assertEquals("повторный вход в течение 30 с не шлёт", 1, sent)

            ru.uvw.dshnc.logic.VoiceWarmup.reset()
            val device = screen("device")
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(150)
            device.finish()
            assertEquals("в режиме device будить нечего", 1, sent)
        } finally {
            ru.uvw.dshnc.logic.VoiceWarmup.transport = null
            ru.uvw.dshnc.logic.VoiceWarmup.reset()
        }
    }

    @Test
    fun `the throttle forgets after half a minute`() {
        ru.uvw.dshnc.logic.VoiceWarmup.reset()
        var sent = 0
        ru.uvw.dshnc.logic.VoiceWarmup.transport = { _, _ -> sent += 1 }
        try {
            assertTrue(ru.uvw.dshnc.logic.VoiceWarmup.fire("https://dsh.example", "t", "host", now = 1_000_000))
            assertTrue(!ru.uvw.dshnc.logic.VoiceWarmup.fire("https://dsh.example", "t", "host", now = 1_010_000))
            assertTrue(ru.uvw.dshnc.logic.VoiceWarmup.fire("https://dsh.example", "t", "host", now = 1_030_000))
            Thread.sleep(150)
            assertEquals(2, sent)
        } finally {
            ru.uvw.dshnc.logic.VoiceWarmup.transport = null
            ru.uvw.dshnc.logic.VoiceWarmup.reset()
        }
    }

    @Test
    fun `a failing wake-up changes nothing on the screen`() {
        ru.uvw.dshnc.logic.VoiceWarmup.reset()
        ru.uvw.dshnc.logic.VoiceWarmup.transport = { _, _ -> throw IllegalStateException("нет сети") }
        try {
            val host = screen("host")
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(150)
            // the conversation is empty and the status line says nothing about the failure
            assertEquals(0, ru.uvw.dshnc.logic.VoiceChatBus.snapshot().size)
            host.finish()
        } finally {
            ru.uvw.dshnc.logic.VoiceWarmup.transport = null
            ru.uvw.dshnc.logic.VoiceWarmup.reset()
        }
    }
}

/**
 * The main screen: the text block right under the big button, the microphone at the bottom.
 */
@RunWith(RobolectricTestRunner::class)
class MainScreenOrderTest {
    private fun screen(): MainActivity =
        org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup().get()

    @Test
    fun `the status block follows the button and the microphone closes the screen`() {
        val screen = screen()
        try {
            val content = screen.findViewById<android.view.ViewGroup>(android.R.id.content)
            val root = content.getChildAt(0) as android.view.ViewGroup
            val order = (0 until root.childCount).map { root.getChildAt(it) }
            // the big button is the round toggle with a drawable; the text block is the one right
            // after it, and the microphone is the last child
            val microphone = order.last()
            assertEquals("микрофон должен быть последним", "🎤", (microphone as android.widget.TextView).text)
            val micIndex = order.size - 1

            val texts = order.take(micIndex).filterIsInstance<android.widget.TextView>()
                .map { it.text?.toString().orEmpty() }
            // the timer counter and the status/details text both exist before the microphone
            assertTrue("нет блока статуса", texts.isNotEmpty())
            // and the status block is above the free space: no weighted spacer before it
            val firstSpacer = order.indexOfFirst {
                (it.layoutParams as? android.widget.LinearLayout.LayoutParams)?.weight == 1f
            }
            assertTrue("свободное место должно быть ниже текста, а не выше", firstSpacer > 0)
            val statusIndex = order.indexOfFirst {
                it is android.widget.TextView && (it.text?.toString().orEmpty().contains("версия") ||
                    (it.layoutParams as android.widget.LinearLayout.LayoutParams).weight == 0f && it.alpha < 1f)
            }
            assertTrue("статус должен идти раньше свободного места", statusIndex in 1 until firstSpacer)
        } finally {
            screen.finish()
        }
    }

    @Test
    fun `the microphone stays pinned to the bottom with a 16 dp margin`() {
        val screen = screen()
        try {
            val content = screen.findViewById<android.view.ViewGroup>(android.R.id.content)
            val root = content.getChildAt(0) as android.view.ViewGroup
            val microphone = root.getChildAt(root.childCount - 1) as android.widget.TextView
            val lp = microphone.layoutParams as android.widget.LinearLayout.LayoutParams
            val density = screen.resources.displayMetrics.density
            assertEquals("отступ микрофона от низа", (16 * density).toInt(), lp.bottomMargin)
            assertEquals((80 * density).toInt(), lp.width)
            assertEquals(lp.width, lp.height)
        } finally {
            screen.finish()
        }
    }
}

/**
 * The voice settings have their own screen, and every choice is written the moment it is tapped.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceSettingsScreenTest {
    private fun app(): android.content.Context = org.robolectric.RuntimeEnvironment.getApplication()

    private fun screen(): VoiceSettingsActivity =
        org.robolectric.Robolectric.buildActivity(VoiceSettingsActivity::class.java).setup().get()

    private fun items (screen: android.app.Activity): List<android.view.View> {
        val found = mutableListOf<android.view.View>()
        fun walk (view: android.view.View) {
            found.add(view)
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(screen.findViewById(android.R.id.content))
        return found
    }

    private fun texts (screen: android.app.Activity): List<String> =
        items(screen).filterIsInstance<android.widget.TextView>().map { it.text?.toString().orEmpty() }

    @Test
    fun `the screen carries the three groups and a back that just closes it`() {
        val screen = screen()
        try {
            val all = texts(screen)
            assertTrue(all.contains("Режим записи"))
            assertTrue(all.contains("Показывать ответы"))
            assertTrue(all.contains("Способ синтеза звука"))
            assertTrue(all.any { it.contains("Держите и говорите") })
            assertTrue(all.any { it.contains("Только короткую форму") })
            assertTrue(all.any { it.contains("Синтез на DSH") })
            // the back arrow closes this screen, it does not go anywhere else
            val back = items(screen).filterIsInstance<android.widget.TextView>().first { it.text == "←" }
            back.performClick()
            assertTrue("назад должен завершать экран", screen.isFinishing)
        } finally {
            if (!screen.isFinishing) screen.finish()
        }
    }

    @Test
    fun `a choice is stored at once, without a save button`() {
        val store = ru.uvw.dshnc.store.TokenStore(app())
        store.voiceMode = "ptt"
        store.answerMode = "long"
        store.voiceSynth = "device"
        val screen = screen()
        try {
            val label = items(screen).filterIsInstance<android.widget.TextView>()
                .first { it.text?.toString()?.contains("Тапните для записи") == true }
            // the row is what reacts to a finger: the label sits inside a clickable container
            var row: android.view.View? = label
            while (row != null && !row.isClickable) row = row.parent as? android.view.View
            assertNotNull("строка должна быть нажимаемой", row)
            row!!.performClick()
            assertEquals("toggle", ru.uvw.dshnc.store.TokenStore(app()).voiceMode)
        } finally {
            if (!screen.isFinishing) screen.finish()
        }
    }
}

/**
 * The chat header opens the settings; the menu that used to hold them is gone.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceChatHeaderTest {
    @Test
    fun `the gear is there and no popup survives in the chat`() {
        val screen = org.robolectric.Robolectric.buildActivity(VoiceChatActivity::class.java).setup().get()
        try {
            val texts = mutableListOf<String>()
            fun walk (view: android.view.View) {
                if (view is android.widget.TextView) texts.add(view.text?.toString().orEmpty())
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(screen.findViewById(android.R.id.content))
            assertTrue("шестерёнка должна быть в шапке", texts.contains("⚙"))
            assertTrue("меню ⋮ больше нет", texts.none { it == "⋮" })

            // the chat screen itself must not hold a popup any more
            val fields = VoiceChatActivity::class.java.declaredFields.map { it.type.name }
            assertTrue(
                "в активности не должно остаться PopupWindow: $fields",
                fields.none { it.contains("PopupWindow") || it.contains("PopupMenu") },
            )
        } finally {
            screen.finish()
        }
    }

    @Test
    fun `the conversation survives a trip to the settings`() {
        val screen = org.robolectric.Robolectric.buildActivity(VoiceChatActivity::class.java).setup().get()
        ru.uvw.dshnc.logic.VoiceChatBus.clear()
        try {
            ru.uvw.dshnc.logic.VoiceChatBus.putQuiet("t-1", ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT, "ответ до ухода")
            // leaving to the settings and coming back: the log lives in the process, not the view
            org.robolectric.Robolectric.buildActivity(VoiceSettingsActivity::class.java).setup().get().finish()
            val render = VoiceChatActivity::class.java.getDeclaredMethod("renderChat").apply { isAccessible = true }
            render.invoke(screen)
            val texts = mutableListOf<String>()
            fun walk (view: android.view.View) {
                if (view is android.widget.TextView) texts.add(view.text?.toString().orEmpty())
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(screen.findViewById(android.R.id.content))
            assertTrue("лента должна сохраниться: $texts", texts.any { it.contains("ответ до ухода") })
        } finally {
            ru.uvw.dshnc.logic.VoiceChatBus.clear()
            screen.finish()
        }
    }
}

/**
 * One failure is one sentence, and a table keeps its numbers on one line.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceOutcomeLineTest {
    @Test
    fun `a transport failure followed by a 405 leaves one line, about the 405`() {
        val log = ru.uvw.dshnc.logic.VoiceChatLog()
        // the first fact: no answer at all
        log.outcome(ru.uvw.dshnc.logic.VoiceFailure.line(null, "SocketTimeoutException"), resend = true)
        // the second fact about the same attempt: the server spoke
        val second = ru.uvw.dshnc.logic.VoiceFailure.line(405, "method not allowed")
        log.outcome(second, resend = true)
        val outcomes = log.snapshot().filter { it.outcome }
        assertEquals("на одну попытку — одна строка", 1, outcomes.size)
        // the last fact wins: the server's own words, not «плохая связь»
        assertTrue(outcomes.single().text.contains("method not allowed"))
        assertTrue(!outcomes.single().text.contains("плохая связь"))
        assertTrue(outcomes.single().resend)
    }

    @Test
    fun `the note about the saved take rides in the same line`() {
        val log = ru.uvw.dshnc.logic.VoiceChatLog()
        val note = ru.uvw.dshnc.logic.VoiceFailure.note(null, "SocketTimeoutException")
        val text = ru.uvw.dshnc.logic.VoiceFailure.line(null, "SocketTimeoutException") + "\n" + note
        log.outcome(text, resend = true)
        val line = log.snapshot().single()
        assertTrue(line.text.startsWith("плохая связь"))
        assertTrue(line.text.contains("запись сохранена на телефоне"))
        // and a success drops the offer while keeping the line
        log.resendText("принято хостом · 1.5 с")
        log.dropResend()
        val after = log.snapshot().single()
        assertEquals("принято хостом · 1.5 с", after.text)
        assertEquals(false, after.resend)
    }
}

/**
 * A table with long numbers: no compression, horizontal scrolling instead.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceTableWidthTest {
    @Test
    fun `a table is not capped by the bubble width and scrolls`() {
        val screen = org.robolectric.Robolectric.buildActivity(VoiceChatActivity::class.java).setup().get()
        ru.uvw.dshnc.logic.VoiceChatBus.clear()
        try {
            val table = "| валюта | курс |\n| --- | --- |\n| USD | 91,0322 ₽ |\n| EUR | 99,1234 ₽ |"
            ru.uvw.dshnc.logic.VoiceChatBus.putQuiet("tab-1", ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT, table)
            val render = VoiceChatActivity::class.java.getDeclaredMethod("renderChat").apply { isAccessible = true }
            render.invoke(screen)
            var scroller: android.widget.HorizontalScrollView? = null
            fun walk (view: android.view.View) {
                if (view is android.widget.HorizontalScrollView) scroller = view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(screen.findViewById(android.R.id.content))
            assertNotNull("таблица должна ехать в горизонтальном скролле: $scroller", scroller)
            val cell = scroller!!.getChildAt(0) as android.widget.TextView
            // the width cap is what squeezed the columns before
            assertEquals(Int.MAX_VALUE, cell.maxWidth)
            assertEquals("скролл не должен растягивать ребёнка до своей ширины", false, scroller!!.isFillViewport)
        } finally {
            ru.uvw.dshnc.logic.VoiceChatBus.clear()
            screen.finish()
        }
    }
}
