package ru.uvw.dshnc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.uvw.dshnc.logic.VoiceChat
import ru.uvw.dshnc.logic.VoiceChatBus
import ru.uvw.dshnc.logic.VoiceChatLog

/**
 * The conversation the screen draws: who said what, and how a take becomes its own transcript.
 *
 * The pairing rule is the part worth testing without a screen: a take is a line without words, the
 * transcript arrives later with the same exchange id, and the person must never see the take twice.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceChatTest {

    @Test
    fun `the two wire roles are read, and nothing else is a role` () {
        assertEquals(VoiceChat.Role.USER, VoiceChat.Role.parse("user"))
        assertEquals(VoiceChat.Role.ASSISTANT, VoiceChat.Role.parse(" ASSISTANT "))
        assertEquals(null, VoiceChat.Role.parse("system"))
        assertEquals(null, VoiceChat.Role.parse(""))
        assertEquals(null, VoiceChat.Role.parse(null))
    }

    @Test
    fun `a long text is cut and marked` () {
        val text = "а".repeat(VoiceChat.MAX_TEXT + 50)
        val clipped = VoiceChat.clip(text)
        assertEquals(VoiceChat.MAX_TEXT, clipped.length)
        assertTrue(clipped.endsWith("…"))
        // a text within the limit keeps its own words, trimmed at the edges
        assertEquals("коротко", VoiceChat.clip("  коротко  "))
    }

    @Test
    fun `a transcript replaces the take it belongs to` () {
        val log = VoiceChatLog()
        assertEquals(VoiceChatLog.Change.ADDED, log.recording("x-1", "вы · 3.2 с"))
        assertEquals(VoiceChatLog.Change.REPLACED, log.put("x-1", VoiceChat.Role.USER, "включи свет"))
        val entries = log.snapshot()
        assertEquals(1, entries.size)
        assertEquals("включи свет", entries[0].text)
        assertTrue(!entries[0].placeholder)
    }

    @Test
    fun `the same transcript twice does not become two lines` () {
        val log = VoiceChatLog()
        log.recording("x-1", "вы · 3.2 с")
        log.put("x-1", VoiceChat.Role.USER, "включи свет")
        val second = log.put("x-1", VoiceChat.Role.USER, "включи свет")
        assertEquals(VoiceChatLog.Change.IGNORED, second)
        assertEquals(1, log.snapshot().size)
        assertEquals(0, log.snapshot().count { it.text == "вы · 3.2 с" })
    }

    @Test
    fun `an answer is appended and carries its exchange` () {
        val log = VoiceChatLog()
        log.recording("x-1", "вы · 4.0 с")
        log.put("x-1", VoiceChat.Role.USER, "который час")
        log.put("x-1", VoiceChat.Role.ASSISTANT, "Половина шестого.")
        val entries = log.snapshot()
        assertEquals(listOf("который час", "Половина шестого."), entries.map { it.text })
        assertEquals(listOf(VoiceChat.Role.USER, VoiceChat.Role.ASSISTANT), entries.map { it.role })
        assertEquals(listOf("x-1", "x-1"), entries.map { it.id })
    }

    @Test
    fun `two exchanges in a row do not mix` () {
        val log = VoiceChatLog()
        log.recording("a", "вы · 2.0 с")
        log.recording("b", "вы · 3.0 с")
        log.put("a", VoiceChat.Role.USER, "первый вопрос")
        log.put("b", VoiceChat.Role.USER, "второй вопрос")
        log.put("a", VoiceChat.Role.ASSISTANT, "ответ на первый")
        log.put("b", VoiceChat.Role.ASSISTANT, "ответ на второй")
        val texts = log.snapshot().map { it.text }
        assertEquals(
            listOf("первый вопрос", "второй вопрос", "ответ на первый", "ответ на второй"),
            texts,
        )
    }

    @Test
    fun `a transcript for an unknown exchange is simply a line` () {
        val log = VoiceChatLog()
        val change = log.put("never-seen", VoiceChat.Role.USER, "слова без записи")
        assertEquals(VoiceChatLog.Change.ADDED, change)
        assertEquals(1, log.snapshot().size)
    }


    @Test
    fun `the screen is notified when a line arrives` () {
        var notified = 0
        VoiceChatBus.clear()
        VoiceChatBus.onChanged = { notified += 1 }
        try {
            VoiceChatBus.recording("x-9", "вы · 1.5 с")
            assertEquals(1, notified)
            VoiceChatBus.put("x-9", VoiceChat.Role.USER, "привет")
            assertEquals(2, notified)
            VoiceChatBus.put("x-9", VoiceChat.Role.ASSISTANT, "привет и тебе")
            assertEquals(3, notified)
        } finally {
            VoiceChatBus.onChanged = null
            VoiceChatBus.clear()
        }
    }

    @Test
    fun `a duplicate line does not wake the screen` () {
        var notified = 0
        VoiceChatBus.clear()
        try {
            VoiceChatBus.put("y-1", VoiceChat.Role.USER, "одна и та же фраза", )
            VoiceChatBus.onChanged = { notified += 1 }
            val change = VoiceChatBus.put("y-1", VoiceChat.Role.USER, "одна и та же фраза")
            assertEquals(VoiceChatLog.Change.IGNORED, change)
            assertEquals(0, notified)
        } finally {
            VoiceChatBus.onChanged = null
            VoiceChatBus.clear()
        }
    }

    @Test
    fun `lines that arrive while the screen is closed are drawn on return` () {
        VoiceChatBus.clear()
        try {
            // the screen is closed: nobody listens to the bus
            VoiceChatBus.onChanged = null
            VoiceChatBus.put("z-1", VoiceChat.Role.USER, "вопрос без экрана")
            VoiceChatBus.put("z-1", VoiceChat.Role.ASSISTANT, "ответ без экрана")
            // the screen opens: it draws the whole log, which is what renderChat does
            val texts = VoiceChatBus.snapshot().map { it.text }
            assertEquals(listOf("вопрос без экрана", "ответ без экрана"), texts)
        } finally {
            VoiceChatBus.clear()
        }
    }

    @Test
    fun `service notes and clearing behave` () {
        val log = VoiceChatLog()
        log.system("хост не принял, сохранено локально")
        assertEquals(VoiceChat.Role.SYSTEM, log.snapshot()[0].role)
        log.clear()
        assertEquals(0, log.snapshot().size)
    }

    @Test
    fun `the log does not grow without bound` () {
        val log = VoiceChatLog(limit = 3)
        repeat(5) { index -> log.system("заметка $index") }
        assertEquals(3, log.snapshot().size)
        assertEquals("заметка 2", log.snapshot()[0].text)
    }
}

/**
 * A press that lasted a fifth of a second is not a remark: sending it costs a round trip and
 * produces an answer to a question nobody asked (a live run came back «良いです。»).
 */
class VoiceTakeTest {
    @Test
    fun `a tap of two tenths of a second is refused` () {
        assertEquals("слишком коротко, не отправлено", ru.uvw.dshnc.logic.VoiceTake.rejectReason(0.2, 20_000))
    }

    @Test
    fun `half a second is a word and goes through` () {
        assertEquals(null, ru.uvw.dshnc.logic.VoiceTake.rejectReason(0.5, 32_000))
    }

    @Test
    fun `the boundary is exactly four tenths` () {
        // just below the threshold is still a tap, at the threshold it is a take
        assertEquals("слишком коротко, не отправлено", ru.uvw.dshnc.logic.VoiceTake.rejectReason(0.399, 20_000))
        assertEquals(null, ru.uvw.dshnc.logic.VoiceTake.rejectReason(ru.uvw.dshnc.logic.VoiceTake.MIN_SECONDS, 20_000))
    }

    @Test
    fun `a long take with almost no bytes is silence, not speech` () {
        assertEquals("ничего не сказано, не отправлено", ru.uvw.dshnc.logic.VoiceTake.rejectReason(2.0, 1_500))
        assertEquals(null, ru.uvw.dshnc.logic.VoiceTake.rejectReason(2.0, ru.uvw.dshnc.logic.VoiceTake.MIN_BYTES))
    }

    @Test
    fun `a nonsensical duration is refused rather than sent` () {
        assertEquals("слишком коротко, не отправлено", ru.uvw.dshnc.logic.VoiceTake.rejectReason(0.0, 20_000))
        assertEquals("слишком коротко, не отправлено", ru.uvw.dshnc.logic.VoiceTake.rejectReason(Double.NaN, 20_000))
    }
}

/**
 * What the screen shows for one line, and what it says: a dump is read, a sentence is heard.
 */
class VoiceChatRenderTest {
    @Test
    fun `the spoken text wins over the displayed one` () {
        assertEquals("Свет включил.", ru.uvw.dshnc.logic.VoiceChat.speechText("$ ls -la …", "Свет включил."))
        assertEquals("показать", ru.uvw.dshnc.logic.VoiceChat.speechText("показать", ""))
        assertEquals("показать", ru.uvw.dshnc.logic.VoiceChat.speechText("показать", null))
    }

    @Test
    fun `code fences disappear and their content is marked as code` () {
        val text = "Вот вывод:\n```bash\nls -la\ntotal 0\n```\nи всё."
        val segments = ru.uvw.dshnc.logic.VoiceChat.segments(text)
        assertEquals(3, segments.size)
        assertEquals(false, segments[0].code)
        assertEquals(true, segments[1].code)
        assertEquals("ls -la\ntotal 0", segments[1].text)
        assertEquals(false, segments[2].code)
        // no marker survives anywhere, in any segment
        assertTrue(segments.none { it.text.contains("```") })
    }

    @Test
    fun `an unclosed fence still renders as code` () {
        val segments = ru.uvw.dshnc.logic.VoiceChat.segments("до\n```\nбез конца")
        assertEquals(listOf(false, true), segments.map { it.code })
        assertEquals("без конца", segments[1].text)
    }

    @Test
    fun `plain text is one prose segment` () {
        val segments = ru.uvw.dshnc.logic.VoiceChat.segments("Привет.\nКак дела?")
        assertEquals(1, segments.size)
        assertEquals("Привет.\nКак дела?", segments[0].text)
    }
}

/**
 * Captions and step counts: the receipt belongs to the take, the steps belong to the answer.
 */
class VoiceChatCaptionTest {
    @Test
    fun `the receipt is dropped once the transcript arrives` () {
        val log = ru.uvw.dshnc.logic.VoiceChatLog()
        log.recording("e-1", "вы · 1.5 с")
        assertTrue(log.note("e-1", "принято хостом · 1.5 с"))
        assertEquals("принято хостом · 1.5 с", log.snapshot().single().note)
        log.put("e-1", ru.uvw.dshnc.logic.VoiceChat.Role.USER, "включи свет")
        assertEquals(null, log.snapshot().single().note)
    }

    @Test
    fun `a receipt for a transcript that already came is not added` () {
        val log = ru.uvw.dshnc.logic.VoiceChatLog()
        log.recording("e-2", "вы · 1.5 с")
        log.put("e-2", ru.uvw.dshnc.logic.VoiceChat.Role.USER, "включи свет")
        assertEquals(false, log.note("e-2", "принято хостом · 1.5 с"))
        assertEquals(null, log.snapshot().single().note)
    }

    @Test
    fun `steps ride on the answer and are forgotten with it` () {
        val log = ru.uvw.dshnc.logic.VoiceChatLog()
        log.recording("e-3", "вы · 2.0 с")
        log.put("e-3", ru.uvw.dshnc.logic.VoiceChat.Role.USER, "который час")
        log.countStep("e-3")
        log.put("e-3", ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT, "Половина шестого.")
        val answer = log.snapshot().last()
        assertEquals(1, answer.steps)
        // the counter is not history: another answer for the same exchange starts from zero
        log.put("e-3", ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT, "И ещё: полшестого.")
        assertEquals(null, log.snapshot().last().steps)
    }

    @Test
    fun `an answer with no steps carries no caption` () {
        val log = ru.uvw.dshnc.logic.VoiceChatLog()
        log.put("e-4", ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT, "просто ответ")
        assertEquals(null, log.snapshot().single().steps)
    }
}
