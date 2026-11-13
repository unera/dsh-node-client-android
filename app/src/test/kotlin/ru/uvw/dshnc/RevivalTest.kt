package ru.uvw.dshnc

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import ru.uvw.dshnc.logic.Revival

class RevivalTest {
    @Test
    fun `an ordinary node comes up`() {
        assertTrue(Revival.allowed(enabled = true, hasToken = true, replaced = false))
    }

    @Test
    fun `a node turned off stays down`() {
        assertFalse(Revival.allowed(enabled = false, hasToken = true, replaced = false))
    }

    @Test
    fun `a node without a token has nothing to say`() {
        assertFalse(Revival.allowed(enabled = true, hasToken = false, replaced = false))
    }

    @Test
    fun `a node whose token was revoked comes up to ask for a new one`() {
        // the desktop client knocks on the same door, and there it is the normal way back
        assertTrue(Revival.allowed(enabled = true, hasToken = false, replaced = false, canEnroll = true))
    }

    @Test
    fun `the right to enrol does not revive a displaced instance`() {
        // otherwise the two instances ping-pong again, only through the admin panel this time
        assertFalse(Revival.allowed(enabled = true, hasToken = false, replaced = true, canEnroll = true))
    }

    @Test
    fun `a displaced instance stays down even though it is enabled`() {
        // the exact shape of the ping-pong: on, with a token, and beaten by a newer instance
        assertFalse(Revival.allowed(enabled = true, hasToken = true, replaced = true))
    }

    @Test
    fun `the disabled state does not hide the displaced one`() {
        assertFalse(Revival.allowed(enabled = false, hasToken = true, replaced = true))
        assertFalse(Revival.allowed(enabled = false, hasToken = false, replaced = true))
    }
}
