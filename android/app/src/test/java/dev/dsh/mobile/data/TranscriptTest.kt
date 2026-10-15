package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for rebuilding the harness's tool cards out of the flat session log.
 *
 * The rule under test is a pairing rule, and a pairing bug is invisible in a
 * screenshot: the transcript still looks plausible, it just attributes one
 * command's output to another command's name. Every branch here — by id, by
 * arrival order, unpaired call, orphan result — is therefore driven directly.
 */
class TranscriptTest {

    /**
     * Build one tool-call event.
     *
     * @param seq the log position.
     * @param callId the harness's call id, or null to imitate an older host.
     * @param name the tool name.
     * @param arguments the raw argument JSON.
     * @param time the event timestamp.
     */
    private fun call(
        seq: Int,
        callId: String? = "call-$seq",
        name: String = "pwsh",
        arguments: String = """{"command":"ls"}""",
        time: Long? = null,
    ) = Entry(
        kind = "tool-call",
        seq = seq,
        type = "tool/call",
        callId = callId,
        name = name,
        arguments = arguments,
        time = time,
    )

    /**
     * Build one tool-result event.
     *
     * @param seq the log position.
     * @param callId the call this answers, or null.
     * @param name the tool name.
     * @param text the result text.
     * @param isError whether the harness reported a failure.
     * @param time the event timestamp.
     * @param blocks optional extra content blocks, appended after the text.
     */
    private fun result(
        seq: Int,
        callId: String? = "call-${seq - 1}",
        name: String = "pwsh",
        text: String = "file.txt",
        isError: Boolean = false,
        time: Long? = null,
        blocks: List<Block> = emptyList(),
    ) = Entry(
        kind = "tool-result",
        seq = seq,
        type = "tool/result",
        callId = callId,
        name = name,
        isError = isError,
        time = time,
        content = listOf(Block(type = "text", text = text)) + blocks,
    )

    /**
     * Build one message event.
     *
     * @param seq the log position.
     * @param text the message text.
     */
    private fun message(seq: Int, text: String) = Entry(
        kind = "message",
        seq = seq,
        type = "assistant/message",
        role = "assistant",
        content = listOf(Block(type = "text", text = text)),
    )

    /**
     * Every tool call a transcript holds, with groups unwrapped.
     *
     * The pairing rules are about calls and results, not about how many of them
     * ended up behind one header, so those tests read through the folding instead
     * of asserting its shape.
     *
     * @param items the built items.
     * @return the steps, in order.
     */
    private fun stepsOf(items: List<TranscriptItem>): List<TranscriptItem.ToolStep> = items.flatMap { item ->
        when (item) {
            is TranscriptItem.ToolStep -> listOf(item)
            is TranscriptItem.ToolGroup -> item.steps
            else -> emptyList()
        }
    }

    @Test
    fun `a call and its result become one card`() {
        val items = buildTranscript(listOf(call(1), result(2)))

        assertEquals(1, items.size)
        val step = stepsOf(items).single()
        assertEquals(1, step.seq)
        assertEquals("pwsh", step.name)
        assertEquals("file.txt", step.output)
        assertTrue(!step.running)
        assertTrue(!step.isError)
    }

    @Test
    fun `parallel calls pair by id and not by position`() {
        // Two calls in flight at once: the results come back in the opposite
        // order. Pairing by position would swap the outputs, which is exactly
        // the failure this asserts against.
        val items = buildTranscript(
            listOf(
                call(1, callId = "a", name = "read", arguments = """{"file_path":"a.txt"}"""),
                call(2, callId = "b", name = "pwsh", arguments = """{"command":"ping"}"""),
                result(3, callId = "b", name = "pwsh", text = "pong"),
                result(4, callId = "a", name = "read", text = "contents of a"),
            ),
        )

        val steps = stepsOf(items)
        assertEquals(2, steps.size)
        val first = steps[0]
        val second = steps[1]
        assertEquals("read", first.name)
        assertEquals("contents of a", first.output)
        assertEquals("pwsh", second.name)
        assertEquals("pong", second.output)
    }

    @Test
    fun `calls without an id pair in arrival order`() {
        val items = buildTranscript(
            listOf(
                call(1, callId = null, arguments = """{"command":"first"}"""),
                call(2, callId = null, arguments = """{"command":"second"}"""),
                result(3, callId = null, text = "out-1"),
                result(4, callId = null, text = "out-2"),
            ),
        )

        val steps = stepsOf(items)
        assertEquals(2, steps.size)
        assertEquals("out-1", steps[0].output)
        assertEquals("out-2", steps[1].output)
    }

    @Test
    fun `a result whose call was never seen stands alone`() {
        // A page boundary can cut the call off the front of the window. The
        // output is still worth showing; attaching it to some other card would
        // not be.
        val items = buildTranscript(listOf(call(1, callId = "a"), result(2, callId = "zzz", text = "orphan")))

        val steps = stepsOf(items)
        assertEquals(2, steps.size)
        assertEquals("orphan", steps[1].output)
        assertTrue("an orphan result is not left running", !steps[1].running)
    }

    @Test
    fun `a call whose result never arrived is still shown as running`() {
        val items = buildTranscript(listOf(call(1, callId = "a")))

        val step = stepsOf(items).single()
        assertTrue(step.running)
        assertNull(step.durationMs)
    }

    @Test
    fun `an id is consumed once so a repeat result does not reopen the card`() {
        val items = buildTranscript(listOf(call(1, callId = "a"), result(2, callId = "a", text = "primo"), result(3, callId = "a", text = "secondo")))

        val steps = stepsOf(items)
        assertEquals(2, steps.size)
        assertEquals("primo", steps[0].output)
        assertEquals("secondo", steps[1].output)
    }

    @Test
    fun `messages keep their order and bookkeeping events are dropped`() {
        val items = buildTranscript(
            listOf(
                Entry(kind = "turn-start", seq = 0, type = "turn/start"),
                message(1, "ciao"),
                Entry(kind = "step-start", seq = 2, type = "step/start"),
                call(3),
                result(4),
                Entry(kind = "step-end", seq = 5, type = "step/end"),
                message(6, "fatto"),
            ),
        )

        assertEquals(3, items.size)
        assertTrue(items[0] is TranscriptItem.Message)
        assertTrue(items[1] is TranscriptItem.ToolStep)
        assertTrue(items[2] is TranscriptItem.Message)
        assertEquals(listOf(1, 3, 6), items.map { it.seq })
    }

    @Test
    fun `a card reports how long the call took`() {
        val items = buildTranscript(
            listOf(call(1, time = 1_000), result(2, time = 1_450)),
        )

        assertEquals(450L, stepsOf(items).single().durationMs)
    }

    @Test
    fun `duration is absent when either half has no timestamp`() {
        val withoutCall = buildTranscript(listOf(call(1, time = null), result(2, time = 1_450)))
        val withoutResult = buildTranscript(listOf(call(1, time = 1_000), result(2, time = null)))

        assertNull(stepsOf(withoutCall).single().durationMs)
        assertNull(stepsOf(withoutResult).single().durationMs)
    }

    @Test
    fun `a failed call is reported as an error and keeps its output`() {
        val items = buildTranscript(listOf(call(1), result(2, text = "boom", isError = true)))

        val step = stepsOf(items).single()
        assertTrue(step.isError)
        assertEquals("boom", step.output)
    }

    @Test
    fun `images produced by a tool are carried on the card`() {
        val items = buildTranscript(
            listOf(
                call(1, name = "screenshot"),
                result(
                    2,
                    name = "screenshot",
                    text = "fatto",
                    blocks = listOf(Block(type = "image", attachmentId = "att-1", mediaType = "image/png")),
                ),
            ),
        )

        val step = stepsOf(items).single()
        assertEquals(1, step.images.size)
        assertEquals("att-1", step.images.single().attachmentId)
    }

    @Test
    fun `a shell call is summarised by its command`() {
        assertEquals("ls -la", cardSummary("pwsh", """{"command":"ls -la","description":"elenca"}"""))
    }

    @Test
    fun `a file call is summarised by its path`() {
        assertEquals(
            "K:\\Repository\\dsh-mobile-connector\\README.md",
            cardSummary("read", """{"file_path":"K:\\Repository\\dsh-mobile-connector\\README.md","offset":1}"""),
        )
    }

    @Test
    fun `a summary keeps only the first line and caps its length`() {
        val long = "x".repeat(400)
        val summary = cardSummary("write", """{"file_path":"$long"}""")

        assertEquals(161, summary!!.length)
        assertTrue(summary.endsWith("…"))
        assertEquals("prima", cardSummary("pwsh", """{"command":"prima\nseconda"}"""))
    }

    @Test
    fun `a summary falls back to the argument names when no key stands out`() {
        assertEquals("(alpha, beta)", cardSummary("custom", """{"alpha":1,"beta":2}"""))
        assertNull(cardSummary("pwsh", null))
        assertNull(cardSummary("pwsh", "   "))
    }

    @Test
    fun `an unparsable payload is summarised rather than hidden`() {
        assertEquals("non-json", cardSummary("pwsh", "non-json"))
    }

    @Test
    fun `a replace-style edit is recovered as a diff`() {
        val preview = editPreview(
            """{"file_path":"K:\\a.kt","old_string":"vecchio","new_string":"nuovo"}""",
        )

        assertEquals("K:\\a.kt", preview!!.filePath)
        assertEquals("vecchio", preview.oldText)
        assertEquals("nuovo", preview.newText)
        assertTrue(!preview.isInsertion)
    }

    @Test
    fun `an edit that only inserts is recognised`() {
        val preview = editPreview("""{"path":"a.txt","old_string":"","new_string":"riga"}""")

        assertTrue(preview!!.isInsertion)
    }

    @Test
    fun `a call that is not a replace-style edit yields no diff`() {
        assertNull(editPreview("""{"command":"ls"}"""))
        assertNull(editPreview("""{"old_string":"uguale","new_string":"uguale"}"""))
        assertNull(editPreview(null))
    }

    @Test
    fun `arguments are pretty-printed for the disclosure`() {
        val pretty = prettyArguments("""{"a":1,"b":[2,3]}""")

        assertEquals("{\n  \"a\": 1,\n  \"b\": [\n    2,\n    3\n  ]\n}", pretty)
    }

    @Test
    fun `a payload that is not json is passed through untouched`() {
        assertEquals("ls -la", prettyArguments("ls -la"))
        assertNull(prettyArguments("  "))
    }

    /**
     * Build one approval event.
     *
     * @param seq the log position.
     * @param kind either half of the pair.
     * @param approvalId the id that pairs them.
     * @param toolName the tool that asked.
     * @param callId the call the approval belongs to.
     * @param outcome the verdict, for the decided half.
     */
    private fun approval(
        seq: Int,
        kind: String = "approval-asked",
        approvalId: String? = "appr-1",
        toolName: String = "pwsh",
        callId: String? = "call-7",
        outcome: String? = null,
    ) = Entry(
        kind = kind,
        seq = seq,
        type = if (kind == "approval-asked") "approval/asked" else "approval/decided",
        approvalId = approvalId,
        name = toolName,
        callId = callId,
        outcome = outcome,
    )

    @Test
    fun `an approval keeps its call id so a live request can be matched`() {
        val items = buildTranscript(listOf(approval(1)))

        val card = items.single() as TranscriptItem.Approval
        assertEquals("call-7", card.callId)
        assertEquals("pwsh", card.toolName)
        assertTrue(!card.settled)
    }

    @Test
    fun `a grant is paired with the request that asked for it`() {
        val items = buildTranscript(
            listOf(approval(1), approval(2, kind = "approval-decided", outcome = "allowed-once")),
        )

        val card = items.single() as TranscriptItem.Approval
        assertTrue(card.settled)
        assertTrue(card.granted)
        assertTrue(!card.refused)
        assertEquals("allowed-once", card.outcome)
    }

    @Test
    fun `a refusal is paired too`() {
        val items = buildTranscript(
            listOf(approval(1), approval(2, kind = "approval-decided", outcome = "rejected")),
        )

        val card = items.single() as TranscriptItem.Approval
        assertTrue(card.refused)
        assertTrue(!card.granted)
    }

    @Test
    fun `two requests in flight are paired by their own ids`() {
        val items = buildTranscript(
            listOf(
                approval(1, approvalId = "a", callId = "call-a"),
                approval(2, approvalId = "b", callId = "call-b"),
                approval(3, kind = "approval-decided", approvalId = "b", outcome = "rejected"),
                approval(4, kind = "approval-decided", approvalId = "a", outcome = "allowed-once"),
            ),
        )

        assertEquals(2, items.size)
        assertEquals("call-a", (items[0] as TranscriptItem.Approval).callId)
        assertEquals("call-b", (items[1] as TranscriptItem.Approval).callId)
        assertTrue((items[0] as TranscriptItem.Approval).granted)
        assertTrue((items[1] as TranscriptItem.Approval).refused)
    }

    @Test
    fun `a verdict whose request was cut off still shows the outcome`() {
        val items = buildTranscript(listOf(approval(1, kind = "approval-decided", approvalId = "gone", outcome = "rejected")))

        val card = items.single() as TranscriptItem.Approval
        assertTrue(card.settled)
        assertTrue(card.refused)
    }

    @Test
    fun `two calls in a row fold into one group`() {
        val items = buildTranscript(listOf(call(1), result(2), call(3), result(4)))

        val group = items.single() as TranscriptItem.ToolGroup
        assertEquals(2, group.steps.size)
        assertEquals(1, group.seq)
        assertEquals(listOf(1, 3), group.steps.map { it.seq })
    }

    @Test
    fun `three calls in a row fold into one group`() {
        // The case that prompted this: three commands in a row are one accordion,
        // not three cards to scroll past.
        val items = buildTranscript(listOf(call(1), result(2), call(3), result(4), call(5), result(6)))

        assertEquals(3, (items.single() as TranscriptItem.ToolGroup).steps.size)
    }

    @Test
    fun `a lone call is not wrapped in a group`() {
        // A group of one is a header with nothing to hide, and every tap it costs
        // is a tap the reader pays for nothing.
        val items = buildTranscript(listOf(call(1), result(2)))

        assertTrue(items.single() is TranscriptItem.ToolStep)
    }

    @Test
    fun `a message between calls splits the groups`() {
        val items = buildTranscript(
            listOf(
                call(1), result(2), call(3), result(4),
                message(5, "in mezzo"),
                call(6), result(7), call(8), result(9),
            ),
        )

        assertEquals(3, items.size)
        assertEquals(2, (items[0] as TranscriptItem.ToolGroup).steps.size)
        assertTrue(items[1] is TranscriptItem.Message)
        assertEquals(2, (items[2] as TranscriptItem.ToolGroup).steps.size)
    }

    @Test
    fun `a group sums the time of its calls and reports the worst outcome`() {
        val items = buildTranscript(
            listOf(
                call(1, time = 1_000), result(2, time = 1_200),
                call(3, time = 2_000), result(4, time = 2_500, isError = true),
            ),
        )

        val group = items.single() as TranscriptItem.ToolGroup
        assertEquals(700L, group.durationMs)
        assertTrue(group.failed)
        assertTrue(!group.running)
        assertEquals(listOf("call-1", "call-3"), group.callIds)
    }

    @Test
    fun `a group with a call still running says so`() {
        val items = buildTranscript(listOf(call(1), result(2), call(3)))

        val group = items.single() as TranscriptItem.ToolGroup
        assertTrue(group.running)
        assertTrue(!group.failed)
    }

    /**
     * Build one `deliverables/presented` entry.
     *
     * @param seq the log position.
     * @param files what the agent declared.
     */
    private fun delivery(seq: Int, files: List<DeclaredFile>) = Entry(
        kind = "deliverable",
        seq = seq,
        type = "deliverables/presented",
        turn = 1,
        callId = "call-$seq",
        files = files,
    )

    @Test
    fun `a delivery becomes its own card with the files it declared`() {
        // The delivery is its own item rather than a row inside the `present`
        // card: this event is the durable half, and it is what the host resolves
        // when the file is opened or downloaded.
        val items = buildTranscript(
            listOf(
                call(1, name = "present", arguments = """{"files":[{"path":"nota.md"}]}"""), result(2, name = "present"),
                delivery(3, listOf(DeclaredFile(index = 0, path = "nota.md", description = "la nota"))),
            ),
        )

        assertEquals(2, items.size)
        assertTrue("the tool call stays a card of its own", items[0] is TranscriptItem.ToolStep)
        val card = items[1] as TranscriptItem.Deliverable
        assertEquals(3, card.seq)
        assertEquals(1, card.files.size)
        assertEquals("nota.md", card.files[0].path)
        assertEquals("la nota", card.files[0].description)
    }

    @Test
    fun `a delivery that declares nothing is not drawn`() {
        // An empty card would be a claim about a handover that did not happen.
        val items = buildTranscript(
            listOf(call(1), result(2), delivery(3, emptyList())),
        )

        assertEquals(1, items.size)
        assertTrue(items[0] is TranscriptItem.ToolStep)
    }

    @Test
    fun `a delivery breaks a run of tool calls`() {
        // The card belongs where the handover happened, not after whatever the
        // agent ran next: grouping across it would file the delivery under a run
        // of commands that came after it.
        val items = buildTranscript(
            listOf(
                call(1), result(2), call(3), result(4),
                delivery(5, listOf(DeclaredFile(index = 0, path = "nota.md"))),
                call(6), result(7), call(8), result(9),
            ),
        )

        assertEquals(3, items.size)
        assertTrue(items[0] is TranscriptItem.ToolGroup)
        assertTrue(items[1] is TranscriptItem.Deliverable)
        assertTrue(items[2] is TranscriptItem.ToolGroup)
    }
}
