package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.uvw.dshnc.logic.LinkParser

class LinkParserTest {
    @Test
    fun `разбирает обычную ссылку`() {
        val parsed = LinkParser.parse("https://dsh.uvw.ru/dsh-nodes/pair?v=abc123")
        assertEquals("https://dsh.uvw.ru/dsh-nodes", parsed?.base)
        assertEquals("abc123", parsed?.code)
    }

    @Test
    fun `пробелы и переводы строк не мешают`() {
        val parsed = LinkParser.parse("  https://host/x/pair?v=k \n")
        assertEquals("https://host/x", parsed?.base)
        assertEquals("k", parsed?.code)
    }

    @Test
    fun `код декодируется`() {
        val parsed = LinkParser.parse("https://h/pair?v=a%2Bb_c")
        assertEquals("a+b_c", parsed?.code)
    }

    @Test
    fun `не ссылка — отказ, а не догадка`() {
        assertNull(LinkParser.parse(null))
        assertNull(LinkParser.parse(""))
        assertNull(LinkParser.parse("dshnc connect https://h/pair?v=1"))
        assertNull(LinkParser.parse("https://h/pair"))
        assertNull(LinkParser.parse("ftp://h/pair?v=1"))
    }
}
