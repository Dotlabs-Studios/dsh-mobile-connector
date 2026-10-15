package dev.dsh.mobile.data

/** What one line of a unified diff is. */
enum class DiffKind { Context, Added, Removed, Gap }

/**
 * One rendered line of a unified diff.
 *
 * @property kind whether the line is context, an addition, a removal, or the
 *   `⋯` marker that stands in for lines the view folded away.
 * @property text the line's content, without its `+`/`-` prefix.
 * @property oldLine the 1-based line number in the old text, when it has one.
 * @property newLine the 1-based line number in the new text, when it has one.
 */
data class DiffLine(
    val kind: DiffKind,
    val text: String,
    val oldLine: Int? = null,
    val newLine: Int? = null,
)

/** How many unchanged lines a hunk keeps on each side, matching the harness. */
private const val CONTEXT_LINES = 3

/**
 * Beyond this many edit-distance steps the diff gives up and reports the whole
 * middle as replaced.
 *
 * Myers is fast when the two texts are similar, which is the only case worth
 * diffing: an edit an agent made to a file it just read. Two files with nothing
 * in common are the pathological input — the work is quadratic in their size —
 * and their diff is not worth drawing line by line on a phone anyway.
 */
private const val MAX_EDIT_DISTANCE = 4_000

/** One step of an edit script. */
private enum class Op { Equal, Delete, Insert }

/**
 * Build a unified diff between two texts.
 *
 * The harness draws an edit as a single column with `+`/`-` prefixes, a
 * coloured marker bar, three lines of context, and a `⋯` where it folded the
 * rest away. This reproduces that shape using a real shortest-edit-script
 * search, because an approximate one is worse than nothing: showing an
 * unchanged line as removed tells the reader a file changed in a way it did
 * not, and no reader can tell that from a correct diff at a glance.
 *
 * @param oldText the text before the change.
 * @param newText the text after the change.
 * @return the diff lines, oldest first; empty when the two texts are equal.
 */
fun lineDiff(oldText: String, newText: String): List<DiffLine> {
    if (oldText == newText) return emptyList()
    val oldLines = splitLines(oldText)
    val newLines = splitLines(newText)
    val script = editScript(oldLines, newLines)
    return render(script)
}

/**
 * Split a text into lines, treating an empty text as no lines at all.
 *
 * `"".split('\n')` is one empty line, which would make creating a file look like
 * a change to an existing one. An empty file has no lines.
 *
 * @param text the text to split.
 * @return its lines.
 */
private fun splitLines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split('\n')

/** One line of an edit script, tagged with the text it carries. */
private data class Step(val op: Op, val text: String, val oldLine: Int?, val newLine: Int?)

/**
 * Compute a shortest edit script between two line lists.
 *
 * Myers' greedy algorithm: walk the edit graph by increasing edit distance and
 * keep the furthest-reaching path on each diagonal. When two texts are alike —
 * always the case here — the distance stays small and this is linear.
 *
 * @param a the old lines.
 * @param b the new lines.
 * @return the script, oldest first, tagged with the line numbers each step has.
 */
private fun editScript(a: List<String>, b: List<String>): List<Step> {
    val n = a.size
    val m = b.size
    if (n == 0 && m == 0) return emptyList()
    val max = n + m
    val offset = max + 1
    val v = IntArray(2 * max + 3)
    val trace = ArrayList<IntArray>()
    var reached = -1

    for (d in 0..max) {
        if (d > MAX_EDIT_DISTANCE) break
        trace.add(v.copyOf())
        var k = -d
        while (k <= d) {
            val index = k + offset
            val x = if (k == -d || (k != d && v[index - 1] < v[index + 1])) v[index + 1] else v[index - 1] + 1
            var xRun = x
            var yRun = xRun - k
            while (xRun < n && yRun < m && a[xRun] == b[yRun]) {
                xRun += 1
                yRun += 1
            }
            v[index] = xRun
            if (xRun >= n && yRun >= m) {
                reached = d
                break
            }
            k += 2
        }
        if (reached >= 0) break
    }

    if (reached < 0) return coarseScript(a, b)
    return backtrack(trace, a, b, reached, offset)
}

/**
 * Walk the recorded diagonals backwards into an ordered script.
 *
 * @param trace the per-distance diagonal states.
 * @param a the old lines.
 * @param b the new lines.
 * @param distance the edit distance the forward pass reached.
 * @param offset the diagonal offset used by the forward pass.
 * @return the script, oldest first.
 */
private fun backtrack(trace: List<IntArray>, a: List<String>, b: List<String>, distance: Int, offset: Int): List<Step> {
    val reversed = ArrayList<Step>()
    var x = a.size
    var y = b.size
    for (d in distance downTo 1) {
        val v = trace[d]
        val k = x - y
        val index = k + offset
        val previousK = if (k == -d || (k != d && v[index - 1] < v[index + 1])) k + 1 else k - 1
        val previousX = v[previousK + offset]
        val previousY = previousX - previousK
        while (x > previousX && y > previousY) {
            reversed += Step(Op.Equal, a[x - 1], x, y)
            x -= 1
            y -= 1
        }
        if (x > previousX) {
            reversed += Step(Op.Delete, a[x - 1], x, null)
            x -= 1
        } else if (y > previousY) {
            reversed += Step(Op.Insert, b[y - 1], null, y)
            y -= 1
        }
    }
    while (x > 0 && y > 0) {
        reversed += Step(Op.Equal, a[x - 1], x, y)
        x -= 1
        y -= 1
    }
    while (x > 0) {
        reversed += Step(Op.Delete, a[x - 1], x, null)
        x -= 1
    }
    while (y > 0) {
        reversed += Step(Op.Insert, b[y - 1], null, y)
        y -= 1
    }
    reversed.reverse()
    return reversed
}

/**
 * The fallback for two texts too different to align within the step budget.
 *
 * Reporting the old block as removed and the new block as added is coarse, but
 * it is true, and it is what a reader needs when two files share nothing.
 *
 * @param a the old lines.
 * @param b the new lines.
 * @return the script.
 */
private fun coarseScript(a: List<String>, b: List<String>): List<Step> =
    a.mapIndexed { index, text -> Step(Op.Delete, text, index + 1, null) } +
        b.mapIndexed { index, text -> Step(Op.Insert, text, null, index + 1) }

/**
 * Turn an edit script into the lines a diff view draws.
 *
 * Only changed regions are kept, each with [CONTEXT_LINES] unchanged lines
 * around it; the rest is folded behind a `⋯`, which is what keeps a one-line
 * change in a large file from filling the screen.
 *
 * @param script the edit script.
 * @return the rendered lines.
 */
private fun render(script: List<Step>): List<DiffLine> {
    val changed = script.indices.filter { script[it].op != Op.Equal }
    if (changed.isEmpty()) return emptyList()

    // Group changed positions into hunks, merging any whose context overlaps.
    val hunks = mutableListOf<IntRange>()
    for (index in changed) {
        val start = maxOf(0, index - CONTEXT_LINES)
        val end = minOf(script.lastIndex, index + CONTEXT_LINES)
        val last = hunks.lastOrNull()
        if (last != null && start <= last.last + 1) {
            hunks[hunks.lastIndex] = last.first..maxOf(last.last, end)
        } else {
            hunks += start..end
        }
    }

    val lines = mutableListOf<DiffLine>()
    for ((position, hunk) in hunks.withIndex()) {
        if (position > 0) lines += DiffLine(DiffKind.Gap, "⋯")
        else if (hunk.first > 0) lines += DiffLine(DiffKind.Gap, "⋯")
        for (index in hunk) {
            val step = script[index]
            lines += when (step.op) {
                Op.Equal -> DiffLine(DiffKind.Context, step.text, step.oldLine, step.newLine)
                Op.Delete -> DiffLine(DiffKind.Removed, step.text, step.oldLine, null)
                Op.Insert -> DiffLine(DiffKind.Added, step.text, null, step.newLine)
            }
        }
    }
    if (hunks.last().last < script.lastIndex) lines += DiffLine(DiffKind.Gap, "⋯")
    return lines
}

/** How many lines a diff adds and removes, for the row's `+N`/`-M` summary. */
data class DiffTotals(val added: Int, val removed: Int) {
    /** Whether there is anything to report. */
    val isEmpty: Boolean get() = added == 0 && removed == 0
}

/**
 * Count the additions and removals of a diff.
 *
 * @param lines a diff produced by [lineDiff].
 * @return the totals.
 */
fun diffTotals(lines: List<DiffLine>): DiffTotals = DiffTotals(
    added = lines.count { it.kind == DiffKind.Added },
    removed = lines.count { it.kind == DiffKind.Removed },
)
