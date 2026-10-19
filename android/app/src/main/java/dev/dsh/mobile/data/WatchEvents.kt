package dev.dsh.mobile.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * One thing the host told the watch service that a person might need to know.
 *
 * Decided here, without Android, because the mapping from a frame to "is this worth
 * a notification, and about what" is the part that can be wrong in a way nobody
 * notices until a phone stays silent through a night.
 */
sealed interface WatchEvent {
    /** A tool is waiting for one of the two answers. */
    data class Approval(
        val approvalId: String,
        val sessionId: String?,
        val toolName: String,
        val prompt: String,
    ) : WatchEvent

    /** The agent stopped to ask a question. */
    data class Question(
        val questionId: String,
        val sessionId: String?,
        val text: String,
    ) : WatchEvent

    /** A turn stopped running. */
    data class TurnEnded(val sessionId: String) : WatchEvent
}

/**
 * Reduce one SSE payload to the notification it deserves.
 *
 * Only three frames earn one: an approval is a turn blocked on a person, a question
 * is the same thing in words, and a `status` frame that says `running: false` is a
 * turn that finished while nobody was looking. Everything else — settled cards,
 * transcript entries, permission changes — is either the app's business or the PC's.
 *
 * @param payload the JSON of one `data:` frame.
 * @return the event, or null when the frame is not one of the three.
 */
fun watchEvent(payload: String): WatchEvent? {
    val frame = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
    return when (frame.string("event")) {
        "approval-request" -> {
            val approval = runCatching { frame["approval"]?.jsonObject }.getOrNull() ?: return null
            WatchEvent.Approval(
                approvalId = approval.string("id") ?: return null,
                sessionId = approval.string("sessionId"),
                toolName = approval.string("toolName") ?: "tool",
                prompt = approval.string("displayReason") ?: approval.string("reason") ?: "",
            )
        }

        "question-request" -> {
            val question = runCatching { frame["question"]?.jsonObject }.getOrNull() ?: return null
            val first = runCatching { question["questions"]?.jsonArray?.firstOrNull()?.jsonObject }.getOrNull()
            WatchEvent.Question(
                questionId = question.string("id") ?: return null,
                sessionId = question.string("sessionId"),
                text = first?.string("question") ?: "",
            )
        }

        "status" -> if (frame.boolean("running") == false) {
            val sessionId = frame.string("sessionId") ?: return null
            WatchEvent.TurnEnded(sessionId)
        } else {
            null
        }

        else -> null
    }
}

/** Read one string field, or null when it is absent or not a string. */
private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** Read one boolean field, or null when it is absent or not a boolean. */
private fun JsonObject.boolean(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString.not() }?.booleanOrNull
