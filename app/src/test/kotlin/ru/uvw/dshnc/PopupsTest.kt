package ru.uvw.dshnc

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import ru.uvw.dshnc.logic.Popups

class PopupsTest {
    @Test
    fun `before Android 14 nobody is asked`() {
        // the permission did not exist: a full screen intent worked on its own
        assertTrue(Popups.allowed(sdk = 33, granted = false))
        assertTrue(Popups.allowed(sdk = 30, granted = false))
    }

    @Test
    fun `since Android 14 the grant decides`() {
        assertTrue(Popups.allowed(sdk = 34, granted = true))
        assertFalse(Popups.allowed(sdk = 34, granted = false))
    }

    @Test
    fun `a newer system is not more permissive on its own`() {
        assertFalse(Popups.allowed(sdk = 35, granted = false))
        assertTrue(Popups.allowed(sdk = 35, granted = true))
    }
}
