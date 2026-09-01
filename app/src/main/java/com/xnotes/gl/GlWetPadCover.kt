package com.xnotes.gl

import android.opengl.GLES30

/**
 * Puts the canvas's own pixels underneath the ink already on the front buffer.
 *
 * This is what makes the handover free. While a stroke is live the pad is transparent and the
 * canvas shows through it, so the two layers together are what the eye sees. Hiding the pad then
 * has to be timed against the canvas drawing the committed stroke, and the two are latched
 * independently: one refresh either way is a blink or a doubled antialiased edge.
 *
 * So at pen up the canvas underneath is captured and drawn *below* the ink, in the pad, in one
 * front-buffer present. After that the pad holds what the screen was already showing, opaquely, and
 * the canvas beneath it can change without being seen. Hiding it later shows the same picture on
 * whichever refresh it happens to land.
 *
 * Drawn with the destination over the source, which is the whole trick: the ink is already there
 * at its own coverage and the capture fills in behind it, so an edge at half alpha ends up at half
 * alpha over the page rather than composited twice.
 */
class GlWetPadCover(contextGen: Int) {

    private val program = GlProgram.build(VERTEX_SRC, FRAGMENT_SRC, contextGen)

    val contextGen: Int get() = program.contextGen

    fun release() = program.release()

    /** Fill the current viewport with [texture], behind whatever is already there. */
    fun drawUnder(texture: Int) {
        program.use()
        GLES30.glEnable(GLES30.GL_BLEND)
        // Destination over source, on colour and alpha alike: what is there stays, and this fills
        // in by exactly the coverage the ink did not claim.
        GLES30.glBlendFuncSeparate(
            GLES30.GL_ONE_MINUS_DST_ALPHA, GLES30.GL_ONE,
            GLES30.GL_ONE_MINUS_DST_ALPHA, GLES30.GL_ONE,
        )
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        program.set("uTex", 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    companion object {
        private val VERTEX_SRC = """#version 300 es
            out vec2 vUv;
            void main() {
                // Corners from the vertex index, so the quad needs no buffer of its own.
                float x = (gl_VertexID == 0 || gl_VertexID == 2) ? -1.0 : 1.0;
                float y = (gl_VertexID == 0 || gl_VertexID == 1) ? -1.0 : 1.0;
                // A captured bitmap's first row is its top and the surface's is its bottom, so the
                // one flip that has to happen anywhere happens here.
                vUv = vec2((x + 1.0) * 0.5, (1.0 - y) * 0.5);
                gl_Position = vec4(x, y, 0.0, 1.0);
            }
        """.trimIndent()

        private val FRAGMENT_SRC = """#version 300 es
            precision mediump float;
            uniform sampler2D uTex;
            in vec2 vUv;
            out vec4 fragColor;
            void main() { fragColor = vec4(texture(uTex, vUv).rgb, 1.0); }
        """.trimIndent()
    }
}
