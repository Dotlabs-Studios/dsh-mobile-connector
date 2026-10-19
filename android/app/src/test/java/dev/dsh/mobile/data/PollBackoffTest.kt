package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for the poll interval both loops share. */
class PollBackoffTest {

    @Test
    fun `a healthy link polls at the base interval, faster while busy`() {
        assertEquals(15_000L, pollDelay(failures = 0, busy = false, activeDelay = 2_500, idleDelay = 15_000))
        assertEquals(2_500L, pollDelay(failures = 0, busy = true, activeDelay = 2_500, idleDelay = 15_000))
    }

    @Test
    fun `each failure doubles the delay`() {
        assertEquals(5_000L, pollDelay(failures = 1, busy = true, activeDelay = 2_500, idleDelay = 15_000))
        assertEquals(10_000L, pollDelay(failures = 2, busy = true, activeDelay = 2_500, idleDelay = 15_000))
        assertEquals(20_000L, pollDelay(failures = 3, busy = true, activeDelay = 2_500, idleDelay = 15_000))
    }

    @Test
    fun `the cap holds however long the outage lasts`() {
        // Five thousand doubled four times would be eighty, so the cap clips it.
        assertEquals(60_000L, pollDelay(failures = 9, busy = true, activeDelay = 5_000, idleDelay = 15_000))
    }

    @Test
    fun `a busy list backs off from the busy interval, not the idle one`() {
        assertEquals(30_000L, pollDelay(failures = 1, busy = false, activeDelay = 15_000, idleDelay = 30_000))
    }

    @Test
    fun `saturation keeps the shift from overflowing`() {
        // Four doublings is the last one; a longer streak repeats it rather than
        // shifting further, which is what keeps an int shift from wrapping.
        assertEquals(40_000L, pollDelay(failures = 4, busy = true, activeDelay = 2_500, idleDelay = 15_000))
        assertEquals(40_000L, pollDelay(failures = 100, busy = true, activeDelay = 2_500, idleDelay = 15_000))
    }
}
