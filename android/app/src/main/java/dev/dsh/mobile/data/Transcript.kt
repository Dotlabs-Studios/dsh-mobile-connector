package dev.dsh.mobile.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One renderable row of a transcript.
 *
 * The session log is a flat, append-only stream: a tool call and its result are
 * two separate events, and the turn/step markers around them are bookkeeping.
 * The harness renders that stream as cards, not as loose rows, so the phone has
 * to rebuild the same grouping before anything can be drawn. Doing it here —
 * Android-free — is what makes the rule testable: joining the wrong call to the
 * wrong result silently shows one command's output under another command's
 * name, and no screenshot would catch it.
 */
sealed interface TranscriptItem {
    /** The log position this item is anchored to. */
    val seq: Int

    /** One message bubble. */
    @Immutable
    data class Message(val entry: Entry) : TranscriptItem {
        override val seq: Int get() = entry.seq
    }

    /**
     * One tool invocation together with its result, when the result has arrived.
     *
     * @property call the `tool-call` event.
     * @property result the matching `tool-result`, or null while it is running.
     */
    @Immutable
    data class ToolStep(val call: Entry, val result: Entry?) : TranscriptItem {
        override val seq: Int get() = call.seq

        /** The tool's name, taken from whichever half carries it. */
        val name: String get() = call.name ?: result?.name ?: "strumento"

        /** Whether the harness reported the invocation as failed. */
        val isError: Boolean get() = result?.isError == true

        /** Whether the invocation has not come back yet. */
        val running: Boolean get() = result == null

        /** Raw argument JSON exactly as the harness recorded it. */
        val arguments: String? get() = call.arguments

        /**
         * The harness's id for this invocation.
         *
         * It is what ties a card to anything the host is holding for it — an
         * approval, a question — because both sides see the same call id while
         * neither sees the other's internal id in time.
         */
        val callId: String? get() = call.callId ?: result?.callId

        /** Result text, with every text block joined. */
        val output: String
            get() = result?.content.orEmpty()
                .filter { it.type == "text" }
                .mapNotNull { it.text }
                .joinToString("\n")

        /** Images the tool produced or that came back in its result. */
        val images: List<Block>
            get() = result?.content.orEmpty().filter { it.type == "image" && it.attachmentId != null }

        /** Files the result refers to by handle. */
        val files: List<Block>
            get() = result?.content.orEmpty().filter { it.type == "file" }

        /**
         * How long the call took, when both halves are timestamped.
         *
         * This is the number the harness puts beside a finished step, and it is
         * the only cost signal a phone can show without the desktop's usage
         * panel.
         */
        val durationMs: Long?
            get() {
                val from = call.time ?: return null
                val to = result?.time ?: return null
                return (to - from).takeIf { it >= 0 }
            }
    }

    /**
     * A run of consecutive tool calls, drawn as one accordion.
     *
     * An agent does not make one call at a time: it reads three files, edits two
     * and runs a command before saying anything. Drawn as separate cards that is
     * a page of headers to scroll past for a turn that produced one sentence,
     * which is why the harness folds a whole turn's process into one block.
     *
     * @property steps the calls, in order; at least two, because a group of one
     *   would be a header with nothing to hide.
     */
    @Immutable
    data class ToolGroup(val steps: List<ToolStep>) : TranscriptItem {
        override val seq: Int get() = steps.first().seq

        /** The call ids in the group, so a result can be matched to its card. */
        val callIds: List<String> get() = steps.mapNotNull { it.callId }

        /** Whether any call in the group is still running. */
        val running: Boolean get() = steps.any { it.running }

        /** Whether any call in the group failed. */
        val failed: Boolean get() = steps.any { it.isError }

        /**
         * How long the group took, when every call is timed.
         *
         * Only the sum of what is known, and only when something is known: a
         * partial total presented as the group's cost would be worse than none.
         */
        val durationMs: Long?
            get() = steps.mapNotNull { it.durationMs }.takeIf { it.isNotEmpty() }?.sum()
    }

    /**
     * One message the harness emitted that is worth a line of its own.
     *
     * Currently only interruptions, which the harness records as their own
     * event rather than as message text.
     */
    @Immutable
    data class Notice(val entry: Entry) : TranscriptItem {
        override val seq: Int get() = entry.seq
    }

    /**
     * The files an agent declared with `present`.
     *
     * Its own item rather than a row inside the `present` call's card, because
     * the delivery is the durable half: the event carries the declarations
     * themselves — path, description, and the index the host resolves by — while
     * the call's arguments are a string a page boundary can cut in half. The
     * `present` card stays where it is: a tool was invoked, and hiding that would
     * be the transcript editing itself.
     */
    @Immutable
    data class Deliverable(val entry: Entry) : TranscriptItem {
        override val seq: Int get() = entry.seq

        /** The declared files, in the order the agent listed them. */
        val files: List<DeclaredFile> get() = entry.files
    }

    /**
     * One permission request, paired with its verdict when there is one.
     *
     * The harness writes two durable events — `approval/asked` and
     * `approval/decided` — under the same approval id, so the transcript can
     * show "asked, and here is what happened" instead of two unrelated lines.
     *
     * @property asked the event that opened the request.
     * @property decided the event that closed it, or null while it is open.
     */
    @Immutable
    data class Approval(val asked: Entry, val decided: Entry?) : TranscriptItem {
        override val seq: Int get() = asked.seq

        /** The harness's own approval id, which pairs the two events. */
        val approvalId: String? get() = asked.approvalId

        /** The tool that asked for permission. */
        val toolName: String get() = asked.name ?: "strumento"

        /**
         * The tool call this approval belongs to.
         *
         * This is what lets an open request in the transcript be matched with
         * the live pending approval the host is holding: both carry the call id,
         * and the approval id is minted inside the harness where no client can
         * see it in time.
         */
        val callId: String? get() = asked.callId

        /** The verdict the harness recorded, or null while the request is open. */
        val outcome: String? get() = decided?.outcome

        /** Whether this was granted for one execution. */
        val granted: Boolean get() = outcome == "allowed-once"

        /** Whether this was explicitly refused. */
        val refused: Boolean get() = outcome == "rejected"

        /** Whether a verdict exists at all. */
        val settled: Boolean get() = decided != null
    }
}

/**
 * Rebuild the harness's card structure from the flat session log.
 *
 * Rules, in the order they matter:
 *
 * 1. A `tool-call` opens a card; the `tool-result` with the same `callId`
 *    closes it. Matching on `callId` rather than on order is what keeps a
 *    parallel tool block honest — several calls can be in flight at once, and
 *    pairing them by position would swap their outputs.
 * 2. When either half carries no `callId`, the pair is closed in arrival order
 *    instead: the oldest still-open call takes the result. That is the best
 *    available guess, and it is the same guess the harness makes.
 * 3. A result whose call was never seen stands on its own rather than being
 *    attached to the wrong card. A page boundary can cut the call off, and a
 *    host can report a result for a call recorded before the window.
 * 4. A call whose result never arrives stays in the list as still running. That
 *    is the normal state while the agent works, and dropping it would make the
 *    phone look like the agent had done nothing.
 * 5. Turn and step markers are dropped: they carry no content, and the cards
 *    already read in order.
 *
 * @param entries the visible transcript, oldest first.
 * @return one item per bubble and one per invocation.
 */
fun buildTranscript(entries: List<Entry>): List<TranscriptItem> {
    val items = ArrayList<TranscriptItem>(entries.size)
    // Two ways to find the card a result belongs to: by the id the harness
    // assigned, and — when it assigned none — by arrival order.
    val named = HashMap<String, Int>()
    val anonymous = ArrayDeque<Int>()
    val openApprovals = HashMap<String, Int>()
    val anonymousApprovals = ArrayDeque<Int>()

    /** Close the open card at [index], or report that it is no longer a call. */
    fun close(index: Int, result: Entry) {
        val opened = items.getOrNull(index) as? TranscriptItem.ToolStep ?: return
        items[index] = opened.copy(result = result)
    }

    for (entry in entries) {
        when (entry.kind) {
            "message" -> items += TranscriptItem.Message(entry)
            "tool-call" -> {
                val id = entry.callId
                if (id != null) named[id] = items.size else anonymous.addLast(items.size)
                items += TranscriptItem.ToolStep(entry, null)
            }
            "tool-result" -> {
                val byId = entry.callId?.let { named.remove(it) }
                val index = byId ?: anonymous.removeFirstOrNull()
                if (index != null) {
                    close(index, entry)
                } else {
                    items += TranscriptItem.ToolStep(call = entry.copy(kind = "tool-call"), result = entry)
                }
            }
            "approval-asked" -> {
                val id = entry.approvalId
                if (id != null) openApprovals[id] = items.size else anonymousApprovals.addLast(items.size)
                items += TranscriptItem.Approval(entry, null)
            }
            "approval-decided" -> {
                val byId = entry.approvalId?.let { openApprovals.remove(it) }
                val index = byId ?: anonymousApprovals.removeFirstOrNull()
                val opened = index?.let { items.getOrNull(it) as? TranscriptItem.Approval }
                if (opened != null && index != null) {
                    items[index] = opened.copy(decided = entry)
                } else {
                    // A verdict whose request was cut off by a page boundary is
                    // still worth showing: the outcome is the fact that matters.
                    items += TranscriptItem.Approval(asked = entry.copy(kind = "approval-asked"), decided = entry)
                }
            }
            // A delivery that declares no readable file has nothing to draw, and
            // an empty card would be a claim about a handover that did not happen.
            "deliverable" -> if (entry.files.isNotEmpty()) items += TranscriptItem.Deliverable(entry)
            else -> Unit
        }
    }
    return groupConsecutiveCalls(items)
}

/** How many calls in a row are worth folding behind one header. */
private const val MIN_GROUP_SIZE = 2

/**
 * Fold runs of consecutive tool calls into one accordion each.
 *
 * Runs of one are left alone: a group of a single call is a header with nothing
 * to hide, and every extra tap it costs is a tap the reader pays for nothing.
 *
 * @param items the transcript, oldest first.
 * @return the transcript with runs folded.
 */
private fun groupConsecutiveCalls(items: List<TranscriptItem>): List<TranscriptItem> {
    val out = ArrayList<TranscriptItem>(items.size)
    val run = ArrayList<TranscriptItem.ToolStep>()

    fun flush() {
        if (run.isEmpty()) return
        if (run.size < MIN_GROUP_SIZE) {
            out += run.toList()
        } else {
            out += TranscriptItem.ToolGroup(run.toList())
        }
        run.clear()
    }

    for (item in items) {
        if (item is TranscriptItem.ToolStep) run += item else {
            flush()
            out += item
        }
    }
    flush()
    return out
}

/** Argument keys worth showing on the card's second line, most specific first. */
private val CARD_KEYS = listOf(
    "file_path", "filePath", "path", "command", "pattern", "query", "url",
    "description", "prompt", "skill", "name",
)

/** How much of one argument value the card's summary keeps. */
private const val CARD_VALUE_LIMIT = 160

/**
 * The one line under a tool card's title, the way the harness labels a call.
 *
 * The harness puts the identifying argument — the file, the command, the
 * pattern — beside the tool name, and hides the full argument object behind a
 * disclosure. A phone has even less room, so this picks the same handful of
 * keys and caps each value.
 *
 * @param name the tool name, which decides which keys are worth looking at.
 * @param arguments the raw argument JSON.
 * @return a single line, or null when there is nothing identifying to show.
 */
fun cardSummary(name: String, arguments: String?): String? {
    val raw = arguments?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val parsed = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        ?: return raw.lineSequence().first().take(CARD_VALUE_LIMIT)

    // A shell call is the one case where the command *is* the whole meaning of
    // the line, so it wins over the generic key order.
    val preferred = if (name in SHELL_TOOLS) listOf("command", "description") + CARD_KEYS else CARD_KEYS
    for (key in preferred) {
        val text = (parsed[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (text.isNotEmpty()) {
            return text.lineSequence().first().take(CARD_VALUE_LIMIT) +
                if (text.length > CARD_VALUE_LIMIT) "…" else ""
        }
    }
    val keys = parsed.keys
    return if (keys.isEmpty()) null else "(${keys.joinToString(", ")})"
}

/** Tool names that run a command, where the command is the identifying detail. */
private val SHELL_TOOLS = setOf("pwsh", "bash", "shell", "terminal", "run", "cmd")

/** Argument keys that carry a replace-style edit, in the order they are read. */
private val EDIT_OLD_KEYS = listOf("old_string", "oldText", "old_str", "old")
private val EDIT_NEW_KEYS = listOf("new_string", "newText", "new_str", "new")

/** One replace-style edit recovered from a tool call's arguments. */
data class EditPreview(
    val filePath: String?,
    val oldText: String,
    val newText: String,
) {
    /** Whether the edit only inserts, which the diff view renders as additions. */
    val isInsertion: Boolean get() = oldText.isEmpty()
}

/**
 * Recover a replace-style edit from a tool call, when it is one.
 *
 * The harness draws these as a diff instead of as a JSON blob, and that is the
 * single biggest readability difference between a log dump and a chat client.
 * The shapes are not contractual, so every plausible key spelling is accepted
 * and a call that matches none simply yields null.
 *
 * @param arguments the raw argument JSON.
 * @return the edit, or null when this call is not a replace-style edit.
 */
fun editPreview(arguments: String?): EditPreview? {
    val raw = arguments?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val parsed = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    val old = EDIT_OLD_KEYS.firstNotNullOfOrNull { (parsed[it] as? JsonPrimitive)?.contentOrNull }
        ?: return null
    val new = EDIT_NEW_KEYS.firstNotNullOfOrNull { (parsed[it] as? JsonPrimitive)?.contentOrNull }
        ?: return null
    if (old == new) return null
    val path = listOf("file_path", "filePath", "path").firstNotNullOfOrNull { (parsed[it] as? JsonPrimitive)?.contentOrNull }
    return EditPreview(filePath = path, oldText = old, newText = new)
}

/**
 * Pretty-print an argument payload for the disclosure under a tool card.
 *
 * The log stores arguments as a compact JSON string. Re-indenting it is what
 * makes a multi-line command readable on a narrow screen; a payload that is not
 * JSON is passed through untouched so nothing is ever hidden by a failed parse.
 *
 * @param arguments the raw argument JSON.
 * @return indented JSON, or the original text when it does not parse.
 */
fun prettyArguments(arguments: String?): String? {
    val raw = arguments?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return raw
    return runCatching { PRETTY_JSON.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), element) }
        .getOrDefault(raw)
}

/** Indent width for the disclosure: two spaces is the harness's own convention. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val PRETTY_JSON = Json { prettyPrint = true; prettyPrintIndent = "  " }
