package com.xnotes.gl

import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * Picks the best surface configuration the device will give us, preferring multisampling.
 *
 * Antialiasing quality is the whole risk of drawing ink on GL: the paged canvas gets Skia's
 * analytic coverage for free, and ink edges are the most closely examined pixels in a handwriting
 * app. MSAA is the right answer on a tile-based mobile GPU because the resolve happens inside tile
 * memory, and unlike per-fragment coverage it composes correctly where a stroke overlaps itself:
 * each sample is covered or not, so the same colour written twice is still that colour.
 *
 * A stencil buffer is not optional either. Translucent ink and the highlighter accumulate opaquely
 * and composite once, which the GL path does by stencilling the stroke then covering it, so a
 * self-crossing highlighter does not darken at the crossing the way the paged canvas never does.
 */
class MsaaConfigChooser(
    private val preferredSamples: Int = 4,
) : GLSurfaceView.EGLConfigChooser {

    /** Samples actually obtained, readable after the surface exists. 0 or 1 means no MSAA. */
    @Volatile
    var chosenSamples: Int = 0
        private set

    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        // Best first: the driver gets each request in turn and we take the first it can honour.
        val ladder = buildList {
            var s = preferredSamples
            while (s >= 2) {
                add(s)
                s /= 2
            }
            add(0)
        }
        for (samples in ladder) {
            pick(egl, display, samples, stencil = true)?.let {
                chosenSamples = samples
                return it
            }
        }
        // No stencil anywhere is a badly broken driver, but a canvas with plain translucent ink
        // still beats a black screen, so fall back rather than refuse to start.
        for (samples in ladder) {
            pick(egl, display, samples, stencil = false)?.let {
                chosenSamples = samples
                Log.w(TAG, "no stencil buffer available; translucent ink will composite naively")
                return it
            }
        }
        throw IllegalStateException("no usable EGL config")
    }

    private fun pick(egl: EGL10, display: EGLDisplay, samples: Int, stencil: Boolean): EGLConfig? {
        val attrs = ArrayList<Int>()
        attrs += listOf(EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT)
        attrs += listOf(EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT)
        attrs += listOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8)
        attrs += listOf(EGL10.EGL_DEPTH_SIZE, 16)
        if (stencil) attrs += listOf(EGL10.EGL_STENCIL_SIZE, 8)
        if (samples >= 2) attrs += listOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples)
        attrs += EGL10.EGL_NONE

        val spec = attrs.toIntArray()
        val count = IntArray(1)
        if (!egl.eglChooseConfig(display, spec, null, 0, count) || count[0] <= 0) return null
        val configs = arrayOfNulls<EGLConfig>(count[0])
        if (!egl.eglChooseConfig(display, spec, configs, count[0], count)) return null
        // eglChooseConfig sorts by its own rules, which can hand back a config with more colour
        // depth than asked for; the first match is still the closest, so take it.
        return configs.firstOrNull { it != null }
    }

    companion object {
        private const val TAG = "xnotes.gl"

        /** EGL_OPENGL_ES3_BIT_KHR; not exposed by the EGL10 constants. */
        const val EGL_OPENGL_ES3_BIT = 0x0040
    }
}
