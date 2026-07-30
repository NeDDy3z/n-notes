package com.xnotes.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.xnotes.core.infinite.BackgroundPattern
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.model.Rgba
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Everything the GL thread needs for one frame, snapshotted on the main thread. The model and the
 * viewport belong to the main thread; copying the handful of numbers a frame reads is cheaper than
 * locking, and it guarantees a frame never sees half of a gesture's update.
 */
data class FrameState(
    val zoom: Double,
    val scrollX: Double,
    val scrollY: Double,
    val widthPx: Int,
    val heightPx: Int,
    val background: CanvasBackground,
    val paper: Rgba,
) {
    companion object {
        val EMPTY = FrameState(1.0, 0.0, 0.0, 0, 0, CanvasBackground(), Rgba(255, 255, 255, 255))
    }
}

/**
 * Content drawn over the background. Implemented by the geometry store from stage 4 on; kept
 * separate so the renderer owns nothing but the context lifecycle and the background.
 */
interface GlScene {
    /**
     * A fresh EGL context exists and every previous GL object is gone. Rebuild all GPU state from
     * the model. Runs on the GL thread.
     */
    fun onContextCreated(contextGen: Int)

    /** Draw the scene for [frame]. Runs on the GL thread with the framebuffer already cleared. */
    fun drawContent(frame: FrameState)
}

/**
 * Owns the EGL context's lifetime and draws one frame: paper, ruling, then the scene.
 *
 * Backgrounding the app or destroying the surface kills every buffer, texture and program handle
 * at once, a failure class the paged canvas simply does not have. So nothing here is built lazily
 * and cached across a context: [onSurfaceCreated] bumps [contextGen], throws away every handle it
 * was holding, and rebuilds. Anything GPU-resident records the generation it was born under, and
 * a stale generation means rebuild rather than bind a dangling name.
 */
class GlRenderer : GLSurfaceView.Renderer {

    /** Snapshot the next frame will draw; replaced wholesale by the main thread. */
    @Volatile
    var frame: FrameState = FrameState.EMPTY

    @Volatile
    var scene: GlScene? = null

    /** Bumped on every new EGL context. Everything GPU-resident is stamped with it. */
    @Volatile
    var contextGen: Int = 0
        private set

    /** Set when a shader would not build, so the host can show a message rather than a black view. */
    @Volatile
    var failure: String? = null
        private set

    private var background: BackgroundShader? = null

    /** Invoked on the GL thread right after a context is (re)built, for host-side bookkeeping. */
    var onContextReady: ((Int) -> Unit)? = null

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        // The old context is already gone: its objects cannot and must not be deleted, only dropped.
        background = null
        contextGen++
        failure = null
        val renderer = GLES30.glGetString(GLES30.GL_RENDERER)
        val version = GLES30.glGetString(GLES30.GL_VERSION)
        Log.i(TAG, "context $contextGen on $renderer, $version")
        try {
            background = BackgroundShader(contextGen)
        } catch (e: GlShaderException) {
            failure = e.message
            Log.e(TAG, "background shader unavailable", e)
        }
        GLES30.glDisable(GLES30.GL_DITHER)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        scene?.onContextCreated(contextGen)
        onContextReady?.invoke(contextGen)
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(unused: GL10?) {
        val f = frame
        if (f.widthPx <= 0 || f.heightPx <= 0) return
        val paper = f.paper
        GLES30.glClearColor(paper.r / 255f, paper.g / 255f, paper.b / 255f, 1f)
        GLES30.glClearStencil(0)
        GLES30.glDepthMask(true)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT or GLES30.GL_STENCIL_BUFFER_BIT)

        background?.let { shader ->
            val resolved = BackgroundPattern.resolve(f.background, f.zoom, f.scrollX, f.scrollY)
            shader.draw(f.background, resolved, paper, f.widthPx, f.heightPx)
        }

        scene?.drawContent(f)
    }

    companion object {
        private const val TAG = "xnotes.gl"
    }
}
