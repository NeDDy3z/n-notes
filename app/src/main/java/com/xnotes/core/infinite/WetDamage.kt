package com.xnotes.core.infinite

import com.xnotes.core.geometry.Rect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A rectangle of whole device pixels. Mutable, because a present unions into it rather than
 * allocating a new one under the pen.
 */
class PixelRect(
    var left: Int = 0,
    var top: Int = 0,
    var right: Int = 0,
    var bottom: Int = 0,
) {
    val isEmpty: Boolean get() = right <= left || bottom <= top
    val width: Int get() = max(right - left, 0)
    val height: Int get() = max(bottom - top, 0)

    fun clear() = set(0, 0, 0, 0)

    fun set(l: Int, t: Int, r: Int, b: Int) {
        left = l
        top = t
        right = r
        bottom = b
    }

    fun set(o: PixelRect) = set(o.left, o.top, o.right, o.bottom)

    /** Grow to hold [o] as well. An empty rectangle contributes nothing. */
    fun union(o: PixelRect) {
        if (o.isEmpty) return
        if (isEmpty) {
            set(o)
            return
        }
        left = min(left, o.left)
        top = min(top, o.top)
        right = max(right, o.right)
        bottom = max(bottom, o.bottom)
    }

    /** Cut down to a surface [w] by [h], which can leave it empty. */
    fun clampTo(w: Int, h: Int) {
        left = left.coerceIn(0, w)
        right = right.coerceIn(0, w)
        top = top.coerceIn(0, h)
        bottom = bottom.coerceIn(0, h)
    }

    /** Whether this and [o] share a pixel. An empty rectangle meets nothing. */
    fun intersects(o: PixelRect): Boolean =
        !isEmpty && !o.isEmpty && left < o.right && o.left < right && top < o.bottom && o.top < bottom

    fun sameAs(o: PixelRect): Boolean =
        left == o.left && top == o.top && right == o.right && bottom == o.bottom

    override fun toString(): String = "($left,$top..$right,$bottom)"
}

/**
 * Where a front-buffered present is allowed to write.
 *
 * The surface is the one the panel is scanning out, so a present may only touch pixels it is about
 * to put back correctly: clearing more than that shows as a flicker of missing ink. The region that
 * has to be restored is the tail's own box plus the box the tail occupied last time, since the tail
 * is rebuilt from scratch every time and can retract.
 *
 * Everything here rounds *outward*. A box a fraction of a pixel short leaves a rim of the old tail
 * behind, which reads as a smear following the nib, and the extra pixel costs nothing.
 */
object WetDamage {

    /** Pixels the box grows on every side, covering antialiasing on its own edge. */
    const val OUTSET = 1.0

    /** [bounds] in content space through a scroll/zoom view, as the whole pixels it can touch. */
    fun map(
        bounds: Rect,
        scrollX: Double,
        scrollY: Double,
        zoom: Double,
        outset: Double,
        into: PixelRect,
    ): Boolean {
        if (!bounds.x.isFinite() || !bounds.y.isFinite()) return false
        if (!bounds.w.isFinite() || !bounds.h.isFinite()) return false
        val l = (bounds.left - scrollX) * zoom - outset
        val t = (bounds.top - scrollY) * zoom - outset
        val r = (bounds.right - scrollX) * zoom + outset
        val b = (bounds.bottom - scrollY) * zoom + outset
        if (!l.isFinite() || !t.isFinite() || !r.isFinite() || !b.isFinite()) return false
        into.set(
            floor(l).coerceIn(LIMIT_LO, LIMIT_HI).toInt(),
            floor(t).coerceIn(LIMIT_LO, LIMIT_HI).toInt(),
            ceil(r).coerceIn(LIMIT_LO, LIMIT_HI).toInt(),
            ceil(b).coerceIn(LIMIT_LO, LIMIT_HI).toInt(),
        )
        return !into.isEmpty
    }

    private const val LIMIT_LO = -1_000_000.0
    private const val LIMIT_HI = 1_000_000.0
}
