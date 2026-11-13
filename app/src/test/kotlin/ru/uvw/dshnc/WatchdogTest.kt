package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.uvw.dshnc.logic.WatchAction
import ru.uvw.dshnc.logic.Watchdog

class WatchdogTest {
    private fun decide (
        enabled: Boolean = true,
        known: Boolean = true,
        uptimeSeconds: Long = 600,
        connected: Boolean = false,
        silenceMs: Long = 60_000,
        pingSeconds: Int = 15,
        stretchTimes: Int = 10,
        sinceRestartMs: Long = -1,
    ) = Watchdog.decide(
        enabled, known, uptimeSeconds, connected, silenceMs, pingSeconds, stretchTimes, sinceRestartMs,
    )

    @Test
    fun `a service that is not running is started`() {
        assertEquals(WatchAction.START, decide(uptimeSeconds = 0))
    }

    @Test
    fun `a node turned off is left alone`() {
        assertEquals(WatchAction.NONE, decide(enabled = false, uptimeSeconds = 0))
    }

    @Test
    fun `a node that does not know where to knock is left alone`() {
        // no address, no token, no uuid: starting anything would only make noise
        assertEquals(WatchAction.NONE, decide(known = false, uptimeSeconds = 0))
    }

    @Test
    fun `a connected node is left alone even after a long silence of the poll`() {
        assertEquals(WatchAction.NONE, decide(connected = true, silenceMs = 3_600_000))
    }

    @Test
    fun `a service that has just come up is given time to poll`() {
        // heartbeat is 0 right after a start: the silence equals the uptime, and the uptime of
        // ten seconds is not a reason to restart anything
        assertEquals(WatchAction.NONE, decide(uptimeSeconds = 10, silenceMs = -1))
    }

    @Test
    fun `a service silent beyond the limit is restarted`() {
        val limit = Watchdog.limitSeconds(15, 10) * 1000
        assertEquals(WatchAction.RESTART, decide(uptimeSeconds = 3600, silenceMs = limit + 1))
    }

    @Test
    fun `the stretch of the ping is not mistaken for silence`() {
        // one full stretch (150 s) plus reconnect backoff is normal and must not restart anything
        assertEquals(WatchAction.NONE, decide(uptimeSeconds = 3600, silenceMs = 200_000))
    }

    @Test
    fun `the cooldown keeps a phone without network from restarting in a circle`() {
        val limit = Watchdog.limitSeconds(15, 10) * 1000
        assertEquals(WatchAction.NONE, decide(uptimeSeconds = 3600, silenceMs = limit * 2, sinceRestartMs = 60_000))
    }

    @Test
    fun `after the cooldown the restart is allowed again`() {
        val limit = Watchdog.limitSeconds(15, 10) * 1000
        assertEquals(
            WatchAction.RESTART,
            decide(uptimeSeconds = 3600, silenceMs = limit * 2, sinceRestartMs = Watchdog.COOLDOWN_MS + 1),
        )
    }

    @Test
    fun `the limit follows the configured ping`() {
        // three full stretches plus two minutes: with the defaults that is nine and a half minutes
        assertEquals(570L, Watchdog.limitSeconds(15, 10))
        // a node that pings every minute with a tenfold stretch is quiet for ten minutes by design
        assertTrue(Watchdog.limitSeconds(60, 10) > 10 * 60)
    }

    @Test
    fun `nonsense in the settings does not turn the limit into zero`() {
        // a zero would restart the service on every round; the defaults have to stand
        assertEquals(Watchdog.limitSeconds(15, 10), Watchdog.limitSeconds(0, 0))
    }
}
