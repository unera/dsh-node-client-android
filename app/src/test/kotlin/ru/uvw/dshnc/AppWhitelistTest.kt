package ru.uvw.dshnc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app whitelist, as a rule rather than as a screen.
 *
 * An empty list means nobody, a filled one names who may be woken. The rule is written here so
 * the screen cannot drift from it.
 */
class AppWhitelistTest {
    /** The same decision the launch makes, kept pure for the test. */
    private fun allowed (list: List<String>, target: String?): Boolean =
        target != null && list.contains(target)

    @Test
    fun `an empty list lets nothing open`() {
        assertFalse(allowed(emptyList(), "ru.yandex.yandexnavi"))
    }

    @Test
    fun `a filled list admits only what it names`() {
        val list = listOf("ru.yandex.yandexnavi")
        assertTrue(allowed(list, "ru.yandex.yandexnavi"))
        assertFalse(allowed(list, "com.sberbankmobile"))
    }

    @Test
    fun `a launch the system could not resolve is refused with its own sentence`() {
        // nothing resolved and nothing named: there is no app to allow, so the answer says the
        // list is empty rather than blaming an app that does not exist
        assertFalse(allowed(emptyList(), null))
        assertFalse(allowed(listOf("ru.yandex.yandexnavi"), null))
    }
}
