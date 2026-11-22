package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.uvw.dshnc.logic.ServerName

/**
 * What the shade calls the server.
 *
 * The title is the one line a person checks at a glance, so it must be the address and nothing
 * else: no scheme, no path, and no default port. An address typed by hand is exactly the input
 * that breaks a parser, so the "nothing to show" answer must be a null, not an exception.
 */
class ServerNameTest {
    @Test
    fun `a full url gives its host`() {
        assertEquals("dsh.uvw.ru", ServerName.host("https://dsh.uvw.ru/"))
        assertEquals("dsh.uvw.ru", ServerName.host("https://dsh.uvw.ru/api/dsh-nodes"))
    }

    @Test
    fun `the scheme is dropped whatever it is`() {
        assertEquals("dsh.uvw.ru", ServerName.host("http://dsh.uvw.ru"))
        assertEquals("dsh.uvw.ru", ServerName.host("dsh.uvw.ru"))
        assertEquals("dsh.uvw.ru", ServerName.host(" dsh.uvw.ru "))
    }

    @Test
    fun `a non-default port stays, the default one does not`() {
        assertEquals("dsh.uvw.ru:8443", ServerName.host("https://dsh.uvw.ru:8443/dsh-nodes"))
        assertEquals("dsh.uvw.ru:8080", ServerName.host("http://dsh.uvw.ru:8080"))
        assertEquals("dsh.uvw.ru", ServerName.host("https://dsh.uvw.ru:443"))
    }

    @Test
    fun `a local address is still an address`() {
        assertEquals("127.0.0.1:8000", ServerName.host("http://127.0.0.1:8000/dsh-nodes"))
        assertEquals("localhost", ServerName.host("localhost"))
    }

    @Test
    fun `nothing to show gives nothing, not a crash`() {
        assertNull(ServerName.host(null))
        assertNull(ServerName.host(""))
        assertNull(ServerName.host("   "))
        assertNull(ServerName.host("https://"))
    }
}
