package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the one word a chat row's dot is made of.
 *
 * A status dot is read at a glance and never checked, which is what makes it worth
 * pinning down here: the states have to stay distinct, a word from a newer harness
 * must not be folded into the nearest one this build happens to know, and
 * "interrotta" must not become the red that means "in errore".
 *
 * The words themselves left this package with the migration — each state is a
 * `status_*` resource, mapped where the dot is drawn (`ui/SessionsScreen.kt`) — so
 * the two tests that are about wording read them out of `strings.xml` ([StringsXml])
 * instead of out of an enum property. The decisions are untouched and still checked
 * here: which wire word is understood, which state is drawn as an absence, and which
 * two states must not be confused.
 */
class ChatStatusTest {

    @Test
    fun `every state the host sends is understood`() {
        assertEquals(ChatStatus.Running, ChatStatus.fromWire("running"))
        assertEquals(ChatStatus.Completed, ChatStatus.fromWire("completed"))
        assertEquals(ChatStatus.Failed, ChatStatus.fromWire("failed"))
        assertEquals(ChatStatus.Stopped, ChatStatus.fromWire("stopped"))
        assertEquals(ChatStatus.Idle, ChatStatus.fromWire("idle"))
    }

    @Test
    fun `the word is matched the way a wire is read, not the way a file is`() {
        assertEquals(ChatStatus.Completed, ChatStatus.fromWire(" Completed "))
        assertEquals(ChatStatus.Failed, ChatStatus.fromWire("FAILED"))
    }

    @Test
    fun `a status this build has never heard of draws nothing`() {
        // The alternative — folding an unknown word into the nearest state — is how
        // a future harness's "queued" would show up here as a red failure.
        assertNull(ChatStatus.fromWire("queued"))
        assertNull(ChatStatus.fromWire(""))
        assertNull(ChatStatus.fromWire("   "))
        assertNull(ChatStatus.fromWire(null))
    }

    @Test
    fun `the idle state is drawn as an absence, read or not`() {
        assertFalse(ChatStatus.Idle.showsDot(unread = true))
        assertFalse(ChatStatus.Idle.showsDot(unread = false))
    }

    @Test
    fun `a finished turn is news, and reading the chat spends it`() {
        // The green dot says "there is an answer here you have not looked at".
        // Once the chat has been opened it is the row repeating itself, so it
        // goes — that is the whole point of the state being news rather than a
        // condition.
        assertTrue(ChatStatus.Completed.showsDot(unread = true))
        assertFalse(ChatStatus.Completed.showsDot(unread = false))
    }

    @Test
    fun `a failure, a stop and a running turn outlive the read`() {
        // These three are how a chat is found again — the one that broke, the one
        // that was interrupted, the one still working — so a read must not hide
        // them: the list would then paint a broken chat like one that never ran.
        listOf(ChatStatus.Running, ChatStatus.Failed, ChatStatus.Stopped).forEach { status ->
            assertTrue("${status.wire} must be drawn on an unread chat", status.showsDot(unread = true))
            assertTrue("${status.wire} must survive a read", status.showsDot(unread = false))
        }
    }

    @Test
    fun `every state has words in the resources`() {
        // The dot itself carries no text, so the state is spoken — and a state whose
        // sentence is missing is a row a screen reader skips in silence.
        ChatStatus.entries.forEach { status ->
            assertTrue("${status.wire} has no ${key(status)}", StringsXml.has(key(status)))
        }
        assertEquals("five states, five sentences", 5, ChatStatus.entries.size)
    }

    @Test
    fun `each state says what happened, and no state says what colour it is`() {
        val labels = ChatStatus.entries.map { StringsXml.value(key(it)) }

        assertEquals("every state needs its own words", labels.size, labels.toSet().size)
        assertTrue("a label is read aloud, so it cannot be a colour", labels.none { it.contains("ross") || it.contains("verde") })
    }

    @Test
    fun `a stopped turn is not described as a failure`() {
        val failed = StringsXml.value(key(ChatStatus.Failed))
        val stopped = StringsXml.value(key(ChatStatus.Stopped))

        assertNotEquals(failed, stopped)
        assertFalse(stopped.contains("error"))
    }

    @Test
    fun `the wire word is the one the host uses, so a log can be read back`() {
        assertEquals("running", ChatStatus.Running.wire)
        assertEquals("completed", ChatStatus.Completed.wire)
        assertEquals("failed", ChatStatus.Failed.wire)
        assertEquals("stopped", ChatStatus.Stopped.wire)
        assertEquals("idle", ChatStatus.Idle.wire)
        assertEquals(ChatStatus.Completed, ChatStatus.fromWire(ChatStatus.Completed.wire))
    }

    /**
     * The resource one state's words live under.
     *
     * The mapping is written out because it cannot be read from the UI: the `when`
     * that chooses between these keys is a composable, and a composable does not run
     * on the JVM. What these tests can still do is insist that the five keys exist,
     * that they carry five different sentences, and that none of them names a
     * colour — which is the promise the enum's old `label` used to carry.
     *
     * @param status the state to name.
     * @return the resource name, without the `R.string.` in front of it.
     */
    private fun key(status: ChatStatus): String = when (status) {
        ChatStatus.Running -> "status_running"
        ChatStatus.Completed -> "status_completed"
        ChatStatus.Failed -> "status_failed"
        ChatStatus.Stopped -> "status_stopped"
        ChatStatus.Idle -> "status_idle"
    }
}
