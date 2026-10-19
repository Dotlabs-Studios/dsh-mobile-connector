package dev.dsh.mobile.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What an approval card can show about the call it is asking permission for.
 *
 * An approval used to arrive as a tool name and the host's sentence, which is
 * enough to know *that* something wants to run and not enough to decide. The
 * arguments are already in the transcript — the call is the thing being approved —
 * so the card can answer the question the buttons actually ask: which command, and
 * what will be written where.
 */
data class ApprovalPreview(
    /** The shell command about to run, verbatim. */
    val command: String? = null,
    /** The file the call names, when it names one. */
    val path: String? = null,
    /** The text the call is about to write, when it carries any. */
    val body: String? = null,
) {
    /** Whether there is anything worth drawing. */
    val isEmpty: Boolean get() = command == null && path == null && body == null
}

/** How much of one argument value an approval preview shows. */
private const val PREVIEW_VALUE_LIMIT = 2_000

/**
 * Extract the identifying parts of a tool call's arguments for the approval card.
 *
 * Reads only the keys the card can draw — a command, a path, the text about to be
 * written — and returns null for anything it cannot parse, because a card that
 * guesses is worse than a card that says less.
 *
 * @param arguments the raw argument JSON, exactly as the transcript recorded it.
 * @return the preview, or null when there is nothing to show.
 */
fun approvalPreview(arguments: String?): ApprovalPreview? {
    val raw = arguments?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val parsed = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    fun value(vararg keys: String): String? {
        for (key in keys) {
            val text = (parsed[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (text.isNotEmpty()) return text.take(PREVIEW_VALUE_LIMIT)
        }
        return null
    }
    val preview = ApprovalPreview(
        command = value("command"),
        path = value("file_path", "path", "filePath", "target_file"),
        body = value("content", "new_string", "new_str"),
    )
    return if (preview.isEmpty) null else preview
}
