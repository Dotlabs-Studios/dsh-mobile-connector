package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for the quoted form a message takes when the reader answers it. */
class MessageQuoteTest {

    @Test
    fun `every line is prefixed so the quote reads as one block`() {
        assertEquals("> uno\n> due\n\n", quotedMessage("uno\ndue"))
    }

    @Test
    fun `a blank line becomes a bare marker`() {
        assertEquals("> uno\n>\n> due\n\n", quotedMessage("uno\n\ndue"))
    }

    @Test
    fun `an empty message quotes nothing`() {
        assertEquals("", quotedMessage(""))
        assertEquals("", quotedMessage("   \n\n "))
    }

    @Test
    fun `a long message is cut and the cut is visible`() {
        val quoted = quotedMessage("x".repeat(500), maxChars = 100)
        assertEquals(1, quoted.count { it == '>' })
        assertEquals(true, quoted.endsWith("…\n\n"))
        assertEquals(100, quoted.removePrefix("> ").removeSuffix("…\n\n").length)
    }

    @Test
    fun `trailing whitespace is not carried into the composer`() {
        assertEquals("> ciao\n\n", quotedMessage("  ciao  \n"))
    }
}
