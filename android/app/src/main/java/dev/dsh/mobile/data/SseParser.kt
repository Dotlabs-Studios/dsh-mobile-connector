package dev.dsh.mobile.data

/**
 * A minimal Server-Sent-Events frame reader.
 *
 * The host's stream is `data: <json>\n\n` frames plus `: keep-alive` comments and a
 * `retry:` hint, which is a small enough dialect that a dependency for it would be
 * more code than this. The parser takes one line at a time so the caller can read a
 * socket without buffering the whole response, and it keeps the multi-line `data:`
 * case because the specification has it.
 *
 * Comments and unknown fields are ignored rather than treated as errors: the
 * route is the host's to extend, and a phone that crashed on a field it did not
 * know would be the wrong kind of strict.
 */
class SseParser {
    private val data = StringBuilder()

    /**
     * Feed one line, without its terminator.
     *
     * @param raw the line as read from the stream.
     * @return the frame's payload when this line completed one, otherwise null.
     */
    fun feed(raw: String): String? {
        val line = raw.trimEnd('\r')
        if (line.isEmpty()) {
            if (data.isEmpty()) return null
            val payload = data.toString()
            data.clear()
            return payload
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val field = line.substring(0, colon)
        if (field != "data") return null
        val value = line.substring(colon + 1).removePrefix(" ")
        if (data.isNotEmpty()) data.append('\n')
        data.append(value)
        return null
    }
}
