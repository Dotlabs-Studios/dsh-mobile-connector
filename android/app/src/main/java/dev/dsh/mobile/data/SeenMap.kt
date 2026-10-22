package dev.dsh.mobile.data

/**
 * The rule the "what this phone has read" map obeys, kept out of the view model
 * so it can be tested without Android.
 *
 * The value for a chat is the newest host timestamp this phone has looked at,
 * and it only ever moves forward. That is not a detail: the list poll and the
 * chat poll answer at different speeds, and a read that lands late — a poll
 * already in flight when the chat was opened, a cached page replayed after a
 * reconnect — carries a moment older than the one just recorded. Letting it
 * through is how a chat that was read grows its dot back on the next poll, which
 * is the one thing an unread marker must never do.
 */

/**
 * Record that one chat has been read up to a host timestamp.
 *
 * @param seen the map before the look, session id to the newest host millis read.
 * @param sessionId the chat that was looked at.
 * @param at the host timestamp the look covers; zero or negative means the host
 *   reported nothing and there is nothing to record.
 * @return a map with the mark moved forward, or the same instance when this look
 *   added nothing — callers compare by identity to skip a write.
 */
fun rememberSeen(seen: Map<String, Long>, sessionId: String, at: Long): Map<String, Long> {
    if (sessionId.isEmpty() || at <= 0L) return seen
    if (at <= (seen[sessionId] ?: 0L)) return seen
    return seen + (sessionId to at)
}
