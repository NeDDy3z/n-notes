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

    /** Non-null when a shader would not build; the host shows a message instead of a black view. */
    val failure: String? get() = glRenderer.failure

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(configChooser)
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
    override fun onTouchEvent(event: MotionEvent): Boolean =
        input?.invoke(event) ?: super.onTouchEvent(event)

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
}
