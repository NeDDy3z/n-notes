package com.xnotes.core.pal

/**
 * The room one typeset equation needs. [ascent] and [descent] straddle the text
 * baseline so an inline formula sits on the same baseline as the words it is set
 * among, rather than on its own bottom edge.
 */
data class MathBox(val width: Double, val ascent: Double, val descent: Double) {
    val height: Double get() = ascent + descent

    /** The vertical room it asks of its line, as the line-height metrics. */
    fun metrics(): LineMetrics = LineMetrics(ascent, descent)
}

/**
 * Typesets LaTeX for the flow (spec: the TEXT tool's math runs). Laying maths
 * out needs font resolution the core has no access to, so the core only ever
 * asks how big an equation is and hands the same string back to be drawn.
 *
 * [measure] MUST be synchronous and MUST agree with what is later drawn, for the
 * same reason [TextMeasurer] must: the flow breaks lines and paginates against
 * these numbers, so a size that arrives late or differs from the paint leaves
 * text wrapped around a box that is not there.
 */
interface MathTypesetter {
    /** [latex] set at [sizePt] in content px, or null when it will not parse. */
    fun measure(latex: String, sizePt: Double): MathBox?
}
