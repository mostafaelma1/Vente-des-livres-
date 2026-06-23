package com.prixref.ao

import com.prixref.ao.net.MoneyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyParserTest {

    @Test
    fun `montant avec espaces et virgule`() {
        assertEquals(500000.0, MoneyParser.parse("500 000,00 DH")!!, 0.001)
    }

    @Test
    fun `virgule decimale`() {
        assertEquals(949000.0, MoneyParser.parse("949000,00")!!, 0.001)
    }

    @Test
    fun `points comme milliers`() {
        assertEquals(1234567.0, MoneyParser.parse("1.234.567 DH")!!, 0.001)
    }

    @Test
    fun `points milliers et virgule decimale`() {
        assertEquals(1234567.89, MoneyParser.parse("1.234.567,89")!!, 0.001)
    }

    @Test
    fun `montant simple`() {
        assertEquals(515000.0, MoneyParser.parse("515000")!!, 0.001)
    }

    @Test
    fun `texte sans montant`() {
        assertNull(MoneyParser.parse("Société Alpha"))
    }
}
