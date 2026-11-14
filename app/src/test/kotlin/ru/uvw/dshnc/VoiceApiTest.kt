package ru.uvw.dshnc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.uvw.dshnc.api.VoiceApi

/**
 * The `voice` API's contract: what the server may ask for and what it gets back.
 *
 * The speaking itself is not tested here — Robolectric has no speech engine, and pretending it
 * does would only test the shadow. What is tested is the part the server depends on: the schema
 * it reads at announce time, and the refusals it must understand instead of a hang.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceApiTest {
    private lateinit var api: VoiceApi

    @Before
    fun setUp () {
        api = VoiceApi(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun schemaDeclaresTheThreeMethods () {
        val schema = api.schema()
        assertEquals("voice", schema.optString("name"))
        assertTrue(schema.optString("version").startsWith("android-"))
        val methods = schema.optJSONArray("methods")
        assertNotNull(methods)
        val names = (0 until methods!!.length()).map { methods.getJSONObject(it).optString("method") }
        assertEquals(listOf("caps", "say", "stop", "chat", "activity", "play", "record"), names)
    }

    @Test
    fun sayRequiresTextAndNothingElse () {
        val methods = api.schema().optJSONArray("methods")!!
        val say = (0 until methods.length())
            .map { methods.getJSONObject(it) }
            .first { it.optString("method") == "say" }
        val args = say.optJSONObject("args")!!
        assertEquals("object", args.optString("type"))
        val required = args.optJSONArray("required")!!
        assertEquals(1, required.length())
        assertEquals("text", required.optString(0))
        val properties = args.optJSONObject("properties")!!
        assertNotNull(properties.optJSONObject("language"))
        assertNotNull(properties.optJSONObject("flush"))
        assertNotNull(properties.optJSONObject("timeout"))
    }

    @Test
    fun anUnknownMethodIsRefusedByCode () {
        val outcome = api.handle("shout", JSONObject(), 1L, 0.0) { }
        assertEquals("error", outcome.optString("status"))
        assertEquals("unknown_method", outcome.optString("code"))
    }

    @Test
    fun blankTextIsBadArgsNotSilence () {
        val outcome = api.handle("say", JSONObject().put("text", "   "), 2L, 0.0) { }
        assertEquals("error", outcome.optString("status"))
        assertEquals("bad_args", outcome.optString("code"))
    }

    @Test
    fun recordAsksForNothingButItsOwnSettings () {
        val methods = api.schema().optJSONArray("methods")!!
        val record = (0 until methods.length())
            .map { methods.getJSONObject(it) }
            .first { it.optString("method") == "record" }
        val args = record.optJSONObject("args")!!
        // no destination on this side: the host pulls the file with files.copyFromDevice
        assertNull(args.optJSONArray("required"))
        val properties = args.optJSONObject("properties")!!
        assertNotNull(properties.optJSONObject("mode"))
        assertNotNull(properties.optJSONObject("maxSeconds"))
        assertNotNull(properties.optJSONObject("timeout"))
        assertNull(properties.optJSONObject("hostPath"))
    }

    @Test
    fun recordRefusesAModeItDoesNotKnow () {
        val outcome = api.handle(
            "record",
            JSONObject().put("mode", "машу руками"),
            4L,
            0.0,
        ) { }
        assertEquals("bad_args", outcome.optString("code"))
    }



    @Test
    fun playIsDeclaredWithAPathAndNothingElseRequired () {
        val methods = api.schema().optJSONArray("methods")!!
        val play = (0 until methods.length())
            .map { methods.getJSONObject(it) }
            .first { it.optString("method") == "play" }
        val args = play.optJSONObject("args")!!
        val required = args.optJSONArray("required")!!
        assertEquals(1, required.length())
        assertEquals("path", required.optString(0))
        assertNotNull(args.optJSONObject("properties")!!.optJSONObject("seconds"))
    }

    @Test
    fun playRefusesAnEmptyPathByCode () {
        val outcome = api.handle("play", JSONObject().put("path", "  "), 9L, 0.0) { }
        assertEquals("error", outcome.optString("status"))
        assertEquals("bad_args", outcome.optString("code"))
    }

    @Test
    fun playWithoutALinkToTheServerDoesNotThrow () {
        // no downloader: the node has not reached the host, so there is nothing to fetch with
        val outcome = api.handle("play", JSONObject().put("path", "/var/lib/dsh/voice/a.wav"), 10L, 0.0) { }
        assertEquals("error", outcome.optString("status"))
        assertEquals("voice_unavailable", outcome.optString("code"))
    }

    @Test
    fun stopIsSafeWhenNothingIsPlayingOrSpeaking () {
        val outcome = api.handle("stop", JSONObject(), 11L, 0.0) { }
        assertEquals("ok", outcome.optString("status"))
        assertEquals(false, outcome.optJSONObject("payload")!!.optBoolean("playing"))
    }


    @Test
    fun chatIsDeclaredWithRoleAndTextRequired () {
        val methods = api.schema().optJSONArray("methods")!!
        val chat = (0 until methods.length())
            .map { methods.getJSONObject(it) }
            .first { it.optString("method") == "chat" }
        val args = chat.optJSONObject("args")!!
        val required = args.optJSONArray("required")!!
        assertEquals(2, required.length())
        assertEquals("role", required.optString(0))
        assertEquals("text", required.optString(1))
        assertNotNull(args.optJSONObject("properties")!!.optJSONObject("id"))
    }

    @Test
    fun chatRefusesAnUnknownRoleAndBlankText () {
        val badRole = api.handle("chat", JSONObject().put("role", "system").put("text", "привет"), 20L, 0.0) { }
        assertEquals("bad_args", badRole.optString("code"))
        val noRole = api.handle("chat", JSONObject().put("text", "привет"), 21L, 0.0) { }
        assertEquals("bad_args", noRole.optString("code"))
        val blank = api.handle("chat", JSONObject().put("role", "user").put("text", "   "), 22L, 0.0) { }
        assertEquals("bad_args", blank.optString("code"))
    }

    @Test
    fun chatClippingAndPairingHappenInTheModel () {
        // the method only clips and stores; the pairing is covered by VoiceChatTest
        val long = "я".repeat(4100)
        val outcome = api.handle(
            "chat",
            JSONObject().put("role", "user").put("text", long).put("id", "x-1"),
            23L,
            0.0,
        ) { }
        assertEquals("ok", outcome.optString("status"))
        assertEquals(true, outcome.optJSONObject("payload")!!.optBoolean("queued"))
        val item = ru.uvw.dshnc.logic.VoiceInbox.drain().single() as ru.uvw.dshnc.logic.VoiceInbox.Item.Chat
        assertEquals(ru.uvw.dshnc.logic.VoiceChat.MAX_TEXT, item.text.length)
    }


    @Test
    fun chatQueuesTheLineAndAnswersAtOnce () {
        ru.uvw.dshnc.logic.VoiceInbox.clear()
        ru.uvw.dshnc.logic.VoiceChatBus.clear()
        try {
            val outcome = api.handle(
                "chat",
                JSONObject().put("role", "assistant").put("text", "ответ").put("id", "q-1"),
                30L,
                0.0,
            ) { }
            assertEquals("ok", outcome.optString("status"))
            assertEquals(true, outcome.optJSONObject("payload")!!.optBoolean("queued"))
            // nothing is drawn by the handler: the screen drains the queue itself
            assertEquals(1, ru.uvw.dshnc.logic.VoiceInbox.size())
            val item = ru.uvw.dshnc.logic.VoiceInbox.drain().single() as ru.uvw.dshnc.logic.VoiceInbox.Item.Chat
            assertEquals("ответ", item.text)
            assertEquals("q-1", item.id)
            assertEquals(ru.uvw.dshnc.logic.VoiceChat.Role.ASSISTANT, item.role)
            assertEquals(0, ru.uvw.dshnc.logic.VoiceChatBus.snapshot().size)
        } finally {
            ru.uvw.dshnc.logic.VoiceInbox.clear()
        }
    }

    @Test
    fun activityQueuesOneStepAndRefusesANegativeNumber () {
        ru.uvw.dshnc.logic.VoiceInbox.clear()
        try {
            val outcome = api.handle(
                "activity",
                JSONObject().put("n", 17).put("name", "bash").put("id", "q-1"),
                31L,
                0.0,
            ) { }
            assertEquals("ok", outcome.optString("status"))
            assertEquals(true, outcome.optJSONObject("payload")!!.optBoolean("queued"))
            val item = ru.uvw.dshnc.logic.VoiceInbox.drain().single() as ru.uvw.dshnc.logic.VoiceInbox.Item.Activity
            assertEquals(17L, item.n)
            assertEquals("bash", item.name)
            val bad = api.handle("activity", JSONObject().put("n", -3), 32L, 0.0) { }
            assertEquals("bad_args", bad.optString("code"))
        } finally {
            ru.uvw.dshnc.logic.VoiceInbox.clear()
        }
    }

    @Test
    fun theVoiceApiBindsNoRulesAtAll () {
        // flushAround here was a mistake with a measured cost: the barrier runs only on an empty
        // pool, and a take lasts up to 45 s of human time, so every later command queued behind it
        assertEquals(0, VoiceApi.rules().length())
    }

    @Test
    fun aCancelForSomeoneElsesCommandIsNotOurs () {
        assertNull(api.control("#INTERNAL.CANCEL", JSONObject().put("seq", 4242L)))
        assertNull(api.control("say", JSONObject()))
    }
}
