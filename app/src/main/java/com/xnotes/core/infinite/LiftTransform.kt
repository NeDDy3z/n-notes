package com.xnotes.core.infinite

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where a dragged selection is drawn, relative to where its triangles actually sit: the linear map
 * ([a], [b], [c], [d]) about [pivot], then a shift by ([dx], [dy]).
 *
 * This is the whole of what a drag costs. Every part of it reaches the vertex shader as uniforms, so
 * moving, turning or resizing a selection is the same price for one stroke and for a thousand,
 * instead of a re-tessellation and a re-upload of every selected item on every touch sample.
 *
 * The shader can match what the model bakes because ink stores its width as a spine offset per
 * vertex. The model maps every sample through the transform and scales the width by one scalar, the
 * transform's [linearScale]. The shader recovers the ribbon's direction from the spine, maps it, and
 * re-lays the spine across the mapped direction at the scaled length, which is the same ribbon. What
 * it cannot match is anything the model rebuilds rather than maps, which is why images and text are
 * never lifted.
 */
data class LiftTransform(
    val dx: Double = 0.0,
    val dy: Double = 0.0,
    val pivot: Pt = Pt.ZERO,
    /** The linear part, laid out like [Affine]: `(x, y) -> (a x + c y, b x + d y)`. */
    val a: Double = 1.0,
    val b: Double = 0.0,
    val c: Double = 0.0,
    val d: Double = 1.0,
) {
    val isLinearIdentity: Boolean get() = a == 1.0 && b == 0.0 && c == 0.0 && d == 1.0

    val isIdentity: Boolean get() = dx == 0.0 && dy == 0.0 && isLinearIdentity

    val determinant: Double get() = a * d - b * c

    /** The one factor the model scales every stroke width by. 1.0 for a turn or a plain shift. */
    val linearScale: Double get() = sqrt(abs(determinant))

    fun apply(p: Pt): Pt {
        if (isLinearIdentity) return Pt(p.x + dx, p.y + dy)
        val rx = p.x - pivot.x
        val ry = p.y - pivot.y
        return Pt(pivot.x + a * rx + c * ry + dx, pivot.y + b * rx + d * ry + dy)
    }

    /** The upright box around a transformed rectangle, for culling and for the blur's scissor. */
    fun bounds(r: Rect): Rect {
        if (isLinearIdentity) return r.translate(dx, dy)
        return Rect.bounding(
            listOf(
                apply(Pt(r.left, r.top)),
                apply(Pt(r.right, r.top)),
                apply(Pt(r.right, r.bottom)),
                apply(Pt(r.left, r.bottom)),
            ),
        )
    }

    /**
     * What is left of this drag since a cached picture was built at [built], as its own transform.
     * The halo layer is blurred once and then read back through this, so it has to compose exactly:
     * `this == since(built) after built`.
     */
    fun since(built: LiftTransform): LiftTransform {
        val det = built.determinant
        if (abs(det) < 1e-12) return this
        // The residual's linear part is this one after the built one's inverse.
        val ia = built.d / det
        val ib = -built.b / det
        val ic = -built.c / det
        val id = built.a / det
        val ma = a * ia + c * ib
        val mb = b * ia + d * ib
        val mc = a * ic + c * id
        val md = b * ic + d * id
        // The shift the built picture already carries has to come back out through that same map.
        return LiftTransform(
            dx - (ma * built.dx + mc * built.dy),
            dy - (mb * built.dx + md * built.dy),
            pivot, ma, mb, mc, md,
        )
    }

    /** The inverse linear part, which is what a texture read needs: destination back to source. */
    fun inverseLinear(): DoubleArray {
        val det = determinant
        if (abs(det) < 1e-12) return doubleArrayOf(1.0, 0.0, 0.0, 1.0)
        return doubleArrayOf(d / det, -b / det, -c / det, a / det)
    }

    companion object {
        val NONE = LiftTransform()

        /** A plain shift, which is what dragging a selection about is. */
        fun shift(dx: Double, dy: Double): LiftTransform = LiftTransform(dx, dy)

        /** A turn about [pivot], which leaves every stroke exactly as wide as it was. */
        fun turn(pivot: Pt, angle: Double): LiftTransform {
            val cs = cos(angle)
            val sn = sin(angle)
            return LiftTransform(0.0, 0.0, pivot, cs, sn, -sn, cs)
        }

        /** Any affine, re-expressed about [pivot] so the shader never handles a large translation. */
        fun of(t: Affine, pivot: Pt): LiftTransform {
            val moved = t.apply(pivot)
            return LiftTransform(
                moved.x - pivot.x, moved.y - pivot.y,
                pivot, t.a, t.b, t.c, t.d,
            )
        }
    }
}
