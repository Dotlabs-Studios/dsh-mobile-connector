package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for what an approval card may show about the call it is asking about. */
class ApprovalPreviewTest {

    @Test
    fun `a shell call exposes its command`() {
        val preview = approvalPreview("""{"command":"gradlew.bat test","description":"run the tests"}""")
        assertEquals("gradlew.bat test", preview?.command)
        assertNull("a shell call has no path to draw", preview?.path)
    }

    @Test
    fun `a write call exposes the target and the text`() {
        val preview = approvalPreview("""{"file_path":"K:\\repo\\README.md","content":"# Titolo"}""")
        assertEquals("K:\\repo\\README.md", preview?.path)
        assertEquals("# Titolo", preview?.body)
        assertNull(preview?.command)
    }

    @Test
    fun `an edit call shows what will replace the old text`() {
        val preview = approvalPreview("""{"path":"nota.md","old_string":"vecchio","new_string":"nuovo"}""")
        assertEquals("nota.md", preview?.path)
        assertEquals("nuovo", preview?.body)
    }

    @Test
    fun `an unparsable payload yields nothing rather than a guess`() {
        assertNull(approvalPreview("not json at all"))
        assertNull(approvalPreview(null))
        assertNull(approvalPreview("   "))
    }

    @Test
    fun `arguments with nothing drawable yield nothing`() {
        assertNull(approvalPreview("""{"timeout":30}"""))
    }

    @Test
    fun `a long body is cut so the card cannot become the transcript`() {
        val long = "x".repeat(5_000)
        val preview = approvalPreview("""{"file_path":"nota.md","content":"$long"}""")
        assertEquals(2_000, preview?.body?.length)
        assertTrue(preview?.isEmpty == false)
    }
}
