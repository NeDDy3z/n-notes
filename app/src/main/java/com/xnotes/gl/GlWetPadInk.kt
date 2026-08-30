package com.xnotes.gl

import android.opengl.GLES30
import android.util.Log
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.MeshPart
import com.xnotes.core.infinite.PixelRect
import com.xnotes.core.infinite.WetDamage
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.floor
import kotlin.math.max

/**
 * The stroke under the pen, drawn into the front buffer.
 *
 * The buffer is the one being scanned out, so a present may only touch pixels it is about to put
 * back correctly. That is the whole shape of this class: work out the damage, rebuild every pixel
 * of it from geometry, and put it down in one blit. Nothing is copied forward and nothing
 * accumulates, so a tail that retracts is not a special case.
 *
 * Ink is antialiased by a multisampled buffer the size of the damage, because the fragment shader
 * emits flat colour and no config with the mutable-render-buffer bit has samples of its own. The
 * resolve is a blit with identical source and destination rectangles, which is the form GLES3
 * allows out of a multisampled read.
 *
 * ### Threads
 *
 * The main thread owns the queue and the session; the render thread owns every GL object and never
 * reads the model. What crosses is five numbers baked at pen down and triangles that were built
 * before they were handed over. The viewport cannot move while a stroke is down, which is what lets
 * the session be baked once.
 */
class GlWetPadInk {

    /** Everything a present needs from the main thread, baked at pen down and never revisited. */
    private class Session(
        val scrollX: Double,
        val scrollY: Double,
        val zoom: Double,
        val width: Int,
        val height: Int,
    )

    /** Triangles handed over, already meshed. A run is appended once; the tail replaces the last. */
    private class Batch(val parts: List<MeshPart>, val settled: Boolean)

    /** An uploaded slice with the pixels it can reach, so a present that misses it can skip it. */
    private class Piece(val slice: BufferSlice, val bounds: PixelRect?)

    private val pending = ConcurrentLinkedQueue<Batch>()

    @Volatile
    private var session: Session? = null

    // --- render thread ---

    private var current: Session? = null
    private var contextGen = -1
    private var ink: InkShader? = null

    private val runs = GeometryStore()
    private var runPieces: List<Piece> = emptyList()

    private val tail = GeometryStore()
    private var tailPieces: List<Piece> = emptyList()

    private var scratchFbo = 0
    private var scratchBuffer = 0
    private var resolveFbo = 0
    private var resolveBuffer = 0
    private var scratchW = 0
    private var scratchH = 0
    private var scratchSamples = 0

    /** Smallest scratch this stroke may use, and the largest bucket it has asked for, per axis. */
    private var floorW = SCRATCH_MIN
    private var floorH = SCRATCH_MIN
    private var peakW = SCRATCH_MIN
    private var peakH = SCRATCH_MIN

    private val damage = PixelRect()
    private val lastTail = PixelRect()

    /** What the last present drew, in view pixels, for the debug readout. */
    @Volatile
    var lastDamage = ""
        private set

    /** Why the last present drew nothing, for the trace. */
    @Volatile
    var why = ""
        private set

    // --- main thread ---

    /** Take the stroke. The view given here is the view the whole stroke is painted through. */
    fun begin(scrollX: Double, scrollY: Double, zoom: Double, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || !zoom.isFinite() || zoom <= 0.0) return false
        pending.clear()
        session = Session(scrollX, scrollY, zoom, width, height)
        return true
    }

    /** Ribbon that has stopped moving, added to what is already down. */
    fun appendRun(parts: List<MeshPart>) {
        if (parts.isEmpty()) return
        pending.add(Batch(parts, settled = true))
    }

    /** The points still in play, replacing whatever was there. */
    fun setTail(parts: List<MeshPart>) {
        pending.add(Batch(parts, settled = false))
    }

    fun end() {
        session = null
    }

    /** Whether a stroke is live, so the pad knows whether a beat has anything to do. */
    val active: Boolean get() = session != null

    // --- render thread ---

    fun onContextCreated(gen: Int) {
        contextGen = gen
        ink = try {
            InkShader(gen)
        } catch (e: GlShaderException) {
            Log.e(TAG, "wet pad ink shader unavailable", e)
            null
        }
        runs.onContextCreated(gen)
        tail.onContextCreated(gen)
        scratchFbo = 0
        scratchBuffer = 0
        resolveFbo = 0
        resolveBuffer = 0
        scratchW = 0
        scratchH = 0
        scratchSamples = 0
        current = null
    }

    /**
     * Paint one present into the bound front buffer, and say whether anything was written.
     *
     * [samples] is what the canvas got for its own surface, so live ink is antialiased to the same
     * standard as the ink it will become.
     */
    fun draw(surfaceW: Int, surfaceH: Int, samples: Int): Boolean {
        val s = session ?: run { why = "no session"; return false }
        val program = ink ?: run { why = "no shader"; return false }
        if (program.contextGen != contextGen) { why = "stale shader"; return false }
        if (s !== current) startSession(s)
        // The view was baked at pen down. A surface that changed size under the stroke would put
        // every pixel somewhere else, so there is nothing to draw that would be right.
        if (surfaceW != s.width || surfaceH != s.height) {
            why = "session ${s.width}x${s.height}"
            return false
        }

        drain(s)
        damage.clampTo(surfaceW, surfaceH)
        if (damage.isEmpty) { why = "no damage, runs=${runPieces.size} tail=${tailPieces.size}"; return false }

        val dw = damage.width
        val dh = damage.height
        peakW = max(peakW, bucket(dw))
        peakH = max(peakH, bucket(dh))
        if (!ensureScratch(max(peakW, floorW), max(peakH, floorH), samples)) return false

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, scratchFbo)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glViewport(0, 0, dw, dh)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        // Separate alpha, unlike the canvas. The canvas draws into an opaque window where the alpha
        // channel is ignored; this is a translucent layer the compositor reads as premultiplied,
        // and the ordinary blend would square the alpha of a sub-pixel stroke and leave its colour
        // too bright for the coverage it claims.
        GLES30.glBlendFuncSeparate(
            GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA,
            GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA,
        )

        val camChunkX = floor(s.scrollX / GeometryStore.CHUNK_SIZE)
        val camChunkY = floor(s.scrollY / GeometryStore.CHUNK_SIZE)
        program.begin(
            camChunkX, camChunkY,
            s.scrollX - camChunkX * GeometryStore.CHUNK_SIZE,
            s.scrollY - camChunkY * GeometryStore.CHUNK_SIZE,
            s.zoom, dw.toDouble(), dh.toDouble(),
            damage.left.toDouble(), damage.top.toDouble(),
        )
        drawBuffer(program, runs, runPieces)
        drawBuffer(program, tail, tailPieces)
        program.disableAttributes()

        // Out of a multisampled read GLES3 allows one shape of blit: identical rectangles. So the
        // resolve lands at the twin's origin, and the twin, which carries no such rule, goes to
        // wherever on the surface the damage is.
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, scratchFbo)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, resolveFbo)
        GLES30.glBlitFramebuffer(0, 0, dw, dh, 0, 0, dw, dh, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        GLES30.glInvalidateFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 1, INVALIDATE_COLOR, 0)

        // The surface counts up from the bottom and the damage counts down from the top.
        val bottom = surfaceH - damage.bottom
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, resolveFbo)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        GLES30.glBlitFramebuffer(
            0, 0, dw, dh,
            damage.left, bottom, damage.left + dw, bottom + dh,
            GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST,
        )
        lastDamage = "${dw}x$dh"
        damage.clear()
        return true
    }

    /** Wipe the whole surface, for the moment the canvas takes the stroke back. */
    fun clearSurface() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    // --- internals ---

    private fun startSession(s: Session) {
        current = s
        floorW = peakW
        floorH = peakH
        peakW = SCRATCH_MIN
        peakH = SCRATCH_MIN
        runs.clear()
        tail.clear()
        runPieces = emptyList()
        tailPieces = emptyList()
        lastTail.clear()
        damage.clear()
    }

    /** Take everything the main thread has handed over, and work out what it dirtied. */
    private fun drain(s: Session) {
        while (true) {
            val batch = pending.poll() ?: break
            if (batch.settled) {
                val added = upload(batch.parts, runs, s)
                if (added.isNotEmpty()) runPieces = runPieces + added
                for (piece in added) piece.bounds?.let { damage.union(it) }
            } else {
                tail.clear()
                tailPieces = upload(batch.parts, tail, s)
                // The tail is rebuilt from scratch and can retract, so where it *was* has to be
                // repainted as well as where it is.
                damage.union(lastTail)
                lastTail.clear()
                for (piece in tailPieces) {
                    val bounds = piece.bounds ?: continue
                    damage.union(bounds)
                    lastTail.union(bounds)
                }
            }
        }
    }

    /** Upload each part and record where it lands, which is both the damage and the cull's box. */
    private fun upload(parts: List<MeshPart>, into: GeometryStore, s: Session): List<Piece> {
        if (parts.isEmpty()) return emptyList()
        val built = ArrayList<Piece>(parts.size)
        for (part in parts) {
            if (part.pass != InkPass.OPAQUE) continue
            val slice = into.put(part.mesh, part.color) ?: continue
            val pixels = PixelRect()
            val bounds = boundsOf(part.mesh.positions)
            // No box means nothing may reject it: a part whose bounds will not map is drawn every
            // present rather than dropped from all of them.
            val mapped = bounds != null &&
                WetDamage.map(bounds, s.scrollX, s.scrollY, s.zoom, OUTSET, pixels)
            built.add(Piece(slice, if (mapped) pixels else null))
        }
        return built
    }

    /** The content-space box of one mesh's triangles. */
    private fun boundsOf(p: DoubleArray): Rect? {
        var lo0 = Double.MAX_VALUE
        var lo1 = Double.MAX_VALUE
        var hi0 = -Double.MAX_VALUE
        var hi1 = -Double.MAX_VALUE
        var any = false
        var i = 0
        while (i < p.size) {
            val x = p[i]
            val y = p[i + 1]
            if (x < lo0) lo0 = x
            if (x > hi0) hi0 = x
            if (y < lo1) lo1 = y
            if (y > hi1) hi1 = y
            any = true
            i += 2
        }
        if (!any) return null
        return Rect(lo0, lo1, hi0 - lo0, hi1 - lo1)
    }

    /**
     * Draw the pieces that reach the damage, which is what keeps a present the size of what moved
     * rather than the size of the stroke. A long stroke is nearly all somewhere else, and a
     * rectangle test throws it out before a vertex is transformed.
     */
    private fun drawBuffer(program: InkShader, store: GeometryStore, pieces: List<Piece>) {
        if (pieces.isEmpty()) return
        if (!store.bindForDraw(contextGen)) return
        store.bindAttributes(program)
        // Runs go into a fresh buffer in order, so a whole stroke's worth land back to back and
        // collapse into one call rather than one per run.
        var start = -1
        var count = 0
        for (piece in pieces) {
            val bounds = piece.bounds
            if (bounds != null && !bounds.intersects(damage)) continue
            val slice = piece.slice
            if (start >= 0 && slice.indexOffset == start + count) {
                count += slice.indexCount
                continue
            }
            if (start >= 0) store.drawRange(start, count)
            start = slice.indexOffset
            count = slice.indexCount
        }
        if (start >= 0) store.drawRange(start, count)
    }

    /**
     * The multisampled buffer a present draws into, and the single-sampled twin it resolves to.
     *
     * A full clear is the fast path on a tiler, so the way to stop paying for a screenful of
     * samples to paint a sliver is to make the attachment smaller rather than to scissor the clear.
     * The two sides are bucketed apart because a stroke's damage is a sliver and a square around it
     * is mostly waste. Grow-only within a stroke, since reallocating mid-stroke costs a present,
     * and each stroke starts at what the last one turned out to need.
     */
    private fun ensureScratch(w: Int, h: Int, samples: Int): Boolean {
        val want = wantedSamples(samples)
        if (scratchFbo != 0 && resolveFbo != 0 && scratchW == w && scratchH == h && scratchSamples == want) {
            return true
        }
        release()
        scratchFbo = framebuffer(w, h, want) ?: return false
        resolveFbo = framebuffer(w, h, 0) ?: return false
        scratchW = w
        scratchH = h
        scratchSamples = want
        return true
    }

    /** A colour-only framebuffer of renderbuffers, because nothing ever samples either of these. */
    private fun framebuffer(w: Int, h: Int, samples: Int): Int? {
        val fbo = IntArray(1)
        val rb = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glGenRenderbuffers(1, rb, 0)
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rb[0])
        if (samples >= 2) {
            GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, samples, GLES30.GL_RGBA8, w, h)
        } else {
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_RGBA8, w, h)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferRenderbuffer(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, rb[0],
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "wet pad target incomplete: $status at ${w}x$h ${samples}x")
            GLES30.glDeleteFramebuffers(1, fbo, 0)
            GLES30.glDeleteRenderbuffers(1, rb, 0)
            return null
        }
        if (samples >= 2) scratchBuffer = rb[0] else resolveBuffer = rb[0]
        return fbo[0]
    }

    private fun bucket(need: Int): Int {
        var size = SCRATCH_MIN
        while (size < need + 2 && size < SCRATCH_MAX) size *= 2
        return size
    }

    private fun wantedSamples(samples: Int): Int {
        if (samples < 2) return 0
        val max = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_SAMPLES, max, 0)
        return minOf(samples, max[0].coerceAtLeast(0))
    }

    private fun release() {
        if (scratchFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(scratchFbo), 0)
        if (resolveFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(resolveFbo), 0)
        if (scratchBuffer != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(scratchBuffer), 0)
        if (resolveBuffer != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(resolveBuffer), 0)
        scratchFbo = 0
        resolveFbo = 0
        scratchBuffer = 0
        resolveBuffer = 0
        scratchW = 0
        scratchH = 0
        scratchSamples = 0
    }

    private companion object {
        const val TAG = "xnotes.gl"

        /** The largest scratch side, in pixels; a damage wider than this is clamped to it. */
        const val SCRATCH_MAX = 1024

        const val SCRATCH_MIN = 16

        /** Read only, and only from the render thread, which is the only thread with a context. */
        val INVALIDATE_COLOR = intArrayOf(GLES30.GL_COLOR_ATTACHMENT0)

        /**
         * Pixels a damaged box grows on every side: one for its own antialiasing, and the width a
         * sub-pixel stroke is pushed back out to before its alpha is taken instead.
         */
        val OUTSET = WetDamage.OUTSET + InkShader.MIN_HALF_WIDTH_PX
    }
}
