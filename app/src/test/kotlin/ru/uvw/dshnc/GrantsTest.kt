package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.uvw.dshnc.logic.Grants

/**
 * Which switch a call needs, and what a refused call answers.
 *
 * The mapping is the whole rule, so it is checked method by method: a method that needs no
 * switch must stay callable with every switch off, or a person who turned everything off could
 * not even ask whether the node is alive.
 */
class GrantsTest {
    @Test
    fun `shell needs its own switch`() {
        assertEquals(Grants.Grant.SHELL, Grants.needed("exec"))
        assertTrue(Grants.allows("exec") { false }.not())
    }

    @Test
    fun `alarms and intents are separate switches`() {
        assertEquals(Grants.Grant.ALARMS, Grants.needed("alarm_set"))
        assertEquals(Grants.Grant.ALARMS, Grants.needed("alarm_next"))
        assertEquals(Grants.Grant.INTENTS, Grants.needed("intent_start"))
        // turning alarms off leaves launches alone: they are different promises
        assertTrue(Grants.allows("intent_start") { it != Grants.Grant.INTENTS }.not())
        assertTrue(Grants.allows("intent_start") { it != Grants.Grant.ALARMS })
    }

    @Test
    fun `always allowed methods stay allowed with every switch off`() {
        for (method in listOf("device_status", "device_info", "ping", "id")) {
            assertNull("$method не должен требовать переключателя", Grants.needed(method))
            assertTrue(Grants.allows(method) { false })
        }
    }

    @Test
    fun `the vpn switch guards the vpn methods and nothing else`() {
        assertEquals(Grants.Grant.VPN, Grants.needed("vpn"))
        assertEquals(Grants.Grant.VPN, Grants.needed("vpn_bind"))
        // switching someone's tunnel is a bigger promise than opening a screen
        assertTrue(Grants.Grant.INTENTS != Grants.needed("vpn"))
        assertTrue(Grants.allows("vpn") { it == Grants.Grant.VPN })
        assertFalse(Grants.allows("vpn") { it != Grants.Grant.VPN })
    }

    @Test
    fun `silent commands have a switch of their own`() {
        assertEquals(Grants.Grant.COMMANDS, Grants.needed("intent_broadcast"))
        assertEquals(Grants.Grant.COMMANDS, Grants.needed("intent_service"))
        assertEquals(Grants.Grant.INTENTS, Grants.needed("intent_start"))
        // turning launches off leaves commands alone, and the other way round
        assertTrue(Grants.allows("intent_broadcast") { it != Grants.Grant.INTENTS })
        assertFalse(Grants.allows("intent_broadcast") { it != Grants.Grant.COMMANDS })
    }

    @Test
    fun `a refusal names the switch and where it lives`() {
        val text = Grants.refusal(Grants.Grant.SHELL)
        assertTrue(text.contains("Команды оболочки"))
        assertTrue(text.contains("Разрешения"))
    }

    @Test
    fun `files and debug are separate`() {
        assertEquals(Grants.Grant.FILES, Grants.needed("read"))
        assertEquals(Grants.Grant.FILES, Grants.needed("roots"))
        assertEquals(Grants.Grant.DEBUG, Grants.needed("debug_info"))
        assertFalse(Grants.allows("read") { it != Grants.Grant.FILES })
    }
}
