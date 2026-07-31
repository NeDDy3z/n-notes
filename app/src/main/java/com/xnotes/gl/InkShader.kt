package com.xnotes.gl

import android.opengl.GLES30
import com.xnotes.core.infinite.CanvasProjection
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
    val attribOffset = program.attrib("aOffset")

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
        viewportW: Double,
        viewportH: Double,
    ) {
        program.use()
        program.set("uCamChunk", camChunkX.toFloat(), camChunkY.toFloat())
        program.set("uChunkSize", GeometryStore.CHUNK_SIZE.toFloat())
        program.set("uLocalScroll", localScrollX.toFloat(), localScrollY.toFloat())
        program.set("uZoom", zoom.toFloat())
        program.set("uViewport", viewportW.toFloat(), viewportH.toFloat())
        program.set("uOffsetScale", (1.0 / GeometryStore.OFFSET_SCALE).toFloat())
        program.set("uMinHalfPx", MIN_HALF_WIDTH_PX)
        setWidthScale(1.0)
        clearLift()
        clearOverride()
    }

    /**
     * Draw this batch turned and shifted, for a selection being dragged.
     *
     * A drag used to move the model itself, which meant re-tessellating and re-uploading every
     * selected item on every touch sample. The vertices never needed to move: the whole design puts
     * the view in a uniform, and a drag is the same kind of thing.
     *
     * [pivotX]/[pivotY] are in the camera's own chunk frame, the same frame the vertices rebuild
     * themselves in, so nothing large is ever subtracted from anything large.
     */
    fun setLift(pivotX: Double, pivotY: Double, cos: Double, sin: Double, dx: Double, dy: Double) {
        program.set("uPivot", pivotX.toFloat(), pivotY.toFloat())
        program.set("uRot", cos.toFloat(), sin.toFloat())
        program.set("uTranslate", dx.toFloat(), dy.toFloat())
    }

    fun clearLift() = setLift(0.0, 0.0, 1.0, 0.0, 0.0, 0.0)

    /**
     * Narrow the ribbon about its own centreline. Neon's white-hot core is the body's very
     * geometry at a fraction of the width, so scaling the stored spine offset draws it from the
     * same vertices rather than from a second tessellation and a second copy in the buffer.
     */
    fun setWidthScale(scale: Double) {
        program.set("uWidthScale", scale.toFloat())
    }

    /** Draw with [color] instead of the baked vertex colour, for neon's body and core layers. */
    fun setOverride(color: Rgba) {
        program.set("uOverride", color.r / 255f, color.g / 255f, color.b / 255f, color.a / 255f)
        program.set("uOverrideMix", 1f)
    }

    fun clearOverride() {
        program.set("uOverrideMix", 0f)
    }

    fun disableAttributes() {
        if (attribLocal >= 0) GLES30.glDisableVertexAttribArray(attribLocal)
        if (attribChunk >= 0) GLES30.glDisableVertexAttribArray(attribChunk)
        if (attribColor >= 0) GLES30.glDisableVertexAttribArray(attribColor)
        if (attribOffset >= 0) GLES30.glDisableVertexAttribArray(attribOffset)
    }

    companion object {
        /**
         * Narrowest half-width a line is ever rasterized at, in device pixels. Below this a line
         * is pushed back out to it and the width it gained is taken out of its alpha, so it fades
         * evenly instead of breaking into a crawling dotted shimmer.
         */
        val MIN_HALF_WIDTH_PX = CanvasProjection.MIN_HALF_WIDTH_PX.toFloat()

        private val VERTEX_SRC = """#version 300 es
            in vec2 aLocal;
            in vec2 aChunk;
            in vec4 aColor;
            in vec2 aOffset;

            uniform vec2 uCamChunk;
            uniform float uChunkSize;
            uniform vec2 uLocalScroll;
            uniform float uZoom;
            uniform vec2 uViewport;
            uniform float uOffsetScale;
            uniform float uMinHalfPx;
            uniform float uWidthScale;
            uniform vec2 uTranslate;
            uniform vec2 uPivot;
            uniform vec2 uRot;
            uniform vec4 uOverride;
            uniform float uOverrideMix;

            out vec4 vColor;

            void main() {
                // Rebuild the position relative to the camera's own chunk, so nothing large is ever
                // subtracted from anything large.
                // The vertex sits its own offset out from the line's centre, so the centre is
                // recoverable and the width can be scaled without touching the buffer.
                vec2 spine = aOffset * uOffsetScale;
                vec2 centre = aLocal - spine;
                spine *= uWidthScale;

                // A dragged selection can be turned as well as shifted. The spine turns with it,
                // which leaves its length alone, so the width the vertex encodes survives the turn
                // and the sub-pixel rule below still measures the right thing. uRot is (1, 0) for
                // everything not under a drag, and the pivot is then zero, so this is exact.
                vec2 pos = (aChunk - uCamChunk) * uChunkSize + centre;
                vec2 rel = pos - uPivot;
                pos = uPivot + vec2(rel.x * uRot.x - rel.y * uRot.y, rel.x * uRot.y + rel.y * uRot.x);
                spine = vec2(spine.x * uRot.x - spine.y * uRot.y, spine.x * uRot.y + spine.y * uRot.x);
                vec2 world = pos + spine + uTranslate;

                // Zoomed out far enough a stroke is thinner than a pixel, and a sub-pixel line does
                // not simply get fainter: it breaks into a dotted shimmer that crawls as the canvas
                // pans. Push the vertex back out to a pixel and take the width it gained straight
                // out of the alpha, so the line stays a line and only its weight drops. A fill has
                // no spine, so its offset is zero and none of this touches it.
                float fade = 1.0;
                float reach = length(spine) * uZoom;
                if (reach > 0.0 && reach < uMinHalfPx) {
                    world += spine * (uMinHalfPx / reach - 1.0);
                    fade = reach / uMinHalfPx;
                }

                vec2 device = (world - uLocalScroll) * uZoom;
                gl_Position = vec4(
                    device.x / uViewport.x * 2.0 - 1.0,
                    1.0 - device.y / uViewport.y * 2.0,
                    0.0, 1.0);
                vec4 base = mix(aColor, uOverride, uOverrideMix);
                vColor = vec4(base.rgb, base.a * fade);
            }
        """.trimIndent()

        private val FRAGMENT_SRC = """#version 300 es
            precision mediump float;
            in vec4 vColor;
            out vec4 fragColor;
            void main() { fragColor = vColor; }
        """.trimIndent()
    }
}
