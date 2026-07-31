package com.xnotes.gl

import android.opengl.GLES30
import com.xnotes.core.infinite.MeshData
import com.xnotes.core.infinite.SlotAllocator
import com.xnotes.core.model.Rgba
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/** Where a mesh lives inside the shared buffers. */
class BufferSlice(
    val vertexOffset: Int,
    val vertexCount: Int,
    val indexOffset: Int,
    val indexCount: Int,
)

/**
 * The persistent vertex and index buffers every committed item is tessellated into once. A frame
 * draws slices of these, so per-frame work is a cull and a handful of draw calls rather than a
 * rasterization.
 *
 * Two things shape the vertex format. A position is stored as a coarse chunk index plus a small
 * local offset, never as an absolute coordinate: the canvas is unbounded, and a float absolute
 * position has visibly quantized long before a user has finished panning. Splitting it keeps every
 * number the GPU sees under [CHUNK_SIZE], so precision is the same at the origin and a hundred
 * million pixels away. Colour is per vertex rather than per draw, so a run of items in different
 * colours still collapses into one draw call.
 *
 * A CPU mirror of both buffers is kept. It makes an edit a small `glBufferSubData` instead of a
 * rebuild, and it makes recovering from EGL context loss a re-upload rather than a re-tessellation
 * of the whole document. Nothing here is the source of truth: all of it is derived from the model
 * and can be thrown away and rebuilt.
 */
class GeometryStore {

    private var vertexMirror: ByteBuffer = allocate(INITIAL_VERTICES * VERTEX_STRIDE)
    private var indexMirror: ByteBuffer = allocate(INITIAL_INDICES * INDEX_STRIDE)

    private val vertexAllocator = SlotAllocator(INITIAL_VERTICES)
    private val indexAllocator = SlotAllocator(INITIAL_INDICES)

    private var vbo = 0
    private var ibo = 0
    private var uploadedGen = -1

    /** Whole-buffer re-upload pending, because a buffer grew or the context was rebuilt. */
    private var fullUpload = true

    // Dirty byte ranges since the last upload, coalesced into one span each.
    private var vertexDirtyLo = Int.MAX_VALUE
    private var vertexDirtyHi = 0
    private var indexDirtyLo = Int.MAX_VALUE
    private var indexDirtyHi = 0

    val vertexCapacity: Int get() = vertexAllocator.capacity
    val indexCapacity: Int get() = indexAllocator.capacity
    val usedVertices: Int get() = vertexAllocator.used
    val usedIndices: Int get() = indexAllocator.used

    /** Bytes the GPU holds for this store, which is the mirror's size on both sides. */
    val gpuBytes: Long
        get() = vertexAllocator.capacity.toLong() * VERTEX_STRIDE +
            indexAllocator.capacity.toLong() * INDEX_STRIDE

    /** Bytes actually referenced by live geometry, so fragmentation shows as the gap to [gpuBytes]. */
    val liveBytes: Long
        get() = vertexAllocator.used.toLong() * VERTEX_STRIDE +
            indexAllocator.used.toLong() * INDEX_STRIDE

    /**
     * Write [mesh] into the buffers in [color] and return where it landed. Positions are split into
     * chunk plus local here rather than in the tessellator, so the tessellator stays pure doubles.
     */
    fun put(mesh: MeshData, color: Rgba): BufferSlice? {
        if (mesh.isEmpty) return null
        val vCount = mesh.vertexCount
        val iCount = mesh.indices.size
        val vOffset = vertexAllocator.allocate(vCount) ?: run {
            growVertices(vCount)
            vertexAllocator.allocate(vCount) ?: return null
        }
        val iOffset = indexAllocator.allocate(iCount) ?: run {
            growIndices(iCount)
            indexAllocator.allocate(iCount) ?: run {
                vertexAllocator.free(vOffset, vCount)
                return null
            }
        }

        val r = color.r.toByte()
        val g = color.g.toByte()
        val b = color.b.toByte()
        val a = color.a.toByte()
        var p = vOffset * VERTEX_STRIDE
        for (i in 0 until vCount) {
            val x = mesh.positions[2 * i]
            val y = mesh.positions[2 * i + 1]
            val cx = chunkIndex(x)
            val cy = chunkIndex(y)
            vertexMirror.putFloat(p, (x - cx * CHUNK_SIZE).toFloat())
            vertexMirror.putFloat(p + 4, (y - cy * CHUNK_SIZE).toFloat())
            vertexMirror.putShort(p + 8, cx.toInt().toShort())
            vertexMirror.putShort(p + 10, cy.toInt().toShort())
            vertexMirror.put(p + 12, r)
            vertexMirror.put(p + 13, g)
            vertexMirror.put(p + 14, b)
            vertexMirror.put(p + 15, a)
            p += VERTEX_STRIDE
        }
        markVertexDirty(vOffset, vCount)

        var q = iOffset * INDEX_STRIDE
        for (i in 0 until iCount) {
            // Mesh indices are relative to their own first vertex; rebase into the shared buffer.
            indexMirror.putInt(q, vOffset + mesh.indices[i])
            q += INDEX_STRIDE
        }
        markIndexDirty(iOffset, iCount)

        return BufferSlice(vOffset, vCount, iOffset, iCount)
    }

    /** Recolour a slice in place, without re-tessellating. */
    fun recolor(slice: BufferSlice, color: Rgba) {
        var p = slice.vertexOffset * VERTEX_STRIDE + 12
        for (i in 0 until slice.vertexCount) {
            vertexMirror.put(p, color.r.toByte())
            vertexMirror.put(p + 1, color.g.toByte())
            vertexMirror.put(p + 2, color.b.toByte())
            vertexMirror.put(p + 3, color.a.toByte())
            p += VERTEX_STRIDE
        }
        markVertexDirty(slice.vertexOffset, slice.vertexCount)
    }

    fun free(slice: BufferSlice) {
        vertexAllocator.free(slice.vertexOffset, slice.vertexCount)
        indexAllocator.free(slice.indexOffset, slice.indexCount)
        // The bytes are left as they are: nothing indexes them any more, and clearing them would
        // dirty a range for no visible effect.
    }

    fun clear() {
        vertexAllocator.reset()
        indexAllocator.reset()
    }

    // --- GL lifecycle ---

    /** Called on the GL thread after a context is created; every previous name is already gone. */
    fun onContextCreated(gen: Int) {
        vbo = 0
        ibo = 0
        uploadedGen = gen
        fullUpload = true
        val names = IntArray(2)
        GLES30.glGenBuffers(2, names, 0)
        vbo = names[0]
        ibo = names[1]
    }

    /** Push pending changes to the GPU and bind the buffers. Returns false when there is nothing. */
    fun bindForDraw(gen: Int): Boolean {
        if (gen != uploadedGen || vbo == 0 || ibo == 0) return false
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        if (fullUpload) {
            vertexMirror.position(0)
            vertexMirror.limit(vertexAllocator.capacity * VERTEX_STRIDE)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexMirror.limit(), vertexMirror, GLES30.GL_DYNAMIC_DRAW)
            indexMirror.position(0)
            indexMirror.limit(indexAllocator.capacity * INDEX_STRIDE)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexMirror.limit(), indexMirror, GLES30.GL_DYNAMIC_DRAW)
            vertexMirror.clear()
            indexMirror.clear()
            fullUpload = false
            clearDirty()
            return true
        }
        if (vertexDirtyLo < vertexDirtyHi) {
            vertexMirror.position(vertexDirtyLo)
            vertexMirror.limit(vertexDirtyHi)
            GLES30.glBufferSubData(
                GLES30.GL_ARRAY_BUFFER, vertexDirtyLo, vertexDirtyHi - vertexDirtyLo, vertexMirror.slice(),
            )
            vertexMirror.clear()
        }
        if (indexDirtyLo < indexDirtyHi) {
            indexMirror.position(indexDirtyLo)
            indexMirror.limit(indexDirtyHi)
            GLES30.glBufferSubData(
                GLES30.GL_ELEMENT_ARRAY_BUFFER, indexDirtyLo, indexDirtyHi - indexDirtyLo, indexMirror.slice(),
            )
            indexMirror.clear()
        }
        clearDirty()
        return true
    }

    /** Point the ink program's attributes at the bound vertex buffer. */
    fun bindAttributes(program: InkShader) {
        val pos = program.attribLocal
        val chunk = program.attribChunk
        val color = program.attribColor
        if (pos >= 0) {
            GLES30.glEnableVertexAttribArray(pos)
            GLES30.glVertexAttribPointer(pos, 2, GLES30.GL_FLOAT, false, VERTEX_STRIDE, 0)
        }
        if (chunk >= 0) {
            GLES30.glEnableVertexAttribArray(chunk)
            GLES30.glVertexAttribPointer(chunk, 2, GLES30.GL_SHORT, false, VERTEX_STRIDE, 8)
        }
        if (color >= 0) {
            GLES30.glEnableVertexAttribArray(color)
            GLES30.glVertexAttribPointer(color, 4, GLES30.GL_UNSIGNED_BYTE, true, VERTEX_STRIDE, 12)
        }
    }

    /** Draw [indexCount] indices starting at [indexOffset]; the buffers must already be bound. */
    fun drawRange(indexOffset: Int, indexCount: Int) {
        if (indexCount <= 0) return
        GLES30.glDrawElements(
            GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, indexOffset * INDEX_STRIDE,
        )
    }

    // --- internals ---

    private fun growVertices(need: Int) {
        var cap = vertexAllocator.capacity
        while (cap < vertexAllocator.used + need) cap *= 2
        val next = allocate(cap * VERTEX_STRIDE)
        vertexMirror.clear()
        next.put(vertexMirror)
        next.clear()
        vertexMirror = next
        vertexAllocator.grow(cap)
        fullUpload = true
    }

    private fun growIndices(need: Int) {
        var cap = indexAllocator.capacity
        while (cap < indexAllocator.used + need) cap *= 2
        val next = allocate(cap * INDEX_STRIDE)
        indexMirror.clear()
        next.put(indexMirror)
        next.clear()
        indexMirror = next
        indexAllocator.grow(cap)
        fullUpload = true
    }

    private fun markVertexDirty(offset: Int, count: Int) {
        val lo = offset * VERTEX_STRIDE
        val hi = (offset + count) * VERTEX_STRIDE
        if (lo < vertexDirtyLo) vertexDirtyLo = lo
        if (hi > vertexDirtyHi) vertexDirtyHi = hi
    }

    private fun markIndexDirty(offset: Int, count: Int) {
        val lo = offset * INDEX_STRIDE
        val hi = (offset + count) * INDEX_STRIDE
        if (lo < indexDirtyLo) indexDirtyLo = lo
        if (hi > indexDirtyHi) indexDirtyHi = hi
    }

    private fun clearDirty() {
        vertexDirtyLo = Int.MAX_VALUE
        vertexDirtyHi = 0
        indexDirtyLo = Int.MAX_VALUE
        indexDirtyHi = 0
    }

    private fun allocate(bytes: Int): ByteBuffer =
        ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    companion object {
        /**
         * Content pixels a chunk spans. Small enough that every local offset stays far inside
         * float32's exact range even at the deepest zoom, large enough that the chunk index fits a
         * signed short across a world of +/- 134 million content pixels.
         */
        const val CHUNK_SIZE = 4096.0

        const val VERTEX_STRIDE = 16
        const val INDEX_STRIDE = 4

        private const val INITIAL_VERTICES = 8192
        private const val INITIAL_INDICES = 24576

        /** Chunk index a content coordinate falls in, clamped to what a short can hold. */
        fun chunkIndex(v: Double): Double {
            if (!v.isFinite()) return 0.0
            return floor(v / CHUNK_SIZE).coerceIn(-32768.0, 32767.0)
        }
    }
}
