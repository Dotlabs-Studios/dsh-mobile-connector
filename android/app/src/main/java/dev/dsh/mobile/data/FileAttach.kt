package dev.dsh.mobile.data

import dev.dsh.mobile.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The most one attached file may weigh.
 *
 * The harness stores a generic file verbatim — no type whitelist, no size limit
 * of its own — so this bound belongs to the phone, and it is there because the
 * bytes are read into memory to be sent. A deck, a dataset or a build log fits
 * comfortably; a video does not belong in a chat prompt.
 */
const val MAX_ATTACHMENT_BYTES: Long = 32L * 1024 * 1024

/** Files one message may carry, matching the photo cap. */
const val MAX_STAGED_FILES = 8

/**
 * One file staged for the next message.
 *
 * [receiptId] is what the prompt names. The host minted it when it stored the
 * bytes, it is bound to one chat, and it is spent by the message that carries
 * it — which is why a staged attachment is not a path the phone could rebuild.
 * [name] and [bytes] are the store's own answer, never the phone's guess: the
 * store reduces a client's path to a leaf name and the chip shows that.
 */
data class OutgoingFile(val receiptId: String, val name: String, val bytes: Long)

/** The host's answer to staging one file. */
@Serializable
data class UploadedFile(
    /** The single-use handle the message names. */
    val receiptId: String = "",
    /** The durable stored reference, reported for the transcript's sake. */
    @SerialName("attachmentId") val attachmentId: String = "",
    val name: String = "",
    val bytes: Long = 0,
)

/**
 * Why a picked file cannot be attached, or null when it can.
 *
 * The reason is *chosen* here and spelled in the UI, so it travels as a
 * [TextRef]: this layer knows that the file passed the bound, not how a sentence
 * about the bound is written.
 *
 * @param bytes the size the picker reported, or the bytes actually read.
 * @returns the refusal, or null when the file fits.
 */
fun attachmentRefusal(bytes: Long): TextRef? = if (bytes > MAX_ATTACHMENT_BYTES) {
    TextRef.of(R.string.file_attach_too_large)
} else {
    null
}

/**
 * The name an attachment wears, with the harness's own fallback.
 *
 * A name and not a sentence: the fallback is what an unnamed attachment is
 * *called* on the wire, and it stays a word rather than a translated label.
 *
 * @param name the display name the host reported, or null when it reported none.
 */
fun attachmentName(name: String?): String = name?.takeIf { it.isNotBlank() } ?: "file"

/**
 * The one-line label an attachment wears wherever it is drawn.
 *
 * Shaped here and unchanged from what the app always showed: a [TextRef] would
 * be the wrong shape for it, because nothing in it is *said* — the paperclip and
 * the separator are punctuation, the name is the host's own answer, and the size
 * arrives already rendered, in units that read the same in both languages.
 *
 * @param name the display name the host reported, or null.
 * @param size the size, already rendered, or null when it is not known.
 */
fun attachmentLabel(name: String?, size: String? = null): String {
    val label = if (size == null) {
        "📎 " + attachmentName(name)
    } else {
        "📎 ${attachmentName(name)} · $size"
    }
    return label.trimEnd(' ', '·')
}
