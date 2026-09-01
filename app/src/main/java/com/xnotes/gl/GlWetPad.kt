package com.xnotes.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.xnotes.core.infinite.MeshPart
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The surface wet ink is drawn into while the pen is down, in the buffer the panel is scanning out.
 *
 * This is a real front buffer, not a layer the compositor latches: `EGL_KHR_mutable_render_buffer`
 * lets an ordinary window surface be switched to `EGL_SINGLE_BUFFER` at run time, after which GL
 * commands land in the buffer being displayed and a `glFlush` is the whole publish step. There is
 * no queue, no fence handed to SurfaceFlinger and no waiting for a refresh boundary, which is what
 * separates this from every "low latency" wrapper that still posts and latches.
 *
 * It has to be a surface of its own because no multisampled config on this device carries the
 * mutable bit, and the canvas is not giving up its antialiasing. Ink quality comes back in
 * [GlWetPadInk], which draws into a small multisampled buffer and resolves the damage into here.
 *
 * ### Threads
 *
 * Everything EGL and GL belongs to [thread]. The main thread posts messages and reads nothing.
 */
class GlWetPad(context: Context, onTop: Boolean = false) : SurfaceView(context), SurfaceHolder.Callback {

    private val ink = GlWetPadInk()

    private var cover: GlWetPadCover? = null
    private var coverTex = 0

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** Set when something has arrived that has not been drawn, so offers collapse into one draw. */
    private val dirty = AtomicBoolean(false)

    /** Samples the canvas got for its own surface; live ink is antialiased to the same standard. */
    @Volatile
    private var samples = 0

    /** Whether the surface and its context exist, so a stroke knows there is anywhere to draw. */
    @Volatile
    var ready = false
        private set

    // --- render thread only ---

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglConfig: EGLConfig? = null
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var single = false
    private var surfaceW = 0
    private var surfaceH = 0
    private var contextGen = 0
    private var traced = 0

    /** Whether the pad is holding an opaque copy of what is under it, so hiding it shows nothing. */
    private var covered = false

    private val mainHandler = Handler(context.mainLooper)

    /** Bumped by anything that outdates a pending handover, so a stale wipe cannot land. */
    private var releaseGen = 0

    /** One refresh of the panel this is on, which is what the handover is timed by. */
    @Volatile
    private var refreshMs = DEFAULT_REFRESH_MS

    /** Whether the surface is live and switched to the front buffer, for the debug readout. */
    @Volatile
    var frontBuffered = false
        private set

    init {
        // Above the window, for a canvas that draws into the window itself: a surface below it
        // punches a transparent hole through everything under it, which would take the page with
        // it. Above the canvas but still below the window otherwise, which is where a GL canvas
        // wants it.
        if (onTop) setZOrderOnTop(true) else setZOrderMediaOverlay(true)
        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
    }

    // --- main thread ---

    override fun surfaceCreated(holder: SurfaceHolder) {
        val t = HandlerThread("xnotes-wet-pad").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        post { createEgl(holder) }
        // Nothing to show until a pen is down, and a hidden layer is one the compositor does not
        // have to blend every refresh.
        setLayerVisible(false)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        post { resize(width, height) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val t = thread ?: return
        post { destroyEgl() }
        thread = null
        handler = null
        t.quitSafely()
        t.join(500)
    }

    /**
     * Take the front buffer for a stroke, painted through the view as it stands now.
     *
     * The switch is posted rather than awaited. The handler is in order, so it has happened before
     * the first draw runs, and nothing here has to block the hand.
     */
    fun beginStroke(
        scrollX: Double,
        scrollY: Double,
        zoom: Double,
        samples: Int,
        clip: com.xnotes.core.infinite.PixelRect? = null,
    ): Boolean {
        if (!ready) return false
        if (!ink.begin(scrollX, scrollY, zoom, width, height, clip)) return false
        // Read here, on the main thread, because the delay the handover needs is one of these and
        // the pad's own thread has no display.
        val hz = display?.refreshRate ?: 0f
        refreshMs = if (hz > 1f) (1000f / hz).toLong().coerceIn(4L, 40L) else DEFAULT_REFRESH_MS
        this.samples = samples
        post {
            // Cancels a handover still waiting to wipe the pad, and takes the last stroke's pixels
            // off it, which the canvas has been holding since long before a hand can come back down.
            releaseGen++
            covered = false
            clearSurface()
            setAutoRefresh(true)
            // Shown before there is ink rather than after: the compositor latches this at the next
            // refresh, and the first present has landed in the buffer well before that.
            setLayerVisible(true)
        }
        return true
    }

    /** Ribbon that has stopped moving. */
    fun appendRun(parts: List<MeshPart>) {
        ink.appendRun(parts)
        offer()
    }

    /** The points still under the nib, replacing whatever was there. */
    fun setTail(parts: List<MeshPart>) {
        ink.setTail(parts)
        offer()
    }

    /**
     * Stop drawing but keep the pixels, because they are the only copy of the stroke until the
     * canvas has drawn the committed item.
     */
    fun freeze() {
        ink.end()
    }

    /** Every pixel this stroke has inked, in view pixels, or null if it inked none. */
    fun strokeBox(): Rect? {
        val r = Rect(ink.boxLeft, ink.boxTop, ink.boxRight, ink.boxBottom)
        return if (r.isEmpty) null else r
    }

    /**
     * Put [capture], the canvas as it stands under [box], behind the ink already on the pad.
     *
     * One front-buffer present, so it is atomic by construction: the pad goes from transparent ink
     * over a visible canvas to an opaque copy of exactly that composite, with no refresh in
     * between where it could be either. [done] runs on the caller's thread once the pixels are
     * down, because nothing may reach the canvas until they are.
     */
    fun coverWith(capture: Bitmap, box: Rect, done: () -> Unit) {
        val pad = handler
        if (pad == null || !ready) {
            done()
            return
        }
        pad.post {
            drawCover(capture, box)
            mainHandler.post(done)
        }
    }

    /**
     * Wipe the pad, once the canvas has the stroke, and let the compositor rest.
     *
     * One refresh after the canvas swapped, and the number matters in both directions. The pad is
     * a front buffer, so its wipe is on the glass at the next scanout; the canvas's swapped frame
     * is only latched at the next vsync. Wipe with the swap and the stroke is gone for the refresh
     * in between; wipe late and both layers draw it, which does not cancel out, because two
     * antialiased edges at half coverage composite to three quarters and the line visibly
     * thickens. One period puts both in the same composite.
     *
     * Measured on this panel over a delay sweep: 0 ms leaves two frames at 15% of the ink,
     * 22 ms and up leave a growing run of frames 13.8% too heavy, and one period leaves neither.
     */
    fun release() = post {
        val gen = ++releaseGen
        if (covered) {
            // The pad is holding an opaque copy of the composite the screen was already showing,
            // so the canvas underneath can take as long as it likes to catch up: nothing of it is
            // visible until this layer goes. Which is why this waits generously rather than
            // racing. Hiding the instant the canvas's frame is queued shows the canvas as it was
            // one refresh earlier, before the committed stroke was in it.
            handler?.postDelayed({
                if (gen == releaseGen) setLayerVisible(false)
            }, refreshMs * 4)
        } else {
            // Nothing was captured, so the two layers still have to be timed against each other.
            // At a vsync, not after a delay: the canvas's buffer was queued one instruction ago
            // and is latched at the next one, and a transaction applied *at* that vsync is latched
            // at the one after, so they overlap for one refresh rather than for none. That is the
            // lesser artefact by a distance. Hiding earlier drops the stroke for a refresh;
            // hiding later leaves both layers drawing it, and two antialiased edges at half
            // coverage composite to three quarters rather than to a half.
            Choreographer.getInstance().postFrameCallback {
                if (gen == releaseGen) setLayerVisible(false)
            }
        }
        handler?.postDelayed({
            if (gen != releaseGen) return@postDelayed
            covered = false
            clearSurface()
            setAutoRefresh(false)
        }, refreshMs * 8)
    }

    /**
     * Show or hide the pad's own layer, through a transaction rather than by writing pixels.
     *
     * This is what makes the handover invisible. Wiping the pad puts the change on the glass at the
     * next scanout, because that is what a front buffer is for, while the canvas's frame is only
     * latched at the next vsync; whichever way that race falls, one refresh shows the stroke twice
     * or not at all. Twice is not harmless: two antialiased edges at half coverage composite to
     * three quarters, and the line thickens. A visibility change is latched like a buffer, so both
     * layers change in the same composite.
     *
     * Below API 29 there is no handle to do it with, and the timed wipe is what is left.
     */
    private fun setLayerVisible(visible: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val control = surfaceControl?.takeIf { it.isValid } ?: return
        SurfaceControl.Transaction().use { t ->
            t.setVisibility(control, visible)
            t.apply()
        }
    }

    /** Both at once, for a stroke that was abandoned rather than committed. */
    fun endStroke() {
        freeze()
        release()
    }

    private fun offer() {
        if (dirty.compareAndSet(false, true)) post { render() }
    }

    private fun post(work: () -> Unit) {
        handler?.post(work)
    }

    /**
     * Draw the capture behind the ink, on the pad's thread.
     *
     * The viewport frames the captured box, so the quad lands exactly where it was taken from, and
     * the blend leaves every pixel the ink already claimed alone.
     */
    private fun drawCover(capture: Bitmap, box: Rect) {
        if (!single || surfaceW <= 0 || surfaceH <= 0) return
        val painter = cover ?: return
        if (coverTex == 0) {
            val tex = IntArray(1)
            GLES30.glGenTextures(1, tex, 0)
            coverTex = tex[0]
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, coverTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, capture, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        // The surface counts up from the bottom and the box counts down from the top.
        GLES30.glViewport(box.left, surfaceH - box.bottom, box.width(), box.height())
        painter.drawUnder(coverTex)
        GLES30.glFlush()
        covered = true
    }

    // --- render thread ---

    private fun createEgl(holder: SurfaceHolder) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return fail("no display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return fail("eglInitialize")
        val config = chooseConfig() ?: return fail("no mutable-render-buffer config")
        eglConfig = config
        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) return fail("eglCreateContext")
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, holder.surface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) return fail("eglCreateWindowSurface ${EGL14.eglGetError()}")
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return fail("eglMakeCurrent")
        contextGen++
        ink.onContextCreated(contextGen)
        cover = try {
            GlWetPadCover(contextGen)
        } catch (e: GlShaderException) {
            Log.e(TAG, "wet pad cover shader unavailable", e)
            null
        }
        coverTex = 0
        Log.i(TAG, "wet pad surface up: ${GLES30.glGetString(GLES30.GL_RENDERER)}")
        // Once, and for the life of the surface. Going back to the back buffer and forward again
        // leaves the compositor showing a buffer this is no longer writing to, so the mode stays
        // and only the refresh is switched.
        enterSingleBuffer()
        setAutoRefresh(false)
        ready = single
    }

    /**
     * The config the front buffer needs: a window that may be switched to single buffered, which
     * on this driver means no multisampling at any sample count.
     */
    private fun chooseConfig(): EGLConfig? {
        for (stencil in booleanArrayOf(true, false)) {
            val attrs = ArrayList<Int>()
            attrs += listOf(EGL14.EGL_RENDERABLE_TYPE, MsaaConfigChooser.EGL_OPENGL_ES3_BIT)
            attrs += listOf(
                EGL14.EGL_SURFACE_TYPE,
                EGL14.EGL_WINDOW_BIT or FrontBufferProbe.MUTABLE_RENDER_BUFFER_BIT,
            )
            attrs += listOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8)
            attrs += listOf(EGL14.EGL_ALPHA_SIZE, 8)
            if (stencil) attrs += listOf(EGL14.EGL_STENCIL_SIZE, 8)
            attrs += EGL14.EGL_NONE
            val count = IntArray(1)
            val configs = arrayOfNulls<EGLConfig>(8)
            if (EGL14.eglChooseConfig(eglDisplay, attrs.toIntArray(), 0, configs, 0, configs.size, count, 0) &&
                count[0] > 0
            ) {
                return configs[0]
            }
        }
        return null
    }

    private fun resize(w: Int, h: Int) {
        surfaceW = w
        surfaceH = h
    }

    /**
     * Draw whatever the stroke is now.
     *
     * Not what it was when an offer was made: the flag is taken first, so samples that arrive
     * while this runs raise it again and are drawn by the next beat rather than queued behind
     * this one. A backlog has to be a backlog of something, and nothing here carries a payload.
     */
    private fun render() {
        dirty.set(false)
        if (!ready) return
        if (!ink.draw(surfaceW, surfaceH, samples)) {
            if (traced < 4) {
                traced++
                Log.i(TAG, "wet pad drew nothing: surface ${surfaceW}x$surfaceH single=$single ${ink.why}")
            }
            return
        }
        samplePresent()
        // The whole publish step. Nothing is queued and nobody is waited on: the compositor is
        // already holding this buffer and auto refresh has it looking at it every scanout.
        GLES30.glFlush()
    }

    private fun samplePresent() {
        presents++
        val now = System.nanoTime()
        if (sinceNs == 0L) {
            sinceNs = now
            return
        }
        val dt = now - sinceNs
        if (dt < RATE_WINDOW_NS) return
        rate = presents * 1_000_000_000.0 / dt
        presents = 0
        sinceNs = now
    }

    private fun clearSurface() {
        if (!ready) return
        ink.clearSurface()
        GLES30.glFlush()
    }

    /**
     * Ask for the front buffer.
     *
     * The transition is deferred: the spec says the surface changes over on the next
     * `eglSwapBuffers`, so one swap follows the attribute, and the mode is read back rather than
     * assumed. Auto refresh is what keeps the compositor showing the layer while nothing is being
     * posted, since in this mode nothing ever is.
     */
    private fun enterSingleBuffer() {
        if (eglSurface == EGL14.EGL_NO_SURFACE || single) return
        if (!EGL14.eglSurfaceAttrib(eglDisplay, eglSurface, EGL_RENDER_BUFFER, EGL_SINGLE_BUFFER)) {
            return fail("eglSurfaceAttrib single buffer: ${EGL14.eglGetError()}")
        }
        // Before the transition swap, not after: set afterwards the compositor never looks at the
        // layer again and every write lands in a buffer nobody reads.
        EGL14.eglSurfaceAttrib(eglDisplay, eglSurface, EGL_FRONT_BUFFER_AUTO_REFRESH, 1)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        val mode = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL_RENDER_BUFFER, mode, 0)
        single = mode[0] == EGL_SINGLE_BUFFER
        Log.i(
            TAG,
            "wet pad front buffer: asked single, got " +
                if (single) "SINGLE" else "BACK (0x%x)".format(mode[0]),
        )
    }

    /**
     * Whether the compositor keeps reading the layer while nothing is posted.
     *
     * On is what makes a front buffer visible at all; off is what lets the display pipeline idle
     * between strokes, since a shared buffer nobody refreshes is a layer nobody composites.
     */
    private fun setAutoRefresh(on: Boolean) {
        if (eglSurface == EGL14.EGL_NO_SURFACE || !single) return
        EGL14.eglSurfaceAttrib(eglDisplay, eglSurface, EGL_FRONT_BUFFER_AUTO_REFRESH, if (on) 1 else 0)
        frontBuffered = on
    }

    private fun destroyEgl() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
        cover = null
        coverTex = 0
        covered = false
        single = false
        ready = false
        frontBuffered = false
    }

    /**
     * What the front buffer is doing, for the debug readout: whether the surface is single
     * buffered at all, whether the compositor is currently reading it, the last damage, and how
     * many presents a second the pen is getting.
     */
    val hud: String
        get() = buildString {
            append(if (single) "single" else "back")
            append(if (frontBuffered) " live" else " idle")
            if (ink.lastDamage.isNotEmpty()) append(" d${ink.lastDamage}")
            append(" %.0f/s".format(rate))
        }

    /** Presents a second, over the last window, so the pen's cadence is readable. */
    @Volatile
    private var rate = 0.0
    private var presents = 0
    private var sinceNs = 0L

    private fun fail(what: String) {
        Log.e(TAG, "wet pad unavailable: $what")
    }

    companion object {
        private const val TAG = "xnotes.gl"

        /** EGL constants for the mutable render buffer, none of which EGL14 declares. */
        const val EGL_RENDER_BUFFER = 0x3086
        const val EGL_SINGLE_BUFFER = 0x3085
        const val EGL_BACK_BUFFER = 0x3084
        const val EGL_FRONT_BUFFER_AUTO_REFRESH = 0x314C

        const val RATE_WINDOW_NS = 500_000_000L

        /** Stands in for a refresh until a eglDisplay says otherwise. */
        const val DEFAULT_REFRESH_MS = 17L
    }
}
