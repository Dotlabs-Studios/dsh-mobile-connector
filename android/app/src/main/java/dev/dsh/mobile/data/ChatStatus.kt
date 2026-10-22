package dev.dsh.mobile.data

/**
 * What the last turn of a chat did — the one fact a dot in the chat list carries.
 *
 * The host derives it (see the plugin's `chatStatusFor`) and sends one word; this
 * side turns the word into the row's dot. Five states, because "not running" is
 * not one thing: *completata* and *in errore* are the two news a reader scans the
 * list for, a turn the user stopped is neither, and a chat that never ran has
 * nothing to say at all. Painting those last two like a failure is the specific
 * lie a status dot is worst at, because it is read at a glance and never checked.
 *
 * Not every state is drawn the same length. A finished turn is *news* — there is
 * an answer in this chat that has not been read — so its green dot goes once the
 * chat has been opened; a failure, a stop and a running turn are *states* of the
 * chat and keep their mark however often it is read, because they are how the
 * chat that broke is found again a week later. [showsDot] is that one decision.
 *
 * The words that say all this are not here: they are the `status_*` resources of
 * `res/values/strings.xml`, chosen where the dot is drawn (`ui/SessionsScreen.kt`).
 * They name what happened rather than what colour it is — "rosso" tells a reader
 * who cannot see the colour nothing at all — and `ChatStatusTest` reads them back
 * out of `strings.xml` to keep the five of them distinct.
 *
 * @property wire the word the host sent, which is also what a log would show.
 */
enum class ChatStatus(val wire: String) {

    /** A turn is being executed on the PC right now. */
    Running("running"),

    /** The last turn finished. */
    Completed("completed"),

    /** The last turn did not finish: the host reported an error, or it stopped mid-turn. */
    Failed("failed"),

    /** The last turn was stopped — by the user, by a shutdown, or by a fork. */
    Stopped("stopped"),

    /** Nothing to report: no turn has run, or one is queued and not started. */
    Idle("idle"),
    ;

    /**
     * Whether this state earns a dot on a row, given whether the row is unread.
     *
     * [Completed] is the one state that is news rather than a condition: "there
     * is an answer here" is spent by reading the chat, and a green dot that
     * outlives the look is the row saying the same thing twice. A running turn,
     * a failure and a stop outlive the look on purpose — they are what the list
     * is scanned for, and hiding them after a read would make a broken chat
     * indistinguishable from one that never ran. [Idle] is drawn as an absence.
     *
     * @param unread whether the chat has moved since this phone last read it.
     * @return whether the row should carry this dot.
     */
    fun showsDot(unread: Boolean): Boolean = when (this) {
        Idle -> false
        Completed -> unread
        Running, Failed, Stopped -> true
    }

    companion object {
        /**
         * Read one status off the wire.
         *
         * A word this build does not know answers null — no dot — rather than
         * being folded into the nearest state it resembles: a harness from a newer
         * version is allowed to have invented a status, and inventing a verdict to
         * match it is exactly what this enum exists to avoid.
         *
         * @param value the `status` field of a session row, if it had one.
         * @return the status, or null when there is nothing to draw.
         */
        fun fromWire(value: String?): ChatStatus? = when (value?.trim()?.lowercase()) {
            "running" -> Running
            "completed" -> Completed
            "failed" -> Failed
            "stopped" -> Stopped
            "idle" -> Idle
            else -> null
        }
    }
}
