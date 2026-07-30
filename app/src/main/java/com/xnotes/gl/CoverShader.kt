package com.xnotes.gl

import android.opengl.GLES30
import com.xnotes.core.model.Rgba

/**
 * A flat-coloured quad in clip space, used as the cover half of stencil-then-cover.
 *
 * A translucent stroke cannot simply be drawn: its ribbon quads and its round ends overlap, and
 * each overlap would blend a second time and leave the crossing darker. The paged canvas avoids
 * that by accumulating the whole stroke opaquely in a layer and compositing it once. This is the
 * same idea with no layer: the stroke's geometry is written to the stencil, then one quad over its
 * bounds paints the colour through that mask exactly once. Because the stencil is multisampled the
 * cover inherits the geometry's antialiasing, and because the cover zeroes the stencil as it goes
 * there is no clear between strokes.
 */
class CoverShader(contextGen: Int) {

    private val program = GlProgram.build(VERTEX_SRC, FRAGMENT_SRC, contextGen)

    val contextGen: Int get() = program.contextGen

    fun release() = program.release()

    /** Draw [color] over the clip-space rectangle spanning [x0]..[x1] and [y0]..[y1]. */
    fun draw(x0: Float, y0: Float, x1: Float, y1: Float, color: Rgba, alpha: Double) {
        program.use()
        program.set("uRect", x0, y0, x1, y1)
        program.set("uColor", color.r / 255f, color.g / 255f, color.b / 255f, (alpha.coerceIn(0.0, 1.0)).toFloat())
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    companion object {
        private val VERTEX_SRC = """#version 300 es
            uniform vec4 uRect;
            void main() {
                // Corners from the vertex index, so the quad needs no buffer of its own.
                float x = (gl_VertexID == 0 || gl_VertexID == 2) ? uRect.x : uRect.z;
                float y = (gl_VertexID == 0 || gl_VertexID == 1) ? uRect.y : uRect.w;
                gl_Position = vec4(x, y, 0.0, 1.0);
            }
        """.trimIndent()

        private val FRAGMENT_SRC = """#version 300 es
            precision mediump float;
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() { fragColor = uColor; }
        """.trimIndent()
    }
}
