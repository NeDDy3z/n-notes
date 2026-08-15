package com.xnotes.core.geometry

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A 2D affine transform mapping `(x, y) -> (a*x + c*y + e, b*x + d*y + f)`, used to bake a
 * selection scale or rotation into item geometry. The canvas [com.xnotes.core.pal.Renderer] is
 * deliberately axis-aligned (translate + scale only, never rotation), so a rotation is never a
 * render-time transform: it is baked into the actual points through this class instead.
 */
data class Affine(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double,
) {
    fun apply(p: Pt): Pt = Pt(a * p.x + c * p.y + e, b * p.x + d * p.y + f)

    /** this ∘ [inner]: the transform that applies [inner] first, then this. */
    fun compose(inner: Affine): Affine = Affine(
        a * inner.a + c * inner.b,
        b * inner.a + d * inner.b,
        a * inner.c + c * inner.d,
        b * inner.c + d * inner.d,
        a * inner.e + c * inner.f + e,
        b * inner.e + d * inner.f + f,
    )

    /**
     * This content-space transform expressed in a frame translated by [offset], so it can be
     * applied to page-local geometry on a page whose origin is [offset]. The linear part is
     * unchanged; only the translation shifts. Equivalent to `translate(-offset) ∘ this ∘
     * translate(offset)`.
     */
    fun translatedFrame(offset: Pt): Affine = Affine(
        a, b, c, d,
        a * offset.x + c * offset.y + e - offset.x,
        b * offset.x + d * offset.y + f - offset.y,
    )

    /** Linear scale of each axis (sign-free); a pure rotation gives 1.0, 1.0. */
    val scaleX: Double get() = hypot(a, b)
    val scaleY: Double get() = hypot(c, d)

    /** Signed area scale; the uniform linear factor is `sqrt(|determinant|)`. */
    val determinant: Double get() = a * d - b * c

    /** Uniform linear scale factor (the geometric mean of the axis scales). 1.0 for a rotation. */
    val linearScale: Double get() = sqrt(abs(determinant))

    /** No rotation or shear: a pure scale-and-translate (the off-diagonal terms are zero). */
    val isAxisAligned: Boolean get() = b == 0.0 && c == 0.0

    /**
     * The turn this transform carries, in radians clockwise: the rotation of its polar
     * decomposition. Zero for any pure scale, the sheared single-axis scale of a turned selection
     * box included, whose linear part is symmetric. Items that store an angle instead of baking a
     * rotation into points (an image) read their turn from here.
     */
    val rotationAngle: Double get() = atan2(b - c, a + d)

    /** An axis-aligned scale with equal factors on both axes. */
    val isUniformScale: Boolean get() = isAxisAligned && a == d

    companion object {
        val IDENTITY = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        /** Scale by ([sx], [sy]) about [pivot], which stays fixed. */
        fun scaleAbout(pivot: Pt, sx: Double, sy: Double): Affine =
            Affine(sx, 0.0, 0.0, sy, pivot.x * (1.0 - sx), pivot.y * (1.0 - sy))

        /** Rotate by [radians] (clockwise in screen space) about [center], which stays fixed. */
        fun rotateAbout(center: Pt, radians: Double): Affine {
            val cs = cos(radians)
            val sn = sin(radians)
            return Affine(
                cs, sn, -sn, cs,
                center.x - cs * center.x + sn * center.y,
                center.y - sn * center.x - cs * center.y,
            )
        }

        /**
         * Scale by ([sx], [sy]) along axes rotated by [axisAngle] (radians), about [anchor], which
         * stays fixed. A uniform scale reduces to [scaleAbout] (rotation-invariant); a single-axis
         * scale of a turned box becomes a shear in world axes. Used to resize an oriented selection
         * box in its own frame. Its `determinant` is `sx*sy`, so width still scales by `sqrt(sx*sy)`.
         */
        fun scaleAlongAxes(anchor: Pt, axisAngle: Double, sx: Double, sy: Double): Affine {
            val cs = cos(axisAngle)
            val sn = sin(axisAngle)
            val cc = cs * cs
            val ss = sn * sn
            val csn = cs * sn
            val m00 = sx * cc + sy * ss
            val m11 = sx * ss + sy * cc
            val m01 = (sx - sy) * csn // symmetric: the (0,1) and (1,0) terms are equal
            return Affine(
                m00, m01, m01, m11,
                anchor.x - (m00 * anchor.x + m01 * anchor.y),
                anchor.y - (m01 * anchor.x + m11 * anchor.y),
            )
        }
    }
}
