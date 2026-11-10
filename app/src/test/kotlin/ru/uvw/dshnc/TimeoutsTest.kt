package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.uvw.dshnc.logic.Timeouts

class TimeoutsTest {
    @Test
    fun `явные миллисекунды главнее всего`() {
        assertEquals(2500, Timeouts.resolveMs(2500.0, 3.0, 60.0))
    }

    @Test
    fun `секунды из аргументов главнее таймаута команды`() {
        assertEquals(3000, Timeouts.resolveMs(null, 3.0, 60.0))
    }

    @Test
    fun `таймаут команды — когда аргументы молчат`() {
        assertEquals(90000, Timeouts.resolveMs(null, null, 90.0))
    }

    @Test
    fun `никто не назвал — null, а не выдуманное число`() {
        assertNull(Timeouts.resolveMs(null, null, 0.0))
        assertNull(Timeouts.resolveMs(0.0, 0.0, -1.0))
    }
}
