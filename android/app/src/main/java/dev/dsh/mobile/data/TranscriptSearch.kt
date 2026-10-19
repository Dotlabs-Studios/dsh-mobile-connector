package dev.dsh.mobile.data

/**
 * The text one transcript row can be found by.
 *
 * A row is not one string: a tool call is a name, a JSON argument blob and an
 * output, a delivery is a file list, an approval carries the host's sentence. This
 * flattens each of them into the text a search should look at, including the parts
 * that are collapsed on screen — a reader searching "gradlew" expects to find the
 * command inside a grouped card, not only in a bubble.
 *
 * @param item the row to flatten.
 * @return everything in the row worth matching against.
 */
fun searchableText(item: TranscriptItem): String = when (item) {
    is TranscriptItem.Message -> blocksText(item.entry.content)
    is TranscriptItem.ToolStep -> listOf(item.name, item.arguments.orEmpty(), item.output).joinToString("\n")
    is TranscriptItem.ToolGroup -> item.steps.joinToString("\n") { searchableText(it) }
    is TranscriptItem.Notice -> blocksText(item.entry.content)
    is TranscriptItem.Deliverable -> buildString {
        append(blocksText(item.entry.content))
        for (file in item.files) {
            append('\n').append(file.path)
            file.description?.let { append(' ').append(it) }
        }
    }
    is TranscriptItem.Approval -> buildString {
        append(item.toolName)
        append('\n').append(blocksText(item.asked.content))
        item.asked.reason?.let { append(' ').append(it) }
    }
}

/**
 * The rows of a transcript that contain [query], in order.
 *
 * An empty query is the whole transcript: search is a filter over what is already
 * held, so it never asks the host for anything and cannot show a result the reader
 * could not have scrolled to.
 *
 * @param items the transcript as it was built.
 * @param query what the reader typed.
 * @return the matching rows.
 */
fun searchTranscript(items: List<TranscriptItem>, query: String): List<TranscriptItem> {
    val needle = query.trim()
    if (needle.isEmpty()) return items
    return items.filter { searchableText(it).contains(needle, ignoreCase = true) }
}

/** Join the text (and tool arguments) of one message's blocks. */
private fun blocksText(blocks: List<Block>): String =
    blocks.joinToString("\n") { block -> listOfNotNull(block.text, block.arguments, block.name).joinToString(" ") }
