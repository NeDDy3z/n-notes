package com.xnotes.gl

import android.opengl.GLES30

/**
 * The two offscreen buffers a neon bloom is built in: geometry into one, blurred across into the
 * other, blurred down and back.
 *
 * They are kept at a fraction of the viewport. A Gaussian wide enough to read as a halo costs a lot
 * of taps at full resolution, and a blur is exactly the operation that hides the resolution it was
 * computed at, so half size is free quality-wise and a quarter of the fill.
 *
 * Like everything else GPU resident, these die with the EGL context and are rebuilt from nothing.
 */
class GlowTarget {

    private val framebuffers = IntArray(2)
    private val textures = IntArray(2)
    private var width = 0
    private var height = 0
    private var contextGen = -1

    /** Viewport pixels per glow-buffer pixel. */
    val downscale: Int get() = DOWNSCALE

    val ready: Boolean get() = width > 0 && height > 0

    fun onContextCreated(gen: Int) {
        contextGen = gen
        framebuffers[0] = 0
        framebuffers[1] = 0
        textures[0] = 0
        textures[1] = 0
        width = 0
        height = 0
    }

    /** Size the buffers for a viewport, rebuilding only when the size actually changed. */
    fun resize(viewportW: Int, viewportH: Int, gen: Int) {
        if (gen != contextGen) return
        // Round up, so an odd viewport still has a whole buffer pixel for its last fraction. The
        // extra sliver is never drawn into and never sampled; see [usedFraction].
        val w = ((viewportW + DOWNSCALE - 1) / DOWNSCALE).coerceAtLeast(1)
        val h = ((viewportH + DOWNSCALE - 1) / DOWNSCALE).coerceAtLeast(1)
        if (w == width && h == height && framebuffers[0] != 0) return
        release()
        width = w
        height = h
        GLES30.glGenFramebuffers(2, framebuffers, 0)
        GLES30.glGenTextures(2, textures, 0)
        for (i in 0 until 2) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[i])
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[i])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, textures[i], 0,
            )
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** Bind buffer [index] and clear it, ready to be drawn into. */
    fun bindAndClear(index: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[index])
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    fun texture(index: Int): Int = textures[index]

    val bufferWidth: Int get() = width
    val bufferHeight: Int get() = height

    /**
     * The fraction of the buffer the viewport actually occupies. Rounding the buffer up leaves a
     * sliver on the right and bottom that was never drawn into, and sampling it would stretch the
     * halo by that fraction and slide it off the stroke.
     */
    fun usedFractionX(viewportW: Int): Float =
        if (width <= 0) 1f else (viewportW.toFloat() / DOWNSCALE / width)

    fun usedFractionY(viewportH: Int): Float =
        if (height <= 0) 1f else (viewportH.toFloat() / DOWNSCALE / height)

    /** Go back to drawing on screen at [viewportW] by [viewportH]. */
    fun unbind(viewportW: Int, viewportH: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewportW, viewportH)
    }

    fun release() {
        if (framebuffers[0] != 0) GLES30.glDeleteFramebuffers(2, framebuffers, 0)
        if (textures[0] != 0) GLES30.glDeleteTextures(2, textures, 0)
        framebuffers[0] = 0
        textures[0] = 0
        width = 0
        height = 0
    }

    companion object {
        /** How much smaller the glow buffers are than the viewport. */
        const val DOWNSCALE = 2
    }
}
