package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the two promises the project sections of the chat list make.
 *
 * The first is what removing a project means. The route behind it is
 * non-destructive — the folder, the files and the chat histories stay exactly
 * where they are — so the only thing this side can get wrong is the *wording*, and
 * a confirmation that reads like "delete these files" is one a person either
 * refuses to tap or taps expecting a cleanup that never happens. The sentence is no
 * longer a Kotlin function, so these tests read it out of the resource file that
 * ships ([StringsXml]): the promise is now held over the words themselves.
 *
 * The second is what an *open* section contains: a fold that is remembered, and a
 * card that shows the newest few chats and offers the tail. Both are pure list
 * arithmetic with a claim about ordering in them — "the last five" has to mean the
 * five newest — so they are pinned here rather than left to a composable, where a
 * wrong cut looks like a plausible screen. The two sentences the tail row can say
 * are read back from the same file.
 */
class WorkspacesTest {

    @Test
    fun `the question names the project that is going away`() {
        // The name lands inside the guillemets the sentence already has: the
        // placeholder is what makes the dialog say *which* project is leaving.
        val question = StringsXml.value("workspace_remove_question")

        assertEquals("Rimuovere «Acme» dai progetti?", String.format(question, "Acme"))
    }

    @Test
    fun `the consequence says the disk is not touched`() {
        val detail = StringsXml.value("workspace_remove_detail")

        assertTrue("the folder has to be named as staying", detail.contains("cartella"))
        assertTrue("and so do the files", detail.contains("file"))
        assertTrue("the promise is explicit, not implied", detail.contains("Non tocca il disco"))
    }

    @Test
    fun `the consequence never uses the words that mean deleting`() {
        val detail = StringsXml.value("workspace_remove_detail").lowercase()

        // The whole point of the confirmation: nothing here may read as a cleanup.
        assertFalse(detail.contains("elimina"))
        assertFalse(detail.contains("cancella"))
        assertFalse(detail.contains("spariscono"))
    }

    @Test
    fun `the two answers are distinct and the safe one is not the destructive one`() {
        val confirm = StringsXml.value("workspace_remove_confirm")
        val cancel = StringsXml.value("workspace_remove_cancel")

        assertNotEquals(cancel, confirm)
        // And the destructive answer is the removal, not a promise about the disk:
        // that is the lie a person acts on.
        assertFalse(confirm.lowercase().contains("elimina"))
        assertFalse(confirm.lowercase().contains("cancella"))
    }

    @Test
    fun `a section is keyed by its project, and the ungrouped bucket has a key of its own`() {
        assertEquals("07bf0fd6-676c-4b78-a527-c2e71984157a", sectionKey("07bf0fd6-676c-4b78-a527-c2e71984157a"))
        // The key is the identity a fold is *persisted* under, and the bucket has
        // no project id to be identified by: the literal is the contract, not an
        // implementation detail, because changing it silently reopens every
        // project that was folded before the update.
        assertEquals("ungrouped", sectionKey(null))
        // A project id can never collide with the bucket: ids come from the host.
        assertFalse(sectionKey(null) == sectionKey("ungrouped-project"))
    }

    @Test
    fun `the card shows five chats and how many it holds back`() {
        val slice = visibleChats(chats(8), expanded = false)

        assertEquals(5, CHATS_BEFORE_MORE)
        assertEquals(5, slice.visible.size)
        assertEquals(3, slice.hidden)
        assertTrue(slice.hasMore)
    }

    @Test
    fun `the newest chats are the ones that stay`() {
        // The list is fed oldest-first on purpose: "the last five" has to mean the
        // five newest chats, not the five that happened to arrive last.
        val slice = visibleChats(chats(8), expanded = false)

        assertEquals(listOf("chat-8", "chat-7", "chat-6", "chat-5", "chat-4"), slice.visible.map { it.id })
        assertFalse(slice.visible.any { it.id == "chat-3" })
    }

    @Test
    fun `a project of exactly five chats holds nothing back`() {
        val slice = visibleChats(chats(5), expanded = false)

        assertEquals(5, slice.visible.size)
        assertEquals(0, slice.hidden)
        assertFalse(slice.hasMore)
        // And the row that would open the tail is drawn from this slice, so with
        // nothing held back there is nothing for it to offer: a toggle that does
        // nothing is what "the button is broken" is made of.
    }

    @Test
    fun `an open card shows every chat and holds nothing back`() {
        val slice = visibleChats(chats(8), expanded = true)

        assertEquals(8, slice.visible.size)
        assertEquals(0, slice.hidden)
        assertFalse(slice.hasMore)
        // Still newest-first, so opening the card does not reshuffle the rows.
        assertEquals("chat-8", slice.visible.first().id)
        assertEquals("chat-1", slice.visible.last().id)
    }

    @Test
    fun `the toggle names how many chats are behind it`() {
        val more = StringsXml.plural("session_slice_more", "other")

        assertEquals("Vedi le altre 3 chat", String.format(more, 3))
        assertEquals("Vedi le altre 4 chat", String.format(more, 4))
    }

    @Test
    fun `one held-back chat is named in the singular`() {
        // The singular is a sentence of its own and carries no number: there is
        // exactly one other chat, and a count in the middle of that phrase would be
        // a machine reading a table aloud.
        assertEquals("Vedi l'altra chat", StringsXml.plural("session_slice_more", "one"))
    }

    @Test
    fun `an open card keeps the way back where the way in was`() {
        val fewer = StringsXml.value("session_slice_fewer")

        assertEquals("Mostra solo le ultime 5", String.format(fewer, 5))
    }

    @Test
    fun `a card with nothing to open has nothing to fold either`() {
        // The row is drawn from the slice and from nothing else: `hasMore` is what
        // offers the chats held back, and the way back is offered only while an open
        // card shows more than CHATS_BEFORE_MORE rows. A project that lost chats
        // while its card was open is neither, so the row is not drawn at all.
        val folded = visibleChats(chats(3), expanded = false)
        val open = visibleChats(chats(3), expanded = true)

        assertFalse(folded.hasMore)
        assertTrue(folded.visible.size <= CHATS_BEFORE_MORE)
        assertFalse(open.hasMore)
        assertTrue(open.visible.size <= CHATS_BEFORE_MORE)
    }

    @Test
    fun `the cut and the label agree on the same five`() {
        // The row counts what the cut left behind — `slice.hidden` is the number it
        // is read with — so a slice of five beside a sentence counting a different
        // tail is not something the card can draw.
        val slice = visibleChats(chats(7), expanded = false)

        assertEquals(CHATS_BEFORE_MORE, slice.visible.size)
        assertEquals(2, slice.hidden)
        assertEquals(
            "Vedi le altre 2 chat",
            String.format(StringsXml.plural("session_slice_more", "other"), slice.hidden),
        )
    }

    /**
     * Build a project's chats, oldest first.
     *
     * @param count how many chats the project has.
     * @return the chats, `chat-1` being the oldest and `chat-N` the newest.
     */
    private fun chats(count: Int): List<SessionSummary> = (1..count).map { index ->
        SessionSummary(id = "chat-$index", updatedAt = index.toLong())
    }
}
