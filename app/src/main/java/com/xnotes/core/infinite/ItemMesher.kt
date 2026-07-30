package com.xnotes.core.infinite

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.tools.Tool

/** How an item's geometry has to reach the framebuffer. */
enum class InkPass {
    /** Straight into the batched draw; the colour is baked into the vertices. */
    OPAQUE,

    /** Stencilled, then covered once at its own alpha, so self-overlap cannot darken. */
    TRANSLUCENT,

    /** Like [TRANSLUCENT] but multiplied, and always drawn last, exactly as the paged canvas does. */
    MULTIPLY,
}

/** An item turned into triangles, with everything the renderer needs to draw it. */
class MeshedItem(
    val mesh: MeshData,
    /** Ink colour including its alpha; the renderer bakes or covers with it depending on [pass]. */
    val color: Rgba,
    val pass: InkPass,
    /** Content-space region the item paints into, used for culling and for the cover quad. */
    val bounds: Rect,
)

/**
 * Turns a model item into the triangles the GL canvas draws. Pure Kotlin so the pass choice and
 * the geometry are both unit-testable; the renderer only uploads what comes out.
 *
 * The pass choice is where parity with the paged canvas lives. Opaque ink can simply be drawn,
 * because overlapping the same colour twice is still that colour. Anything translucent cannot: its
 * ribbon and its round ends overlap, and a second blend would leave the crossing darker than the
 * stroke, which the paged canvas avoids by accumulating the stroke opaquely and compositing once.
 */
object ItemMesher {

    fun mesh(item: CanvasItem, tolerance: Double = StrokeTessellator.DEFAULT_TOLERANCE): MeshedItem? =
        when (item) {
            is Stroke -> meshStroke(item, tolerance)
            else -> null // shapes and images arrive at their own stages
        }

    private fun meshStroke(stroke: Stroke, tolerance: Double): MeshedItem? {
        if (stroke.isEmpty) return null
        val mesh = StrokeTessellator.tessellate(stroke.geometry(), tolerance)
        if (mesh.isEmpty) return null
        return MeshedItem(mesh, stroke.renderColor, passFor(stroke), stroke.paintBounds())
    }

    /** The pass a stroke has to take, from its tool and its resolved ink alpha. */
    fun passFor(stroke: Stroke): InkPass = when {
        stroke.tool == Tool.HIGHLIGHTER -> InkPass.MULTIPLY
        stroke.renderColor.a >= 255 -> InkPass.OPAQUE
        else -> InkPass.TRANSLUCENT
    }

    /** [c] lerped toward white by `1 - alpha`: the colour a multiply blend tints by [alpha]. */
    fun multiplyColor(c: Rgba, alpha: Double): Rgba {
        val f = alpha.coerceIn(0.0, 1.0)
        return Rgba(
            (255 - (255 - c.r) * f).toInt().coerceIn(0, 255),
            (255 - (255 - c.g) * f).toInt().coerceIn(0, 255),
            (255 - (255 - c.b) * f).toInt().coerceIn(0, 255),
            255,
        )
    }
}
