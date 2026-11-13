package ru.uvw.dshnc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.uvw.dshnc.logic.VoiceChat
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
