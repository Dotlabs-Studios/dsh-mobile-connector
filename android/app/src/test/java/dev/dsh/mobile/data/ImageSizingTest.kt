package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the two arithmetic rules behind image handling.
 *
 * Both are pure for a reason: they decide how many pixels a phone decodes (a
 * memory question) and how many it uploads (a data question), and neither can be
 * checked on a device in this project's suite.
 */
class ImageSizingTest {

    @Test
    fun `a landscape photo is scaled to the long edge`() {
        assertEquals(1568 to 1176, scaledDimensions(4000, 3000))
    }

    @Test
    fun `a portrait photo is scaled on its height`() {
        assertEquals(588 to 1568, scaledDimensions(3000, 8000))
    }

    @Test
    fun `a photo inside the bound is left alone`() {
        assertEquals(800 to 600, scaledDimensions(800, 600))
    }

    @Test
    fun `a degenerate dimension never becomes zero`() {
        assertEquals(1568 to 1, scaledDimensions(10_000, 3))
    }

    @Test
    fun `sample size halves until the long edge fits`() {
        assertEquals(1, sampleSizeFor(800, 600, 720))
        assertEquals(2, sampleSizeFor(1600, 1200, 720))
        assertEquals(4, sampleSizeFor(3200, 2400, 720))
        assertEquals(8, sampleSizeFor(6000, 4000, 720))
    }

    @Test
    fun `sample size is never zero or negative`() {
        assertEquals(1, sampleSizeFor(100, 100, 0))
        assertEquals(1, sampleSizeFor(0, 0, 720))
    }

    @Test
    fun `a twelve megapixel photo needs downscaling`() {
        assertTrue(needsDownscale(4000, 3000, 3_000_000, "image/jpeg"))
    }

    @Test
    fun `a snapshot inside both bounds is sent untouched`() {
        assertFalse(needsDownscale(1280, 720, 400_000, "image/jpeg"))
    }

    @Test
    fun `an animation is never re-encoded`() {
        assertFalse(needsDownscale(4000, 3000, 5_000_000, "image/gif"))
    }
}
