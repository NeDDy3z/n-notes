package com.xnotes.gl

import android.opengl.GLES30
import com.xnotes.core.model.Rgba

/**
 * Draws committed geometry. Zoom and scroll arrive as uniforms and the vertices never move, which
 * is the whole point of the design: nothing is rasterized at a scale that can then be wrong, so a
 * pinch has no blur to resolve when it settles.
 *
 * The vertex shader reassembles a world position from the chunk index and local offset the store
 * split it into. Every intermediate stays small: the chunk difference is a handful of units for
 * anything on screen, and the local offset is under one chunk, so the arithmetic is as exact a
 * hundred million pixels out as it is at the origin.
 */
class InkShader(contextGen: Int) {

    private val program = GlProgram.build(VERTEX_SRC, FRAGMENT_SRC, contextGen)

    val contextGen: Int get() = program.contextGen
    val attribLocal = program.attrib("aLocal")
    val attribChunk = program.attrib("aChunk")
    val attribColor = program.attrib("aColor")

    fun release() = program.release()

    /**
     * Bind the program for a frame. [camChunkX]/[camChunkY] are the chunk the viewport's origin
     * falls in, and [localScrollX]/[localScrollY] the scroll expressed inside that chunk, so both
     * the uniform and the attribute stay under a chunk's span.
     */
    fun begin(
        camChunkX: Double,
        camChunkY: Double,
        localScrollX: Double,
        localScrollY: Double,
        zoom: Double,
        viewportW: Int,
        viewportH: Int,
    ) {
        program.use()
        program.set("uCamChunk", camChunkX.toFloat(), camChunkY.toFloat())
        program.set("uChunkSize", GeometryStore.CHUNK_SIZE.toFloat())
        program.set("uLocalScroll", localScrollX.toFloat(), localScrollY.toFloat())
        program.set("uZoom", zoom.toFloat())
        program.set("uViewport", viewportW.toFloat(), viewportH.toFloat())
        setTint(NO_TINT)
    }

    /**
     * Multiply every vertex colour by [tint]. Left at opaque white for normal ink; used to fade a
     * stroke whose true width has fallen under a pixel, and to force full alpha while a translucent
     * stroke is accumulating into the stencil.
     */
    fun setTint(tint: Rgba) {
        program.set("uTint", tint.r / 255f, tint.g / 255f, tint.b / 255f, tint.a / 255f)
    }

    fun disableAttributes() {
        if (attribLocal >= 0) GLES30.glDisableVertexAttribArray(attribLocal)
        if (attribChunk >= 0) GLES30.glDisableVertexAttribArray(attribChunk)
        if (attribColor >= 0) GLES30.glDisableVertexAttribArray(attribColor)
    }

    companion object {
        val NO_TINT = Rgba(255, 255, 255, 255)

        private val VERTEX_SRC = """#version 300 es
            in vec2 aLocal;
            in vec2 aChunk;
            in vec4 aColor;

            uniform vec2 uCamChunk;
            uniform float uChunkSize;
            uniform vec2 uLocalScroll;
            uniform float uZoom;
            uniform vec2 uViewport;

            out vec4 vColor;

            void main() {
                // Rebuild the position relative to the camera's own chunk, so nothing large is ever
                // subtracted from anything large.
                vec2 world = (aChunk - uCamChunk) * uChunkSize + aLocal;
                vec2 device = (world - uLocalScroll) * uZoom;
                gl_Position = vec4(
                    device.x / uViewport.x * 2.0 - 1.0,
                    1.0 - device.y / uViewport.y * 2.0,
                    0.0, 1.0);
                vColor = aColor;
            }
        """.trimIndent()

        private val FRAGMENT_SRC = """#version 300 es
            precision mediump float;
            in vec4 vColor;
            uniform vec4 uTint;
            out vec4 fragColor;
            void main() {
                vec4 c = vColor * uTint;
                // Premultiply so the standard blend function composites a faded stroke correctly.
                fragColor = vec4(c.rgb, c.a);
            }
        """.trimIndent()
    }
}
