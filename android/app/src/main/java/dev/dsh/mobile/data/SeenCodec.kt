package dev.dsh.mobile.data

/**
 * The storage format of the "last seen" map, kept out of the preferences class so
 * the parsing can be tested without Android.
 *
 * One line per session, `id=millis`. The id is the substring before the **last**
 * `=`, so an id that happens to contain one still round-trips.
 */

/**
 * Encode the seen map for storage.
 * @param values session id to last seen host timestamp.
 * @return one `id=millis` line per entry.
 */
fun encodeSeen(values: Map<String, Long>): String =
    values.entries.joinToString("\n") { "${it.key}=${it.value}" }

/**
 * Decode the seen map, dropping anything that no longer parses.
 *
 * A dropped line costs one unread badge on one chat; refusing the whole file would
 * cost all of them, which is the wrong trade for a cache of "what I have looked at".
 *
 * @param raw the stored text, or null when nothing was ever written.
 * @return the entries that parsed.
 */
fun decodeSeen(raw: String?): Map<String, Long> {
    if (raw.isNullOrEmpty()) return emptyMap()
    return raw.lineSequence()
        .mapNotNull { line ->
            val at = line.lastIndexOf('=')
            if (at <= 0) {
                null
            } else {
                line.substring(at + 1).toLongOrNull()?.let { value -> line.substring(0, at) to value }
            }
        }
        .toMap()
}
