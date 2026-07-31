package com.xnotes.gl

import android.opengl.GLES30
import com.xnotes.core.model.Rgba

/**
 * The eraser cursor: an antialiased ring in device space.
 *
 * Drawn on a quad just big enough to hold the ring rather than over the whole viewport, so the
 * fragment shader shades a few thousand pixels rather than the three million a fullscreen pass
 * would, and the outline itself comes from a distance field rather than from geometry so it stays
 * exactly one pixel wide at any size.
 */
class CursorShader(contextGen: Int) {

    private val program = GlProgram.build(VERTEX_SRC, FRAGMENT_SRC, contextGen)

    val contextGen: Int get() = program.contextGen

    fun release() = program.release()

    /** Draw a ring centred on device pixel ([cx], [cy]) with [radius] device pixels. */
    fun draw(cx: Double, cy: Double, radius: Double, color: Rgba, viewportW: Int, viewportH: Int) {
        if (viewportW <= 0 || viewportH <= 0 || radius <= 0.0) return
        val pad = RING_WIDTH_PX + 2.0
        val x0 = ((cx - radius - pad) / viewportW * 2.0 - 1.0).coerceIn(-1.0, 1.0)
        val x1 = ((cx + radius + pad) / viewportW * 2.0 - 1.0).coerceIn(-1.0, 1.0)
        val y0 = (1.0 - (cy - radius - pad) / viewportH * 2.0).coerceIn(-1.0, 1.0)
        val y1 = (1.0 - (cy + radius + pad) / viewportH * 2.0).coerceIn(-1.0, 1.0)
        program.use()
        program.set("uRect", x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat())
        program.set("uCenter", cx.toFloat(), cy.toFloat())
        program.set("uRadius", radius.toFloat())
        program.set("uWidth", RING_WIDTH_PX.toFloat())
        program.set("uViewportH", viewportH.toFloat())
        program.set("uColor", color.r / 255f, color.g / 255f, color.b / 255f, color.a / 255f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    companion object {
        /** Ring thickness in device pixels, so the cursor reads the same at any eraser size. */
        const val RING_WIDTH_PX = 1.5

        private val VERTEX_SRC = """#version 300 es
            uniform vec4 uRect;
            void main() {
                float x = (gl_VertexID == 0 || gl_VertexID == 2) ? uRect.x : uRect.z;
                float y = (gl_VertexID == 0 || gl_VertexID == 1) ? uRect.y : uRect.w;
                gl_Position = vec4(x, y, 0.0, 1.0);
            }
        """.trimIndent()

        private val FRAGMENT_SRC = """#version 300 es
            precision mediump float;
            uniform vec2 uCenter;
            uniform float uRadius;
            uniform float uWidth;
            uniform float uViewportH;
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() {
                // GL counts y up from the bottom; the cursor arrives in top-left device pixels.
                vec2 p = vec2(gl_FragCoord.x, uViewportH - gl_FragCoord.y);
                float d = abs(length(p - uCenter) - uRadius);
                float a = 1.0 - smoothstep(uWidth * 0.5 - 0.5, uWidth * 0.5 + 0.5, d);
                if (a <= 0.0) discard;
                fragColor = vec4(uColor.rgb, uColor.a * a);
            }
        """.trimIndent()
    }
}
