package com.xnotes.core.vector

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FillRule

/** How a stroked line ends. */
enum class LineCap { BUTT, ROUND, SQUARE }

/** How two segments of a stroked line meet. */
enum class LineJoin { MITER, ROUND, BEVEL }

/** What a gradient does outside its own span. */
enum class SpreadMethod { PAD, REFLECT, REPEAT }

/**
 * One segment of a contour. Curves stay curves: the flattener picks its tolerance from the size
 * the item is actually placed at, which the reader cannot know.
 */
sealed interface VectorSeg {
    val end: Pt

    class Line(override val end: Pt) : VectorSeg
    class Cubic(val c1: Pt, val c2: Pt, override val end: Pt) : VectorSeg
}

/** One subpath: where it starts, the segments that follow, and whether it closes back. */
class VectorContour(val start: Pt, val segments: List<VectorSeg>, val closed: Boolean)

/** One stop on a gradient ramp. */
class GradientStop(val offset: Double, val color: Rgba)

/**
 * What a fill or an outline paints with. Every coordinate is already in the document's own space,
 * so the mesher never has to resolve `objectBoundingBox` units or a `gradientTransform`.
 */
sealed interface VectorPaint {

    class Solid(val color: Rgba) : VectorPaint

    class Linear(
        val x0: Double,
        val y0: Double,
        val x1: Double,
        val y1: Double,
        val stops: List<GradientStop>,
        val spread: SpreadMethod,
    ) : VectorPaint

    /**
     * A radial ramp from the focus out to the circle. [fx]/[fy] fall back to the centre, which is
     * the SVG default and by far the common case.
     */
    class Radial(
        val cx: Double,
        val cy: Double,
        val r: Double,
        val fx: Double,
        val fy: Double,
        val stops: List<GradientStop>,
        val spread: SpreadMethod,
    ) : VectorPaint
}

/**
 * One painted path: its outline, and how it is filled and stroked. The contours are already in the
 * document's own coordinate space with every transform applied, so nothing downstream carries a
 * matrix.
 */
class VectorPath(
    val contours: List<VectorContour>,
    val fill: VectorPaint? = null,
    val fillRule: FillRule = FillRule.NONZERO,
    val stroke: VectorPaint? = null,
    val strokeWidth: Double = 1.0,
    val cap: LineCap = LineCap.BUTT,
    val join: LineJoin = LineJoin.MITER,
    val miterLimit: Double = 4.0,
    val dash: DoubleArray? = null,
    val dashOffset: Double = 0.0,
    /** Rectangular clip this path is under, in document space, or null when it is unclipped. */
    val clip: Rect? = null,
)

/**
 * A parsed vector document: its paths in paint order, in the coordinate space of its viewBox.
 *
 * A derived render artifact and never persisted: the `.xnote` bundle keeps the SVG file itself, and
 * [com.xnotes.format.SvgReader] builds one of these when the infinite canvas needs triangles.
 */
class VectorScene(
    val width: Double,
    val height: Double,
    val paths: List<VectorPath>,
    /**
     * SVG features this document uses that the vector pipeline does not draw. Logged once per file
     * so a gap in coverage is visible rather than silent.
     */
    val skipped: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = paths.isEmpty()

    companion object {
        val EMPTY = VectorScene(1.0, 1.0, emptyList())
    }
}

/**
 * A 2x3 affine, as SVG writes them. Contours are transformed on their way out of the reader, so
 * this never escapes parsing.
 */
class Affine(
    val a: Double = 1.0,
    val b: Double = 0.0,
    val c: Double = 0.0,
    val d: Double = 1.0,
    val e: Double = 0.0,
    val f: Double = 0.0,
) {
    fun map(p: Pt): Pt = Pt(a * p.x + c * p.y + e, b * p.x + d * p.y + f)

    /** [other] applied first, then this. */
    fun times(other: Affine): Affine = Affine(
        a * other.a + c * other.b,
        b * other.a + d * other.b,
        a * other.c + c * other.d,
        b * other.c + d * other.d,
        a * other.e + c * other.f + e,
        b * other.e + d * other.f + f,
    )

    /** How much this scales lengths by, as one number: the square root of the area factor. */
    fun lengthScale(): Double {
        val det = kotlin.math.abs(a * d - b * c)
        return if (det <= 0.0) 0.0 else kotlin.math.sqrt(det)
    }

    val isIdentity: Boolean
        get() = a == 1.0 && b == 0.0 && c == 0.0 && d == 1.0 && e == 0.0 && f == 0.0

    companion object {
        val IDENTITY = Affine()

        fun translate(tx: Double, ty: Double) = Affine(e = tx, f = ty)

        fun scale(sx: Double, sy: Double) = Affine(a = sx, d = sy)
    }
}
