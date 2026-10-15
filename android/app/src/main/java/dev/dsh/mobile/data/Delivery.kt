package dev.dsh.mobile.data

import kotlinx.serialization.Serializable

/**
 * One delivered file as the host described it.
 *
 * This is the second half of a delivery: the `deliverable` entry in the
 * transcript says *that* an agent handed a file over and where it sits on the
 * PC, and this says what the host found when it went to look — the absolute path
 * the declaration resolved to, the size on disk, and whether the content is text
 * a reader can be shown or bytes that have to leave the phone's own storage.
 *
 * [binary] is the host's answer, not a guess from the extension: the same
 * NUL-byte test the host uses for a linked file decides it, so a `.txt` that is
 * really a JPEG is reported as the bytes it is.
 *
 * @property path the absolute host path the declaration resolved to, which is
 *   what makes a *relative* declaration legible.
 * @property name the file's own name on the PC.
 * @property bytes its size on disk, whether or not all of it was served.
 * @property text the content, when it is text, cut at the host's own bound.
 * @property truncated whether that bound cut it.
 * @property binary whether the content is not text at all.
 * @property description the sentence the agent wrote beside the file, if any.
 */
@Serializable
data class DeliveredFile(
    val path: String = "",
    val name: String = "",
    val bytes: Long = 0,
    val text: String = "",
    val truncated: Boolean = false,
    val binary: Boolean = false,
    val description: String? = null,
) {
    /** The name a sheet titles itself with, falling back to the resolved path. */
    val displayName: String get() = name.takeIf { it.isNotBlank() } ?: lastSegment(path) ?: "consegna"

    /**
     * Whether the sheet can show this here rather than handing it to another app.
     *
     * Text is what this app can render — markdown and source alike — and a binary
     * file is exactly the case the phone has no reader for: a PDF or a slide deck
     * belongs to whichever viewer the user has, which is also why the download and
     * share actions exist beside it.
     */
    val readable: Boolean get() = !binary

    /** Whether the text is markdown, which the sheet renders instead of showing. */
    val isMarkdown: Boolean get() = isMarkdownName(name)
}

/** Extensions that are markdown, including the ones editors write by habit. */
private val MARKDOWN_SUFFIXES = setOf("md", "markdown", "mdown", "mkd", "mkdn", "mdx")

/**
 * Whether a file name is one the app renders as markdown.
 *
 * @param name the file's own name.
 * @return true for a markdown suffix.
 */
internal fun isMarkdownName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in MARKDOWN_SUFFIXES

/**
 * Media types for the extensions a delivery actually wears.
 *
 * Android needs a type to choose the app that opens a file, and a wrong one is
 * worse than a generic one: `text/plain` on a spreadsheet offers the text editor,
 * which then shows binary noise. So the table names the formats a harness
 * produces a document in and nothing else — anything outside it gets the honest
 * `application/octet-stream`, and the *extension* still travels in the file's
 * name, which is what most viewers actually look at.
 */
private val DELIVERY_MEDIA_TYPES = mapOf(
    "pdf" to "application/pdf",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "doc" to "application/msword",
    "xls" to "application/vnd.ms-excel",
    "ppt" to "application/vnd.ms-powerpoint",
    "odt" to "application/vnd.oasis.opendocument.text",
    "ods" to "application/vnd.oasis.opendocument.spreadsheet",
    "odp" to "application/vnd.oasis.opendocument.presentation",
    "txt" to "text/plain",
    "md" to "text/markdown",
    "csv" to "text/csv",
    "json" to "application/json",
    "xml" to "application/xml",
    "html" to "text/html",
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "webp" to "image/webp",
    "gif" to "image/gif",
    "svg" to "image/svg+xml",
    "zip" to "application/zip",
    "mp3" to "audio/mpeg",
    "mp4" to "video/mp4",
)

/**
 * The media type to open one delivered file with.
 *
 * @param name the file's name as the host reported it.
 * @returns a media type, generic when the extension is one this build does not
 *   name.
 */
fun deliveryMimeType(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    return DELIVERY_MEDIA_TYPES[extension] ?: "application/octet-stream"
}

/** Characters a phone filesystem or the media store will not take in a name. */
private val ILLEGAL_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

/** Longest name kept, leaving room under the 255-byte limit of most filesystems. */
private const val NAME_LIMIT = 120

/**
 * The name a delivered file is saved under on the phone.
 *
 * A PC file name is not a phone file name: it can carry characters Android's
 * media store rejects, it can be a path when the host reported one, and it can be
 * long enough to be refused outright. The name is therefore reduced to its last
 * segment and repaired rather than trusted — but only repaired, never invented:
 * a reader who downloads `Bilancio 2026.xlsx` must find exactly that in the
 * Download folder, and the extension survives every repair, because it is what
 * decides which app the file opens with.
 *
 * @param path the file's name, or the host path it came from.
 * @returns a name that is safe to write and still recognisable.
 */
fun deliverySaveName(path: String): String {
    val leaf = lastSegment(path) ?: path
    val cleaned = leaf.replace(ILLEGAL_NAME_CHARS, "_").trim().trim('.')
    // Nothing to recognise is not a name: a declaration that resolved to a bare
    // separator, or to a row of underscores, would land in Download as a file the
    // user cannot pick out of the list.
    if (cleaned.none { it.isLetterOrDigit() }) return "consegna"
    if (cleaned.length <= NAME_LIMIT) return cleaned
    val extension = cleaned.substringAfterLast('.', "")
    val stem = cleaned.substringBeforeLast('.', cleaned)
    if (extension.isEmpty() || extension.length >= NAME_LIMIT) return cleaned.take(NAME_LIMIT)
    val keep = NAME_LIMIT - extension.length - 1
    return "${stem.take(keep)}.$extension"
}
