package dev.dsh.mobile.data

/**
 * The browse pane's decisions, kept out of the ViewModel and the composable so
 * they can be checked on the JVM.
 *
 * They exist because of the shape the host answers a create with: the *parent's*
 * refreshed level plus the path it just made ([DirListing.created]). Drawing that
 * answer as-is leaves the picker on the folder **above** the new one, so the chat
 * started on the next tap runs one level too high — and at a unit root the host
 * cannot prepare that directory at all. These rules name the destination the user
 * asked for instead of the level that came back.
 */

/** What the browse pane's primary action is about to do. */
sealed interface BrowseIntent {

    /** Start the chat in the level already on screen. */
    data class Here(val path: String) : BrowseIntent

    /**
     * Create [name] under [parent], then start the chat inside what was created.
     *
     * This is the reading of the screen: the folder-name field sits directly
     * above the button, so a name typed there belongs to the chat that is about
     * to start — not to a second tap on a small icon.
     */
    data class CreateThenHere(val parent: String, val name: String) : BrowseIntent

    /**
     * The name is already a child of the level on screen: the chat goes in that
     * folder, which is what asking for it would have produced anyway.
     *
     * [name] is the host's own spelling, not the one that was typed, so the
     * button shows the folder that is really there.
     */
    data class EnterExisting(val path: String, val name: String) : BrowseIntent
}

/**
 * The child of [level] that already carries [name], or null when the level has
 * no such folder.
 *
 * The comparison ignores case because the host's filesystems do: on Windows
 * `nuovo` and `Nuovo` are one folder, and promising to create the second one
 * would answer with a refusal the user cannot act on.
 *
 * @param level the level on screen, or null while it is still loading.
 * @param name the name as typed.
 */
fun existingChild(level: DirListing?, name: String): DirEntry? {
    val wanted = name.trim()
    if (wanted.isEmpty()) return null
    return level?.entries?.firstOrNull { it.name.equals(wanted, ignoreCase = true) && it.path.isNotBlank() }
}

/**
 * The directory a create just made, or null when the host reported none.
 *
 * A create never answers with the child's own level: the new path travels in
 * [DirListing.created], while [DirListing.path] still names the parent.
 *
 * @param response the level a create answered with.
 */
fun createdDirectory(response: DirListing): String? =
    response.created?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Decide what the picker's primary action does.
 *
 * The name is passed through as typed, including one the host will refuse: the
 * host owns that validation, and its refusal is reported in its own words rather
 * than guessed at here.
 *
 * @param level the level on screen, or null while it is still loading.
 * @param draft the folder name in the field.
 * @return the intent, or null when there is no level to act on.
 */
fun browseIntent(level: DirListing?, draft: String): BrowseIntent? {
    val path = level?.path?.trim().orEmpty()
    if (path.isEmpty()) return null
    val name = draft.trim()
    if (name.isEmpty()) return BrowseIntent.Here(path)
    val existing = existingChild(level, name)
    return if (existing != null) {
        BrowseIntent.EnterExisting(existing.path, existing.name)
    } else {
        BrowseIntent.CreateThenHere(path, name)
    }
}

/**
 * The level above [level], or null when the host reported none.
 *
 * The crumbs are two lists wearing one name: the volume roots ride in front of
 * every level as *shortcuts* — that is how a phone learns that another drive
 * exists at all — and behind them come the real ancestors, root first. Stepping
 * "up" by taking whichever row happens to precede the current one therefore
 * reads a shortcut as a parent, and on a volume root it answers with a
 * *different volume*. So the two are told apart by the flag the host sets on a
 * drive row, and a shortcut is not a step up: above `K:\` there is nothing, and
 * the honest answer is nothing.
 *
 * The current level is matched from the **end** for the same reason: a drive
 * shortcut and the ancestor it duplicates (`K:\` appears twice on a level below
 * it) are identical by path, and only the last one is the ancestor.
 *
 * @param level the level on screen, or null while it is still loading.
 * @return the absolute path to step up to, or null when there is none.
 */
fun browseParent(level: DirListing?): String? {
    val listing = level ?: return null
    val here = listing.path.trim()
    if (here.isEmpty()) return null
    val crumbs = listing.crumbs
    val index = crumbs.indexOfLast { it.path.trim().equals(here, ignoreCase = true) }
    if (index <= 0) return null
    val parent = crumbs[index - 1]
    if (parent.drive) return null
    val path = parent.path.trim()
    if (path.isEmpty() || path.equals(here, ignoreCase = true)) return null
    return path
}
