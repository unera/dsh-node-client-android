package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.uvw.dshnc.logic.Declaration

class DeclarationTest {
    @Test
    fun `в кадрах null ровно два раза и только в начале соединения`() {
        val declaration = Declaration()
        // what goes out, in order: hello, the first check_state of the same connection,
        // a wait frame after a batch, then a whole second connection
        val frames = mutableListOf<Long?>(
            declaration.hello(0),
            declaration.checkState(0),
            declaration.checkState(0),
            declaration.hello(11),
            declaration.checkState(11),
        )
        assertEquals(listOf<Long?>(null, null, 0L, 11L, 11L), frames)
        // the frame log: `known` values in the order they would go on the wire
        println("frames: " + frames.joinToString(" → ") { it?.toString() ?: "null" })
    }

    @Test
    fun `объявление считается отправленным только после первого check_state`() {
        val declaration = Declaration()
        assertFalse(declaration.announced())
        declaration.hello(0)
        assertFalse("hello сервер не читает, ждать по нему нельзя", declaration.announced())
        declaration.checkState(0)
        assertTrue(declaration.announced())
    }

    @Test
    fun `смена поколения объявляет рестарт ещё раз и снова один раз`() {
        val declaration = Declaration()
        declaration.checkState(42)
        declaration.restart()
        assertNull(declaration.checkState(42))
        assertEquals(43L, declaration.checkState(43))
        assertEquals(44L, declaration.checkState(44))
    }

    @Test
    fun `второй процесс без рестарта ничего не объявляет`() {
        val declaration = Declaration(pending = false)
        assertEquals(7L, declaration.hello(7))
        assertEquals(7L, declaration.checkState(7))
        assertTrue(declaration.announced())
    }
}
