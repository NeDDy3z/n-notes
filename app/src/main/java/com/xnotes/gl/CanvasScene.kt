package com.xnotes.gl

import android.opengl.GLES30
import android.util.Log
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.infinite.MeshData
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Rgba
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.floor

/**
 * The GL-thread-owned mirror of the document: one record per committed item, holding where its
 * triangles live and what it takes to draw them. The model itself is never touched from here.
 * Edits arrive as messages, already tessellated on the thread that made them, so a frame never
 * races an edit and tessellation never blocks the render thread.
 */
class CanvasScene(private val store: GeometryStore = GeometryStore()) : GlScene {

    private class Record(
        val item: CanvasItem,
        var slice: BufferSlice,
        var bounds: Rect,
        var pass: InkPass,
        var coverColor: Rgba,
        var coverAlpha: Double,
        var z: Int,
    )

    /** An edit handed over from the main thread. */
    private sealed class Edit {
        class Upsert(
            val item: CanvasItem,
            val mesh: MeshData,
            val color: Rgba,
            val pass: InkPass,
            val bounds: Rect,
        ) : Edit()

        /** The stroke under the pen, re-tessellated as samples arrive; a null mesh clears it. */
        class Wet(
            val mesh: MeshData?,
            val color: Rgba,
            val pass: InkPass,
            val bounds: Rect,
        ) : Edit()

        class Remove(val item: CanvasItem) : Edit()
        class Order(val items: List<CanvasItem>) : Edit()
        object Reset : Edit()
    }

    private val pending = ConcurrentLinkedQueue<Edit>()

    /**
     * The stroke under the pen lives in its own small buffer, rewritten every time a sample
     * arrives, so growing it never disturbs the committed geometry's allocation. On pen up it is
     * cleared and the finished stroke arrives through the normal upsert path.
     */
    private val wetStore = GeometryStore()
    private var wetSlice: BufferSlice? = null
    private var wetColor = Rgba(0, 0, 0, 255)
    private var wetPass = InkPass.OPAQUE
    private var wetBounds = Rect(0.0, 0.0, 0.0, 0.0)
    private val records = IdentityHashMap<CanvasItem, Record>()

    /** Records bucketed by content chunk, so a frame only visits what the viewport overlaps. */
    private val buckets = HashMap<Long, ArrayList<Record>>()
    private val oversized = ArrayList<Record>()

    /** Next unseen depth, for an item that arrives before its order message. */
    private var nextZ = 0

    private var ink: InkShader? = null
    private var cover: CoverShader? = null
    private var contextGen = -1

    /** Reused per frame so drawing allocates nothing. */
    private val visible = ArrayList<Record>()
    private val deferred = ArrayList<Record>()

    /** Items currently drawn, for the debug readout. */
    val itemCount: Int get() = records.size

    var lastDrawCalls: Int = 0
        private set

    var lastVisibleItems: Int = 0
        private set

    // --- main-thread API ---

    fun upsert(item: CanvasItem, mesh: MeshData, color: Rgba, pass: InkPass, bounds: Rect) {
        pending.add(Edit.Upsert(item, mesh, color, pass, bounds))
    }

    fun remove(item: CanvasItem) {
        pending.add(Edit.Remove(item))
    }

    /** Publish the document's z order; the scene draws back to front by it. */
    fun setOrder(items: List<CanvasItem>) {
        pending.add(Edit.Order(ArrayList(items)))
    }

    /** Publish the in-progress stroke's triangles, or clear it when [mesh] is null. */
    fun setWet(mesh: MeshData?, color: Rgba, pass: InkPass, bounds: Rect) {
        pending.add(Edit.Wet(mesh, color, pass, bounds))
    }

    fun reset() {
        pending.add(Edit.Reset)
    }

    // --- GL thread ---

    override fun onContextCreated(contextGen: Int) {
        this.contextGen = contextGen
        ink = null
        cover = null
        try {
            ink = InkShader(contextGen)
            cover = CoverShader(contextGen)
        } catch (e: GlShaderException) {
            Log.e(TAG, "ink shaders unavailable", e)
        }
        // The mirrors survived, so the whole document re-uploads without re-tessellating anything.
        store.onContextCreated(contextGen)
        wetStore.onContextCreated(contextGen)
    }

    override fun drawContent(frame: FrameState) {
        drainEdits()
        val program = ink ?: return
        if (program.contextGen != contextGen) return
        if (records.isEmpty() && wetSlice == null) {
            lastDrawCalls = 0
            lastVisibleItems = 0
            return
        }

        val camChunkX = floor(frame.scrollX / GeometryStore.CHUNK_SIZE)
        val camChunkY = floor(frame.scrollY / GeometryStore.CHUNK_SIZE)
        program.begin(
            camChunkX, camChunkY,
            frame.scrollX - camChunkX * GeometryStore.CHUNK_SIZE,
            frame.scrollY - camChunkY * GeometryStore.CHUNK_SIZE,
            frame.zoom, frame.widthPx, frame.heightPx,
        )
        if (!store.bindForDraw(contextGen)) return
        store.bindAttributes(program)


        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        collectVisible(frame)
        lastVisibleItems = visible.size
        lastDrawCalls = 0

        // Back to front, batching every run of opaque items that happens to be contiguous in the
        // buffer into one call. Freshly loaded content is laid down in z order, so a screenful of
        // it is usually a single draw.
        deferred.clear()
        var runStart = -1
        var runCount = 0
        for (record in visible) {
            when (record.pass) {
                InkPass.MULTIPLY -> {
                    // Highlighters composite over the finished picture, matching the paged canvas,
                    // so their multiply darkens everything beneath instead of washing it out.
                    deferred.add(record)
                }
                InkPass.OPAQUE -> {
                    if (runStart >= 0 && record.slice.indexOffset == runStart + runCount) {
                        runCount += record.slice.indexCount
                    } else {
                        flushRun(runStart, runCount)
                        runStart = record.slice.indexOffset
                        runCount = record.slice.indexCount
                    }
                }
                InkPass.TRANSLUCENT -> {
                    flushRun(runStart, runCount)
                    runStart = -1
                    runCount = 0
                    drawMasked(record, frame, multiply = false)
                    program.begin(
                        camChunkX, camChunkY,
                        frame.scrollX - camChunkX * GeometryStore.CHUNK_SIZE,
                        frame.scrollY - camChunkY * GeometryStore.CHUNK_SIZE,
                        frame.zoom, frame.widthPx, frame.heightPx,
                    )
                }
            }
        }
        flushRun(runStart, runCount)

        for (record in deferred) {
            drawMasked(record, frame, multiply = true)
            program.begin(
                camChunkX, camChunkY,
                frame.scrollX - camChunkX * GeometryStore.CHUNK_SIZE,
                frame.scrollY - camChunkY * GeometryStore.CHUNK_SIZE,
                frame.zoom, frame.widthPx, frame.heightPx,
            )
        }

        drawWet(program, frame, camChunkX, camChunkY)

        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        program.disableAttributes()
    }

    /**
     * The stroke under the pen, drawn last because it is the newest thing on the canvas. It lives
     * in its own buffer, so it is bound over the committed one for these few triangles and the
     * committed geometry's allocation is never disturbed while a stroke grows.
     */
    private fun drawWet(program: InkShader, frame: FrameState, camChunkX: Double, camChunkY: Double) {
        val slice = wetSlice ?: return
        if (!wetStore.bindForDraw(contextGen)) return
        wetStore.bindAttributes(program)
        if (wetPass == InkPass.OPAQUE) {
            wetStore.drawRange(slice.indexOffset, slice.indexCount)
            lastDrawCalls++
        } else {
            drawMasked(
                wetStore, slice, wetBounds, wetColor.withAlpha(255), wetColor.a / 255.0,
                frame, multiply = wetPass == InkPass.MULTIPLY,
            )
            program.begin(
                camChunkX, camChunkY,
                frame.scrollX - camChunkX * GeometryStore.CHUNK_SIZE,
                frame.scrollY - camChunkY * GeometryStore.CHUNK_SIZE,
                frame.zoom, frame.widthPx, frame.heightPx,
            )
        }
        // Leave the committed buffers bound for the next frame's first draw.
        store.bindForDraw(contextGen)
        store.bindAttributes(program)
    }

    private fun flushRun(start: Int, count: Int) {
        if (start < 0 || count <= 0) return
        store.drawRange(start, count)
        lastDrawCalls++
    }

    /**
     * Stencil the stroke, then paint its colour through the mask exactly once. The cover zeroes the
     * stencil as it draws, so consecutive strokes need no clear between them.
     */
    private fun drawMasked(record: Record, frame: FrameState, multiply: Boolean) =
        drawMasked(store, record.slice, record.bounds, record.coverColor, record.coverAlpha, frame, multiply)

    private fun drawMasked(
        from: GeometryStore,
        slice: BufferSlice,
        bounds: Rect,
        coverColor: Rgba,
        coverAlpha: Double,
        frame: FrameState,
        multiply: Boolean,
    ) {
        val quad = cover ?: return
        GLES30.glEnable(GLES30.GL_STENCIL_TEST)
        GLES30.glStencilFunc(GLES30.GL_ALWAYS, 1, 0xFF)
        GLES30.glStencilOp(GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_REPLACE)
        GLES30.glStencilMask(0xFF)
        GLES30.glColorMask(false, false, false, false)
        from.drawRange(slice.indexOffset, slice.indexCount)
        lastDrawCalls++

        GLES30.glColorMask(true, true, true, true)
        GLES30.glStencilFunc(GLES30.GL_EQUAL, 1, 0xFF)
        GLES30.glStencilOp(GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_ZERO)
        if (multiply) {
            // Exact multiply: the destination is scaled by the source and nothing is added.
            GLES30.glBlendFunc(GLES30.GL_DST_COLOR, GLES30.GL_ZERO)
        } else {
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }
        val r = clipRect(bounds, frame)
        // A multiply cover must present the ink already faded toward white by its own alpha, since
        // the blend function cannot apply an alpha of its own.
        val color = if (multiply) ItemMesher.multiplyColor(coverColor, coverAlpha) else coverColor
        quad.draw(r[0], r[1], r[2], r[3], color, if (multiply) 1.0 else coverAlpha)
        lastDrawCalls++

        GLES30.glDisable(GLES30.GL_STENCIL_TEST)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    /** Content bounds to clip space, grown a couple of pixels so the mask is never clipped short. */
    private fun clipRect(bounds: Rect, frame: FrameState): FloatArray {
        val pad = 2.0
        val x0 = (bounds.left - frame.scrollX) * frame.zoom - pad
        val y0 = (bounds.top - frame.scrollY) * frame.zoom - pad
        val x1 = (bounds.right - frame.scrollX) * frame.zoom + pad
        val y1 = (bounds.bottom - frame.scrollY) * frame.zoom + pad
        val w = frame.widthPx.toDouble()
        val h = frame.heightPx.toDouble()
        return floatArrayOf(
            (x0 / w * 2.0 - 1.0).coerceIn(-1.0, 1.0).toFloat(),
            (1.0 - y0 / h * 2.0).coerceIn(-1.0, 1.0).toFloat(),
            (x1 / w * 2.0 - 1.0).coerceIn(-1.0, 1.0).toFloat(),
            (1.0 - y1 / h * 2.0).coerceIn(-1.0, 1.0).toFloat(),
        )
    }

    // --- record bookkeeping ---

    private fun drainEdits() {
        while (true) {
            when (val edit = pending.poll() ?: return) {
                is Edit.Upsert -> applyUpsert(edit)
                is Edit.Wet -> applyWet(edit)
                is Edit.Remove -> applyRemove(edit.item)
                is Edit.Order -> applyOrder(edit.items)
                Edit.Reset -> applyReset()
            }
        }
    }

    private fun applyUpsert(edit: Edit.Upsert) {
        // An item edited in place keeps its depth: only a structural change reorders, and that
        // arrives as its own message.
        val previousZ = records[edit.item]?.z
        applyRemove(edit.item)
        // A translucent stroke accumulates at full alpha and gets its alpha back at cover time,
        // which is what stops its own overlaps from compounding.
        val baked = if (edit.pass == InkPass.OPAQUE) edit.color else edit.color.withAlpha(255)
        val slice = store.put(edit.mesh, baked) ?: return
        val record = Record(
            item = edit.item,
            slice = slice,
            bounds = edit.bounds,
            pass = edit.pass,
            coverColor = edit.color.withAlpha(255),
            coverAlpha = edit.color.a / 255.0,
            z = previousZ ?: nextZ++,
        )
        records[edit.item] = record
        fileRecord(record)
    }

    private fun applyWet(edit: Edit.Wet) {
        // The whole buffer is rewritten rather than appended to, so a long stroke does not leave a
        // trail of dead allocations behind it.
        wetStore.clear()
        wetSlice = null
        val mesh = edit.mesh ?: return
        wetColor = edit.color
        wetPass = edit.pass
        wetBounds = edit.bounds
        val baked = if (edit.pass == InkPass.OPAQUE) edit.color else edit.color.withAlpha(255)
        wetSlice = wetStore.put(mesh, baked)
    }

    private fun applyRemove(item: CanvasItem) {
        val record = records.remove(item) ?: return
        unfileRecord(record)
        store.free(record.slice)
    }

    private fun applyOrder(items: List<CanvasItem>) {
        for (i in items.indices) records[items[i]]?.z = i
    }

    private fun applyReset() {
        nextZ = 0
        records.clear()
        buckets.clear()
        oversized.clear()
        store.clear()
    }

    // --- culling ---

    private fun collectVisible(frame: FrameState) {
        visible.clear()
        val view = Rect(
            frame.scrollX,
            frame.scrollY,
            frame.widthPx / frame.zoom,
            frame.heightPx / frame.zoom,
        )
        for (record in oversized) if (record.bounds.intersects(view)) visible.add(record)
        val x0 = chunkOf(view.left)
        val y0 = chunkOf(view.top)
        val x1 = chunkOf(view.right)
        val y1 = chunkOf(view.bottom)
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong() > MAX_QUERY_CELLS) {
            // Zoomed so far out that walking cells costs more than testing everything.
            for (record in records.values) if (record.bounds.intersects(view)) visible.add(record)
            visible.sortBy { it.z }
            dedupeSorted()
            return
        }
        for (cy in y0..y1) {
            for (cx in x0..x1) {
                val bucket = buckets[key(cx, cy)] ?: continue
                for (record in bucket) if (record.bounds.intersects(view)) visible.add(record)
            }
        }
        visible.sortBy { it.z }
        dedupeSorted()
    }

    /** Drop the repeats a record filed in several chunks produced; the list is already z sorted. */
    private fun dedupeSorted() {
        if (visible.size < 2) return
        var write = 1
        for (read in 1 until visible.size) {
            if (visible[read] !== visible[write - 1]) {
                visible[write] = visible[read]
                write++
            }
        }
        while (visible.size > write) visible.removeAt(visible.size - 1)
    }

    private fun fileRecord(record: Record) {
        val x0 = chunkOf(record.bounds.left)
        val y0 = chunkOf(record.bounds.top)
        val x1 = chunkOf(record.bounds.right)
        val y1 = chunkOf(record.bounds.bottom)
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong() > MAX_FILE_CELLS) {
            oversized.add(record)
            return
        }
        for (cy in y0..y1) {
            for (cx in x0..x1) buckets.getOrPut(key(cx, cy)) { ArrayList(4) }.add(record)
        }
    }

    private fun unfileRecord(record: Record) {
        val x0 = chunkOf(record.bounds.left)
        val y0 = chunkOf(record.bounds.top)
        val x1 = chunkOf(record.bounds.right)
        val y1 = chunkOf(record.bounds.bottom)
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong() > MAX_FILE_CELLS) {
            oversized.removeAll { it === record }
            return
        }
        for (cy in y0..y1) {
            for (cx in x0..x1) {
                val k = key(cx, cy)
                val bucket = buckets[k] ?: continue
                bucket.removeAll { it === record }
                if (bucket.isEmpty()) buckets.remove(k)
            }
        }
    }

    private fun chunkOf(v: Double): Int {
        if (!v.isFinite()) return 0
        return floor(v / GeometryStore.CHUNK_SIZE).coerceIn(-32768.0, 32767.0).toInt()
    }

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

    companion object {
        private const val TAG = "xnotes.gl"

        /** Widest cell span a record is binned across before it is just tested on every frame. */
        private const val MAX_FILE_CELLS = 256L

        /** Cells a cull will walk before it gives up and scans every record instead. */
        private const val MAX_QUERY_CELLS = 4096L
    }
}
