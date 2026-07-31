package com.xnotes.gl

import android.opengl.GLES30
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba

/**
 * Solid and outlined rectangles in device space, for the minimap panel and the markers on it.
 *
 * The minimap is drawn from item bounds rather than from the scene's own geometry. Re-running the
 * whole scene through a second transform would cost a second full pass of everything visible on a
 * canvas that may hold a great deal; a dot per item conveys where the work is at a hundredth of
 * the cost, and at minimap scale a stroke is a dot anyway.
 */
class MinimapShader(contextGen: Int) {

    private val program = GlProgram.build(VERTEX_SRC, FRAGMENT_SRC, contextGen)

    val contextGen: Int get() = program.contextGen

    fun release() = program.release()

    /** Fill [rect] (device pixels, y down) with [color]. */
    fun fill(rect: Rect, color: Rgba, viewportW: Int, viewportH: Int) {
        if (rect.w <= 0.0 || rect.h <= 0.0) return
        program.use()
        program.set(
            "uRect",
            (rect.left / viewportW * 2.0 - 1.0).toFloat(),
            (1.0 - rect.top / viewportH * 2.0).toFloat(),
            (rect.right / viewportW * 2.0 - 1.0).toFloat(),
            (1.0 - rect.bottom / viewportH * 2.0).toFloat(),
        )
        program.set("uColor", color.r / 255f, color.g / 255f, color.b / 255f, color.a / 255f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Outline [rect] with a [width]-pixel border, as four fills. */
    fun outline(rect: Rect, width: Double, color: Rgba, viewportW: Int, viewportH: Int) {
        fill(Rect(rect.left, rect.top, rect.w, width), color, viewportW, viewportH)
        fill(Rect(rect.left, rect.bottom - width, rect.w, width), color, viewportW, viewportH)
        fill(Rect(rect.left, rect.top, width, rect.h), color, viewportW, viewportH)
        fill(Rect(rect.right - width, rect.top, width, rect.h), color, viewportW, viewportH)
    }

    companion object {
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
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() { fragColor = uColor; }
        """.trimIndent()
    }
}
