package dev.dsh.mobile.data

/**
 * The quoted form of one message, ready to be typed over.
 *
 * Quoting is how a long conversation stays readable: the composer carries the line
 * being answered, and the agent receives it as ordinary markdown. The text is cut at
 * [maxChars] because a quote of a whole tool result is not a quote, it is a copy —
 * and the cut is marked rather than silent.
 *
 * @param text the message the reader is answering.
 * @param maxChars how much of it to carry into the composer.
 * @return the markdown block, or an empty string for an empty message.
 */
fun quotedMessage(text: String, maxChars: Int = 240): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    val cut = trimmed.take(maxChars)
    val quoted = cut.lineSequence().joinToString("\n") { line -> if (line.isBlank()) ">" else "> $line" }
    val ellipsis = if (trimmed.length > maxChars) "…" else ""
    return "$quoted$ellipsis\n\n"
}
