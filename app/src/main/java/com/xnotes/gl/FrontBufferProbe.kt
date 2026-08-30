package com.xnotes.gl

import android.opengl.EGL14
import android.util.Log

/**
 * What this device will give an ordinary app that wants to draw into the buffer being scanned out.
 *
 * `HardwareBuffer.USAGE_FRONT_BUFFER` is one way to ask and is refused here; the other is
 * `EGL_KHR_mutable_render_buffer`, which switches an ordinary window surface between the back
 * buffer and the front one at runtime. This reports whether the extension is there and which of
 * the configs the canvas actually wants can carry it.
 */
object FrontBufferProbe {

    /** EGL_MUTABLE_RENDER_BUFFER_BIT_KHR, absent from the EGL14 constants. */
    const val MUTABLE_RENDER_BUFFER_BIT = 0x1000

    fun log() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            Log.w(TAG, "front buffer probe: no display")
            return
        }
        val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS) ?: ""
        val mutable = "EGL_KHR_mutable_render_buffer" in extensions
        val autoRefresh = "EGL_ANDROID_front_buffer_auto_refresh" in extensions
        val partial = "EGL_KHR_partial_update" in extensions
        Log.i(TAG, "front buffer probe: mutable=$mutable autoRefresh=$autoRefresh partialUpdate=$partial")

        for (samples in intArrayOf(4, 2, 0)) {
            for (stencil in booleanArrayOf(true, false)) {
                val n = count(display, samples, stencil, mutableBit = true)
                val plain = count(display, samples, stencil, mutableBit = false)
                Log.i(TAG, "front buffer probe: samples=$samples stencil=$stencil mutable=$n plain=$plain")
            }
        }
    }

    private fun count(display: android.opengl.EGLDisplay, samples: Int, stencil: Boolean, mutableBit: Boolean): Int {
        val attrs = ArrayList<Int>()
        attrs += listOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT or 0x0040)
        attrs += listOf(
            EGL14.EGL_SURFACE_TYPE,
            EGL14.EGL_WINDOW_BIT or (if (mutableBit) MUTABLE_RENDER_BUFFER_BIT else 0),
        )
        attrs += listOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8)
        if (stencil) attrs += listOf(EGL14.EGL_STENCIL_SIZE, 8)
        if (samples >= 2) attrs += listOf(EGL14.EGL_SAMPLE_BUFFERS, 1, EGL14.EGL_SAMPLES, samples)
        attrs += EGL14.EGL_NONE
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attrs.toIntArray(), 0, null, 0, 0, count, 0)) return -1
        return count[0]
    }

    private const val TAG = "xnotes.gl"
}
