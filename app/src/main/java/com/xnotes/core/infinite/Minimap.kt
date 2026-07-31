package com.xnotes.core.infinite

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect

/**
 * Where the minimap sits and what it maps.
 *
 * On an unbounded canvas a minimap cannot show "the document", because there is no edge to it. It
 * shows the extent of what has actually been drawn, unioned with where the viewport currently is,
 * so panning off into blank space still leaves the marker somewhere meaningful rather than pinning
 * it to the border.
 *
 * Pure geometry, so the mapping both ways is unit-testable and the renderer only draws.
 */
object Minimap {

    /** Panel size and inset from the corner, in device pixels. */
    const val WIDTH_PX = 168.0
    const val HEIGHT_PX = 120.0
    const val MARGIN_PX = 14.0

    /** Space left inside the panel around the mapped extent. */
    const val PAD_PX = 8.0

    /** The panel's own rectangle, in device pixels, bottom-right of a viewport that size. */
    fun panel(viewportW: Int, viewportH: Int): Rect = Rect(
        viewportW - WIDTH_PX - MARGIN_PX,
        viewportH - HEIGHT_PX - MARGIN_PX,
        WIDTH_PX,
        HEIGHT_PX,
    )

    /**
     * The content region the panel maps: everything drawn, plus wherever the view is now, so the
     * viewport marker is always inside the panel.
     */
    fun mappedExtent(contentBounds: Rect?, visible: Rect): Rect {
        val union = contentBounds?.union(visible) ?: visible
        // A degenerate extent (a single dot, an empty canvas) still needs an area to map into.
        val w = if (union.w > 1e-6) union.w else visible.w.coerceAtLeast(1.0)
        val h = if (union.h > 1e-6) union.h else visible.h.coerceAtLeast(1.0)
        return Rect(union.centerX - w / 2.0, union.centerY - h / 2.0, w, h)
    }

    /** Device pixels per content pixel inside the panel, fitting [extent] with [PAD_PX] to spare. */
    fun scaleFor(extent: Rect, panel: Rect): Double {
        if (extent.w <= 0.0 || extent.h <= 0.0) return 1.0
        val availW = (panel.w - 2 * PAD_PX).coerceAtLeast(1.0)
        val availH = (panel.h - 2 * PAD_PX).coerceAtLeast(1.0)
        return minOf(availW / extent.w, availH / extent.h)
    }

    /** A content point mapped into device pixels inside the panel. */
    fun toPanel(p: Pt, extent: Rect, panel: Rect): Pt {
        val scale = scaleFor(extent, panel)
        return Pt(
            panel.centerX + (p.x - extent.centerX) * scale,
            panel.centerY + (p.y - extent.centerY) * scale,
        )
    }

    /** A content rectangle mapped into device pixels inside the panel. */
    fun toPanel(r: Rect, extent: Rect, panel: Rect): Rect {
        val scale = scaleFor(extent, panel)
        val topLeft = toPanel(Pt(r.left, r.top), extent, panel)
        return Rect(topLeft.x, topLeft.y, r.w * scale, r.h * scale)
    }

    /** The content point a tap inside the panel means, so tapping the map jumps the view there. */
    fun toContent(p: Pt, extent: Rect, panel: Rect): Pt {
        val scale = scaleFor(extent, panel)
        if (scale <= 0.0) return Pt(extent.centerX, extent.centerY)
        return Pt(
            extent.centerX + (p.x - panel.centerX) / scale,
            extent.centerY + (p.y - panel.centerY) / scale,
        )
    }
}
