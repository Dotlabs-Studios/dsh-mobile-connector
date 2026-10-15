package dev.dsh.mobile.data

/**
 * What a tapped link in a message actually points at.
 *
 * A markdown link is not a promise about the destination. An agent that has just
 * written a file links it the way it would link a page — `[LORE-bozza.md](LORE-bozza.md)`
 * — and the same tap that means "read this page" for `https://…` means "read this
 * file" for a path. Sending both to one reader is what produced
 * *not a valid URL: LORE-bozza.md*: the reference arrived at the host's web
 * fetcher, which was right to refuse it, because it was never a URL.
 */
sealed interface LinkTarget {

    /** A page: read by the host's web fetcher, under the harness's own policy. */
    data class Web(val url: String) : LinkTarget

    /**
     * A file on the host, exactly as the agent wrote it.
     *
     * The reference is forwarded unchanged rather than resolved here: only the
     * host knows what a separator, a drive letter or a `file:` URL mean on its
     * platform, and a relative name belongs to the chat that printed it.
     */
    data class File(val reference: String) : LinkTarget
}

/** A URI scheme, as RFC 3986 spells one: a letter, then letters, digits, `+`, `.` or `-`. */
private val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:")

/** The four characters that make a Windows drive-relative reference, e.g. `K:nota.md`. */
private const val DRIVE_PREFIX_LENGTH = 2

/**
 * Decide how a tapped link is read: as a page, or as a file on the host.
 *
 * The rule is about what the reference *is*, not about what it looks like it might
 * be, and the cases fall out of one question — does this start with a scheme?
 *
 * - no scheme: a bare name or a relative path (`LORE-bozza.md`, `docs/x.md`) is a
 *   file, and it is the case this whole classification exists for;
 * - `http`/`https`: a page, as before;
 * - `file:`, and a single letter before the colon (`K:\x.md`, which is a drive and
 *   not a scheme named "K"): a file. A `file:` URL is unwrapped by the *host*,
 *   which is the side that knows what one means there;
 * - anything else (`mailto:`, `tel:`, `data:`): handed to the web preview, whose
 *   host answers in its own words and whose sheet keeps *Apri nel browser* as the
 *   way out. Reading `mailto:` as a file name would be a worse lie than refusing it.
 *
 * @param raw the link target as written by the author of the message.
 * @return the target, or null when there is nothing to open.
 */
fun classifyLink(raw: String): LinkTarget? {
    val target = raw.trim()
    if (target.isEmpty()) return null
    val scheme = URI_SCHEME.find(target)?.value?.lowercase()
    return when {
        scheme == null -> LinkTarget.File(target)
        scheme == "http:" || scheme == "https:" -> LinkTarget.Web(target)
        scheme == "file:" -> LinkTarget.File(target)
        scheme.length == DRIVE_PREFIX_LENGTH -> LinkTarget.File(target)
        else -> LinkTarget.Web(target)
    }
}
