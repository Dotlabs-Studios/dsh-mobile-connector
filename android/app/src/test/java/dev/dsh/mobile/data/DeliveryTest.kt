package dev.dsh.mobile.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for what a phone does with a delivered file.
 *
 * Two rules live here, and both are invisible in a screenshot. The first is the
 * *name* a delivery is written under: a PC name can carry characters Android
 * refuses, and a save that fails or that writes a file nobody can find again is
 * indistinguishable from a download that never happened. The second is the media
 * type an intent is built with: a spreadsheet offered to a text editor opens as
 * noise, and the user blames the file.
 *
 * The wire shapes are pinned here too, because every model in this app was
 * written by hand against the plugin's routes.
 */
class DeliveryTest {

    /** The decoder the app itself uses: unknown fields are the host's business. */
    private val decoder = Json { ignoreUnknownKeys = true }

    // ── the name a delivery lands under ──────────────────────────────────────

    @Test
    fun `a windows path keeps only its own file name`() {
        assertEquals("Bilancio 2026.xlsx", deliverySaveName("""K:\Repository\Acme\Bilancio 2026.xlsx"""))
    }

    @Test
    fun `a unix path keeps only its own file name`() {
        assertEquals("nota.md", deliverySaveName("/home/acid/report/nota.md"))
    }

    @Test
    fun `a bare name is left alone`() {
        assertEquals("consegna.md", deliverySaveName("consegna.md"))
    }

    @Test
    fun `characters android refuses are repaired, not dropped`() {
        // The characters a phone filesystem will not take become underscores: the
        // name stays recognisable, which is the whole point of downloading a file
        // the user is going to look for in the Download folder.
        assertEquals("a_b_c_d_e_f_g_h.txt", deliverySaveName("""a<b>c:d"e|f?g*h.txt"""))
        assertEquals("no_ta.md", deliverySaveName("no\u0001ta.md"))
    }

    @Test
    fun `a trailing dot or space is trimmed`() {
        // Windows tolerates both and Android does not: a file called `nota.md.`
        // is a file the picker cannot open.
        assertEquals("nota.md", deliverySaveName("nota.md."))
        assertEquals("nota.md", deliverySaveName("  nota.md  "))
    }

    @Test
    fun `a path that names no file falls back to a name`() {
        assertEquals("consegna", deliverySaveName("/"))
        assertEquals("consegna", deliverySaveName(""))
        assertEquals("consegna", deliverySaveName("   "))
    }

    @Test
    fun `a very long name is cut but keeps its extension`() {
        val long = "a".repeat(300) + ".xlsx"
        val saved = deliverySaveName(long)
        assertTrue("the name must fit a filesystem entry (got ${saved.length})", saved.length <= 120)
        assertTrue("the extension is what decides which app opens it", saved.endsWith(".xlsx"))
    }

    // ── the type an intent is built with ─────────────────────────────────────

    @Test
    fun `office formats carry their openxml types`() {
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            deliveryMimeType("Bilancio.xlsx"),
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            deliveryMimeType("Relazione.docx"),
        )
        assertEquals("application/pdf", deliveryMimeType("contratto.pdf"))
    }

    @Test
    fun `an uppercase extension is the same extension`() {
        assertEquals("application/pdf", deliveryMimeType("REPORT.PDF"))
        assertEquals("image/png", deliveryMimeType("Schermata.PNG"))
    }

    @Test
    fun `an unknown extension gets the honest generic type`() {
        // `text/plain` here would offer the editor for a binary of unknown shape,
        // and a wrong type is worse than a generic one.
        assertEquals("application/octet-stream", deliveryMimeType("dati.xyz"))
        assertEquals("application/octet-stream", deliveryMimeType("senza-estensione"))
    }

    // ── the two wire shapes ──────────────────────────────────────────────────

    @Test
    fun `a deliverable entry decodes with its declarations`() {
        val json = """
            {"kind":"deliverable","seq":8,"type":"deliverables/presented","turn":1,"callId":"call-9",
             "files":[{"index":0,"path":"consegna.md","description":"la nota"},
                      {"index":2,"path":"consegna.bin"}]}
        """.trimIndent()

        val entry = decoder.decodeFromString<Entry>(json)

        assertEquals("deliverable", entry.kind)
        assertEquals(8, entry.seq)
        assertEquals(2, entry.files.size)
        assertEquals(0, entry.files[0].index)
        assertEquals("consegna.md", entry.files[0].path)
        assertEquals("la nota", entry.files[0].description)
        // The index is the coordinate the host resolves by, so it must survive
        // exactly as it arrived — including a gap the dropped entry left behind.
        assertEquals(2, entry.files[1].index)
        assertNull(entry.files[1].description)
    }

    @Test
    fun `a declared file names itself from the last segment of its path`() {
        assertEquals("nota.md", DeclaredFile(index = 0, path = "docs/nota.md").name)
        assertEquals("nota.md", DeclaredFile(index = 0, path = """K:\Repository\nota.md""").name)
        assertEquals("nota.md", DeclaredFile(index = 0, path = "nota.md").name)
    }

    @Test
    fun `a described delivery knows how it can be read`() {
        val text = DeliveredFile(path = """K:\x\nota.md""", name = "nota.md", bytes = 12, text = "# ciao", binary = false)
        val blob = DeliveredFile(path = """K:\x\Bilancio.xlsx""", name = "Bilancio.xlsx", bytes = 4096, binary = true)

        assertTrue("text is what this app can render", text.readable)
        assertTrue("a markdown delivery is rendered, not shown", text.isMarkdown)
        assertFalse("bytes are for another app", blob.readable)
        assertFalse(blob.isMarkdown)
    }

    @Test
    fun `a delivery without a name falls back to the path it resolved to`() {
        val file = DeliveredFile(path = """K:\Repository\Acme\Bilancio.xlsx""", name = "")
        assertEquals("Bilancio.xlsx", file.displayName)
    }
}
