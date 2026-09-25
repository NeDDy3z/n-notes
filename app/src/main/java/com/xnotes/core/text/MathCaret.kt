package com.xnotes.core.text

/**
 * Where a formula ends, for a caret. The offset at a formula's edge names two
 * places at once: beside the equation, and at the near end of the LaTeX behind
 * it. Nothing in the position separates them, so which one the caret is at is a
 * matter of where it came from, and [held] carries that: the start offset of the
 * formula an arrow key stepped into, or -1.
 *
 * Pure, because getting this wrong is invisible until someone types. A formula
 * that counts as open must also be the one text typed there goes into, or the
 * source shows while the characters land somewhere else.
 */
object MathCaret {

    /** The math run [off] lies inside, as its start offset, or -1. Edges are not inside. */
    fun within(para: Paragraph, off: Int): Int {
        forEachMath(para) { start, end ->
            if (off > start && off < end) return start
        }
        return -1
    }

    /**
     * The math run [off] stands at the entering edge of, moving by [delta], or -1.
     * Going left that edge is the formula's end, going right its start, so either
     * direction steps in at the near end of the LaTeX and reads on as before.
     */
    fun edgeAt(para: Paragraph, off: Int, delta: Int): Int {
        if (delta == 0) return -1
        forEachMath(para) { start, end ->
            if (if (delta < 0) off == end else off == start) return start
        }
        return -1
    }

    /**
     * The formula [held] names, when moving by [delta] from [off] leaves it, or
     * -1. Going left that is its start and going right its end: the far edge is
     * two places just as the near one was, so stepping out of the source costs
     * the press that stepping in did, and the formula is drawn again before the
     * caret carries on past it.
     */
    fun exitAt(para: Paragraph, off: Int, delta: Int, held: Int): Int {
        if (held < 0 || delta == 0) return -1
        forEachMath(para) { start, end ->
            if (start == held && (if (delta < 0) off == start else off == end)) return start
        }
        return -1
    }

    /**
     * The math run showing its source with the caret at [off], or -1: the one the
     * caret is within, or the one [held] names while the caret is still on it.
     */
    fun revealed(para: Paragraph, off: Int, held: Int): Int {
        forEachMath(para) { start, end ->
            if (off > start && off < end) return start
            if (held == start && off in start..end) return start
        }
        return -1
    }

    /** Whether the formula starting at [held] still has the caret at [off] on it. */
    fun holds(para: Paragraph, off: Int, held: Int): Boolean {
        if (held < 0) return false
        forEachMath(para) { start, end ->
            if (start == held) return off in start..end
        }
        return false
    }

    /** The style of the run [held] names, when [off] is still on it; else null. */
    fun heldStyle(para: Paragraph, off: Int, held: Int): CharStyle? {
        if (held < 0) return null
        var at = 0
        for (run in para.runs) {
            val end = at + run.text.length
            if (at == held) return if (run.style.math && off in at..end) run.style else null
            at = end
        }
        return null
    }

    private inline fun forEachMath(para: Paragraph, body: (start: Int, end: Int) -> Unit) {
        var at = 0
        for (run in para.runs) {
            val end = at + run.text.length
            if (run.style.math) body(at, end)
            at = end
        }
    }
}
