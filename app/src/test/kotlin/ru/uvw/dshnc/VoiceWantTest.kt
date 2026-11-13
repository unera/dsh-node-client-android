package ru.uvw.dshnc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.uvw.dshnc.logic.VoiceMode
import ru.uvw.dshnc.logic.VoiceWant

/**
 * The announcement that a person started speaking.
 *
 * The screen records on its own, so this contract is about one thing only: whether the host is
 * listening. An accepted answer changes no local state; a refused one costs the person nothing, so
 * both have to be read the same way every time.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceWantTest {

    @Test
    fun `the body carries the intent and the button habit, nothing else` () {
        val body = VoiceWant.body(VoiceMode.TOGGLE)
        val voice = body.getJSONObject("voice")
        assertEquals("record", voice.getString("want"))
        assertEquals("toggle", voice.getString("mode"))
        // no session and no text: the host is told what is happening, not what was said
        assertEquals(1, body.length())
        assertEquals(2, voice.length())
    }

    @Test
    fun `the agreed 202 is acceptance` () {
        val result = VoiceWant.interpret(202, "{\"delivered\":true}")
        assertTrue(result is VoiceWant.Result.Accepted)
    }

    @Test
    fun `an empty body with 200 is still acceptance` () {
        assertTrue(VoiceWant.interpret(200, "") is VoiceWant.Result.Accepted)
        assertTrue(VoiceWant.interpret(204, null) is VoiceWant.Result.Accepted)
    }

    @Test
    fun `a refusal keeps the server's own words` () {
        val result = VoiceWant.interpret(404, "{\"code\":\"no_session\",\"message\":\"сессия не найдена\"}")
        assertTrue(result is VoiceWant.Result.Failed)
        assertEquals("сессия не найдена (no_session)", (result as VoiceWant.Result.Failed).reason)
    }

    @Test
    fun `a refusal with a broken body still says something` () {
        val result = VoiceWant.interpret(503, "<html>bad gateway</html>")
        assertTrue(result is VoiceWant.Result.Failed)
        assertEquals("HTTP 503", (result as VoiceWant.Result.Failed).reason)
    }

    @Test
    fun `the refused line is the one the person reads` () {
        assertEquals("хост не ответил, запись сохранена локально", VoiceWant.REFUSED_LINE)
        // the announcement never carries the recording itself: the take stays a local file
        val body = VoiceWant.body(VoiceMode.PTT)
        assertTrue(body.getJSONObject("voice").optString("path").isEmpty())
        assertEquals(JSONObject().put("want", "record").put("mode", "ptt").toString(),
            body.getJSONObject("voice").toString())
    }
}
