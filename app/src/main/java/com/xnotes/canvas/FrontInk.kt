package com.xnotes.canvas

import android.graphics.Bitmap
import android.graphics.Rect as AndroidRect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.infinite.PixelRect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.gl.GlWetPad
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Wet ink on the front buffer, for a paged note.
 *
 * The canvas here paints into the window with Skia, so the ink under the pen cannot be quicker than
 * the view tree's next frame. [GlWetPad] is a surface of its own, switched to `EGL_SINGLE_BUFFER`,
 * where a `glFlush` puts pixels on the glass without a queue or a refresh boundary in between. This
 * is the piece that decides what goes there, feeds it, and hands the stroke back at pen up.
 *
 * ### The view, as one scroll
 *
 * A page reaches the screen as `(p + pageRect.topLeft + insets) * zoom + origin`, which is a scroll
 * and a zoom and nothing else while the view is upright, so the pad needs no notion of pages at
 * all: the whole page transform bakes down into the scroll it is given at pen down. A *rotated*
 * view is the one case that does not fit, and it keeps the ordinary path.
 *
 * ### The handover
 *
 * At pen up the committed stroke is held out of the ink cache while the canvas as it stands is
 * captured and drawn *under* the ink already on the pad. The pad then holds an opaque copy of the
 * composite the screen was showing, the canvas underneath can take the stroke unseen, and taking
 * the pad down is a no-op on any refresh. Without it the two layers have to be raced, and one
 * refresh either way is a blink or a doubled antialiased edge.
 */
class FrontInk(
    private val state: CanvasState,
    private val view: CanvasView,
    val pad: GlWetPad,
) {

    private val handler = Handler(Looper.getMainLooper())

    /** The stroke whose runs are already on the pad, by identity. */
    private var owner: Stroke? = null

    /** Ribbon points of [owner] already meshed, and the arc those points spent. */
    private var meshed = 0
    private var arc = 0.0

    /** Points a run holds; a front-buffered stroke uses a much shorter one (see [decide]). */
    private var runPoints = WET_RUN_POINTS

    /** Whether this stroke's route has been chosen. */
    private var decided = false

    /** Whether the pad is painting the stroke under the pen. */
    var live = false
        private set

    /**
     * The committed item the pad is still showing, kept out of the ink cache until it is not.
     *
     * The document and the undo stack take it immediately; only its pixels wait, so nothing about
     * the edit is delayed by the handover.
     */
    var held: CanvasItem? = null
        private set

    private var heldPage: Page? = null

    /** Bumped by anything that outdates a handover in flight, so a stale capture cannot land. */
    private var handoffGen = 0

    /** What the front buffer is doing, for the debug HUD, or null when there is no pad. */
    val hud: String? get() = if (pad.ready) pad.hud else null

    // --- the stroke under the pen ---

    /**
     * Publish the stroke under the pen, in two pieces where the pen allows it.
     *
     * The run that has stopped moving is uploaded once and never again; only the few points still
     * in play are rebuilt each present. The two overlap by a point so the quad bridging them
     * belongs to the later one and no gap can open on the join.
     */
    fun wet(stroke: Stroke?, pageIndex: Int?) {
        if (stroke == null || pageIndex == null) return abandon()
        val ribbon = stroke.wetRibbon
        // Ink whose runs cannot simply be laid over each other keeps the ordinary path, which
        // composites the whole stroke every frame and is what the wet cache is for.
        if (ribbon == null || !stroke.wetCacheable) return abandon()
        if (owner !== stroke) {
            abandon()
            owner = stroke
        }
        if (!decided) decide(stroke, pageIndex)
        if (!live) return

        val settled = ribbon.settledCount
        if (settled - meshed >= runPoints) {
            val from = (meshed - 1).coerceAtLeast(0)
            ItemMesher.meshRun(stroke, ribbon, from, settled - from, arc)?.let { pad.appendRun(listOf(it)) }
            for (k in from + 1 until settled) {
                arc += hypot(ribbon.cx(k) - ribbon.cx(k - 1), ribbon.cy(k) - ribbon.cy(k - 1))
            }
            meshed = settled
        }
        val tailFrom = (meshed - 1).coerceAtLeast(0)
        val tail = ItemMesher.meshRun(stroke, ribbon, tailFrom, ribbon.pointCount - tailFrom, arc)
        pad.setTail(if (tail == null) emptyList() else listOf(tail))
    }

    /** Give the pad back with nothing to hand over: a stroke abandoned, snapped to a shape, or gone. */
    fun abandon() {
        owner = null
        meshed = 0
        arc = 0.0
        decided = false
        runPoints = WET_RUN_POINTS
        if (!live) return
        live = false
        view.setUnbufferedStylus(false)
        pad.endStroke()
    }

    /**
     * Whether this stroke can go on the front buffer, decided from what it meshes to rather than
     * from the tool.
     *
     * The pad has no copy of what is under it, so anything that composites against the page cannot
     * live there. Plain triangles can, and that is what a pen and a pencil are.
     */
    private fun decide(stroke: Stroke, pageIndex: Int) {
        decided = true
        if (ItemMesher.passFor(stroke) != InkPass.OPAQUE) return
        // A rotated view is a rotation, and the ink shader has room for a scroll and a zoom.
        if (state.rotationDeg != 0) return
        val page = state.document.pages.getOrNull(pageIndex) ?: return
        val rect = state.pageRects.getOrNull(pageIndex) ?: return
        val zoom = state.zoom
        if (zoom <= 0.0 || !zoom.isFinite()) return
        val origin = state.origin()
        val insets = state.insets(page)
        val scrollX = -(rect.left + insets.left + origin.x / zoom)
        val scrollY = -(rect.top + insets.top + origin.y / zoom)
        // A stroke committed moments ago may still be held: the pad is about to be cleared for
        // this one, so its pixels are gone either way and the canvas has to take it now.
        settle()
        live = pad.beginStroke(scrollX, scrollY, zoom, SAMPLES, paperClip(rect))
        if (!live) return
        // A shorter run on the front buffer, because there the tail is what a present has to clear
        // and rebuild, and its extent is the size of everything that present does.
        runPoints = FRONT_RUN_POINTS
        view.setUnbufferedStylus(true)
    }

    /** The paper's own pixels, so ink running off the page is cut at the edge as the canvas cuts it. */
    private fun paperClip(rect: Rect): PixelRect {
        val topLeft = state.contentToViewport(Pt(rect.left, rect.top))
        val bottomRight = state.contentToViewport(Pt(rect.right, rect.bottom))
        return PixelRect(
            floor(topLeft.x).toInt(), floor(topLeft.y).toInt(),
            ceil(bottomRight.x).toInt(), ceil(bottomRight.y).toInt(),
        )
    }

    // --- the handover ---

    /**
     * Take the just-committed [item] if the pad is showing it, so the caller leaves it out of the
     * ink cache. Returns false when the stroke was never on the front buffer and the caller should
     * file it as it always has.
     */
    fun hold(item: CanvasItem, page: Page): Boolean {
        if (!live) return false
        abandonToHold()
        // Any handover still in flight belongs to an older stroke, whose pixels this stroke has
        // already painted over. Give it to the canvas, and leave the pad to this one.
        settle()
        held = item
        heldPage = page
        capture()
        return true
    }

    /** Stop drawing but keep the pixels: they are the only copy of the stroke until the canvas has it. */
    private fun abandonToHold() {
        owner = null
        meshed = 0
        arc = 0.0
        decided = false
        runPoints = WET_RUN_POINTS
        live = false
        pad.freeze()
        view.setUnbufferedStylus(false)
    }

    /**
     * Take the canvas as it stands, put it behind the ink on the pad, and only then hand over.
     *
     * Every failure path ends the same way, because a handover that is merely timed is far better
     * than a stroke that never reaches the canvas.
     */
    private fun capture() {
        val box = pad.strokeBox() ?: return finish()
        val window = window() ?: return finish()
        val src = inWindow(window, box) ?: return finish()
        val shot = try {
            Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return finish()
        }
        val gen = handoffGen
        // However the capture goes, the stroke reaches the canvas: late is a blink, never is a lost
        // stroke. One Runnable, held, because a fresh method reference cannot be cancelled.
        val timeout = Runnable { if (gen == handoffGen) finish() }
        handler.postDelayed(timeout, CAPTURE_TIMEOUT_MS)
        try {
            PixelCopy.request(window, src, shot, { result ->
                handler.removeCallbacks(timeout)
                if (gen != handoffGen) return@request
                if (result != PixelCopy.SUCCESS) return@request finish()
                pad.coverWith(shot, box) { if (gen == handoffGen) finish() }
            }, handler)
        } catch (e: IllegalArgumentException) {
            handler.removeCallbacks(timeout)
            finish()
        }
    }

    /** Let the held item reach the canvas, and take the pad down once that frame is out. */
    private fun finish() {
        if (!settle()) return
        view.publishThen { pad.release() }
    }

    /**
     * Give the held item back to the canvas, leaving the pad alone, and outdate whatever capture
     * was still coming for it.
     */
    private fun settle(): Boolean {
        handoffGen++
        val item = held ?: return false
        val page = heldPage
        held = null
        heldPage = null
        if (page != null) state.appendToCache(page, item)
        view.requestRender()
        return true
    }

    /** The window the canvas draws into, which is what a capture of what is under the ink comes from. */
    private fun window(): Window? {
        var context: android.content.Context? = view.context
        while (context is android.content.ContextWrapper) {
            if (context is android.app.Activity) return context.window
            context = context.baseContext
        }
        return null
    }

    /**
     * [box], in the canvas's pixels, as window pixels inside the window's own bounds.
     *
     * The pad is a layer above the window, so a copy of the window is the canvas *without* the ink
     * that is on the pad, which is exactly what has to go behind it.
     */
    private fun inWindow(window: Window, box: AndroidRect): AndroidRect? {
        val decor = window.peekDecorView() ?: return null
        val at = IntArray(2)
        view.getLocationInWindow(at)
        val src = AndroidRect(box.left + at[0], box.top + at[1], box.right + at[0], box.bottom + at[1])
        // Whole or not at all: a capture cut down to the window would be stretched back over the
        // box it was taken from, which is worse than not covering at all.
        if (src.isEmpty || src.left < 0 || src.top < 0) return null
        if (src.right > decor.width || src.bottom > decor.height) return null
        return src
    }

    private companion object {
        /** Points a run holds on the ordinary path, matching the wet cache's own bake size. */
        const val WET_RUN_POINTS = 96

        const val FRONT_RUN_POINTS = 8

        /** Samples the pad antialiases live ink with; the canvas itself draws through Skia. */
        const val SAMPLES = 4

        /** How long the handover waits for the capture before giving the stroke back anyway (ms). */
        const val CAPTURE_TIMEOUT_MS = 120L
    }
}
