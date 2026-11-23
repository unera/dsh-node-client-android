package ru.uvw.dshnc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.uvw.dshnc.logic.Updater

/**
 * Version comparison decides whether a phone ever sees an update, and it fails quietly: a wrong
 * answer means "обновлений нет" forever. These are the cases a plain string comparison gets wrong.
 */
class UpdaterTest {
    @Test
    fun hundredIsNewerThanEightySix() {
        assertTrue(Updater.newer("0.10.100", "0.10.86"))
        assertFalse(Updater.newer("0.10.9", "0.10.86"))
    }

    @Test
    fun minorBeatsPatch() {
        assertTrue(Updater.newer("0.11.0", "0.10.86"))
        assertFalse(Updater.newer("0.9.99", "0.10.86"))
    }

    @Test
    fun sameVersionIsNotAnUpdate() {
        assertFalse(Updater.newer("0.10.87", "0.10.87"))
        assertFalse(Updater.newer("0.10", "0.10.0"))
        assertFalse(Updater.newer("v0.10.87", "0.10.87"))
    }

    @Test
    fun tagPrefixDoesNotMatter() {
        assertTrue(Updater.newer("v0.10.88", "0.10.87"))
    }
}
