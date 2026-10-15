package dev.dsh.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * One choice an agent offered.
 *
 * @property label the text the reader picks, and the value the harness is
 *   answered with — the answer is keyed by label, not by position.
 * @property description what the choice means, when the author wrote one.
 */
@Serializable
data class QuestionOption(
    val label: String = "",
    val description: String? = null,
) {
    /**
     * Whether the author marked this option as the recommended one.
     *
     * The harness draws a badge when a label carries the marker rather than
     * putting it in a field of its own, so the marker has to be read back out of
     * the text — and removed, because a badge and a parenthetical say the same
     * thing twice.
     */
    val recommended: Boolean
        get() = RECOMMENDED_MARKERS.any { label.contains(it, ignoreCase = true) }

    /** The label without its recommendation marker. */
    val cleanLabel: String
        get() {
            var text = label
            RECOMMENDED_MARKERS.forEach { marker -> text = text.replace(marker, "", ignoreCase = true) }
            return text.trim().trimEnd('—', '-', '·', ':', ',').trim()
        }
}

/**
 * How an author writes "this is the one I suggest", in the two languages seen.
 *
 * These are the only literals in this file that look like copy and are not: they
 * are matched against a label the *model* wrote, in whatever language it chose to
 * write in, so they belong to the input rather than to the app's own text. The
 * badge the reader sees is the UI's, and that one is a resource.
 */
private val RECOMMENDED_MARKERS = listOf("(Consigliato)", "(Consigliata)", "(Recommended)")

/**
 * One question an agent asked, and the choices it offered.
 *
 * The same shape arrives from two places: the harness's live request, and the
 * `ask_user_question` tool call recorded in the session log. Reading it out of
 * the log is what lets the phone draw the card from history — a question asked
 * yesterday should still read as a question, with the answer that was given.
 */
@Serializable
data class QuestionItem(
    val id: String = "",
    val header: String? = null,
    val question: String = "",
    val detail: String? = null,
    @SerialName("multiSelect") val multiSelect: Boolean = false,
    val options: List<QuestionOption> = emptyList(),
) {
    /** What to show above the question: its header, falling back to nothing. */
    val title: String? get() = header?.takeIf { it.isNotBlank() }
}

/**
 * One question set the host is waiting on.
 *
 * @property expired whether the harness's own two-minute window has closed. The
 *   question is still answerable — the agent was told to carry on — but the card
 *   has to say so, because the answer now arrives as a follow-up.
 */
@Serializable
data class PendingQuestion(
    val id: String,
    @SerialName("sessionId") val sessionId: String = "",
    @SerialName("callId") val callId: String? = null,
    @SerialName("createdAt") val createdAt: Long = 0L,
    val expired: Boolean = false,
    val questions: List<QuestionItem> = emptyList(),
)

/** The pending-question list response. */
@Serializable
data class QuestionListResponse(val items: List<PendingQuestion> = emptyList())

/** One answer to one question. */
@Serializable
data class QuestionAnswer(
    val id: String,
    val selected: List<String> = emptyList(),
    val custom: String? = null,
) {
    /** Whether anything was actually chosen. */
    val isEmpty: Boolean get() = selected.isEmpty() && custom.isNullOrBlank()
}

/** The receipt for answering one question set. */
@Serializable
data class QuestionAnswerResponse(
    val resolved: Boolean = false,
    @SerialName("questionId") val questionId: String = "",
    /** True when the answer went through the harness's late channel. */
    val late: Boolean = false,
)

/** Reusable tolerant parser for both the wire and a tool call's arguments. */
private val QUESTIONS_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Read the questions recorded in an `ask_user_question` tool call.
 *
 * The arguments are model-written JSON, so every field is optional and a shape
 * that does not match produces no card rather than an exception: a malformed
 * question must not take the transcript down with it.
 *
 * @param arguments the tool call's raw argument JSON.
 * @return the questions, or an empty list when there are none to show.
 */
fun parseQuestions(arguments: String?): List<QuestionItem> {
    val raw = arguments?.trim().orEmpty()
    if (raw.isEmpty()) return emptyList()
    val root = runCatching { QUESTIONS_JSON.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyList()
    val items = root["questions"] as? JsonArray ?: return emptyList()
    return items.mapNotNull { element -> (element as? JsonObject)?.let(::questionFrom) }
}

/**
 * Build one question from its JSON object.
 *
 * @param source the question's JSON object.
 * @return the question, or null when it has no text to ask.
 */
private fun questionFrom(source: JsonObject): QuestionItem? {
    val text = (source["question"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    if (text.isEmpty()) return null
    val options = (source["options"] as? JsonArray).orEmpty().mapNotNull { element ->
        val option = element as? JsonObject ?: return@mapNotNull null
        val label = (option["label"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (label.isEmpty()) return@mapNotNull null
        QuestionOption(
            label = label,
            description = (option["description"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
        )
    }
    return QuestionItem(
        id = (source["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "q0",
        header = (source["header"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
        question = text,
        detail = (source["detail"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
        // The tool schema writes `multi_select`; the harness's own type writes
        // `multiSelect`. Both are in the wild, so both are read.
        multiSelect = (source["multi_select"] as? JsonPrimitive)?.booleanOrNull == true ||
            (source["multiSelect"] as? JsonPrimitive)?.booleanOrNull == true,
        options = options,
    )
}

/**
 * Read the answers recorded in an `ask_user_question` tool result.
 *
 * This is how a question asked in the past shows what was chosen: the tool's
 * result carries the answers, and nothing else in the log does.
 *
 * @param result the tool result's text.
 * @return the chosen labels per question id.
 */
fun parseQuestionAnswers(result: String?): Map<String, List<String>> {
    val raw = result?.trim().orEmpty()
    if (raw.isEmpty()) return emptyMap()
    val root = runCatching { QUESTIONS_JSON.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyMap()
    val items = root["answers"] as? JsonArray ?: return emptyMap()
    val out = mutableMapOf<String, List<String>>()
    for (element in items) {
        val answer = element as? JsonObject ?: continue
        val id = (answer["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
        val selected = (answer["selected"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { label -> label.isNotBlank() } }
        // A free-text answer is a chosen answer too, and dropping it would make
        // an answered question look unanswered.
        val custom = (answer["custom"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        out[id] = if (custom == null) selected else selected + custom
    }
    return out
}
