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
    fun theBoundRulesNameTheTwoLongCommandsAndNothingElse () {
        val rules = VoiceApi.rules()
        assertEquals(2, rules.length())
        val described = (0 until rules.length()).map { index ->
            val rule = rules.getJSONObject(index)
            Triple(rule.optString("api"), rule.optString("method"), rule.optString("rule"))
        }
        assertEquals(
            listOf(
                Triple("voice", "record", "flushAround"),
                Triple("voice", "say", "flushAround"),
            ),
            described,
        )
    }

    @Test
    fun theServerWouldAcceptTheseRules () {
        // the same checks the server makes before it writes anything: known rule names, declared
        // methods, and no second rule for one (api, version, method) pair
        val known = setOf("timing", "flushBefore", "flushAfter", "flushAround")
        val methods = api.schema().optJSONArray("methods")!!
        val declared = (0 until methods.length()).map { methods.getJSONObject(it).optString("method") }
        val seen = mutableSetOf<String>()
        val rules = VoiceApi.rules()
        for (index in 0 until rules.length()) {
            val rule = rules.getJSONObject(index)
            assertEquals("voice", rule.optString("api"))
            assertTrue(rule.optString("rule") in known)
            assertTrue("method ${rule.optString("method")} must be declared", rule.optString("method") in declared)
            val key = rule.optString("method")
            assertTrue("a second rule for $key would be refused", seen.add(key))
        }
    }

    @Test
    fun aSecondRuleForTheSamePairIsTheOneWeDoNotBind () {
        // documented in VoiceApi.rules(): timing on record would be refused by the server, so the
        // duration of a take is measured on the device instead
        val rules = VoiceApi.rules()
        val timing = (0 until rules.length())
            .map { rules.getJSONObject(it) }
            .count { it.optString("rule") == "timing" }
        assertEquals(0, timing)
    }

    @Test
    fun aCancelForSomeoneElsesCommandIsNotOurs () {
        assertNull(api.control("#INTERNAL.CANCEL", JSONObject().put("seq", 4242L)))
        assertNull(api.control("say", JSONObject()))
    }
}
