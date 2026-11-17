package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.uvw.dshnc.logic.Eviction

/**
 * Being pushed out of the channel is our own doing until it repeats.
 *
 * The phone used to sleep after the first `4001 replaced`, which is exactly what its own racing
 * reconnects produce — and only a person could wake it.
 */
class EvictionTest {
    @Test
    fun `the first pushes after an open channel are our own churn`() {
        var streak = Eviction.onOpen()
        repeat(Eviction.CHURN_LIMIT) {
            streak = Eviction.onReplaced(streak)
            assertFalse("после $streak вытеснений узел ещё живёт", Eviction.sleep(streak))
        }
    }

    @Test
    fun `a real second instance wins after the streak`() {
        var streak = Eviction.onOpen()
        repeat(Eviction.CHURN_LIMIT + 1) { streak = Eviction.onReplaced(streak) }
        assertTrue("устойчивое вытеснение усыпляет узел", Eviction.sleep(streak))
    }

    @Test
    fun `an opened channel forgets the pushes`() {
        assertEquals(0, Eviction.onOpen())
        assertFalse(Eviction.sleep(Eviction.onOpen()))
    }
}
