package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the storage format of the unread map. */
class SeenCodecTest {

    @Test
    fun `a map round-trips through its storage format`() {
        val values = mapOf("session-aaaa" to 1_760_000_000_000L, "session-bbbb" to 42L)
        assertEquals(values, decodeSeen(encodeSeen(values)))
    }

    @Test
    fun `nothing stored decodes to nothing`() {
        assertEquals(emptyMap<String, Long>(), decodeSeen(null))
        assertEquals(emptyMap<String, Long>(), decodeSeen(""))
    }

    @Test
    fun `a broken line costs one entry, not the file`() {
        val decoded = decodeSeen("session-aaaa=100\nnonsense\nsession-bbbb=not-a-number\nsession-cccc=300")
        assertEquals(mapOf("session-aaaa" to 100L, "session-cccc" to 300L), decoded)
    }

    @Test
    fun `an id that contains the separator still round-trips`() {
        val values = mapOf("weird=id" to 7L)
        assertEquals(values, decodeSeen(encodeSeen(values)))
    }

    @Test
    fun `a negative or zero timestamp is kept as written`() {
        val values = mapOf("session-aaaa" to 0L, "session-bbbb" to -1L)
        val decoded = decodeSeen(encodeSeen(values))
        assertEquals(0L, decoded["session-aaaa"])
        assertEquals(-1L, decoded["session-bbbb"])
    }

    @Test
    fun `an empty id is dropped rather than stored`() {
        assertTrue(decodeSeen("=100").isEmpty())
        assertTrue(decodeSeen("session-aaaa=100").isNotEmpty())
    }
}
