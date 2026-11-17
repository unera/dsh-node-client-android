package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.uvw.dshnc.api.ApiException
import ru.uvw.dshnc.logic.EventsGate

/**
 * The owner's switch, as the phone sees it.
 *
 * `dsh-nodes` 0.4.x refuses device events with `403 events_disabled` until the owner allows them
 * for that node. The app has to name that case, and keep ignoring everything transient.
 */
class EventsGateTest {
    @Test
    fun `the disabled code becomes a sentence a person can act on`() {
        val refusal = ApiException(403, "для устройства Телефон события выключены в админке", "events_disabled")

        assertEquals(EventsGate.DISABLED_TEXT, EventsGate.explain(refusal))
        assertTrue(EventsGate.isDisabled(refusal))
    }

    @Test
    fun `other refusals stay silent`() {
        assertNull(EventsGate.explain(ApiException(409, "epoch", "epoch-mismatch")))
        assertNull(EventsGate.explain(ApiException(500, "boom")))
        assertNull(EventsGate.explain(java.io.IOException("no network")))
        assertNull(EventsGate.explain(null))
        assertFalse(EventsGate.isDisabled(java.io.IOException("no network")))
    }

}
