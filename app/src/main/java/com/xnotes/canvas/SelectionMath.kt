package com.xnotes.canvas

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Page

/** A selected item plus the index of the page it lives on. */
data class Selected(val pageIndex: Int, val item: CanvasItem)

/** Pure selection-membership tests (spec 06 §6–7). */
object SelectionMath {

    /**
     * Band selection over a flat item list, for a canvas with no pages: every item whose bounds
     * meet [band]. The paged overloads below are the same test with a page-space translation in
     * front of it; sharing the rule is the point, so the two canvases select alike.
     */
    fun bandMembers(items: List<CanvasItem>, band: Rect): List<CanvasItem> =
        items.filter { it.bounds().intersects(band) }

    /** Lasso selection over a flat item list: every item whose centroid lies inside [polygon]. */
    fun lassoMembers(items: List<CanvasItem>, polygon: List<Pt>): List<CanvasItem> {
        if (polygon.size < 3) return emptyList()
        return items.filter { Geometry.pointInPolygon(polygon, it.centroid()) }
    }

    /**
     * Band selection: every item whose content-space bounds intersect [band]. [toContentRect]
     * maps an item's page-space bounds on page i into content space (a plain page-rect
     * translation, plus the display rotation when the view is rotated).
     */
    fun bandMembers(
        pages: List<Page>,
        pageRects: List<Rect>,
        band: Rect,
        toContentRect: (Int, Rect) -> Rect = { i, r -> r.translate(pageRects[i].left, pageRects[i].top) },
    ): List<Selected> {
        val out = ArrayList<Selected>()
        for (i in pages.indices) {
            val pr = pageRects.getOrNull(i) ?: continue
            // Skip a page the band cannot reach, before touching its items. item.bounds() builds the
            // stroke's ribbon, so without this one small drag rebuilds every stroke in the document
            // and undoes the canvas's geometry eviction. Outset by OFF_PAGE_SLACK because an item
            // may paint past its page edge (a neon glow, ink drawn over the margin).
            if (!pr.outset(OFF_PAGE_SLACK).intersects(band)) continue
            for (item in pages[i].items) {
                if (toContentRect(i, item.bounds()).intersects(band)) out.add(Selected(i, item))
            }
        }
        return out
    }

    /** How far past its page edge an item may reach and still be reachable by a band that misses
     *  the page rect. Covers neon bloom and ink drawn over the margin. */
    const val OFF_PAGE_SLACK = 64.0

    /**
     * Lasso selection: every item whose content-space centroid lies inside [polygon] (even-odd).
     * [toContent] maps a page-space point on page i into content space, like [bandMembers].
     */
    fun lassoMembers(
        pages: List<Page>,
        pageRects: List<Rect>,
        polygon: List<Pt>,
        toContent: (Int, Pt) -> Pt = { i, p -> Pt(p.x + pageRects[i].left, p.y + pageRects[i].top) },
    ): List<Selected> {
        if (polygon.size < 3) return emptyList()
        val out = ArrayList<Selected>()
        for (i in pages.indices) {
            if (pageRects.getOrNull(i) == null) continue
            for (item in pages[i].items) {
                if (Geometry.pointInPolygon(polygon, toContent(i, item.centroid()))) {
                    out.add(Selected(i, item))
                }
            }
        }
        return out
    }
}
