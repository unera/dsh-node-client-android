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
        assertEquals(listOf("caps", "say", "stop", "record"), names)
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
    fun recordNeedsAPlaceToPutTheFile () {
        val outcome = api.handle("record", JSONObject().put("hostPath", "  "), 3L, 0.0) { }
        assertEquals("error", outcome.optString("status"))
        assertEquals("bad_args", outcome.optString("code"))
    }

    @Test
    fun recordRefusesAModeItDoesNotKnow () {
        val outcome = api.handle(
            "record",
            JSONObject().put("hostPath", "/tmp/voice.wav").put("mode", "машу руками"),
            4L,
            0.0,
        ) { }
        assertEquals("bad_args", outcome.optString("code"))
    }

    @Test
    fun recordWithoutALinkToTheServerSaysSoInsteadOfHanging () {
        // no uploader: the node has not reached the host, and a take has nowhere to travel
        val outcome = api.handle("record", JSONObject().put("hostPath", "/tmp/voice.wav"), 5L, 0.0) { }
        assertEquals("error", outcome.optString("status"))
        assertEquals("voice_unavailable", outcome.optString("code"))
    }

    @Test
    fun aCancelForSomeoneElsesCommandIsNotOurs () {
        assertNull(api.control("#INTERNAL.CANCEL", JSONObject().put("seq", 4242L)))
        assertNull(api.control("say", JSONObject()))
    }
}
