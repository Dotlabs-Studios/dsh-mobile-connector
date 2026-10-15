package dev.dsh.mobile.data

/**
 * What a project's section of the chat list *is* — and never what it says.
 *
 * The sentences live in `res/values/strings.xml` and are read where they are drawn
 * (`ui/SessionsScreen.kt`); what belongs here is the *structure* they describe: the
 * key a group folds under, how many of its chats an open card shows, and how many it
 * is holding back. Keeping the two apart is what lets this arithmetic be checked on
 * the JVM — no phone, no resources — and the sentences be checked against
 * `strings.xml` without compiling the app.
 *
 * One promise made of words is still worth naming here, because the arithmetic
 * serves it: removing a project is non-destructive by contract — the folder, its
 * files and the chat histories are never touched, and the project simply leaves the
 * list — so a confirmation that *could* be read as "delete these files" is one
 * someone will refuse to tap, or worse, tap expecting a cleanup that never happens.
 * That sentence is `workspace_remove_detail`, and `WorkspacesTest` reads it out of
 * `strings.xml` so it cannot drift.
 */

/**
 * The section key the chat list folds one group under.
 *
 * A group is its project id, and the trailing bucket of chats no project owns is
 * a section too — it folds like any other, so it needs a key of its own. One
 * function for both keeps the key the list renders under and the key the fold is
 * remembered by from drifting apart, which is the bug that would show up as "the
 * wrong group closed".
 *
 * @param workspaceId the project id, or null for the ungrouped bucket.
 * @return the key.
 */
fun sectionKey(workspaceId: String?): String = workspaceId ?: "ungrouped"

/**
 * How many of a project's chats an open card shows before it offers the rest.
 *
 * A heading is a way *into* a project, not a copy of it: past a handful of rows
 * the card stops being the place one chat is found and becomes a wall the next
 * project has to be scrolled past — the same problem the fold solves one level
 * up, one level down. Five is what fits under a heading on a phone while still
 * covering the working set of "what was I doing here".
 */
const val CHATS_BEFORE_MORE: Int = 5

/**
 * One open project's chats, cut to what the card is willing to draw.
 *
 * @property visible the chats to draw, newest first.
 * @property hidden how many are being held back, zero when nothing is.
 */
data class ChatSlice(val visible: List<SessionSummary>, val hidden: Int) {
    /** Whether the card is holding chats back. */
    val hasMore: Boolean get() = hidden > 0
}

/**
 * The chats one open project shows.
 *
 * The cut is by [SessionSummary.updatedAt] descending rather than by the order the
 * list happens to arrive in: "the last five" has to mean the five *newest* chats
 * whatever order a host answers in, and sorting here is what keeps that promise
 * independent of the wire. The sort is stable, so chats sharing a timestamp keep
 * the order the host gave them.
 *
 * @param sessions the project's chats, however they arrived.
 * @param expanded whether the reader asked for the whole list.
 * @param limit how many stay visible when they did not.
 * @return the slice to draw, and what it leaves behind.
 */
fun visibleChats(
    sessions: List<SessionSummary>,
    expanded: Boolean,
    limit: Int = CHATS_BEFORE_MORE,
): ChatSlice {
    val ordered = sessions.sortedByDescending { it.updatedAt }
    if (expanded || ordered.size <= limit) return ChatSlice(ordered, 0)
    return ChatSlice(ordered.take(limit), ordered.size - limit)
}
