package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests for choosing the address to try when the current one stops answering. */
class HostFailoverTest {

    private val lan = "http://192.168.1.20:4380"
    private val tailnet = "http://100.101.102.103:4380"

    @Test
    fun `the address in use is never its own fallback`() {
        assertEquals(listOf(tailnet), failoverCandidates(lan, listOf(lan, tailnet)))
        assertEquals(listOf(lan), failoverCandidates(tailnet, listOf(lan, tailnet)))
    }

    @Test
    fun `duplicates and blanks are dropped`() {
        val candidates = failoverCandidates(lan, listOf(tailnet, tailnet, "", "  ", "$tailnet "))
        assertEquals(listOf(tailnet), candidates)
    }

    @Test
    fun `nothing known means nothing to try`() {
        assertEquals(emptyList<String>(), failoverCandidates(lan, emptyList()))
        assertNull(nextFailoverHost(lan, emptyList(), emptySet()))
    }

    @Test
    fun `an address already tried is not tried again`() {
        assertEquals(tailnet, nextFailoverHost(lan, listOf(tailnet), emptySet()))
        assertNull("the one candidate was already tried", nextFailoverHost(lan, listOf(tailnet), setOf(tailnet)))
    }

    @Test
    fun `the order the host reported is kept`() {
        val a = "http://10.0.0.2:4380"
        val b = "http://10.0.0.3:4380"
        assertEquals(listOf(a, b), failoverCandidates(lan, listOf(a, b)))
    }

    @Test
    fun `a null current leaves every address available`() {
        assertEquals(listOf(lan, tailnet), failoverCandidates(null, listOf(lan, tailnet)))
    }
}
