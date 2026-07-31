package com.xnotes.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.infinite.CanvasViewport
import com.xnotes.core.model.Rgba

/**
 * The on-screen infinite canvas: a [GLSurfaceView] driving [GlRenderer].
 *
 * Nothing here is rasterized at a fixed resolution, so there is no cache to be at the wrong scale
 * and no settle-and-rebuild after a pinch. Zoom and scroll reach the shader as uniforms, which is
 * what makes the view sharp during a gesture rather than after it.
 *
 * Rendering is [RENDERMODE_WHEN_DIRTY]: the canvas repaints on interaction, like the paged one,
 * not at the display refresh rate. If stylus latency proves worse than the paged `View` path, the
 * swap to an owned EGL context on a dedicated render thread is confined to this file.
 *
 * The input hooks deliberately mirror `canvas.CanvasView`'s, so the same interaction layer and the
 * same hard-won device-specific stylus handling drive either canvas.
 */
class InfiniteCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    private val configChooser = MsaaConfigChooser()
    private val glRenderer = GlRenderer()

    /** The view onto the canvas. Main-thread owned; snapshotted for the GL thread by [publish]. */
    val viewport = CanvasViewport()

    var background: CanvasBackground = CanvasBackground()
        set(value) {
            field = value
            publish()
        }

    /** Paper colour behind the ruling, resolved from the canvas or the theme by the host. */
    var paperColor: Rgba = Rgba(255, 255, 255, 255)
        set(value) {
            field = value
            publish()
        }

    /** Content drawn over the ruling; installed by the editor once geometry exists. */
    var scene: GlScene?
        get() = glRenderer.scene
        set(value) {
            glRenderer.scene = value
        }

    /** Pointer handler installed by the interaction layer. */
    var input: ((MotionEvent) -> Boolean)? = null

    /** Hover handler (stylus/mouse hover), for the eraser cursor. */
    var hover: ((MotionEvent) -> Boolean)? = null

    /** Stylus side-button presses on the generic-motion stream, for pens that omit them from touch. */
    var genericMotion: ((MotionEvent) -> Unit)? = null

    /** Hardware keys arriving while this view holds focus. */
    var onKey: ((KeyEvent) -> Boolean)? = null

    /** Invoked after the viewport is laid out, so the host can apply an initial fit. */
    var afterLayout: (() -> Unit)? = null

    /** Invoked on the GL thread once a context exists or has been rebuilt after being lost. */
    var onContextReady: ((Int) -> Unit)?
        get() = glRenderer.onContextReady
        set(value) {
            glRenderer.onContextReady = value
        }

    /** Multisample count actually granted, for the debug readout. 0 or 1 means no MSAA. */
    val msaaSamples: Int get() = configChooser.chosenSamples

    /** What the last frame did. Read on the main thread; written whole on the GL thread. */
    val stats: GlStats get() = glRenderer.stats.copy(msaaSamples = configChooser.chosenSamples)

    /** Fired on a clean four-finger tap, the same gesture that toggles the paged canvas's HUD. */
    var onFourFingerTap: (() -> Unit)? = null

    /** Non-null when a shader would not build; the host shows a message instead of a black view. */
    val failure: String? get() = glRenderer.failure

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(configChooser)
        glRenderer.msaaSamples = { configChooser.chosenSamples }
        setRenderer(glRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        // Only a hint, and routinely ignored: onSurfaceCreated still has to rebuild everything.
        preserveEGLContextOnPause = true
        isFocusableInTouchMode = true
    }

    /**
     * Copy the current view onto the render thread and ask for a frame. Called after anything that
     * changes what a frame would draw. Snapshotting beats locking here: the numbers are few, and a
     * frame can never catch a gesture's update half applied.
     */
    fun publish() {
        glRenderer.frame = FrameState(
            zoom = viewport.zoom,
            scrollX = viewport.scrollX,
            scrollY = viewport.scrollY,
            widthPx = viewport.widthPx,
            heightPx = viewport.heightPx,
            background = background,
            paper = paperColor,
        )
        requestRender()
    }

    /** Run [work] on the GL thread, for GPU state that must be touched with the context current. */
    fun onGlThread(work: () -> Unit) = queueEvent(work)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewport.widthPx = w
        viewport.heightPx = h
        afterLayout?.invoke()
        publish()
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (trackFourFingerTap(event)) return true
        return input?.invoke(event) ?: super.onTouchEvent(event)
    }

    // --- four-finger-tap recognition (toggles the debug HUD, as on the paged canvas) ---

    private var gestureDownMs = 0L
    private var gestureMaxPointers = 0
    private var fourFingerActive = false
    private var fourCx = 0f
    private var fourCy = 0f
    private var fourMoved = false

    /**
     * Watch for a clean four-finger tap. Once a fourth finger lands the in-flight gesture is
     * cancelled and the rest of it swallowed, so the HUD can never be toggled by something that
     * also drew or panned.
     */
    private fun trackFourFingerTap(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureDownMs = e.eventTime
                gestureMaxPointers = 1
                fourFingerActive = false
                fourMoved = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                gestureMaxPointers = maxOf(gestureMaxPointers, e.pointerCount)
                if (!fourFingerActive && e.pointerCount >= 4) {
                    fourFingerActive = true
                    val (cx, cy) = centroid(e)
                    fourCx = cx
                    fourCy = cy
                    cancelInteraction(e)
                }
                if (fourFingerActive) return true
            }
            MotionEvent.ACTION_MOVE -> if (fourFingerActive) {
                val (cx, cy) = centroid(e)
                if (kotlin.math.hypot((cx - fourCx).toDouble(), (cy - fourCy).toDouble()) > TAP_SLOP) {
                    fourMoved = true
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> if (fourFingerActive) return true
            MotionEvent.ACTION_UP -> if (fourFingerActive) {
                fourFingerActive = false
                val quick = e.eventTime - gestureDownMs <= TAP_TIMEOUT_MS
                if (quick && !fourMoved && gestureMaxPointers == 4) onFourFingerTap?.invoke()
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (fourFingerActive) {
                fourFingerActive = false
                return true
            }
        }
        return false
    }

    private fun centroid(e: MotionEvent): Pair<Float, Float> {
        var sx = 0f
        var sy = 0f
        for (i in 0 until e.pointerCount) {
            sx += e.getX(i)
            sy += e.getY(i)
        }
        return sx / e.pointerCount to sy / e.pointerCount
    }

    /** Forward a synthetic CANCEL so the interaction layer abandons whatever it had begun. */
    private fun cancelInteraction(e: MotionEvent) {
        val cancel = MotionEvent.obtain(e)
        cancel.action = MotionEvent.ACTION_CANCEL
        input?.invoke(cancel)
        cancel.recycle()
    }

    override fun onHoverEvent(event: MotionEvent): Boolean =
        hover?.invoke(event) ?: super.onHoverEvent(event)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        genericMotion?.invoke(event)
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        onKey?.invoke(event) == true || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        onKey?.invoke(event) == true || super.onKeyUp(keyCode, event)

    companion object {
        /** Longest gesture still counted as a tap. */
        private const val TAP_TIMEOUT_MS = 500L

        /** Furthest the four fingers may drift and still count as a tap, in viewport px. */
        private const val TAP_SLOP = 40.0
    }
}
