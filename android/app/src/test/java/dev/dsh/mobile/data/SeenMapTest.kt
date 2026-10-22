package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the one rule the "what this phone has read" map obeys.
 *
 * A mark that can move backwards is the failure this file exists for: the list
 * poll and the chat poll answer at different speeds, so a late answer carries a
 * moment older than one already recorded, and letting it through makes a chat
 * that was just read grow its dot back.
 */
class SeenMapTest {

    @Test
    fun `a chat that has never been read is added`() {
        val seen = rememberSeen(emptyMap(), "session-aaaa", 1_760_000_000_000L)
        assertEquals(mapOf("session-aaaa" to 1_760_000_000_000L), seen)
    }

    @Test
    fun `a newer look moves the mark forward`() {
        val before = mapOf("session-aaaa" to 100L)
        val after = rememberSeen(before, "session-aaaa", 200L)
        assertEquals(200L, after["session-aaaa"])
    }

    @Test
    fun `a late look cannot take the mark back`() {
        val before = mapOf("session-aaaa" to 200L)

        assertSame("an older answer must be a no-op", before, rememberSeen(before, "session-aaaa", 100L))
        assertSame("the same answer must be a no-op", before, rememberSeen(before, "session-aaaa", 200L))
    }

    @Test
    fun `a timestamp the host did not report is not a mark`() {
        val before = mapOf("session-aaaa" to 200L)

        assertSame(before, rememberSeen(before, "session-aaaa", 0L))
        assertSame(before, rememberSeen(before, "session-aaaa", -1L))
        assertTrue(rememberSeen(emptyMap(), "session-aaaa", 0L).isEmpty())
    }

    @Test
    fun `one chat's mark leaves the others alone`() {
        val before = mapOf("session-aaaa" to 100L, "session-bbbb" to 200L)
        val after = rememberSeen(before, "session-aaaa", 300L)

        assertEquals(300L, after["session-aaaa"])
        assertEquals(200L, after["session-bbbb"])
        assertEquals("two chats, two marks", 2, after.size)
    }

    @Test
    fun `an empty id is not a chat`() {
        assertTrue(rememberSeen(emptyMap(), "", 100L).isEmpty())
    }
}
