package dev.dsh.mobile.data

/**
 * One block of a parsed message body.
 *
 * The harness renders agent replies as markdown, and replies are written in it:
 * headings that separate sections, lists for steps, tables for comparisons,
 * fenced code for anything runnable. Showing the source instead means the reader
 * sees `**Attenzione**` where the author wrote emphasis and a row of `|` where
 * the author drew a table — which is what makes a well-written answer look like
 * a dump of its own markup.
 *
 * This is the subset that appears in practice, not the whole specification:
 * every construct here is one an agent actually emits, and anything else falls
 * through to a paragraph, which is the honest way to be incomplete.
 */
sealed interface MdBlock {
    /**
     * An ATX heading.
     *
     * @property level 1 to 6, as written.
     * @property text the heading's inline content.
     */
    data class Heading(val level: Int, val text: String) : MdBlock

    /** A run of prose, with the author's soft line breaks joined. */
    data class Paragraph(val text: String) : MdBlock

    /**
     * A fenced code block.
     *
     * @property language the info string's first token, shown in the banner.
     * @property title an explicit `title="…"` from the info string, which wins.
     * @property code the block's body, without the fence lines.
     */
    data class CodeBlock(val language: String?, val title: String?, val code: String) : MdBlock

    /**
     * A list, bullet or ordered.
     *
     * @property items the entries, in order, each carrying its own depth.
     */
    data class Bullets(val items: List<Bullet>) : MdBlock

    /**
     * One list entry.
     *
     * @property depth the nesting level, from the line's indentation.
     * @property number the item's number for an ordered list, or null for a bullet.
     * @property text the entry's inline content.
     */
    data class Bullet(val depth: Int, val number: Int?, val text: String)

    /** A block quote. */
    data class Quote(val text: String) : MdBlock

    /** A thematic break. */
    data object Rule : MdBlock

    /**
     * A table.
     *
     * @property header the header cells.
     * @property rows the body rows, each padded to the header's width.
     */
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
}

/**
 * One run of inline content.
 *
 * @see parseInline
 */
sealed interface MdInline {
    /** Literal text. */
    data class Text(val text: String) : MdInline

    /** A `` `code span` ``. */
    data class Code(val text: String) : MdInline

    /** `**strong**`. */
    data class Strong(val children: List<MdInline>) : MdInline

    /** `*emphasis*`. */
    data class Emphasis(val children: List<MdInline>) : MdInline

    /** `~~struck~~`. */
    data class Strike(val children: List<MdInline>) : MdInline

    /** `[label](target)`. */
    data class Link(val label: String, val target: String) : MdInline
}

/** The deepest list nesting this parser tracks; past it, entries stay at the last level. */
private const val MAX_LIST_DEPTH = 3

/**
 * Parse a message body into blocks.
 *
 * An unclosed fence becomes code to the end of the message rather than prose.
 * That is not an edge case to tolerate: a reply arrives while it is still being
 * written, so a half-delivered fence is the normal state of a streaming message,
 * and rendering its body as prose would make the text jump on every poll.
 *
 * @param text the message body.
 * @return the blocks, in order, with blank runs dropped.
 */
fun parseMarkdown(text: String): List<MdBlock> {
    if (text.isBlank()) return emptyList()
    val lines = text.split('\n')
    val blocks = mutableListOf<MdBlock>()
    val paragraph = mutableListOf<String>()
    val listItems = mutableListOf<MdBlock.Bullet>()
    val quote = mutableListOf<String>()
    var index = 0

    /** Close whichever block the next line does not continue. */
    fun flush() {
        if (paragraph.isNotEmpty()) {
            // Consecutive lines of one paragraph are separated by a space, which
            // is what markdown means by them; a blank line is what ends one.
            blocks += MdBlock.Paragraph(paragraph.joinToString(" ").trim())
            paragraph.clear()
        }
        if (listItems.isNotEmpty()) {
            blocks += MdBlock.Bullets(listItems.toList())
            listItems.clear()
        }
        if (quote.isNotEmpty()) {
            blocks += MdBlock.Quote(quote.joinToString("\n"))
            quote.clear()
        }
    }

    while (index < lines.size) {
        val line = lines[index]
        val marker = fenceMarker(line)

        if (marker != null) {
            flush()
            val info = line.trim().removePrefix(marker).trim()
            val body = StringBuilder()
            index += 1
            while (index < lines.size) {
                val closing = fenceMarker(lines[index])
                if (closing != null && closing.length >= marker.length) break
                body.append(lines[index]).append('\n')
                index += 1
            }
            index += 1
            val code = body.toString().trimEnd('\n')
            if (code.isNotBlank()) {
                blocks += MdBlock.CodeBlock(
                    language = info.split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() },
                    title = fenceTitle(info),
                    code = code,
                )
            }
            continue
        }

        if (line.isBlank()) {
            flush()
            index += 1
            continue
        }

        val heading = headingOf(line)
        if (heading != null) {
            flush()
            blocks += MdBlock.Heading(heading.first, heading.second)
            index += 1
            continue
        }

        if (isRule(line)) {
            flush()
            blocks += MdBlock.Rule
            index += 1
            continue
        }

        val table = tableAt(lines, index)
        if (table != null) {
            flush()
            blocks += table.first
            index += table.second
            continue
        }

        val bullet = bulletOf(line, insideList = listItems.isNotEmpty())
        if (bullet != null) {
            // A list interrupts a paragraph, which is how a reply usually starts
            // one: "Ecco cosa ho fatto:" followed by the steps.
            if (paragraph.isNotEmpty() || quote.isNotEmpty()) flush()
            val depth = bullet.first.coerceIn(0, MAX_LIST_DEPTH)
            listItems += MdBlock.Bullet(depth = depth, number = bullet.second, text = bullet.third)
            index += 1
            continue
        }

        if (line.trimStart().startsWith(">")) {
            // A second `>` line continues the quote rather than starting a new
            // one; only a line that is not a quote ends it.
            if (paragraph.isNotEmpty() || listItems.isNotEmpty()) flush()
            quote += line.trimStart().removePrefix(">").removePrefix(" ").trimEnd()
            index += 1
            continue
        }

        if (listItems.isNotEmpty() || quote.isNotEmpty()) flush()
        paragraph += line.trim()
        index += 1
    }
    flush()
    return blocks
}

/**
 * Read an ATX heading.
 *
 * @param line one line.
 * @return the level and the title, or null when the line is not a heading.
 */
private fun headingOf(line: String): Pair<Int, String>? {
    val trimmed = line.trimStart()
    val hashes = trimmed.takeWhile { it == '#' }.length
    if (hashes !in 1..6) return null
    val rest = trimmed.drop(hashes)
    // `#Heading` is not a heading; markdown requires the space after the hashes.
    if (rest.isNotEmpty() && !rest.startsWith(" ")) return null
    // A closing run of hashes is decoration, not content.
    return hashes to rest.trim().trimEnd('#').trim()
}

/**
 * Whether a line is a thematic break.
 *
 * @param line one line.
 * @return true for `---`, `***`, `___`, and their spaced forms.
 */
private fun isRule(line: String): Boolean {
    val stripped = line.trim().replace(" ", "")
    if (stripped.length < 3) return false
    val first = stripped.first()
    if (first != '-' && first != '*' && first != '_') return false
    return stripped.all { it == first }
}

/**
 * Read a list entry.
 *
 * The four-space rule is markdown's indented code block, and it applies to a
 * line that would start a block of its own — not to a line inside a list, where
 * four spaces is simply two levels of nesting. Applying it there would turn a
 * nested bullet into a paragraph, which is what a nested list is most likely to
 * look like in an agent's answer.
 *
 * @param line one line.
 * @param insideList whether a list is already open at this point.
 * @return its depth, its number for an ordered list (null for a bullet), and its
 *   text; or null when the line is not an entry.
 */
private fun bulletOf(line: String, insideList: Boolean): Triple<Int, Int?, String>? {
    val indent = line.length - line.trimStart().length
    val maxIndent = if (insideList) MAX_LIST_DEPTH * 2 + 1 else 3
    if (indent > maxIndent) return null
    val body = line.trimStart()
    val marker = body.takeWhile { it == '-' || it == '*' || it == '+' }
    if (marker.length == 1 && body.length > 1 && body[1].isWhitespace()) {
        return Triple(indent / 2, null, body.drop(1).trim())
    }
    val digits = body.takeWhile { it.isDigit() }
    if (digits.isNotEmpty() && digits.length <= 9) {
        val after = body.drop(digits.length)
        if (after.length > 1 && (after[0] == '.' || after[0] == ')') && after[1].isWhitespace()) {
            return Triple(indent / 2, digits.toIntOrNull(), after.drop(1).trim())
        }
    }
    return null
}

/**
 * Read a table when one starts here.
 *
 * A table is recognised by its delimiter row, not by a pipe alone: a paragraph
 * containing a pipe is a paragraph, and treating it as a table would mangle
 * ordinary prose — which is exactly what a file path or a shell pipeline is.
 *
 * @param lines every line of the message.
 * @param start the index of the candidate header row.
 * @return the table and how many lines it consumed, or null.
 */
private fun tableAt(lines: List<String>, start: Int): Pair<MdBlock.Table, Int>? {
    if (start + 1 >= lines.size) return null
    val header = lines[start]
    if (!header.contains('|')) return null
    if (!isTableDelimiter(lines[start + 1])) return null
    val headerCells = splitRow(header)
    if (headerCells.isEmpty()) return null
    val rows = mutableListOf<List<String>>()
    var index = start + 2
    while (index < lines.size) {
        val row = lines[index]
        if (row.isBlank() || !row.contains('|')) break
        val cells = splitRow(row)
        // Pad a short row so every column exists; the renderer then never has to
        // know that an author left a trailing cell empty.
        rows += List(headerCells.size) { column -> cells.getOrElse(column) { "" } }
        index += 1
    }
    return MdBlock.Table(headerCells, rows) to (index - start)
}

/**
 * Whether a line is a table's delimiter row.
 *
 * @param line one line.
 * @return true for `|---|:--:|`.
 */
private fun isTableDelimiter(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || '-' !in trimmed) return false
    if (trimmed.any { it != '-' && it != '|' && it != ':' && it != ' ' && it != '\t' }) return false
    return trimmed.count { it == '-' } >= 3
}

/**
 * Split one table row into its cells.
 *
 * @param line one row.
 * @return the cells, with the outer pipes' empty edges dropped.
 */
private fun splitRow(line: String): List<String> {
    val body = line.trim().removePrefix("|").removeSuffix("|")
    return body.split('|').map { it.trim() }
}

/**
 * The backtick run that opens a fence on this line, if it is one.
 *
 * @param line one line of the message body.
 * @return the run of backticks, or null when the line is not a fence.
 */
private fun fenceMarker(line: String): String? {
    val trimmed = line.trimStart()
    // Four spaces of indent is an indented code block, not a fence.
    if (line.length - trimmed.length >= 4) return null
    val run = trimmed.takeWhile { it == '`' }
    return run.takeIf { it.length >= 3 }
}

/**
 * Read an explicit `title="…"` out of a fence's info string.
 *
 * @param info the info string, or null.
 * @return the title, or null when there is none.
 */
private fun fenceTitle(info: String?): String? {
    if (info == null) return null
    val match = Regex("""title\s*=\s*"([^"]*)"""").find(info) ?: return null
    return match.groupValues[1].takeIf { it.isNotBlank() }
}

/**
 * Parse one run of inline text.
 *
 * Handled: code spans, strong, emphasis, strikethrough, links, and images (which
 * become their alt text, because a path a phone cannot fetch is not an image).
 * Anything else is literal, including a marker with no partner — a lone `*` is
 * far more often a bullet's sibling or a footnote than a broken emphasis.
 *
 * @param text one block's inline content.
 * @return the runs, in order.
 */
fun parseInline(text: String): List<MdInline> {
    if (text.isEmpty()) return emptyList()
    val out = mutableListOf<MdInline>()
    val plain = StringBuilder()
    var index = 0

    /** Flush the pending literal text into its own run. */
    fun flushPlain() {
        if (plain.isNotEmpty()) {
            out += MdInline.Text(plain.toString())
            plain.setLength(0)
        }
    }

    while (index < text.length) {
        val rest = text.substring(index)
        var consumed = 0

        // Code spans come first: backticks win over every other marker, which is
        // exactly what an author writes them for — `**not bold**`.
        if (text[index] == '`') {
            val run = rest.takeWhile { it == '`' }.length
            val closeAt = text.indexOf("`".repeat(run), index + run)
            if (closeAt > index) {
                flushPlain()
                out += MdInline.Code(text.substring(index + run, closeAt).trim())
                consumed = closeAt + run - index
            }
        }

        // An image is reduced to its alt text before the link rule sees it.
        if (consumed == 0 && rest.startsWith("![")) {
            val match = readLink(rest, offset = 1)
            if (match != null) {
                flushPlain()
                if (match.label.isNotBlank()) out += MdInline.Text(match.label)
                consumed = match.length
            }
        }

        if (consumed == 0 && text[index] == '[') {
            val match = readLink(rest, offset = 0)
            if (match != null) {
                flushPlain()
                out += MdInline.Link(match.label, match.target)
                consumed = match.length
            }
        }

        // A bare URL is a link whose label is itself. The harness linkifies
        // these in its own renderer, and a model pastes an address far more
        // often than it writes markdown around one — without this rule the most
        // common link in a reply would be the one that cannot be opened.
        if (consumed == 0) {
            val match = readAutolink(text, index)
            if (match != null) {
                flushPlain()
                out += MdInline.Link(match.target, match.target)
                consumed = match.length
            }
        }

        if (consumed == 0) {
            for (marker in DOUBLE_MARKERS) {
                // The marker has to be *here*. Without this guard the search for
                // a closing marker starts two characters past whatever character
                // happens to be at this position, finds the next `**` anywhere in
                // the text, and swallows everything up to it — which drops the
                // two characters it started on and bolds text the author wrote
                // plainly. It is the difference between "the marker is at this
                // position" and "a marker exists somewhere later".
                if (!rest.startsWith(marker.open)) continue
                val inner = readWrapped(text, index, marker.open) ?: continue
                flushPlain()
                out += marker.build(parseInline(inner.text))
                consumed = inner.end - index
                break
            }
        }

        if (consumed == 0) {
            val character = text[index]
            if (character == '*' || (character == '_' && !isWordBefore(text, index))) {
                val inner = readWrapped(text, index, character.toString())
                if (inner != null && inner.text.isNotBlank()) {
                    flushPlain()
                    out += MdInline.Emphasis(parseInline(inner.text))
                    consumed = inner.end - index
                }
            }
        }

        if (consumed == 0) {
            plain.append(text[index])
            consumed = 1
        }
        index += consumed
    }
    flushPlain()
    return out
}

/** One doubled marker, with the run it builds from its children. */
private class DoubleMarker(
    val open: String,
    val build: (List<MdInline>) -> MdInline,
)

/** The doubled markers, longest first so `**` is tried before `*`. */
private val DOUBLE_MARKERS = listOf(
    DoubleMarker("**") { MdInline.Strong(it) },
    DoubleMarker("__") { MdInline.Strong(it) },
    DoubleMarker("~~") { MdInline.Strike(it) },
)

/** A matched pair of markers. */
private data class Wrapped(val text: String, val end: Int)

/** A matched link. */
private data class LinkMatch(val label: String, val target: String, val length: Int)

/**
 * Whether the character before [index] is part of a word.
 *
 * @param text the whole inline text.
 * @param index the candidate marker's position.
 * @return true when a `_` here belongs to an identifier rather than to emphasis.
 */
private fun isWordBefore(text: String, index: Int): Boolean {
    val previous = text.getOrNull(index - 1) ?: return false
    return previous.isLetterOrDigit()
}

/**
 * Read a marker pair starting at [start].
 *
 * The flanking rules are CommonMark's, in their simplest form: a marker followed
 * by whitespace cannot open emphasis, and one preceded by whitespace cannot close
 * it. Without them `2 * 3 = 6` becomes italic and loses its asterisks — which is
 * ordinary arithmetic in an agent's answer, not a formatting mistake.
 *
 * @param text the whole inline text.
 * @param start where the opening marker begins.
 * @param marker the marker, used to close as well as to open.
 * @return the inner text and the index just past the closing marker, or null.
 */
private fun readWrapped(text: String, start: Int, marker: String): Wrapped? {
    val from = start + marker.length
    if (from >= text.length) return null
    if (text[from].isWhitespace()) return null
    val at = text.indexOf(marker, from)
    if (at < 0) return null
    if (text[at - 1].isWhitespace()) return null
    val inner = text.substring(from, at)
    // `****`, and a pair with nothing inside, are not emphasis.
    if (inner.isEmpty()) return null
    return Wrapped(inner, at + marker.length)
}

/**
 * Read a `[label](target)` starting at [offset] within [rest].
 *
 * @param rest the text from the candidate onward.
 * @param offset 0 for a link, 1 to step over the `!` of an image.
 * @return the match and its total length, or null.
 */
private fun readLink(rest: String, offset: Int): LinkMatch? {
    if (rest.length <= offset || rest[offset] != '[') return null
    val close = rest.indexOf(']', offset + 1)
    if (close < 0) return null
    if (close + 1 >= rest.length || rest[close + 1] != '(') return null
    val end = rest.indexOf(')', close + 2)
    if (end < 0) return null
    return LinkMatch(
        label = rest.substring(offset + 1, close),
        target = rest.substring(close + 2, end).trim(),
        length = end + 1,
    )
}

/** Characters a bare URL may end with; anything else would be sentence punctuation. */
private const val AUTOLINK_BODY = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?#[]@!\$&'()*+,;=%"

/**
 * Read one bare `http(s)://…` address starting exactly at [index].
 *
 * The address runs over every character a URL may legally hold — which is
 * nearly all of them, including `)` — and then [trimLinkPunctuation] gives back
 * the ones that belong to the sentence around it. Without that second step a
 * parenthetical `(see https://x.it)` would own its closing bracket.
 *
 * @param text the whole text being parsed.
 * @param index the candidate's first character.
 * @return the match and its length, or null when nothing starts here.
 */
private fun readAutolink(text: String, index: Int): LinkMatch? {
    for (scheme in SCHEMES) {
        if (!text.startsWith(scheme, index)) continue
        var end = index + scheme.length
        while (end < text.length && text[end] in AUTOLINK_BODY) end += 1
        val target = trimLinkPunctuation(text.substring(index, end), scheme.length)
        if (target.length <= scheme.length) continue
        // A host is the one part that cannot be empty; without one this is prose
        // that happens to start with a scheme.
        val host = target.drop(scheme.length).substringBefore('/').substringBefore('?').substringBefore('#')
        if (host.isBlank() || !host.contains('.')) continue
        return LinkMatch(label = target, target = target, length = target.length)
    }
    return null
}

/**
 * Give back the trailing characters the surrounding prose owns.
 *
 * Terminal punctuation is prose; a closing bracket is the URL's only while more
 * of its kind are open inside the address than closed before it — the rule that
 * lets `wiki/Python_(linguaggio)` keep its brackets while `(see https://x.it)`
 * loses them.
 *
 * @param candidate the scanned address.
 * @param floor the scheme's length, which nothing may strip below.
 * @returns the address without the prose's punctuation.
 */
private fun trimLinkPunctuation(candidate: String, floor: Int): String {
    var target = candidate
    while (target.length > floor) {
        val last = target.last()
        if (last in ".,;:!?") {
            target = target.dropLast(1)
            continue
        }
        if (last == ')' && target.count { it == ')' } > target.count { it == '(' }) {
            target = target.dropLast(1)
            continue
        }
        break
    }
    return target
}

/** The schemes a bare address may start with. */
private val SCHEMES = listOf("http://", "https://")
