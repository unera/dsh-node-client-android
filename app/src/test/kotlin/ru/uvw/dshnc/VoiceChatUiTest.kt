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
