package com.xnotes.gl

import android.opengl.GLES30

/**
 * The two halves of a bloom: a separable Gaussian, then a composite of the blurred result over the
 * picture at the halo's own alpha.
 *
 * Separable is what makes a wide halo affordable: a radius of `r` costs `2r` taps across two passes
 * instead of `r * r` in one. The taps are capped, and a radius past the cap widens the step between
 * them rather than adding more, which for a Gaussian this soft is indistinguishable.
 */
class GlowShader(contextGen: Int) {

    private val blur = GlProgram.build(FULLSCREEN_VERTEX_SRC, BLUR_FRAGMENT_SRC, contextGen)
    private val composite = GlProgram.build(FULLSCREEN_VERTEX_SRC, COMPOSITE_FRAGMENT_SRC, contextGen)

    val contextGen: Int get() = blur.contextGen

    fun release() {
        blur.release()
        composite.release()
    }

    /**
     * Blur [texture] along one axis into whatever is bound. [radiusPx] is in the buffer's own
     * pixels, and [horizontal] picks the axis.
     */
    fun blur(texture: Int, radiusPx: Double, horizontal: Boolean, bufferW: Int, bufferH: Int) {
        blur.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        blur.set("uTexture", 0)
        val taps = tapsFor(radiusPx)
        blur.set("uTaps", taps)
        // Past the tap cap the step widens instead of the count growing, so a very wide halo stays
        // one pass rather than becoming unaffordable.
        blur.set("uStep", (radiusPx / taps).coerceAtLeast(1.0).toFloat())
        blur.set("uSigma", (radiusPx / 2.0).coerceAtLeast(0.5).toFloat())
        blur.set(
            "uDirection",
            if (horizontal) 1f / bufferW else 0f,
            if (horizontal) 0f else 1f / bufferH,
        )
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    /**
     * Draw [texture] over whatever is bound, at [alpha], with premultiplied alpha.
     *
     * Premultiplied, not straight, and that is load bearing. A halo now passes through an
     * intermediate layer on its way to the screen, and straight alpha cannot survive two
     * compositing steps: the colour is scaled by the alpha at each one, so a halo at 0.42 arrives
     * at roughly 0.07 and reads as absent. Premultiplied composites the same however many hops it
     * takes. It also fixes what blurring straight alpha does to the edges, where averaging against
     * transparent black dragged the halo toward black rather than toward nothing.
     *
     * The source is already premultiplied by construction: the halo geometry goes into the buffer
     * at full alpha, so a texel is the colour inside the ribbon and zero outside, and blurring that
     * gives colour times coverage alongside coverage, which is exactly premultiplied.
     */
    fun compositeOver(
        texture: Int,
        alpha: Double,
        uvScaleX: Float = 1f,
        uvScaleY: Float = 1f,
        uvOffsetX: Float = 0f,
        uvOffsetY: Float = 0f,
        uvMapA: Float = 1f,
        uvMapB: Float = 0f,
        uvMapC: Float = 0f,
        uvMapD: Float = 1f,
        uvPivotX: Float = 0f,
        uvPivotY: Float = 0f,
        uvSizeX: Float = 1f,
        uvSizeY: Float = 1f,
    ) {
        composite.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        composite.set("uTexture", 0)
        composite.set("uAlpha", alpha.coerceIn(0.0, 1.0).toFloat())
        // Sample only the part of the buffer the viewport occupied, so rounding the buffer up
        // cannot stretch the halo away from the stroke it belongs to.
        composite.set("uUvScale", uvScaleX, uvScaleY)
        // Slide the whole blurred picture, which is how a dragged selection's halo follows its ink
        // without being blurred again: a blur commutes with a translation.
        composite.set("uUvOffset", uvOffsetX, uvOffsetY)
        // And map it, for the same reason: an isotropic Gaussian commutes with a rotation, and with
        // a uniform scale once the radius goes along for the ride, so a selection being turned or
        // resized also gets one blur for the whole gesture rather than one a frame. A single-axis
        // scale is the one case this only approximates, and the caller re-blurs past a threshold.
        composite.setMat2("uUvMap", uvMapA, uvMapB, uvMapC, uvMapD)
        composite.set("uUvPivot", uvPivotX, uvPivotY)
        composite.set("uUvSize", uvSizeX, uvSizeY)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun tapsFor(radiusPx: Double): Int =
        radiusPx.toInt().coerceIn(1, MAX_TAPS)

    companion object {
        /** Most samples taken per side, per pass. */
        const val MAX_TAPS = 24

        private val FULLSCREEN_VERTEX_SRC = """#version 300 es
            out vec2 vUv;
            void main() {
                float x = float((gl_VertexID & 1) << 2) - 1.0;
                float y = float((gl_VertexID & 2) << 1) - 1.0;
                vUv = vec2((x + 1.0) * 0.5, (y + 1.0) * 0.5);
                gl_Position = vec4(x, y, 0.0, 1.0);
            }
        """.trimIndent()

        private val BLUR_FRAGMENT_SRC = """#version 300 es
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uDirection;
            uniform int uTaps;
            uniform float uStep;
            uniform float uSigma;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                vec4 sum = texture(uTexture, vUv);
                float weight = 1.0;
                for (int i = 1; i <= 32; i++) {
                    if (i > uTaps) break;
                    float d = float(i) * uStep;
                    float w = exp(-(d * d) / (2.0 * uSigma * uSigma));
                    vec2 offset = uDirection * d;
                    sum += texture(uTexture, vUv + offset) * w;
                    sum += texture(uTexture, vUv - offset) * w;
                    weight += 2.0 * w;
                }
                fragColor = sum / weight;
            }
        """.trimIndent()

        private val COMPOSITE_FRAGMENT_SRC = """#version 300 es
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            uniform vec2 uUvScale;
            uniform vec2 uUvOffset;
            uniform mat2 uUvMap;
            uniform vec2 uUvPivot;
            uniform vec2 uUvSize;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                // The slide goes on before the map, which is the inverse of mapping the picture and
                // then sliding it. Applied after, a drag that both turned and moved would slide
                // along the wrong axes.
                vec2 uv = vUv * uUvScale + uUvOffset;
                // Map about the pivot in pixels, not in uv: uv is normalized per axis, so a turn
                // applied to it directly would shear a non-square buffer. Identity for everything
                // but a selection being turned or resized.
                vec2 d = (uv - uUvPivot) * uUvSize;
                uv = uUvPivot + (uUvMap * d) / uUvSize;
                // A slid buffer runs off its own edge, and clamping there would smear the last row
                // of texels across the gap. Nothing outside the buffer was ever drawn, so it reads
                // as nothing.
                vec2 within = step(vec2(0.0), uv) * step(uv, vec2(1.0));
                // Already premultiplied, so scaling the whole texel by the halo's brightness is
                // the whole of the operation; the blend function does the rest.
                fragColor = texture(uTexture, uv) * uAlpha * within.x * within.y;
            }
        """.trimIndent()
    }
}
