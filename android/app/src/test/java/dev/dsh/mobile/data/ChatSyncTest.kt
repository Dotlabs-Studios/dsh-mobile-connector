package dev.dsh.mobile.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the chat-sync state machine.
 *
 * These rules decide what the transcript shows after every poll, and getting
 * them wrong silently corrupts what the user reads. They were previously
 * private methods on an Android view model, reachable only from a phone; here
 * every branch — append, replace-on-rewrite, and the no-progress guard — is
 * driven directly.
 */
class ChatSyncTest {

    /**
     * A scripted source serving one prepared page per *forward* read.
     *
     * A backwards read (`beforeSeq != null`) is answered by [pagingPages] and
     * does not advance the script, because the view model issues those itself:
     * consuming a page for them would desynchronise the script from the calls
     * under test.
     */
    private class ScriptedSource(
        private val forwardPages: MutableList<Result<MessagePage>>,
        private val pagingPages: MutableList<Result<MessagePage>> = mutableListOf(),
    ) : ChatSource {
        var forwardCalls = 0
            private set
        var lastBeforeSeq: Int? = null
            private set

        override suspend fun messages(sessionId: String, beforeSeq: Int?, limit: Int): MessagePage {
            lastBeforeSeq = beforeSeq
            return if (beforeSeq != null) {
                if (pagingPages.isEmpty()) throw ApiException("transport", "no scripted paging page left")
                pagingPages.removeAt(0).getOrThrow()
            } else {
                forwardCalls += 1
                if (forwardPages.isEmpty()) throw ApiException("transport", "no scripted forward page left")
                forwardPages.removeAt(0).getOrThrow()
            }
        }
    }

    /**
     * Build one message entry.
     *
     * @param seq the log position.
     * @param text the message text.
     * @param role the author.
     */
    private fun msg(seq: Int, text: String, role: String = "assistant") = Entry(
        kind = "message",
        seq = seq,
        type = if (role == "user") "user/message" else "assistant/message",
        role = role,
        content = listOf(Block(type = "text", text = text)),
    )

    /**
     * Build one page.
     *
     * @param entries the page's entries.
     * @param hasMore whether older entries remain.
     */
    private fun page(entries: List<Entry>, hasMore: Boolean = false) = MessagePage(
        sessionId = "session-1",
        cursor = entries.lastOrNull()?.seq ?: -1,
        hasMore = hasMore,
        entries = entries,
    )

    private fun texts(sync: ChatSync) = sync.entries.map { it.content.firstOrNull()?.text }

    @Test
    fun `loadNewest replaces everything and records the window`() = runBlocking {
        val source = ScriptedSource(
            mutableListOf(Result.success(page(listOf(msg(1, "uno"), msg(2, "due")), hasMore = true))),
        )
        val sync = ChatSync(source)

        sync.loadNewest("session-1")

        assertEquals(listOf("uno", "due"), texts(sync))
        assertEquals(2, sync.newestSeq)
        assertEquals(1, sync.oldestSeq)
        assertTrue(sync.hasOlder)
        assertNull(sync.lastError)
        assertNull("the newest page must not page backwards", source.lastBeforeSeq)
    }

    @Test
    fun `refresh appends only what is new`() = runBlocking {
        val source = ScriptedSource(
            mutableListOf(
                Result.success(page(listOf(msg(1, "uno"), msg(2, "due")))),
                // The tail page repeats the held window and adds one entry.
                Result.success(page(listOf(msg(1, "uno"), msg(2, "due"), msg(3, "tre")))),
            ),
        )
        val sync = ChatSync(source)

        sync.loadNewest("session-1")
        sync.refresh("session-1")

        assertEquals(listOf("uno", "due", "tre"), texts(sync))
        assertEquals(3, sync.newestSeq)
        assertEquals(1, sync.oldestSeq)
    }

    @Test
    fun `refresh is a no-op when nothing changed`() = runBlocking {
        val held = listOf(msg(1, "uno"), msg(2, "due"))
        val source = ScriptedSource(
            mutableListOf(
                Result.success(page(held)),
                Result.success(page(held)),
                Result.success(page(held)),
            ),
        )
        val sync = ChatSync(source)

        sync.loadNewest("session-1")
        sync.refresh("session-1")
        sync.refresh("session-1")

        assertEquals(listOf("uno", "due"), texts(sync))
        assertEquals(2, sync.newestSeq)
    }

    @Test
    fun `a slid-forward tail page appends instead of discarding history`() = runBlocking {
        // The held window is the tail of a longer log; a later page shows only
        // the newest fifty. Its oldest position has moved past the held one,
        // which is what a growing log looks like, not a rewrite. Nothing may
        // be dropped.
        val source = ScriptedSource(
            mutableListOf(
                Result.success(page(listOf(msg(1, "uno"), msg(2, "due")))),
                Result.success(page(listOf(msg(9, "nove"), msg(10, "dieci")))),
            ),
        )
        val sync = ChatSync(source)

        sync.loadNewest("session-1")
        sync.refresh("session-1")

        assertEquals(listOf("uno", "due", "nove", "dieci"), texts(sync))
        assertEquals(10, sync.newestSeq)
        assertEquals(1, sync.oldestSeq)
    }

    @Test
    fun `a short tail page cannot erase history loaded by paging back`() = runBlocking {
        // The realistic shape of a big transcript: the newest page is shorter
        // than what the user has paged through, so a rewrite check based on the
        // window alone would throw away everything older.
        val scrollback = (1..40).map { msg(it, "vecchio-$it") }
        val newestFifty = (51..100).map { msg(it, "recente-$it") }
        val source = ScriptedSource(
            forwardPages = mutableListOf(
                Result.success(page(newestFifty, hasMore = true)),
                Result.success(page(newestFifty + msg(101, "ultimo"))),
            ),
            pagingPages = mutableListOf(Result.success(page(scrollback))),
        )
        val sync = ChatSync(source)

        sync.loadNewest("session-1")
        assertEquals(50, sync.entries.size)

        // The view model pages backwards and hands over the wider window.
        val older = source.messages("session-1", beforeSeq = 51, limit = 40)
        sync.replaceWindow(
            next = older.entries + sync.entries,
            oldestOverride = older.entries.first().seq,
            hasOlder = true,
        )
        assertEquals(90, sync.entries.size)

        sync.refresh("session-1")
        assertEquals("only the one new entry may be appended", 91, sync.entries.size)
        assertEquals("ultimo", sync.entries.last().content.first().text)
        assertEquals("vecchio-1", sync.entries.first().content.first().text)
    }

    @Test
    fun `entries already held are never duplicated by a later poll`() = runBlocking {
        val firstPage = listOf(msg(1, "a"), msg(2, "b"), msg(3, "c"))
        val source = ScriptedSource(
            mutableListOf(
                Result.success(page(firstPage)),
                // The server repeats the whole window and adds one: only the new
                // entry may appear once.
                Result.success(page(firstPage + msg(4, "d"))),
                Result.success(page(firstPage + msg(4, "d") + msg(5, "e"))),
            ),
        )
        val sync = ChatSync(source)

        sync.loadNewest("session-1")
        sync.refresh("session-1")
        sync.refresh("session-1")

        assertEquals(listOf("a", "b", "c", "d", "e"), texts(sync))
        assertEquals(5, sync.newestSeq)
    }

    @Test
    fun `an empty first page leaves an empty window rather than failing`() = runBlocking {
        val source = ScriptedSource(mutableListOf(Result.success(page(emptyList()))))
        val sync = ChatSync(source)

        sync.loadNewest("session-1")

        assertEquals(emptyList<String>(), texts(sync))
        assertEquals(-1, sync.newestSeq)
        assertNull(sync.oldestSeq)
        assertNull(sync.lastError)
    }

    @Test
    fun `a failed refresh keeps the held transcript and records the failure`() = runBlocking {
        val source = ScriptedSource(
            mutableListOf(
                Result.success(page(listOf(msg(1, "uno")))),
                Result.failure(ApiException("transport", "host irraggiungibile")),
            ),
        )
        val sync = ChatSync(source)

        sync.loadNewest("session-1")
        sync.refresh("session-1")

        assertEquals("a transient failure must not blank the chat", listOf("uno"), texts(sync))
        assertEquals("transport", sync.lastError?.code)
    }

    @Test
    fun `a failed first load records the failure`() = runBlocking {
        val source = ScriptedSource(mutableListOf(Result.failure(ApiException("session/not-found", "non trovata", 404))))
        val sync = ChatSync(source)

        sync.loadNewest("session-1")

        assertEquals(404, sync.lastError?.status)
        assertEquals(emptyList<String>(), texts(sync))
    }

    @Test
    fun `a later success clears an earlier failure`() = runBlocking {
        val source = ScriptedSource(
            mutableListOf(
                Result.failure(ApiException("transport", "giù")),
                Result.success(page(listOf(msg(1, "tornato")))),
            ),
        )
        val sync = ChatSync(source)

        sync.loadNewest("session-1")
        assertEquals("transport", sync.lastError?.code)

        sync.loadNewest("session-1")
        assertNull("a recovered poll must clear the banner", sync.lastError)
        assertEquals(listOf("tornato"), texts(sync))
    }

    @Test
    fun `hasMore is sticky across polls so older pages stay reachable`() = runBlocking {
        val source = ScriptedSource(
            mutableListOf(
                Result.success(page(listOf(msg(50, "recente")), hasMore = true)),
                Result.success(page(listOf(msg(50, "recente"), msg(51, "nuovo")), hasMore = false)),
            ),
        )
        val sync = ChatSync(source)

        sync.loadNewest("session-1")
        assertTrue(sync.hasOlder)

        sync.refresh("session-1")
        assertTrue("a tail page must not hide earlier history", sync.hasOlder)
        assertEquals(listOf("recente", "nuovo"), texts(sync))
    }

    @Test
    fun `tool entries flow through the same window as messages`() = runBlocking {        val toolEntry = Entry(
            kind = "tool-call",
            seq = 2,
            type = "tool/call",
            name = "pwsh",
            arguments = """{"command":"ls"}""",
        )
        val source = ScriptedSource(
            mutableListOf(
                Result.success(page(listOf(msg(1, "faccio"), toolEntry))),
                Result.success(
                    page(
                        listOf(
                            msg(1, "faccio"),
                            toolEntry,
                            Entry(kind = "tool-result", seq = 3, type = "tool/result", name = "pwsh"),
                        ),
                    ),
                ),
            ),
        )
        val sync = ChatSync(source)

        sync.loadNewest("session-1")
        sync.refresh("session-1")

        assertEquals(listOf("message", "tool-call", "tool-result"), sync.entries.map { it.kind })
    }

    @Test
    fun `the system prompt and developer context stay out of the transcript`() {
        val entries = listOf(
            Entry(kind = "message", seq = 0, type = "system/message", role = "system", source = "system-prompt", content = listOf(Block(type = "text", text = "You are an AI agent…"))),
            Entry(kind = "turn-start", seq = 1, type = "turn/start", turn = 1),
            Entry(kind = "message", seq = 2, type = "developer/message", role = "developer", source = "developer", content = listOf(Block(type = "text", text = "workspace rules"))),
            msg(3, "ciao", role = "user").copy(source = "user"),
            msg(4, "ecco la risposta").copy(source = "model"),
            Entry(kind = "tool-call", seq = 5, type = "tool/call", name = "pwsh"),
        )

        val visible = visibleTranscript(entries)

        assertEquals(
            "only the conversation survives",
            listOf("ciao", "ecco la risposta"),
            visible.filter { it.kind == "message" }.map { it.content.first().text },
        )
        assertEquals(listOf("user", "assistant"), visible.filter { it.kind == "message" }.map { it.role })
        assertEquals("the turn marker, two messages, and the tool row are kept", 4, visible.size)
        assertTrue("no system entry may reach the chat", visible.none { it.role == "system" })
        assertTrue("no developer entry may reach the chat", visible.none { it.role == "developer" })
    }

    @Test
    fun `harness-injected prompts logged as user messages are hidden`() {
        // These are the real provenance values the harness records for its own
        // injected material, measured from a live session.
        val injected = listOf("runtime-context", "skill-catalog", "system-reminder")
        val entries = buildList {
            add(msg(0, "You are an AI agent powered by DeepSeek Harness.", role = "user").copy(source = "system-prompt"))
            injected.forEachIndexed { index, source ->
                add(msg(index + 1, "injected: $source", role = "user").copy(source = source))
            }
            add(msg(10, "Rispondi solo con la parola: MOBILEOK", role = "user").copy(source = "user"))
            add(msg(11, "MOBILEOK").copy(source = "model"))
        }

        val visible = visibleTranscript(entries)

        assertEquals(
            "only the operator's own words and the model's answer remain",
            listOf("Rispondi solo con la parola: MOBILEOK", "MOBILEOK"),
            visible.map { it.content.first().text },
        )
    }

    @Test
    fun `a user message without provenance is still shown`() {
        // Older or imported logs may not carry a source; the safe reading of a
        // user-role entry with no provenance is that a person wrote it.
        val entries = listOf(msg(1, "messaggio senza provenienza", role = "user"))

        assertEquals(1, visibleTranscript(entries).size)
    }

    @Test
    fun `filtering never renumbers what is left`() {
        val entries = listOf(
            Entry(kind = "message", seq = 0, type = "system/message", role = "system", source = "system-prompt", content = listOf(Block(type = "text", text = "prompt"))),
            msg(7, "dopo il prompt", role = "user").copy(source = "user"),
        )

        val visible = visibleTranscript(entries)

        assertEquals("the log position must survive filtering", 7, visible.single().seq)
    }

    @Test
    fun `a tool call is summarised instead of dumped`() {
        // The real shape that filled the screen: an edit call carrying an entire
        // path plus old and new file fragments.
        val arguments = """
            {"file_path": "K:\\Repository\\demo-app\\apps\\server\\src\\credits\\catalog.ts",
             "old_string": "    case \"deepseek\":\n      return this.config.DEEPSEEK_API_KEY ?? null;",
             "new_string": "    case \"deepseek\":\n      return this.config.DEEPSEEK_API_KEY ?? null;\n    case \"xiaomi\":\n      return this.config.XIAOMI_API_KEY ?? null;"}
        """.trimIndent()

        val summary = toolCallSummary(arguments)

        assertTrue("the path identifies the call", summary!!.contains("catalog.ts"))
        assertTrue("the path key is named", summary.contains("file_path="))
        assertTrue("the summary collapses newlines", !summary.contains("\n"))
        assertTrue("the summary stays one line", summary.length < 400)
        assertTrue("whole file fragments are not dumped", !summary.contains("XIAOMI_API_KEY ?? null;\n"))
    }

    @Test
    fun `a shell call shows its command`() {
        val summary = toolCallSummary("""{"command":"pnpm run build","description":"build the app"}""")
        assertTrue(summary!!.contains("command=pnpm run build"))
        assertTrue(summary.contains("description=build the app"))
    }

    @Test
    fun `a search call shows its pattern`() {
        val summary = toolCallSummary("""{"pattern":"TODO|FIXME","path":"src"}""")
        assertTrue(summary!!.contains("pattern=TODO|FIXME"))
    }

    @Test
    fun `arguments without a known key degrade to a bounded blob`() {
        val summary = toolCallSummary("""{"weird_key":"value"}""")
        assertEquals("(weird_key)", summary)
    }

    @Test
    fun `a very long unknown argument is truncated`() {
        val summary = toolCallSummary("""{"blob":"${"x".repeat(900)}"}""")
        assertTrue("the fallback is bounded", summary!!.length <= SUMMARY_BOUND)
    }

    @Test
    fun `malformed arguments are truncated rather than crashing`() {
        val summary = toolCallSummary("{not json at all".repeat(40))
        assertTrue(summary != null)
        assertTrue(summary!!.length <= SUMMARY_BOUND)
        assertNull(toolCallSummary(null))
        assertNull(toolCallSummary("   "))
    }

    private companion object {
        /** Generous bound: the fallback cap plus the ellipsis. */
        const val SUMMARY_BOUND = 320
    }
}
