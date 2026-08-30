package com.xnotes.gl

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView

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
class GlWetPad(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    // --- render thread only ---

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglConfig: EGLConfig? = null
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var single = false
    private var width = 0
    private var height = 0

    /** Whether the surface is live and switched to the front buffer, for the debug readout. */
    @Volatile
    var frontBuffered = false
        private set

    init {
        setZOrderMediaOverlay(true)
        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
    }

    // --- main thread ---

    override fun surfaceCreated(holder: SurfaceHolder) {
        val t = HandlerThread("xnotes-wet-pad").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        post { createEgl(holder) }
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

    /** Take the front buffer for a stroke. Returns nothing: the switch is reported on [frontBuffered]. */
    fun begin() = post { enterSingleBuffer() }

    /** Give it back, so the surface goes back to posting buffers like any other. */
    fun end() = post { leaveSingleBuffer() }

    /**
     * Fill a rectangle of the surface, in view pixels counting down from the top left.
     *
     * A scissored clear is the smallest write there is, which is what it is here for: it proves
     * the mode end to end before there is any ink to draw through it.
     */
    fun paint(l: Int, t: Int, r: Int, b: Int) = post { fill(l, t, r, b) }

    private fun post(work: () -> Unit) {
        handler?.post(work)
    }

    // --- render thread ---

    private fun createEgl(holder: SurfaceHolder) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return fail("no display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return fail("eglInitialize")
        val config = chooseConfig() ?: return fail("no mutable-render-buffer config")
        eglConfig = config
        eglContext = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) return fail("eglCreateContext")
        eglSurface = EGL14.eglCreateWindowSurface(
            display, config, holder.surface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) return fail("eglCreateWindowSurface ${EGL14.eglGetError()}")
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) return fail("eglMakeCurrent")
        Log.i(TAG, "wet pad surface up: ${GLES30.glGetString(GLES30.GL_RENDERER)}")
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
            if (EGL14.eglChooseConfig(display, attrs.toIntArray(), 0, configs, 0, configs.size, count, 0) &&
                count[0] > 0
            ) {
                return configs[0]
            }
        }
        return null
    }

    private fun resize(w: Int, h: Int) {
        width = w
        height = h
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
        if (!EGL14.eglSurfaceAttrib(display, eglSurface, EGL_RENDER_BUFFER, EGL_SINGLE_BUFFER)) {
            return fail("eglSurfaceAttrib single buffer: ${EGL14.eglGetError()}")
        }
        EGL14.eglSurfaceAttrib(display, eglSurface, EGL_FRONT_BUFFER_AUTO_REFRESH, 1)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(display, eglSurface)
        val mode = IntArray(1)
        EGL14.eglQuerySurface(display, eglSurface, EGL_RENDER_BUFFER, mode, 0)
        single = mode[0] == EGL_SINGLE_BUFFER
        frontBuffered = single
        Log.i(TAG, "wet pad front buffer: asked single, got ${if (single) "SINGLE" else "BACK (0x%x)".format(mode[0])}")
    }

    private fun leaveSingleBuffer() {
        if (eglSurface == EGL14.EGL_NO_SURFACE || !single) return
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glFlush()
        EGL14.eglSurfaceAttrib(display, eglSurface, EGL_FRONT_BUFFER_AUTO_REFRESH, 0)
        EGL14.eglSurfaceAttrib(display, eglSurface, EGL_RENDER_BUFFER, EGL_BACK_BUFFER)
        EGL14.eglSwapBuffers(display, eglSurface)
        single = false
        frontBuffered = false
    }

    private fun fill(l: Int, t: Int, r: Int, b: Int) {
        if (!single || width <= 0 || height <= 0) return
        val x = l.coerceIn(0, width)
        val w = (r.coerceIn(0, width) - x).coerceAtLeast(0)
        // GL counts up from the bottom and the caller counts down from the top.
        val y = (height - b).coerceIn(0, height)
        val h = (height - t.coerceIn(0, height) - y).coerceAtLeast(0)
        if (w == 0 || h == 0) return
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glScissor(x, y, w, h)
        GLES30.glClearColor(1f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        // The whole publish step. Nothing is queued and nobody is waited on.
        GLES30.glFlush()
    }

    private fun destroyEgl() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
        EGL14.eglTerminate(display)
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
        single = false
        frontBuffered = false
    }

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
    }
}
