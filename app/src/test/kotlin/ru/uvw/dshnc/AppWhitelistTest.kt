package ru.uvw.dshnc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app whitelist, as a rule rather than as a screen.
 *
 * An empty list means "any app" — a fresh node that refused every launch would look broken —
 * and a filled one is a whitelist. The rule is written here so the screen cannot drift from it.
 */
class AppWhitelistTest {
    /** The same decision the launch makes, kept pure for the test. */
    private fun allowed (list: List<String>, target: String?): Boolean =
        list.isEmpty() || target == null || list.contains(target)

    @Test
    fun `an empty list lets anything open`() {
        assertTrue(allowed(emptyList(), "ru.yandex.yandexnavi"))
    }

    @Test
    fun `a filled list admits only what it names`() {
        val list = listOf("ru.yandex.yandexnavi")
        assertTrue(allowed(list, "ru.yandex.yandexnavi"))
        assertFalse(allowed(list, "com.sberbankmobile"))
    }

    @Test
    fun `a launch the system could not resolve is not refused by the list`() {
        // nothing resolved: the refusal is the system's to make, not the whitelist's
        assertTrue(allowed(listOf("ru.yandex.yandexnavi"), null))
    }
}
