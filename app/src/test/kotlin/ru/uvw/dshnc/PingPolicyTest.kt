package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.uvw.dshnc.logic.PingPolicy

class PingPolicyTest {
    @Test
    fun `работа сбрасывает растяжение`() {
        assertEquals(15 to 1, PingPolicy.next(15, 10, 5, true))
    }

    @Test
    fun `без работы интервал растёт до потолка`() {
        var state = 15 to 1
        state = PingPolicy.next(15, 3, state.second, false)
        assertEquals(30 to 2, state)
        state = PingPolicy.next(15, 3, state.second, false)
        assertEquals(45 to 3, state)
        state = PingPolicy.next(15, 3, state.second, false)
        assertEquals(45 to 3, state)
    }

    @Test
    fun `ноль выключает пинг`() {
        assertEquals(0 to 1, PingPolicy.next(0, 10, 4, false))
    }

    @Test
    fun `молчание дольше двух интервалов — обрыв`() {
        assertFalse(PingPolicy.isSilent(29_000, 15, 1))
        assertTrue(PingPolicy.isSilent(31_000, 15, 1))
        assertTrue(PingPolicy.isSilent(61_000, 15, 2))
    }
}
