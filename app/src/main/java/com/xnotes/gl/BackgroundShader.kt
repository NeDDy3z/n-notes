package com.xnotes.gl

import android.opengl.GLES30
import com.xnotes.core.infinite.BackgroundPattern
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.Rgba

/**
 * The canvas ruling, drawn procedurally over the whole viewport in one fragment shader. Nothing
 * about it is cached or rasterized, so it is exactly as sharp at 64x as at 0.02x, and a pinch
 * changes only two uniforms.
 *
 * The shader never sees a content coordinate. [BackgroundPattern] resolves the ruling to a period
 * and a phase already in device pixels, both small numbers, which is what keeps the pattern exact
 * on a canvas panned a long way from the origin: float32 would have lost a world coordinate, but
 * `scroll mod period` survives it.
 *
 * Two levels draw each frame, the base one solid and the half-period one fading in as you zoom, so
 * the grid subdivides continuously instead of popping between scales.
 */
class BackgroundShader(contextGen: Int) {

    private val program = GlProgram.build(VERTEX_SRC, FRAGMENT_SRC, contextGen)
    val contextGen: Int get() = program.contextGen

    fun release() = program.release()

    /**
     * Draw the paper and its ruling over the full viewport. Assumes the framebuffer is already
     * bound and the viewport set; leaves blending enabled with the standard alpha function.
     */
    fun draw(
        background: CanvasBackground,
        resolved: BackgroundPattern.Resolved,
        paper: Rgba,
        viewportW: Int,
        viewportH: Int,
    ) {
        program.use()
        program.set("uViewport", viewportW.toFloat(), viewportH.toFloat())
        program.set("uPaper", paper.r / 255f, paper.g / 255f, paper.b / 255f, paper.a / 255f)
        val ink = background.patternColor
        program.set("uInk", ink.r / 255f, ink.g / 255f, ink.b / 255f, ink.a / 255f)
        program.set("uMode", modeOf(background.pattern))
        program.set("uPeriod", resolved.periodPx.toFloat())
        program.set("uPhase", resolved.phaseXPx.toFloat(), resolved.phaseYPx.toFloat())
        program.set("uSubPeriod", resolved.subPeriodPx.toFloat())
        program.set("uSubPhase", resolved.subPhaseXPx.toFloat(), resolved.subPhaseYPx.toFloat())
        program.set("uSubAlpha", resolved.subdivisionAlpha.toFloat())
        program.set("uLineWidth", BackgroundPattern.LINE_WIDTH_PX.toFloat())
        program.set("uDotRadius", BackgroundPattern.DOT_RADIUS_PX.toFloat())

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        // A single oversized triangle covers the viewport with no vertex buffer at all: the
        // positions come from gl_VertexID, so there is nothing to allocate or rebuild.
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun modeOf(pattern: PagePattern): Int = when (pattern) {
        PagePattern.NONE -> MODE_NONE
        PagePattern.LINES -> MODE_LINES
        PagePattern.DOTS -> MODE_DOTS
        PagePattern.GRID -> MODE_GRID
    }

    companion object {
        const val MODE_NONE = 0
        const val MODE_LINES = 1
        const val MODE_DOTS = 2
        const val MODE_GRID = 3

        private val VERTEX_SRC = """#version 300 es
            void main() {
                // Fullscreen triangle from the vertex index; covers clip space with no attributes.
                float x = float((gl_VertexID & 1) << 2) - 1.0;
                float y = float((gl_VertexID & 2) << 1) - 1.0;
                gl_Position = vec4(x, y, 0.0, 1.0);
            }
        """.trimIndent()

        private val FRAGMENT_SRC = """#version 300 es
            precision highp float;

            uniform vec2 uViewport;
            uniform vec4 uPaper;
            uniform vec4 uInk;
            uniform int uMode;
            uniform float uPeriod;
            uniform vec2 uPhase;
            uniform float uSubPeriod;
            uniform vec2 uSubPhase;
            uniform float uSubAlpha;
            uniform float uLineWidth;
            uniform float uDotRadius;

            out vec4 fragColor;

            // Device-pixel distance from p to the nearest lattice line of this period.
            float axisDistance(float p, float period, float phase) {
                if (period <= 0.0) return 1e9;
                float q = mod(p + phase, period);
                return min(q, period - q);
            }

            // Coverage of a mark whose edge sits at 'radius', antialiased over one pixel.
            float coverage(float distance, float radius) {
                return 1.0 - smoothstep(radius - 0.5, radius + 0.5, distance);
            }

            float patternAt(vec2 p, float period, vec2 phase) {
                float halfW = uLineWidth * 0.5;
                if (uMode == 1) {
                    return coverage(axisDistance(p.y, period, phase.y), halfW);
                }
                if (uMode == 2) {
                    vec2 d = vec2(axisDistance(p.x, period, phase.x),
                                  axisDistance(p.y, period, phase.y));
                    return coverage(length(d), uDotRadius);
                }
                if (uMode == 3) {
                    float cx = coverage(axisDistance(p.x, period, phase.x), halfW);
                    float cy = coverage(axisDistance(p.y, period, phase.y), halfW);
                    return max(cx, cy);
                }
                return 0.0;
            }

            void main() {
                fragColor = uPaper;
                if (uMode == 0) return;
                // GL counts y up from the bottom; the viewport transform counts it down from the top.
                vec2 p = vec2(gl_FragCoord.x, uViewport.y - gl_FragCoord.y);

                float base = patternAt(p, uPeriod, uPhase);
                float sub = patternAt(p, uSubPeriod, uSubPhase) * uSubAlpha;
                // The subdivision sits under the base level, so a shared line stays one line
                // rather than compounding into a darker one.
                float mask = max(base, sub);
                if (mask <= 0.0) return;

                float a = uInk.a * mask;
                fragColor = vec4(mix(uPaper.rgb, uInk.rgb, a), 1.0);
            }
        """.trimIndent()
    }
}
