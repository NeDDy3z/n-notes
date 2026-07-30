package com.xnotes.core.infinite

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * The window onto an unbounded canvas: a zoom factor plus the content coordinate sitting at the
 * viewport's top-left corner. Screen position is `(content - scroll) * zoom`, so both panning and
 * zooming are a two-number change that the GL renderer hands straight to a shader uniform. There
 * is no page layout to lay out against and no scroll clamp: the canvas runs in every direction.
 *
 * Pure math with no rendering state, so the transforms unit-test on the plain JVM.
 */
class CanvasViewport {

    /** Viewport size in device pixels; set by the view on layout. */
    var widthPx: Int = 0
    var heightPx: Int = 0

    var zoom: Double = 1.0
        set(value) {
            field = value.coerceIn(minZoom, maxZoom)
        }

    /** Content coordinate at the viewport's left edge. */
    var scrollX: Double = 0.0

    /** Content coordinate at the viewport's top edge. */
    var scrollY: Double = 0.0

    var minZoom: Double = MIN_ZOOM
    var maxZoom: Double = MAX_ZOOM

    /** Clamp [zoom] back into the current limits, after the limits themselves moved. */
    fun clampZoom() {
        zoom = zoom
    }

    // --- transforms ---

    fun contentToViewport(p: Pt): Pt = Pt((p.x - scrollX) * zoom, (p.y - scrollY) * zoom)

    fun viewportToContent(p: Pt): Pt = Pt(p.x / zoom + scrollX, p.y / zoom + scrollY)

    fun contentToViewport(r: Rect): Rect =
        Rect((r.x - scrollX) * zoom, (r.y - scrollY) * zoom, r.w * zoom, r.h * zoom)

    fun viewportToContent(r: Rect): Rect =
        Rect(r.x / zoom + scrollX, r.y / zoom + scrollY, r.w / zoom, r.h / zoom)

    /** The content rectangle currently on screen. */
    fun visibleContentRect(): Rect =
        Rect(scrollX, scrollY, widthPx / zoom, heightPx / zoom)

    /** [visibleContentRect] grown by [margin] device pixels, for culling with a little slack. */
    fun cullRect(margin: Double = 0.0): Rect = visibleContentRect().outset(margin / zoom)

    /** Content pixels per device pixel at the current zoom, the scale a stroke width is drawn at. */
    val contentPxPerDevicePx: Double get() = 1.0 / zoom

    // --- movement ---

    /** Drag the content with a finger: [dx]/[dy] are viewport-pixel deltas of the pointer. */
    fun panByViewport(dx: Double, dy: Double) {
        scrollX -= dx / zoom
        scrollY -= dy / zoom
    }

    fun panByContent(dx: Double, dy: Double) {
        scrollX += dx
        scrollY += dy
    }

    /**
     * Zoom to [target] while holding the content point currently under viewport pixel
     * ([vx], [vy]) in place, which is what a pinch's focal point wants. Returns the zoom
     * actually reached after clamping.
     */
    fun zoomAround(vx: Double, vy: Double, target: Double): Double {
        val anchor = viewportToContent(Pt(vx, vy))
        zoom = target
        scrollX = anchor.x - vx / zoom
        scrollY = anchor.y - vy / zoom
        return zoom
    }

    /** [zoomAround] the viewport's centre, for keyboard and button zooming. */
    fun zoomAroundCenter(target: Double): Double =
        zoomAround(widthPx / 2.0, heightPx / 2.0, target)

    /** Put content point ([cx], [cy]) at the viewport centre, leaving zoom alone. */
    fun centerOn(cx: Double, cy: Double) {
        scrollX = cx - widthPx / (2.0 * zoom)
        scrollY = cy - heightPx / (2.0 * zoom)
    }

    val centerContent: Pt
        get() = Pt(scrollX + widthPx / (2.0 * zoom), scrollY + heightPx / (2.0 * zoom))

    /**
     * Frame [rect] with [padPx] device pixels of margin on every side. A degenerate or empty
     * rect just centres at the current zoom, so "fit" on an empty canvas is not a divide by zero.
     */
    fun fit(rect: Rect, padPx: Double = DEFAULT_FIT_PAD_PX) {
        if (widthPx <= 0 || heightPx <= 0) return
        val availW = max(1.0, widthPx - 2 * padPx)
        val availH = max(1.0, heightPx - 2 * padPx)
        if (rect.w > 0.0 && rect.h > 0.0) {
            zoom = min(availW / rect.w, availH / rect.h)
        } else if (rect.w > 0.0) {
            zoom = availW / rect.w
        } else if (rect.h > 0.0) {
            zoom = availH / rect.h
        }
        centerOn(rect.centerX, rect.centerY)
    }

    // --- waypoints ---

    /** Capture the current view as a resolution-independent centre plus zoom. */
    fun toWaypoint(name: String = ""): Waypoint =
        Waypoint(name, centerContent.x, centerContent.y, zoom)

    /** Restore a view captured by [toWaypoint]; the centre is preserved across screen sizes. */
    fun apply(w: Waypoint) {
        zoom = w.zoom
        centerOn(w.cx, w.cy)
    }

    companion object {
        /** Zoomed all the way out: a wall of content shrinks to a thumbnail. */
        const val MIN_ZOOM = 0.02

        /** Zoomed all the way in: a hair of ink fills the screen. */
        const val MAX_ZOOM = 64.0

        /** Device-pixel margin left around the content by [fit]. */
        const val DEFAULT_FIT_PAD_PX = 48.0
    }
}
