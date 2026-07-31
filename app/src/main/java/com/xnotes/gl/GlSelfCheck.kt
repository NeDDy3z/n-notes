package com.xnotes.gl

import android.opengl.GLES30
import android.util.Log
import com.xnotes.core.infinite.CanvasProjection
import com.xnotes.core.infinite.MeshData
import com.xnotes.core.model.Rgba
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Renders a handful of cases whose answers are known, reads the pixels back, and says whether the
 * GPU agreed. Runs once when a context comes up, never per frame.
 *
 * The unit tests cover none of this layer and cannot: there is no GL in a JVM test. So everything
 * from the vertex format through the shaders to the blend equations is verified only by someone
 * looking at the screen, and the failures it hides are not exceptions. A uniform left unset
 * multiplies to zero and the canvas is blank. A factor applied twice puts the halo in the corner.
 * Compositing straight alpha through an intermediate layer scales the colour twice and the halo
 * fades to nothing. Every one of those drew perfectly happily.
 *
 * Each check below is one of those bugs, turned into a question with a numeric answer.
 *
 * It is not a proof. It holds the pipeline to my model of what the pipeline should do, so where the
 * model is wrong the check agrees with the bug. It catches regressions and typos, not design errors.
 */
class GlSelfCheck {

    class Result(val failures: List<String>, val notes: List<String>) {
        val ok: Boolean get() = failures.isEmpty()

        /** One line for the debug readout. */
        val summary: String
            get() = if (ok) "ok${if (notes.isEmpty()) "" else " (${notes.joinToString(", ")})"}"
            else failures.joinToString("; ")
    }

    private val pixels: ByteBuffer =
        ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())

    /** The check's own buffer, so a pass that wandered off to a glow buffer can come back. */
    private var framebuffer = 0

    /** Run every check against the live context. Never throws; a broken driver is a Result. */
    fun run(contextGen: Int): Result {
        val startedNs = System.nanoTime()
        val failures = ArrayList<String>()
        val notes = ArrayList<String>()
        checkCapabilities(failures, notes)

        var ink: InkShader? = null
        var glow: GlowShader? = null
        val store = GeometryStore()
        val glowTarget = GlowTarget()
        var texture = 0
        try {
            ink = InkShader(contextGen)
            glow = GlowShader(contextGen)
            store.onContextCreated(contextGen)
            glowTarget.onContextCreated(contextGen)
            glowTarget.resize(SIZE * GlowTarget.DOWNSCALE, SIZE * GlowTarget.DOWNSCALE, contextGen)

            val names = IntArray(1)
            GLES30.glGenFramebuffers(1, names, 0)
            framebuffer = names[0]
            GLES30.glGenTextures(1, names, 0)
            texture = names[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, SIZE, SIZE, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            bindSelf()
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0,
            )
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                failures += "offscreen buffer incomplete (0x${Integer.toHexString(status)})"
                return Result(failures, notes)
            }
            // The readback has to be the shader's own answer, so nothing may round or drop it.
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            GLES30.glDisable(GLES30.GL_DITHER)
            GLES30.glDepthMask(false)

            checkProjection(ink, store, contextGen, failures)
            checkColor(ink, store, contextGen, failures)
            checkComposite(ink, glow, store, glowTarget, contextGen, failures)
            drainErrors()?.let { failures += "GL error during the check: $it" }
        } catch (e: GlShaderException) {
            failures += "shaders unavailable: ${e.message}"
        } catch (e: RuntimeException) {
            failures += "self check aborted: $e"
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            ink?.disableAttributes()
            ink?.release()
            glow?.release()
            store.release()
            glowTarget.release()
            if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            framebuffer = 0
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
            GLES30.glDisable(GLES30.GL_BLEND)
            drainErrors()
        }

        notes += "%.1f ms".format((System.nanoTime() - startedNs) / 1_000_000.0)
        val result = Result(failures, notes)
        if (result.ok) Log.i(TAG, "self check ${result.summary}")
        else for (f in failures) Log.e(TAG, "self check: $f")
        return result
    }

    /**
     * What the driver actually gave us, as opposed to what was asked for. Multisampling is not
     * checked here: the config chooser walks a ladder down to none on purpose, and the count it
     * settled on is already on the readout.
     */
    private fun checkCapabilities(failures: MutableList<String>, notes: MutableList<String>) {
        drainErrors()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val v = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_STENCIL_BITS, v, 0)
        // Translucent ink and the highlighter are stencilled and then covered, so without a stencil
        // buffer a self-crossing highlighter darkens at every crossing instead of compositing once.
        if (v[0] < 8) failures += "stencil ${v[0]} bits, translucent ink cannot mask"
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, v, 0)
        if (v[0] < MIN_TEXTURE_SIZE) failures += "max texture ${v[0]}, below the ES 3.0 floor"
        notes += "tex ${v[0]}"
        drainErrors()?.let { failures += "GL error before the first frame: $it" }
    }

    /**
     * Does a vertex land where the arithmetic says it lands?
     *
     * The quad is placed a million pixels out and straddles a chunk boundary, so the chunk index,
     * the local offset and the camera's own chunk all have to be handled for it to come out square.
     * The whole buffer is read back and the covered region compared against [CanvasProjection],
     * which is the same formula the vertex shader states in GLSL. A factor applied twice halves
     * that region and moves it into a corner, which is exactly what a halo drifting away from its
     * stroke looked like.
     */
    private fun checkProjection(
        ink: InkShader,
        store: GeometryStore,
        contextGen: Int,
        failures: MutableList<String>,
    ) {
        val scrollX = CHUNK_BOUNDARY - 8.0
        val scrollY = 2_000_000.0
        val zoom = 2.0
        val left = scrollX + 4.0
        val top = scrollY + 8.0
        val right = scrollX + 24.0
        val bottom = scrollY + 24.0
        val slice = store.put(quad(left, top, right, bottom), MARK) ?: run {
            failures += "geometry would not upload"
            return
        }

        bindSelf()
        clear(0f, 0f, 0f, 1f)
        beginInk(ink, store, contextGen, scrollX, scrollY, zoom)
        GLES30.glDisable(GLES30.GL_BLEND)
        store.drawRange(slice.indexOffset, slice.indexCount)
        readPixels()

        val camera = CanvasProjection.Camera(scrollX, scrollY, zoom, SIZE.toDouble(), SIZE.toDouble())
        val topLeft = CanvasProjection.devicePoint(CanvasProjection.Vertex.of(left, top), camera)
        val bottomRight =
            CanvasProjection.devicePoint(CanvasProjection.Vertex.of(right, bottom), camera)
        // A pixel lights when its centre falls inside, so the far edges land one column short.
        val expected = doubleArrayOf(topLeft.x, topLeft.y, bottomRight.x - 1.0, bottomRight.y - 1.0)
        val box = coveredBox()
        if (box == null) {
            failures += "projection drew nothing"
            store.free(slice)
            return
        }
        val names = arrayOf("left", "top", "right", "bottom")
        for (i in 0 until 4) {
            val drift = abs(box[i] - expected[i])
            if (drift > EDGE_TOLERANCE) failures += "projection ${names[i]} off by %.1f px".format(drift)
        }
        // A chunk mishandled on one side of the seam tears the quad rather than moving it.
        if (hasGapAcross(box)) failures += "geometry torn at a chunk boundary"
        store.free(slice)
    }

    /**
     * Does a known colour survive the shader?
     *
     * Three draws: the baked vertex colour, the override neon paints its body with, and the vertex
     * colour again once the override is cleared. The blank canvas was a uniform that no longer had
     * an initializer, so every fragment came out transparent black while the geometry, the buffers
     * and the projection were all perfect.
     *
     * The three go into three bands of one buffer and come back in one read. A readback stalls
     * until the GPU has caught up, which on a tile-based part means resolving the tile, and it is
     * far and away the most expensive thing here.
     */
    private fun checkColor(
        ink: InkShader,
        store: GeometryStore,
        contextGen: Int,
        failures: MutableList<String>,
    ) {
        val slice = store.put(fullBuffer(), MARK) ?: run {
            failures += "geometry would not upload"
            return
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        bindSelf()
        clear(0f, 0f, 0f, 1f)
        beginInk(ink, store, contextGen, 0.0, 0.0, 1.0)

        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        scissorBand(0)
        store.drawRange(slice.indexOffset, slice.indexCount)
        scissorBand(1)
        ink.setOverride(OVERRIDE)
        store.drawRange(slice.indexOffset, slice.indexCount)
        scissorBand(2)
        ink.clearOverride()
        store.drawRange(slice.indexOffset, slice.indexCount)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)

        readPixels()
        compare("vertex colour", bandCentre(0), MARK, failures)
        compare("colour override", bandCentre(1), OVERRIDE, failures)
        compare("override cleared", bandCentre(2), MARK, failures)
        store.free(slice)
    }

    /** Restrict drawing to one of the three horizontal bands the colour cases share. */
    private fun scissorBand(index: Int) {
        val height = SIZE / 3
        GLES30.glScissor(0, SIZE - (index + 1) * height, SIZE, height)
    }

    /** The readback row at the middle of band [index]; rows arrive bottom up. */
    private fun bandCentre(index: Int): Int {
        val height = SIZE / 3
        return SIZE - 1 - (index * height + height / 2)
    }

    /**
     * Does a halo still weigh what it should after the trip through the glow buffers?
     *
     * The field is partly covered, and that is the whole test. A halo reaches the composite as
     * colour already scaled by coverage alongside the coverage itself, which is what premultiplied
     * means, and the two ways of compositing only part company where coverage is below one. At full
     * coverage they agree exactly, so a solid field would have passed the bug straight through.
     *
     * Straight alpha scales the colour by the coverage a second time. That took a halo at 0.42 down
     * to roughly a seventh of its weight, once per hop, and read on screen as the halo not being
     * there at all.
     */
    private fun checkComposite(
        ink: InkShader,
        glow: GlowShader,
        store: GeometryStore,
        target: GlowTarget,
        contextGen: Int,
        failures: MutableList<String>,
    ) {
        if (!target.ready) {
            failures += "glow buffers would not allocate"
            return
        }
        val slice = store.put(fullBuffer(), HALO_PREMULTIPLIED) ?: run {
            failures += "geometry would not upload"
            return
        }

        target.bind(0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        beginInk(ink, store, contextGen, 0.0, 0.0, 1.0)
        store.drawRange(slice.indexOffset, slice.indexCount)
        ink.disableAttributes()

        // A normalized kernel over a uniform field returns the field, so whatever comes out of the
        // two blur passes is still exactly what went in, whatever shape the Gaussian has.
        target.bind(1)
        glow.blur(target.texture(0), BLUR_RADIUS, true, target.bufferWidth, target.bufferHeight)
        target.bind(0)
        glow.blur(target.texture(1), BLUR_RADIUS, false, target.bufferWidth, target.bufferHeight)

        bindSelf()
        clear(BACKDROP.r / 255f, BACKDROP.g / 255f, BACKDROP.b / 255f, 1f)
        glow.compositeOver(target.texture(0), HALO_ALPHA)

        // Premultiplied over: the source contributes its own colour times the halo's brightness,
        // and hides the backdrop only in proportion to its coverage.
        val hiding = (HALO_PREMULTIPLIED.a / 255.0) * HALO_ALPHA
        val expected = Rgba(
            over(HALO_PREMULTIPLIED.r, BACKDROP.r, hiding),
            over(HALO_PREMULTIPLIED.g, BACKDROP.g, hiding),
            over(HALO_PREMULTIPLIED.b, BACKDROP.b, hiding),
            255,
        )
        compareCentre("glow composite", expected, failures, COMPOSITE_TOLERANCE)
        store.free(slice)
    }

    // --- helpers ---

    private fun over(source: Int, backdrop: Int, hiding: Double): Int =
        (source * HALO_ALPHA + backdrop * (1.0 - hiding)).roundToInt().coerceIn(0, 255)

    /** Bind the ink program for a view, leaving whichever buffer the caller chose bound. */
    private fun beginInk(
        ink: InkShader,
        store: GeometryStore,
        contextGen: Int,
        scrollX: Double,
        scrollY: Double,
        zoom: Double,
    ) {
        val camChunkX = CanvasProjection.chunkIndex(scrollX)
        val camChunkY = CanvasProjection.chunkIndex(scrollY)
        ink.begin(
            camChunkX, camChunkY,
            CanvasProjection.localScroll(scrollX, camChunkX),
            CanvasProjection.localScroll(scrollY, camChunkY),
            zoom, SIZE.toDouble(), SIZE.toDouble(),
        )
        store.bindForDraw(contextGen)
        store.bindAttributes(ink)
    }

    /** Point drawing back at the check's own 64x64 buffer after a glow pass took it away. */
    private fun bindSelf() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, SIZE, SIZE)
    }

    private fun clear(r: Float, g: Float, b: Float, a: Float) {
        GLES30.glClearColor(r, g, b, a)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    private fun readPixels() {
        pixels.position(0)
        GLES30.glReadPixels(0, 0, SIZE, SIZE, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
    }

    /**
     * A quad far larger than the buffer, so the checks that ask about colour still cover the pixel
     * they read even when the projection is the thing that is broken. One failure reporting itself
     * as three is a worse readout than one.
     */
    private fun fullBuffer(): MeshData {
        val reach = (SIZE * 4).toDouble()
        return quad(-reach, -reach, SIZE + reach, SIZE + reach)
    }

    /** A filled rectangle in content space, with no spine, so the sub-pixel rule leaves it alone. */
    private fun quad(left: Double, top: Double, right: Double, bottom: Double) = MeshData(
        doubleArrayOf(left, top, right, top, right, bottom, left, bottom),
        DoubleArray(8),
        intArrayOf(0, 1, 2, 0, 2, 3),
    )

    /** Read the centre pixel and compare it against what the arithmetic says it should be. */
    private fun compareCentre(
        what: String,
        expected: Rgba,
        failures: MutableList<String>,
        tolerance: Int = COLOR_TOLERANCE,
    ) {
        readPixels()
        compare(what, SIZE / 2, expected, failures, tolerance)
    }

    /** Compare the middle of readback row [row] against [expected]; the pixels are already read. */
    private fun compare(
        what: String,
        row: Int,
        expected: Rgba,
        failures: MutableList<String>,
        tolerance: Int = COLOR_TOLERANCE,
    ) {
        val at = (row * SIZE + SIZE / 2) * 4
        val r = pixels.get(at).toInt() and 0xFF
        val g = pixels.get(at + 1).toInt() and 0xFF
        val b = pixels.get(at + 2).toInt() and 0xFF
        val off = maxOf(abs(r - expected.r), abs(g - expected.g), abs(b - expected.b))
        if (off > tolerance) {
            failures += "$what read $r,$g,$b, expected ${expected.r},${expected.g},${expected.b}"
        }
    }

    /** Bounding box of anything that is not the clear colour, in device pixels with y down. */
    private fun coveredBox(): IntArray? {
        var minX = SIZE
        var minY = SIZE
        var maxX = -1
        var maxY = -1
        for (row in 0 until SIZE) {
            for (col in 0 until SIZE) {
                if (!covered(row, col)) continue
                // glReadPixels hands back rows bottom up; the projection works top down.
                val y = SIZE - 1 - row
                if (col < minX) minX = col
                if (col > maxX) maxX = col
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return null
        return intArrayOf(minX, minY, maxX, maxY)
    }

    /** True when the box's middle row has a hole in it, which a mishandled chunk would leave. */
    private fun hasGapAcross(box: IntArray): Boolean {
        val row = SIZE - 1 - (box[1] + box[3]) / 2
        if (row !in 0 until SIZE) return false
        for (col in box[0]..box[2]) if (!covered(row, col)) return true
        return false
    }

    private fun covered(row: Int, col: Int): Boolean {
        val at = (row * SIZE + col) * 4
        val r = pixels.get(at).toInt() and 0xFF
        val g = pixels.get(at + 1).toInt() and 0xFF
        val b = pixels.get(at + 2).toInt() and 0xFF
        return r + g + b > 24
    }

    /** Drain and report any pending GL error, so the next check starts from a clean slate. */
    private fun drainErrors(): String? {
        var seen: String? = null
        var err = GLES30.glGetError()
        while (err != GLES30.GL_NO_ERROR) {
            if (seen == null) seen = "0x${Integer.toHexString(err)}"
            err = GLES30.glGetError()
        }
        return seen
    }

    companion object {
        private const val TAG = "xnotes.gl"

        /** Edge of the check's own buffer. Small enough to read back in microseconds. */
        private const val SIZE = 64

        /** Device pixels an edge may drift before it counts as misplaced. */
        private const val EDGE_TOLERANCE = 1.0

        /** Eight-bit channels round trip exactly, so anything past this is a real error. */
        private const val COLOR_TOLERANCE = 2

        /** The glow path rounds through three eight-bit buffers and a mediump blur. */
        private const val COMPOSITE_TOLERANCE = 5

        /** The ES 3.0 floor; below it the driver is not the API it claims to be. */
        private const val MIN_TEXTURE_SIZE = 2048

        /** A chunk boundary a long way out, so the split is genuinely exercised. */
        private const val CHUNK_BOUNDARY = 245 * CanvasProjection.CHUNK_SIZE

        private const val HALO_ALPHA = 0.42
        private const val BLUR_RADIUS = 6.0

        private val MARK = Rgba(37, 121, 214, 255)
        private val OVERRIDE = Rgba(214, 87, 37, 255)

        /**
         * A neon halo at half coverage, as the blur hands it over: the colour already scaled by the
         * coverage, and the coverage in the alpha. Half rather than full precisely so the two ways
         * of compositing it disagree.
         */
        private val HALO_PREMULTIPLIED = Rgba(12, 116, 65, 128)
        private val BACKDROP = Rgba(20, 20, 20, 255)
    }
}
