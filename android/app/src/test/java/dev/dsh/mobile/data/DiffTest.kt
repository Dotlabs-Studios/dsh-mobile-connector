package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the unified diff an edit-style tool call is drawn with.
 *
 * A wrong diff is worse than no diff: it tells the reader a file changed in a
 * way it did not. These pin the head/tail trim, the context window, and the
 * degenerate cases where the two texts share nothing.
 */
class DiffTest {

    @Test
    fun `a change in the middle keeps its context`() {
        val old = "a\nb\nc\nd\ne\nf\ng"
        val new = "a\nb\nc\nD\ne\nf\ng"

        val lines = lineDiff(old, new)

        assertEquals(1, lines.count { it.kind == DiffKind.Removed })
        assertEquals(1, lines.count { it.kind == DiffKind.Added })
        assertEquals("d", lines.first { it.kind == DiffKind.Removed }.text)
        assertEquals("D", lines.first { it.kind == DiffKind.Added }.text)
        // Three lines of context on each side, which is what the harness keeps.
        assertEquals(3, lines.count { it.kind == DiffKind.Context && it.oldLine!! < 4 })
        assertEquals(3, lines.count { it.kind == DiffKind.Context && it.oldLine!! > 4 })
    }

    @Test
    fun `distant context is folded behind a gap marker`() {
        val old = (1..20).joinToString("\n") { "line$it" }
        val new = old.replace("line10", "changed")

        val lines = lineDiff(old, new)

        assertEquals(DiffKind.Gap, lines.first().kind)
        assertEquals(DiffKind.Gap, lines.last().kind)
        // Three lines of context on each side of the single change, plus the two
        // fold markers: the other twelve lines are never sent to the screen.
        assertEquals(10, lines.size)
        assertEquals(1, lines.count { it.kind == DiffKind.Added })
        assertEquals(1, lines.count { it.kind == DiffKind.Removed })
    }

    @Test
    fun `a replacement argues the two lines rather than a whole block`() {
        // The naive head/tail approach reports `c` as changed here, because the
        // appended line hides the shared tail. A real edit script does not.
        val lines = lineDiff("a\nb\nc", "a\nB\nc\nD")

        assertEquals(listOf(DiffKind.Context, DiffKind.Removed, DiffKind.Added, DiffKind.Context, DiffKind.Added), lines.map { it.kind })
        assertEquals("a", lines[0].text)
        assertEquals("b", lines[1].text)
        assertEquals("B", lines[2].text)
        assertEquals("c", lines[3].text)
        assertEquals("D", lines[4].text)
    }

    @Test
    fun `identical texts produce nothing`() {
        assertTrue(lineDiff("same", "same").isEmpty())
    }

    @Test
    fun `a pure insertion reports only additions`() {
        val lines = lineDiff("a\nb", "a\nnew\nb")

        assertEquals(0, lines.count { it.kind == DiffKind.Removed })
        assertEquals(1, lines.count { it.kind == DiffKind.Added })
        assertEquals("new", lines.first { it.kind == DiffKind.Added }.text)
    }

    @Test
    fun `a file created from nothing is all additions`() {
        val lines = lineDiff("", "prima\nseconda")

        assertEquals(0, lines.count { it.kind == DiffKind.Removed })
        assertEquals(2, lines.count { it.kind == DiffKind.Added })
    }

    @Test
    fun `texts with nothing in common fall back to old-then-new`() {
        val lines = lineDiff("vecchio", "nuovo")

        assertEquals(1, lines.count { it.kind == DiffKind.Removed })
        assertEquals(1, lines.count { it.kind == DiffKind.Added })
        // Removals precede additions, as they do in a unified diff.
        assertTrue(lines.indexOfFirst { it.kind == DiffKind.Added } > lines.indexOfFirst { it.kind == DiffKind.Removed })
    }

    @Test
    fun `totals count what the row reports`() {
        val totals = diffTotals(lineDiff("a\nb\nc", "a\nB\nc\nD"))

        assertEquals(2, totals.added)
        assertEquals(1, totals.removed)
        assertTrue(!totals.isEmpty)
    }

    @Test
    fun `an unchanged diff reports no totals`() {
        assertTrue(diffTotals(lineDiff("x", "x")).isEmpty)
    }
}
