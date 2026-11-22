package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.uvw.dshnc.logic.Naming

/**
 * The naming rules the phone applies before anything is stored or sent.
 *
 * A name that cannot be called by, or a list that quietly drops its tail on the way to the
 * server, is a bug that only shows up as "the node is called something else" — so the rules are
 * checked here, apart from the screen.
 */
class NamingTest {
    @Test
    fun `a name typed on the phone wins over the model`() {
        assertEquals("трубка", Naming.localName("трубка", "Pixel 7"))
    }

    @Test
    fun `without a typed name the model is used`() {
        assertEquals("Pixel 7", Naming.localName(null, "Pixel 7"))
        assertEquals("Pixel 7", Naming.localName("   ", "Pixel 7"))
    }

    @Test
    fun `no name and no model still gives something callable`() {
        assertEquals("android", Naming.localName(null, null))
        assertEquals("android", Naming.localName("", " "))
    }

    @Test
    fun `aliases are split on commas and newlines`() {
        assertEquals(listOf("телефон", "phone", "трубка"), Naming.parseAliases("телефон, phone\nтрубка"))
    }

    @Test
    fun `an empty entry is not an alias`() {
        assertEquals(listOf("телефон"), Naming.parseAliases(" , телефон ,, \n "))
    }

    @Test
    fun `a repeat in another case is not a second address`() {
        assertEquals(listOf("Phone", "телефон"), Naming.parseAliases("Phone, phone, PHONE, телефон"))
    }

    @Test
    fun `the list is capped before it is stored`() {
        val many = (1..30).joinToString(", ") { "alias-$it" }
        assertEquals(Naming.ALIAS_LIMIT, Naming.parseAliases(many).size)
    }

    @Test
    fun `a long alias is cut to the server's limit`() {
        val long = "a".repeat(200)
        assertEquals(Naming.NAME_LIMIT, Naming.parseAliases(long).first().length)
    }

    @Test
    fun `the name is not repeated in the alias list`() {
        val without = Naming.withoutName(listOf("Телефон", "phone"), "телефон")
        assertEquals(listOf("phone"), without)
        assertTrue(Naming.withoutName(emptyList(), "телефон").isEmpty())
    }

    @Test
    fun `the field shows the list on one line`() {
        assertEquals("телефон, phone", Naming.formatAliases(listOf("телефон", "phone")))
    }
}
