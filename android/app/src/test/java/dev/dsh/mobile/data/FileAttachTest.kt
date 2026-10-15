package dev.dsh.mobile.data

import dev.dsh.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for what an attachment is allowed to be and how it is labelled.
 *
 * The harness imposes no limit of its own on a generic file — it stores the
 * bytes verbatim, whatever they are and however many — so the only ceiling in
 * the whole chain is the one here, and it is enforced twice: on the size the
 * picker reports, and on the bytes actually read when the picker reports none.
 * A test that pins it is the difference between "the phone refuses politely"
 * and "the phone reads a film into memory".
 */
class FileAttachTest {

    @Test
    fun `a file at the cap is accepted`() {
        assertNull(attachmentRefusal(MAX_ATTACHMENT_BYTES))
        assertNull(attachmentRefusal(0))
    }

    @Test
    fun `one byte past the cap is refused with the sentence that names the bound`() {
        val refusal = attachmentRefusal(MAX_ATTACHMENT_BYTES + 1) as? TextRef.Res

        assertNotNull("a file past the cap is refused", refusal)
        // The reason is *chosen* here and *spelled* by the UI, so what this layer
        // can still pin is which sentence was chosen. That the sentence names the
        // limit — "il massimo è 32 MB" — is a fact about the value in
        // `res/values/strings.xml`, which a pure JUnit test can only read once
        // there is a helper that parses that file (`StringsXml`); until then the
        // wording is checked by the build's own table checks, which refuse an
        // empty or placeholder-less translation.
        assertEquals(R.string.file_attach_too_large, refusal!!.id)
    }

    @Test
    fun `a missing name wears the harness's own label`() {
        assertEquals("file", attachmentName(null))
        assertEquals("file", attachmentName("   "))
        assertEquals("nota.md", attachmentName("nota.md"))
    }

    @Test
    fun `a label carries the name and the size, and no dangling separator`() {
        assertEquals("📎 nota.md · 11 B", attachmentLabel("nota.md", "11 B"))
        assertEquals("📎 nota.md", attachmentLabel("nota.md"))
        assertEquals("📎 file", attachmentLabel(null, null))
    }
}
