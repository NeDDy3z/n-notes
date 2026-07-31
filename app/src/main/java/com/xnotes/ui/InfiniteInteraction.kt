package com.xnotes.ui

import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.CanvasViewport
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.stroke.StrokeSimplify
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.canvas.InteractionController
import com.xnotes.canvas.StylusButtonLatch
import kotlin.math.exp

/** What the current gesture is doing. */
enum class CanvasPointerMode { IDLE, PAN, PINCH, DRAW }

/**
 * Gestures on the infinite canvas.
 *
 * The navigation half is what an infinite canvas needs before anything else, and it is
 * deliberately the paged canvas's feel rather than a second one: the velocity smoothing, fling
 * friction and start/stop thresholds are read from [InteractionController]'s constants rather than
 * copied, so tuning either canvas tunes both.
 *
 * Everything a pinch changes is two numbers on [CanvasViewport], and the renderer takes them as
 * shader uniforms. There is no cache to invalidate, no settle debounce, and no zoom clamp against
 * a page: zoom runs the full configured range and scroll runs forever in all four directions.
 */
class InfiniteInteraction(
    private val viewport: CanvasViewport,
    private val requestRender: () -> Unit,
    /** Called whenever the view moved, so the host can refresh a zoom readout or schedule a save. */
    private val onViewChanged: () -> Unit = {},
    /** True while a gesture or a glide is live, so the renderer can keep drawing every refresh. */
    private val setInteractive: (Boolean, Boolean) -> Unit = { _, _ -> },
    /** The style the armed tool draws with, including the toolbar's active ink colour. */
    private val configFor: (Tool) -> ToolConfig = { ToolConfig() },
    /** The wet stroke changed: re-tessellate it into the dynamic buffer, or clear it when null. */
    private val onWetStroke: (Stroke?) -> Unit = {},
    /** Pen up on a finished stroke: add it to the document and push the undo command. */
    private val onCommitStroke: (Stroke) -> Unit = {},
    /** Content pixels per dp, so the speed pen judges gesture speed independently of zoom. */
    private val devicePxPerDp: () -> Double = { 1.0 },
) {

    private val choreographer = Choreographer.getInstance()

    /** The armed tool. */
    var tool: Tool = Tool.PEN

    /** Whether a finger draws, or pans instead. Mirrors the paged canvas's preference. */
    var fingerDraws: Boolean = false

    /** Tool the stylus side button arms while it is held; null leaves the button alone. */
    var penButtonTool: Tool? = Tool.ERASER

    private val stylusButtons = StylusButtonLatch()

    var mode = CanvasPointerMode.IDLE
        private set

    // The stroke being drawn, live until the pen lifts.
    private var liveStroke: Stroke? = null
    private var drawingPointerId = -1
    private var drawingIsStylus = false
    private var strokeStartTimeMs = 0L

    // Pan and inertial fling, in viewport px and viewport px/s.
    private var lastPan = Pt.ZERO
    private var lastMoveMs = 0L
    private var panVel = Pt.ZERO
    private var flinging = false
    private var flingVel = Pt.ZERO
    private var lastFlingMs = 0L
    private val flingFrame = Choreographer.FrameCallback { stepFling(it) }

    // Pinch.
    private var pinchInitDist = 1.0
    private var pinchInitZoom = 1.0
    private var pinchAnchorContent = Pt.ZERO

    fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(e)
            MotionEvent.ACTION_MOVE -> handleMove(e)
            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(e)
            MotionEvent.ACTION_UP -> handleUp(e)
            MotionEvent.ACTION_CANCEL -> abortGesture()
        }
        return true
    }

    /**
     * Latch a side button reported only on the hovering generic-motion stream, which is the only
     * place some pens put it.
     */
    fun onGenericMotion(e: MotionEvent) {
        stylusButtons.onGenericMotion(e)
    }

    /** Latch a side button delivered as a key event, which is all Bluetooth and USI pens send. */
    fun onStylusButtonKey(keyCode: Int, down: Boolean): Boolean = stylusButtons.onKey(keyCode, down)

    /** Drop any in-flight gesture and stop a glide, so a document swap cannot bleed into the next. */
    fun resetGestureState() {
        stopFling()
        mode = CanvasPointerMode.IDLE
        panVel = Pt.ZERO
        liveStroke = null
        onWetStroke(null)
        stylusButtons.reset()
    }

    // --- pointer handling ---

    private fun handleDown(e: MotionEvent) {
        stopFling() // a new touch halts any in-progress glide
        drawingPointerId = e.getPointerId(0)
        drawingIsStylus = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        val vx = e.getX(0).toDouble()
        val vy = e.getY(0).toDouble()

        // Which tool this pointer actually drives: the pen's eraser end and its held side button
        // both override the armed tool, and a finger pans unless finger-draw is on. This mirrors
        // the paged canvas so a pen behaves the same on either surface.
        val toolType = e.getToolType(0)
        val buttonHeld = stylusButtons.heldFor(e)
        val effective: Tool = when {
            toolType == MotionEvent.TOOL_TYPE_ERASER -> Tool.ERASER
            buttonHeld && penButtonTool != null -> penButtonTool!!
            toolType == MotionEvent.TOOL_TYPE_FINGER && !fingerDraws && tool.fingerPansWhenOff -> Tool.PAN
            else -> tool
        }

        if (effective.isStroke) beginDraw(vx, vy, effective, e) else beginPan(vx, vy)
    }

    private fun handlePointerDown(e: MotionEvent) {
        // A stylus stroke ignores an incidental palm or second finger; a finger stroke yields to a
        // pinch, since two fingers can only mean a zoom.
        if (mode == CanvasPointerMode.DRAW && drawingIsStylus) return
        if (e.pointerCount >= 2) {
            if (mode == CanvasPointerMode.DRAW) abandonStroke()
            beginPinch(e)
        }
    }

    private fun handleMove(e: MotionEvent) {
        when (mode) {
            CanvasPointerMode.PAN -> extendPan(e.getX(0).toDouble(), e.getY(0).toDouble())
            CanvasPointerMode.PINCH -> updatePinch(e)
            CanvasPointerMode.DRAW -> extendDraw(e)
            CanvasPointerMode.IDLE -> Unit
        }
    }

    private fun handlePointerUp(e: MotionEvent) {
        if (mode != CanvasPointerMode.PINCH) return
        // Dropping to one finger continues as a pan from wherever that finger is, so a pinch that
        // relaxes into a drag does not jump.
        if (e.pointerCount == 2) {
            val remaining = if (e.actionIndex == 0) 1 else 0
            beginPan(e.getX(remaining).toDouble(), e.getY(remaining).toDouble())
        } else {
            mode = CanvasPointerMode.IDLE
        }
    }

    private fun handleUp(e: MotionEvent) {
        val wasMoving = mode == CanvasPointerMode.PAN || mode == CanvasPointerMode.PINCH
        if (mode == CanvasPointerMode.DRAW) endDraw(e)
        mode = CanvasPointerMode.IDLE
        setInteractive(false, true)
        if (wasMoving) startFling(panVel)
        onViewChanged()
        requestRender()
    }

    private fun abortGesture() {
        if (mode == CanvasPointerMode.DRAW) abandonStroke()
        mode = CanvasPointerMode.IDLE
        setInteractive(false, true)
        stopFling()
        requestRender()
    }

    // --- drawing ---

    private fun beginDraw(vx: Double, vy: Double, drawTool: Tool, e: MotionEvent) {
        val base = configFor(drawTool)
        // SCALE off: divide the width by the draw-time zoom so the stroke keeps a constant
        // on-screen thickness whatever zoom it was drawn at. Baked in, so it is ordinary ink after.
        val z = viewport.zoom
        val config = if (base.scale) {
            base
        } else {
            base.copy(
                baseWidth = base.baseWidth / z,
                dashLength = base.dashLength / z,
                dashGap = base.dashGap / z,
                scale = true,
            )
        }
        val straight = drawTool == Tool.HIGHLIGHTER && config.straightLine
        val stroke = Stroke(
            drawTool, config,
            speedScale = z / devicePxPerDp().coerceAtLeast(1e-9),
            straight = straight,
        )
        // Live until the pen lifts, so lift-time rules cannot fire mid-draw.
        stroke.finished = false
        // Inking wants the shortest path from nib to pixel, so it draws on demand: a render thread
        // left running keeps the buffer queue full and puts a frame of lag under the pen.
        setInteractive(false, false)
        strokeStartTimeMs = e.eventTime
        val p = viewport.viewportToContent(Pt(vx, vy))
        stroke.addSample(Sample(p.x, p.y, pressureOf(e, 0)))
        liveStroke = stroke
        mode = CanvasPointerMode.DRAW
        onWetStroke(stroke)
        requestRender()
    }

    private fun extendDraw(e: MotionEvent) {
        val idx = e.findPointerIndex(drawingPointerId)
        if (idx < 0) return
        // Historical points first: the digitizer batches several samples into one event, and
        // dropping them coarsens a fast stroke into visible chords.
        for (h in 0 until e.historySize) {
            addStrokePoint(
                e.getHistoricalX(idx, h).toDouble(),
                e.getHistoricalY(idx, h).toDouble(),
                if (drawingIsStylus) e.getHistoricalPressure(idx, h).toDouble() else 1.0,
                e.getHistoricalEventTime(h),
                force = false,
            )
        }
        addStrokePoint(
            e.getX(idx).toDouble(), e.getY(idx).toDouble(),
            pressureOf(e, idx), e.eventTime, force = false,
        )
        onWetStroke(liveStroke)
        requestRender()
    }

    private fun addStrokePoint(vx: Double, vy: Double, pressure: Double, timeMs: Long, force: Boolean) {
        val stroke = liveStroke ?: return
        val p = viewport.viewportToContent(Pt(vx, vy))
        val t = (timeMs - strokeStartTimeMs).toDouble()
        if (stroke.straight) {
            stroke.setStraightEnd(Sample(p.x, p.y, pressure.coerceIn(0.0, 1.0), t))
            return
        }
        val last = stroke.samples.lastOrNull()
        // Decimate by on-screen spacing rather than content spacing, so drawing while zoomed in
        // keeps its detail instead of faceting into chords a zoom factor long.
        val gate = (InteractionController.MIN_SAMPLE_DIST / viewport.zoom)
            .coerceAtMost(InteractionController.MIN_SAMPLE_DIST)
        if (force || last == null || Pt(last.x, last.y).manhattanTo(p) >= gate) {
            stroke.addSample(Sample(p.x, p.y, pressure.coerceIn(0.0, 1.0), t))
        }
    }

    private fun endDraw(e: MotionEvent) {
        val idx = e.findPointerIndex(drawingPointerId).coerceAtLeast(0)
        addStrokePoint(
            e.getX(idx).toDouble(), e.getY(idx).toDouble(),
            pressureOf(e, idx), e.eventTime, force = true,
        )
        val stroke = liveStroke
        liveStroke = null
        if (stroke == null || stroke.isEmpty) {
            onWetStroke(null)
            return
        }
        // The pen is up: rebuild with lift-time rules on before the stroke is committed. The wet
        // buffer is deliberately not cleared here: the commit releases it in the same step, so no
        // frame can land between the two and blink.
        stroke.finished = true
        simplifyForCommit(stroke)
        onCommitStroke(stroke)
    }

    /** Drop the wet stroke without committing it, when a second finger turns the gesture into a zoom. */
    private fun abandonStroke() {
        liveStroke = null
        onWetStroke(null)
    }

    /** Shed the samples the ribbon does not need, at the tolerance the draw zoom justifies. */
    private fun simplifyForCommit(stroke: Stroke) {
        if (stroke.straight) return
        val eps = (InteractionController.SIMPLIFY_EPS / viewport.zoom)
            .coerceAtMost(InteractionController.SIMPLIFY_EPS)
        val slim = StrokeSimplify.simplify(stroke.samples, stroke.geometry().halfWidths, eps)
        if (slim.size == stroke.samples.size) return
        stroke.samples.clear()
        stroke.samples.addAll(slim)
        stroke.invalidate()
    }

    private fun pressureOf(e: MotionEvent, index: Int): Double =
        if (drawingIsStylus) e.getPressure(index).toDouble() else 1.0

    // --- pan ---

    private fun beginPan(vx: Double, vy: Double) {
        mode = CanvasPointerMode.PAN
        // Moving the view is paced by the display, so the render thread stays up for it.
        setInteractive(true, true)
        startTrackingVelocity(vx, vy)
    }

    private fun extendPan(vx: Double, vy: Double) {
        trackVelocity(vx, vy)
        viewport.panByViewport(vx - lastPan.x, vy - lastPan.y)
        lastPan = Pt(vx, vy)
        onViewChanged()
        requestRender()
    }

    // --- pinch ---

    private fun beginPinch(e: MotionEvent) {
        mode = CanvasPointerMode.PINCH
        setInteractive(true, true)
        val a = Pt(e.getX(0).toDouble(), e.getY(0).toDouble())
        val b = Pt(e.getX(1).toDouble(), e.getY(1).toDouble())
        val mid = (a + b) * 0.5
        pinchInitDist = a.distanceTo(b).coerceAtLeast(1.0)
        pinchInitZoom = viewport.zoom
        pinchAnchorContent = viewport.viewportToContent(mid)
        startTrackingVelocity(mid.x, mid.y)
    }

    private fun updatePinch(e: MotionEvent) {
        if (e.pointerCount < 2) return
        val a = Pt(e.getX(0).toDouble(), e.getY(0).toDouble())
        val b = Pt(e.getX(1).toDouble(), e.getY(1).toDouble())
        val dist = a.distanceTo(b)
        if (dist < 1e-3) return
        val mid = (a + b) * 0.5
        trackVelocity(mid.x, mid.y)
        // Zoom about the pinch's own midpoint, and let that midpoint drag the canvas at the same
        // time: the content under the fingers stays under the fingers whether they spread or slide.
        viewport.zoom = pinchInitZoom * (dist / pinchInitDist)
        viewport.scrollX = pinchAnchorContent.x - mid.x / viewport.zoom
        viewport.scrollY = pinchAnchorContent.y - mid.y / viewport.zoom
        lastPan = mid
        onViewChanged()
        requestRender()
    }

    // --- velocity and fling ---

    private fun startTrackingVelocity(vx: Double, vy: Double) {
        stopFling()
        lastPan = Pt(vx, vy)
        lastMoveMs = System.nanoTime() / 1_000_000L
        panVel = Pt.ZERO
    }

    private fun trackVelocity(vx: Double, vy: Double) {
        val now = System.nanoTime() / 1_000_000L
        val dt = ((now - lastMoveMs).coerceAtLeast(1L)) / 1000.0
        val inst = Pt((vx - lastPan.x) / dt, (vy - lastPan.y) / dt)
        val k = InteractionController.VEL_SMOOTH
        panVel = Pt(panVel.x * k + inst.x * (1 - k), panVel.y * k + inst.y * (1 - k))
        lastMoveMs = now
    }

    private fun startFling(fingerVel: Pt) {
        if (fingerVel.length() < InteractionController.FLING_MIN_START) return
        setInteractive(true, true) // the glide is still motion, so the render thread stays up for it
        flingVel = fingerVel
        flinging = true
        lastFlingMs = System.nanoTime() / 1_000_000L
        choreographer.postFrameCallback(flingFrame)
    }

    fun stopFling() {
        flinging = false
    }

    private fun stepFling(frameTimeNanos: Long) {
        if (!flinging) return
        val now = frameTimeNanos / 1_000_000L
        val dt = ((now - lastFlingMs).coerceIn(1L, 40L)) / 1000.0
        lastFlingMs = now
        viewport.panByViewport(flingVel.x * dt, flingVel.y * dt)
        val decay = exp(-InteractionController.FLING_FRICTION * dt)
        flingVel = Pt(flingVel.x * decay, flingVel.y * decay)
        onViewChanged()
        requestRender()
        // Nothing bounds an infinite canvas, so a glide only ever ends by running out of speed.
        if (flingVel.length() < InteractionController.FLING_MIN_STOP) {
            flinging = false
            setInteractive(false, true)
        } else {
            choreographer.postFrameCallback(flingFrame)
        }
    }
}
