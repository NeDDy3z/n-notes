package com.xnotes.ui

import android.view.Choreographer
import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.CanvasViewport
import com.xnotes.canvas.InteractionController
import kotlin.math.exp

/** What the current gesture is doing. */
enum class CanvasPointerMode { IDLE, PAN, PINCH }

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
) {

    private val choreographer = Choreographer.getInstance()

    var mode = CanvasPointerMode.IDLE
        private set

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
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount >= 2) beginPinch(e)
            MotionEvent.ACTION_MOVE -> handleMove(e)
            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(e)
            MotionEvent.ACTION_UP -> handleUp()
            MotionEvent.ACTION_CANCEL -> abortGesture()
        }
        return true
    }

    /** Drop any in-flight gesture and stop a glide, so a document swap cannot bleed into the next. */
    fun resetGestureState() {
        stopFling()
        mode = CanvasPointerMode.IDLE
        panVel = Pt.ZERO
    }

    // --- pointer handling ---

    private fun handleDown(e: MotionEvent) {
        stopFling() // a new touch halts any in-progress glide
        beginPan(e.getX(0).toDouble(), e.getY(0).toDouble())
    }

    private fun handleMove(e: MotionEvent) {
        when (mode) {
            CanvasPointerMode.PAN -> extendPan(e.getX(0).toDouble(), e.getY(0).toDouble())
            CanvasPointerMode.PINCH -> updatePinch(e)
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

    private fun handleUp() {
        val wasMoving = mode == CanvasPointerMode.PAN || mode == CanvasPointerMode.PINCH
        mode = CanvasPointerMode.IDLE
        if (wasMoving) startFling(panVel)
        onViewChanged()
        requestRender()
    }

    private fun abortGesture() {
        mode = CanvasPointerMode.IDLE
        stopFling()
        requestRender()
    }

    // --- pan ---

    private fun beginPan(vx: Double, vy: Double) {
        mode = CanvasPointerMode.PAN
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
        } else {
            choreographer.postFrameCallback(flingFrame)
        }
    }
}
