package com.xnotes.core.infinite

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where a dragged selection is drawn, relative to where its triangles actually sit: turned by
 * [angle] radians about [pivot], then shifted by ([dx], [dy]).
 *
 * This is the whole of what a drag costs. Both parts reach the vertex shader as uniforms, so moving
 * or turning a selection is the same price for one stroke and for a thousand, instead of a
 * re-tessellation and a re-upload of every selected item on every touch sample.
 *
 * A rotation is exact here in a way a resize is not. Ink stores its width as a spine offset per
 * vertex, and turning that offset by the same angle leaves its length alone, which is precisely what
 * the model does when it bakes a rotation. A scale would have to change the width too, and the
 * shader cannot know the direction the ribbon runs in, so resizing still goes through the model.
 */
data class LiftTransform(
    val dx: Double = 0.0,
    val dy: Double = 0.0,
    val pivot: Pt = Pt.ZERO,
    val angle: Double = 0.0,
) {
    val cos: Double get() = cos(angle)
    val sin: Double get() = sin(angle)

    val isIdentity: Boolean get() = dx == 0.0 && dy == 0.0 && angle == 0.0

    val turns: Boolean get() = angle != 0.0

    fun apply(p: Pt): Pt {
        if (!turns) return Pt(p.x + dx, p.y + dy)
        val cs = cos
        val sn = sin
        val rx = p.x - pivot.x
        val ry = p.y - pivot.y
        return Pt(pivot.x + rx * cs - ry * sn + dx, pivot.y + rx * sn + ry * cs + dy)
    }

    /** The upright box around a transformed rectangle, for culling and for the blur's scissor. */
    fun bounds(r: Rect): Rect {
        if (!turns) return r.translate(dx, dy)
        return Rect.bounding(
            listOf(
                apply(Pt(r.left, r.top)),
                apply(Pt(r.right, r.top)),
                apply(Pt(r.right, r.bottom)),
                apply(Pt(r.left, r.bottom)),
            ),
        )
    }

    /** What is left of this drag since a cached picture was built at [built], as its own transform. */
    fun since(built: LiftTransform): LiftTransform =
        LiftTransform(dx - built.dx, dy - built.dy, pivot, angle - built.angle)

    companion object {
        val NONE = LiftTransform()
    }
}
