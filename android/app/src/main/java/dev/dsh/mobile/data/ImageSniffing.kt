package dev.dsh.mobile.data

/**
 * Image signature detection for picked files.
 *
 * The harness rejects a media type that disagrees with the bytes it receives,
 * and Android content providers routinely report the wrong MIME type for
 * renamed or downloaded files. Sniffing the leading bytes is therefore the
 * only reliable way to label a photo before sending it.
 *
 * This lives in the data layer, free of Android types, so it stays unit
 * testable on the JVM.
 */
object ImageSniffing {
    /** The media types the mobile API accepts. */
    val SUPPORTED: Set<String> = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

    /**
     * Identify an image's media type from its leading bytes.
     *
     * @param bytes the file content.
     * @return the MIME type, or null when the signature is not a supported image.
     */
    fun sniff(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        fun at(index: Int): Int = bytes[index].toInt() and 0xFF
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x38 -> "image/gif"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "image/webp"
            else -> null
        }
    }
}
