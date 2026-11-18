package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.uvw.dshnc.logic.IntentRequest

/**
 * What may be asked of the phone, and in what words.
 *
 * The rules are here and not in the view: an allowed action, an allowed scheme, extras that
 * survive a Bundle. A refusal has to be a sentence a person can act on, not a stack trace.
 */
class IntentRequestTest {
    @Test
    fun `a plain link is a request to open it`() {
        val request = IntentRequest.parse(mapOf("data" to "geo:0,0?q=Пречистенка"))
        assertEquals(IntentRequest.VIEW, request.action)
        assertEquals("geo:0,0?q=Пречистенка", request.data)
    }

    @Test
    fun `the schemes a phone opens are allowed, others are not`() {
        for (url in listOf("https://example.com", "tel:+74951234567", "mailto:a@b.c", "sms:+7495", "market://details?id=x")) {
            IntentRequest.parse(mapOf("data" to url))
        }
        val failure = runCatching { IntentRequest.parse(mapOf("data" to "file:///etc/passwd")) }.exceptionOrNull()
        assertTrue("файловая схема должна быть отклонена", failure is IllegalArgumentException)
        assertTrue(failure?.message?.contains("схема не разрешена") == true)
    }

    @Test
    fun `an action nobody allowed is refused`() {
        val failure = runCatching {
            IntentRequest.parse(mapOf("action" to "android.intent.action.FACTORY_RESET"))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message?.contains("не разрешено") == true)
    }

    @Test
    fun `a view without data says what is missing`() {
        val failure = runCatching { IntentRequest.parse(mapOf("action" to IntentRequest.VIEW)) }.exceptionOrNull()
        assertTrue(failure?.message?.contains("нужен data") == true)
    }

    @Test
    fun `extras keep the types that survive a bundle`() {
        val request = IntentRequest.parse(mapOf(
            "action" to "android.intent.action.SEND",
            "type" to "text/plain",
            "extras" to mapOf("android.intent.extra.TEXT" to "привет", "n" to 3, "flag" to true),
        ))
        assertEquals("text/plain", request.type)
        assertEquals("привет", request.extras["android.intent.extra.TEXT"])
        assertEquals(3, request.extras["n"])
        assertEquals(true, request.extras["flag"])
        assertNull(request.data)
    }

    @Test
    fun `an extra that cannot travel is refused with its name`() {
        val failure = runCatching {
            IntentRequest.parse(mapOf("data" to "https://example.com", "extras" to mapOf("bad" to listOf(1, 2))))
        }.exceptionOrNull()
        assertTrue(failure?.message?.contains("bad") == true)
    }

    @Test
    fun `an app scheme of its own is allowed when the app is named`() {
        // "navigate to a point" in the navigator's own words needs its own scheme; without a
        // package the same call is refused, because nobody said who should answer.
        val refused = runCatching {
            IntentRequest.parse(mapOf("data" to "yandexnavi://build_route_on_map?lat_to=55.7"))
        }.exceptionOrNull()
        assertTrue("схема без указания приложения должна быть отклонена", refused is IllegalArgumentException)
        val pinned = IntentRequest.parse(mapOf(
            "data" to "yandexnavi://build_route_on_map?lat_to=55.7",
            "package" to "ru.yandex.yandexnavi",
        ))
        assertEquals("ru.yandex.yandexnavi", pinned.packageName)
    }

    @Test
    fun `a command needs an app to receive it`() {
        // A broadcast or a service has no screen to fall back on: the caller names the package,
        // and without one the request is refused before anything is sent.
        val refused = runCatching {
            IntentRequest.parse(mapOf("action" to "com.example.SET_ON"), IntentRequest.Kind.BROADCAST)
        }.exceptionOrNull()
        assertTrue(refused is IllegalArgumentException)
        assertTrue(refused?.message?.contains("package") == true)
        val sent = IntentRequest.parse(
            mapOf("action" to "com.example.SET_ON", "package" to "com.example.app"),
            IntentRequest.Kind.BROADCAST,
        )
        assertEquals(IntentRequest.Kind.BROADCAST, sent.kind)
        assertEquals("com.example.app", sent.packageName)
        // no data is required for a command: the action and the extras are the whole message
        assertNull(sent.data)
    }

    @Test
    fun `a nested object becomes a bundle and travels with the command`() {
        // The Locale/Tasker plugin protocol carries its settings as one Bundle extra:
        // `com.twofortyfouram.locale.intent.extra.BUNDLE`. Without nesting it cannot be spoken.
        val request = IntentRequest.parse(
            mapOf(
                "action" to "com.twofortyfouram.locale.intent.action.FIRE_SETTING",
                "package" to "com.v2raytun.android",
                "extras" to mapOf(
                    "com.twofortyfouram.locale.intent.extra.BLURB" to "start",
                    "com.twofortyfouram.locale.intent.extra.BUNDLE" to mapOf("action" to "start", "on" to true),
                ),
            ),
            IntentRequest.Kind.BROADCAST,
        )
        val bundle = request.extras["com.twofortyfouram.locale.intent.extra.BUNDLE"]
        assertTrue("вложенный объект должен остаться объектом", bundle is Map<*, *>)
        @Suppress("UNCHECKED_CAST")
        val nested = bundle as Map<String, Any>
        assertEquals("start", nested["action"])
        assertEquals(true, nested["on"])
    }

    @Test
    fun `a package pins the request to one app`() {
        val request = IntentRequest.parse(mapOf("data" to "geo:55.75,37.61", "package" to "ru.yandex.yandexmaps"))
        assertEquals("ru.yandex.yandexmaps", request.packageName)
    }
}
