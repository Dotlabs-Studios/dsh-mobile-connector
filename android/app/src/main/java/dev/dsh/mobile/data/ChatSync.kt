package dev.dsh.mobile.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The chat-sync state machine, extracted from the view model so it can be
 * tested on the JVM.
 *
 * Extracting it was not ceremony: the rules below are the ones most likely to
 * corrupt a transcript, and they are exactly the ones that cannot be exercised
 * without a phone. Keeping them in an Android-free class makes every branch
 * reachable from a unit test.
 */
class ChatSync(private val source: ChatSource) {

    /** The transcript currently held, oldest first. */
    var entries: List<Entry> = emptyList()
        private set

    /** The newest log position already held, or -1 when empty. */
    var newestSeq: Int = -1
        private set

    /** The oldest log position held, used to decide whether a page was rewritten. */
    var oldestSeq: Int? = null
        private set

    /** Whether older pages remain before the held window. */
    var hasOlder: Boolean = false
        private set

    /** The last failure, or null when the last operation succeeded. */
    var lastError: ApiException? = null
        private set

    /**
     * Replace the held window wholesale.
     *
     * Used when the view model pages backwards: the prepended range comes from
     * a bounded request whose endpoint does not depend on how far forward the
     * window reaches, so the caller owns the resulting window.
     *
     * @param next the complete window, oldest first.
     * @param oldestOverride the oldest position, when the caller knows it exactly.
     * @param hasOlder whether pages remain before the window.
     */
    fun replaceWindow(next: List<Entry>, oldestOverride: Int? = null, hasOlder: Boolean = this.hasOlder) {
        entries = next
        newestSeq = next.lastOrNull()?.seq ?: newestSeq
        oldestSeq = oldestOverride ?: next.firstOrNull()?.seq
        this.hasOlder = hasOlder
    }

    /** Clear the window, ready to load a different session. */
    fun reset() {
        entries = emptyList()
        newestSeq = -1
        oldestSeq = null
        hasOlder = false
        lastError = null
    }

    /**
     * Replace the transcript with the newest page.
     *
     * @param sessionId the session to read.
     * @param limit how many entries to request.
     */
    suspend fun loadNewest(sessionId: String, limit: Int = 60) {
        try {
            val page = source.messages(sessionId, beforeSeq = null, limit = limit)
            entries = page.entries
            newestSeq = page.entries.lastOrNull()?.seq ?: -1
            oldestSeq = page.entries.firstOrNull()?.seq
            hasOlder = page.hasMore
            lastError = null
        } catch (error: ApiException) {
            lastError = error
        }
    }

    /**
     * Pick up whatever was appended since the held window.
     *
     * The rule is deliberately "append what is new, never replace". A harness
     * session log only grows: a compaction shadows earlier nodes by appending a
     * replacement event, and it never deletes or renumbers what came before. So
     * every held sequence stays valid, and appending cannot splice two
     * incompatible histories together.
     *
     * An earlier version replaced the window whenever a page's oldest position
     * had moved past the held one. That is exactly what a slid-forward tail
     * page looks like, and the effect was to throw away history the user had
     * paged back through. Append-only is both simpler and lossless.
     *
     * @param sessionId the session to read.
     * @param limit how many entries to request.
     */
    suspend fun refresh(sessionId: String, limit: Int = 60) {
        try {
            val page = source.messages(sessionId, beforeSeq = null, limit = limit)
            val fresh = page.entries.filter { it.seq > newestSeq }

            if (fresh.isEmpty()) {
                lastError = null
                return
            }

            entries = entries + fresh
            newestSeq = entries.last().seq
            oldestSeq = entries.first().seq
            hasOlder = hasOlder || page.hasMore
            lastError = null
        } catch (error: ApiException) {
            lastError = error
        }
    }
}

/** The read side of the harness the chat sync depends on. */
interface ChatSource {
    /**
     * Read one page of history.
     *
     * @param sessionId the addressed session.
     * @param beforeSeq exclusive upper bound, or null for the newest page.
     * @param limit maximum entries to return.
     */
    suspend fun messages(sessionId: String, beforeSeq: Int? = null, limit: Int = 60): MessagePage
}

/**
 * Entry roles and sources a phone chat should not render.
 *
 * A session log records more than the conversation. The harness renders the
 * whole agent persona, tool guidance, and skill catalog into a system prompt,
 * and injects runtime context and reminders as `user/message` events so the
 * model sees them in order. All of it is real model input, and all of it dwarfs
 * the actual exchange — in one measured session the system prompt alone was
 * 5,985 characters against 37 characters of the user's own message.
 *
 * The `source` field is what separates them: the operator's own words carry
 * `user`, everything the harness composed carries its own provenance. Dropping
 * the non-`user` sources keeps the transcript honest without guessing at
 * content, and the operator who needs to audit the injected material has the
 * desktop GUI for exactly that.
 */
private val HIDDEN_ROLES = setOf("system", "developer")

/** The one source that means a human typed it. */
private const val USER_SOURCE = "user"

/**
 * Whether a message entry belongs in the conversation as a chat bubble.
 *
 * @param entry a transcript entry.
 * @return true for the operator's own prompts and the model's answers.
 */
private fun isConversational(entry: Entry): Boolean = when (entry.role) {
    null -> true
    "assistant" -> true
    "user" -> entry.source == null || entry.source == USER_SOURCE
    else -> entry.role !in HIDDEN_ROLES
}

/**
 * Drop the entries that would bury the conversation.
 *
 * @param entries the full held window, oldest first.
 * @return the entries a chat client should show.
 */
fun visibleTranscript(entries: List<Entry>): List<Entry> = entries.filter { entry ->
    entry.kind != "message" || isConversational(entry)
}

/** Argument keys worth surfacing, in the order a reader wants them. */
private val SUMMARY_KEYS = listOf(
    "file_path", "path", "filePath", "command", "pattern", "query", "url",
    "description", "prompt", "old_string", "new_string",
)

/** How much of one argument value a collapsed tool row shows. */
private const val SUMMARY_VALUE_LIMIT = 120

/** How much raw argument text is shown when no key stands out. */
private const val SUMMARY_FALLBACK_LIMIT = 300

/**
 * Reduce a tool call's arguments to one readable line.
 *
 * A tool call is often the largest object in the log — an edit call carries the
 * complete old and new file contents. Dumping that into a chat row pushes the
 * conversation off the screen, so the row summarises instead: the file path, the
 * command, the pattern, whichever identifies the call, with every value capped.
 *
 * @param arguments the raw JSON argument string, as recorded in the log.
 * @return a compact summary, or null when there is nothing to show.
 */
fun toolCallSummary(arguments: String?): String? {
    val raw = arguments?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val parsed = runCatching {
        Json.parseToJsonElement(raw) as? JsonObject
    }.getOrNull()

    if (parsed == null) {
        return raw.take(SUMMARY_FALLBACK_LIMIT) + if (raw.length > SUMMARY_FALLBACK_LIMIT) "…" else ""
    }

    val parts = SUMMARY_KEYS.mapNotNull { key ->
        val value = parsed[key]
        val primitive = value as? JsonPrimitive
        val text = primitive?.takeIf { it.isString }?.content ?: return@mapNotNull null
        if (text.isBlank()) null
        else "$key=${text.replace('\n', ' ').take(SUMMARY_VALUE_LIMIT)}"
    }

    if (parts.isNotEmpty()) return parts.joinToString("  ·  ")

    val keys = parsed.keys
    return if (keys.isEmpty()) null else "(${keys.joinToString(", ")})"
}
