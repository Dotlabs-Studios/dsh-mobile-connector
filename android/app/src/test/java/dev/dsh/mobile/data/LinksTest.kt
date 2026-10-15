package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for how a tapped link is read.
 *
 * The case they were written for is the one that shipped broken: an agent that
 * writes a file links it like a page, and `[LORE-bozza.md](LORE-bozza.md)` went
 * to the host's *web* fetcher, which answered — correctly, about a reference that
 * was never a URL — *not a valid URL: LORE-bozza.md*. What a link is has to be
 * decided before it is sent anywhere, and the decision is small enough to pin
 * down here instead of discovering it one sheet at a time on a phone.
 */
class LinksTest {

    private fun file(reference: String) = LinkTarget.File(reference)

    private fun web(url: String) = LinkTarget.Web(url)

    @Test
    fun `a bare file name is a file, which is the defect this exists for`() {
        assertEquals(file("LORE-bozza.md"), classifyLink("LORE-bozza.md"))
    }

    @Test
    fun `relative paths are files, however they are spelled`() {
        assertEquals(file("./docs/guida.md"), classifyLink("./docs/guida.md"))
        assertEquals(file("../README.md"), classifyLink("../README.md"))
        // Forwarded exactly as written, separators included: which of the two a
        // host's filesystem accepts is the host's business, not this classifier's.
        assertEquals(file("sub\\dir\\nota.txt"), classifyLink("sub\\dir\\nota.txt"))
        assertEquals(file("Makefile"), classifyLink("Makefile"))
    }

    @Test
    fun `an absolute path is a file and is forwarded as written`() {
        // The reference travels verbatim: only the host knows what a separator or
        // a drive letter means on its own platform.
        assertEquals(file("K:\\Repository\\dsh-mobile-connector\\README.md"), classifyLink("K:\\Repository\\dsh-mobile-connector\\README.md"))
        assertEquals(file("C:/Users/you/nota.md"), classifyLink("C:/Users/you/nota.md"))
        assertEquals(file("\\\\server\\share\\nota.md"), classifyLink("\\\\server\\share\\nota.md"))
        assertEquals(file("/home/acid/nota.md"), classifyLink("/home/acid/nota.md"))
    }

    @Test
    fun `a file URL is a file, and the host is the one that unwraps it`() {
        assertEquals(file("file:///K:/Repository/x.md"), classifyLink("file:///K:/Repository/x.md"))
        assertEquals(file("file:/K:/Repository/x.md"), classifyLink("file:/K:/Repository/x.md"))
    }

    @Test
    fun `a drive letter is not a scheme named K`() {
        // `K:\x` and `file:`-style references share the colon; reading a drive as
        // a URI scheme would send a Windows path to the web reader.
        assertEquals(file("K:\\x.md"), classifyLink("K:\\x.md"))
        // Drive-relative is the host's to refuse, in its own words — the app does
        // not re-implement that rule here.
        assertEquals(file("K:nota.md"), classifyLink("K:nota.md"))
    }

    @Test
    fun `pages stay pages`() {
        assertEquals(web("https://example.com/guida"), classifyLink("https://example.com/guida"))
        assertEquals(web("http://127.0.0.1:4380/x"), classifyLink("http://127.0.0.1:4380/x"))
        assertEquals(web("HTTPS://example.com/Guida"), classifyLink("HTTPS://example.com/Guida"))
    }

    @Test
    fun `a scheme this app has no reader for keeps the browser as its way out`() {
        // Answering "file not found" for a `mailto:` would be a worse lie than
        // letting the host refuse it and leaving *Apri nel browser* one tap away.
        assertEquals(web("mailto:qualcuno@example.com"), classifyLink("mailto:qualcuno@example.com"))
        assertEquals(web("tel:+391234567"), classifyLink("tel:+391234567"))
        assertEquals(web("data:text/plain,ciao"), classifyLink("data:text/plain,ciao"))
    }

    @Test
    fun `surrounding whitespace never changes what a link is`() {
        assertEquals(file("LORE-bozza.md"), classifyLink("  LORE-bozza.md\n"))
        assertEquals(web("https://example.com"), classifyLink("\thttps://example.com "))
    }

    @Test
    fun `an empty target opens nothing`() {
        assertNull(classifyLink(""))
        assertNull(classifyLink("   "))
        assertNull(classifyLink("\n\t"))
    }
}
