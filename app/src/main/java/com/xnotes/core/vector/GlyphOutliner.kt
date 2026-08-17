package com.xnotes.core.vector

/** How a run of text is set: the family it asks for and the face within it. */
class GlyphStyle(
    val family: String?,
    val size: Double,
    val bold: Boolean,
    val italic: Boolean,
    /** Extra space between characters, in the same units as [size]. */
    val letterSpacing: Double,
)

/** A run of text as outlines, laid out from the origin with its baseline on y = 0. */
class GlyphRun(val contours: List<VectorContour>, val advance: Double)

/**
 * Turns text into outlines, so a placed SVG's labels are geometry like everything else on it and
 * stay sharp however far the canvas zooms in.
 *
 * An interface because glyph outlines only exist on the platform: the font files, the family
 * matching and the shaping all live there. The reader takes one of these when it has one, and
 * names `text` as unsupported when it does not, which is what keeps the parser itself testable on
 * a plain JVM.
 */
interface GlyphOutliner {

    /** [text] set in [style], or null when it cannot be outlined. */
    fun outline(text: String, style: GlyphStyle): GlyphRun?

    /** How wide [text] sets in [style], for anchoring a whole line before any of it is outlined. */
    fun measure(text: String, style: GlyphStyle): Double
}
