package dev.dsh.mobile.data

import kotlinx.serialization.Serializable

/**
 * One file of the PC, as the host served it.
 *
 * This is the file half of what a tapped link can mean. A page arrives reduced to
 * its words by the host's fetcher; a file arrives as its own text, because that is
 * what a file is — and a source file, a note or a config is read, not summarised.
 *
 * @property path the absolute host path the reference resolved to, which is what
 *   makes a *relative* link legible: the reader sees which file was meant.
 * @property name the file's own name.
 * @property bytes its size on disk, whether or not all of it was served.
 * @property text the content, cut at the host's own bound.
 * @property truncated whether that bound cut it.
 * @property line the 1-based line a `#L35` fragment pointed at, when the link had
 *   one: the sheet reads the whole file, and this is what keeps half of the link
 *   from being dropped in silence.
 */
@Serializable
data class HostFile(
    val path: String = "",
    val name: String = "",
    val bytes: Long = 0,
    val text: String = "",
    val truncated: Boolean = false,
    val line: Int? = null,
) {

    /**
     * Whether this file should be read as markdown rather than shown as source.
     *
     * The sheet renders markdown for the transcript already, so a `.md` the agent
     * linked is displayed the way the same text would be inside a reply. Anything
     * else keeps its characters exactly as they are on disk: guessing that a
     * `.txt` is markdown would eat the `*` and `#` an author typed on purpose.
     */
    val isMarkdown: Boolean
        get() = isMarkdownName(name)
}
