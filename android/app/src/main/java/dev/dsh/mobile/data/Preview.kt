package dev.dsh.mobile.data

import kotlinx.serialization.Serializable

/**
 * One link preview, as the host served it.
 *
 * The host — not this phone — fetched the page, under the harness's own web
 * policy, so everything here is already readable: `text` is the page's words,
 * not its markup, and a `text` kind means the source was plain text and is
 * reproduced verbatim.
 *
 * @property url the address the fetch ended on, after any redirects the host followed.
 * @property kind `html` when reduced from a document, `text` when passed through.
 * @property title the page's own title, when it declared one.
 * @property description the page's meta description, when it declared one.
 * @property text the readable content.
 * @property truncated whether either the host's fetch or the phone-size cap cut it.
 * @property statusCode the HTTP status the host saw.
 */
@Serializable
data class PreviewResponse(
    val url: String,
    val kind: String? = null,
    val title: String? = null,
    val description: String? = null,
    val text: String = "",
    val truncated: Boolean = false,
    val statusCode: Int? = null,
) {
    /** Whether the text was reduced from an HTML document. */
    val fromHtml: Boolean get() = kind == "html"
}
