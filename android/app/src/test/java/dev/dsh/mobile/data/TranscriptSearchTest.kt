package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for searching a transcript the phone already holds. */
class TranscriptSearchTest {

    private fun message(seq: Int, text: String, role: String = "assistant") = TranscriptItem.Message(
        Entry(
            kind = "message",
            seq = seq,
            type = if (role == "user") "user/message" else "assistant/message",
            role = role,
            content = listOf(Block(type = "text", text = text)),
        ),
    )

    private fun call(seq: Int, name: String, arguments: String, output: String? = null) = TranscriptItem.ToolStep(
        call = Entry(kind = "tool-call", seq = seq, type = "tool/call", name = name, arguments = arguments),
        result = output?.let {
            Entry(kind = "tool-result", seq = seq + 1, type = "tool/result", name = name, isError = false, content = listOf(Block(type = "text", text = it)))
        },
    )

    private val transcript = listOf(
        message(1, "Sistema il README", role = "user"),
        call(2, "pwsh", """{"command":"gradlew.bat test"}""", output = "BUILD SUCCESSFUL"),
        message(4, "Fatto, il README ora dice questo."),
    )

    @Test
    fun `an empty query is the whole transcript`() {
        assertEquals(transcript, searchTranscript(transcript, ""))
        assertEquals(transcript, searchTranscript(transcript, "   "))
    }

    @Test
    fun `a message is found by its words, case-insensitively`() {
        val found = searchTranscript(transcript, "readme")
        assertEquals(2, found.size)
        assertTrue(found.all { it is TranscriptItem.Message })
    }

    @Test
    fun `a tool call is found by its command and by its output`() {
        assertEquals(1, searchTranscript(transcript, "gradlew").size)
        assertEquals(1, searchTranscript(transcript, "build successful").size)
    }

    @Test
    fun `a grouped call is found through the steps it hides`() {
        val group = TranscriptItem.ToolGroup(listOf(call(2, "pwsh", """{"command":"gradlew.bat test"}""", "ok")))
        assertEquals(listOf(group), searchTranscript(listOf(group), "gradlew"))
    }

    @Test
    fun `an approval is found by the tool it names and the host's sentence`() {
        val approval = TranscriptItem.Approval(
            asked = Entry(kind = "approval-asked", seq = 1, type = "approval/asked", name = "write", reason = "write a file"),
            decided = null,
        )
        assertEquals(1, searchTranscript(listOf(approval), "write").size)
        assertEquals(1, searchTranscript(listOf(approval), "a file").size)
    }

    @Test
    fun `a query with no match yields nothing`() {
        assertTrue(searchTranscript(transcript, "inesistente").isEmpty())
    }

    @Test
    fun `a delivery is found by the file it declares`() {
        val delivered = TranscriptItem.Deliverable(
            Entry(
                kind = "deliverable",
                seq = 1,
                type = "deliverables/presented",
                files = listOf(DeclaredFile(index = 0, path = "consegna.md", description = "la nota")),
            ),
        )
        assertEquals(1, searchTranscript(listOf(delivered), "consegna").size)
        assertEquals(1, searchTranscript(listOf(delivered), "la nota").size)
    }
}
