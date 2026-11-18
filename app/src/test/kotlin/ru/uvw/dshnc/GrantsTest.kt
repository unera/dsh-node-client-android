package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.uvw.dshnc.logic.Grants
import ru.uvw.dshnc.logic.VpnClient

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
    fun `a command aimed at a vpn client is recognised as one`() {
        // Without this, intent_broadcast walks around the VPN switch and makes it decorative.
        val full = "com.v2raytun.android.receiver.TaskerReceiver"
        assertTrue(VpnClient.byCommand("com.v2raytun.android", full) != null)
        assertTrue(VpnClient.byCommand("com.v2raytun.android", ".receiver.TaskerReceiver")?.type == "v2raytun")
        assertTrue(VpnClient.byCommand("com.v2raytun.android", "receiver.TaskerReceiver")?.type == "v2raytun")
        assertTrue(VpnClient.byCommand("com.v2raytun.android", "TaskerReceiver")?.type == "v2raytun")
        assertTrue(VpnClient.byCommand("com.v2raytun.android", null)?.type == "v2raytun")
        // a different receiver of the same app is not the VPN command
        assertTrue(VpnClient.byCommand("com.v2raytun.android", "com.v2raytun.android.receiver.BootReceiver") == null)
        // and another package is nobody's
        assertTrue(VpnClient.byCommand("org.telegram.messenger", full) == null)
    }

    @Test
    fun `a known vpn client is named, never described by a caller`() {
        // The list is the allow list: a caller names "v2raytun", and the node knows the package
        // and the receiver. "Some package, some action" is a broadcast primitive, not a VPN.
        val client = VpnClient.byType("v2raytun")
        assertTrue(client != null)
        assertEquals("com.v2raytun.android", client?.packageName)
        assertEquals("com.v2raytun.android.receiver.TaskerReceiver", client?.receiver)
        assertTrue(VpnClient.byType("V2RayTun") != null)
        assertTrue(VpnClient.byType("openvpn") == null)
        assertTrue(VpnClient.byType("") == null)
        // a switch per client, so one client cannot be switched by another's permission
        assertEquals("vpn-v2raytun", client?.grantKey())
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
