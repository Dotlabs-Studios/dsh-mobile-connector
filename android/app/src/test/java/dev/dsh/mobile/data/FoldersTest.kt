package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the browse pane's decisions.
 *
 * They exist because of one wire shape that reads as a trap: a create answers
 * with the *parent's* refreshed level plus the path it made. Drawing that answer
 * as-is leaves the picker on the folder above the new one, so the chat started on
 * the next tap runs one level too high — and on a unit root the host cannot even
 * prepare that directory, which is the failure that put these rules here.
 */
class FoldersTest {

    private fun level(path: String, created: String? = null, entries: List<DirEntry> = emptyList()) =
        DirListing(path = path, created = created, entries = entries)

    private fun child(name: String) = DirEntry(name = name, path = "K:\\$name")

    @Test
    fun `a create names its child, never the level it came back with`() {
        // The exact answer the live host gives for POST /mobile/dirs.
        val response = level("K:\\", created = "K:\\DizionarioAHO")

        assertEquals("K:\\DizionarioAHO", createdDirectory(response))
    }

    @Test
    fun `a plain level has no created directory`() {
        assertNull(createdDirectory(level("K:\\Repository")))
    }

    @Test
    fun `a blank created path is no directory either`() {
        assertNull(createdDirectory(level("K:\\", created = "   ")))
        assertNull(createdDirectory(level("K:\\", created = "")))
    }

    @Test
    fun `an empty field starts the chat in the level on screen`() {
        assertEquals(BrowseIntent.Here("K:\\Repository"), browseIntent(level("K:\\Repository"), ""))
    }

    @Test
    fun `a typed name creates that folder and starts the chat inside it`() {
        // What the screenshot's gesture means: the name is what the button is for.
        assertEquals(
            BrowseIntent.CreateThenHere("K:\\", "DizionarioAHO"),
            browseIntent(level("K:\\"), "DizionarioAHO"),
        )
    }

    @Test
    fun `the name is trimmed, and spaces alone are not a name`() {
        assertEquals(
            BrowseIntent.CreateThenHere("K:\\Repository", "Nuovo"),
            browseIntent(level("K:\\Repository"), "  Nuovo \n"),
        )
        assertEquals(BrowseIntent.Here("K:\\Repository"), browseIntent(level("K:\\Repository"), "   "))
    }

    @Test
    fun `a name the host will refuse is still the host's to refuse`() {
        // The app does not re-implement the host's validation: it asks, and the
        // refusal comes back in the host's own words rather than being guessed.
        assertEquals(
            BrowseIntent.CreateThenHere("K:\\Repository", "a\\b"),
            browseIntent(level("K:\\Repository"), "a\\b"),
        )
    }

    @Test
    fun `a name the level already has is that folder, not a new one`() {
        // Asking to create what is already there would be refused by the host
        // with a message the user can do nothing with. The level lists it, so
        // the button offers the chat in it instead — spelled the host's way.
        val listed = level("K:\\", entries = listOf(child("DataBridge-Storage"), child("DizionarioAHO")))

        assertEquals(BrowseIntent.EnterExisting("K:\\DizionarioAHO", "DizionarioAHO"), browseIntent(listed, "dizionarioaho"))
    }

    @Test
    fun `a name that is not in the level is created`() {
        val listed = level("K:\\", entries = listOf(child("Repository")))

        assertEquals(BrowseIntent.CreateThenHere("K:\\", "Nuovo"), browseIntent(listed, "Nuovo"))
    }

    @Test
    fun `a child row without a path is not a folder to enter`() {
        val listed = level("K:\\", entries = listOf(DirEntry(name = "Nuovo", path = "  ")))

        assertEquals(BrowseIntent.CreateThenHere("K:\\", "Nuovo"), browseIntent(listed, "Nuovo"))
    }

    @Test
    fun `nothing is offered before a level has loaded`() {
        assertNull(browseIntent(null, "DizionarioAHO"))
        assertNull(browseIntent(level(""), ""))
        assertNull(browseIntent(level("   "), "x"))
    }

    @Test
    fun `the button names the folder it is about to make`() {
        // Which sentence the button shows is the intent's business, and the intent
        // is pinned above; the sentences themselves are resources now, so they are
        // read from the file that ships ([StringsXml]) and the folder's name is
        // dropped where the tap will put it.
        assertEquals(
            "Crea «DizionarioAHO» e inizia la chat",
            String.format(StringsXml.value("browse_create_and_start"), "DizionarioAHO"),
        )
        assertEquals(
            "Inizia la chat in «DizionarioAHO»",
            String.format(StringsXml.value("browse_start_in"), "DizionarioAHO"),
        )
        assertEquals("Inizia una chat in questa cartella", StringsXml.value("browse_start_here"))
    }

    // ── stepping up ──────────────────────────────────────────────────────────

    /**
     * One crumb as the host reports it: a level's own row, or a drive shortcut.
     */
    private fun crumb(path: String, drive: Boolean = false) =
        DirEntry(name = path.trimEnd('\\', '/'), path = path, drive = drive)

    /** The shortcut block a host with fixed volumes leads every level with. */
    private val drives = listOf(crumb("C:\\", drive = true), crumb("K:\\", drive = true))

    @Test
    fun `up is the ancestor the host reported, not the row above`() {
        // The live shape of `K:\Repository\dsh-mobile-connector`: the drive shortcuts, then
        // the ancestry — in which `K:\` legitimately appears twice.
        val listing = DirListing(
            path = "K:\\Repository\\dsh-mobile-connector",
            crumbs = drives + listOf(crumb("K:\\"), crumb("K:\\Repository"), crumb("K:\\Repository\\dsh-mobile-connector")),
        )

        assertEquals("K:\\Repository", browseParent(listing))
    }

    @Test
    fun `the duplicate of the current drive is read as the ancestor`() {
        // Both `K:\` rows match the path, and only the later one is the parent of
        // what is below it. Matching the first would answer `H:\`.
        val listing = DirListing(
            path = "K:\\Repository",
            crumbs = drives + listOf(crumb("K:\\"), crumb("K:\\Repository")),
        )

        assertEquals("K:\\", browseParent(listing))
    }

    @Test
    fun `a volume root has no level above it`() {
        // The shortcuts lead the level, so the row before the root is another
        // volume: stepping up from `K:\` is not "go to H:\", it is nothing.
        val withShortcuts = DirListing(
            path = "K:\\",
            crumbs = listOf(crumb("C:\\", drive = true), crumb("H:\\", drive = true), crumb("K:\\")),
        )
        assertNull(browseParent(withShortcuts))

        // An older host suppressed the whole shortcut block on a drive root, and
        // the answer has to be the same one.
        assertNull(browseParent(DirListing(path = "K:\\", crumbs = listOf(crumb("K:\\")))))
    }

    @Test
    fun `a level the host reported no ancestry for cannot step up`() {
        // Without a crumb for the current path there is no parent to name —
        // guessing one from the leftovers is what stepped onto another volume.
        assertNull(browseParent(DirListing(path = "K:\\Repository", crumbs = drives)))
        assertNull(browseParent(DirListing(path = "K:\\Repository", crumbs = emptyList())))
        assertNull(browseParent(DirListing(path = "", crumbs = drives)))
        assertNull(browseParent(null))
    }

    @Test
    fun `up compares paths the way the host's filesystem does`() {
        val listing = DirListing(
            path = "k:\\repository",
            crumbs = listOf(crumb("K:\\"), crumb("K:\\Repository")),
        )

        assertEquals("K:\\", browseParent(listing))
    }

    @Test
    fun `a host without drive letters steps up to its root`() {
        val listing = DirListing(
            path = "/home/acid/repo",
            crumbs = listOf(crumb("/"), crumb("/home"), crumb("/home/acid"), crumb("/home/acid/repo")),
        )

        assertEquals("/home/acid", browseParent(listing))
        assertNull(browseParent(DirListing(path = "/", crumbs = listOf(crumb("/")))))
    }
}
