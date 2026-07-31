package com.xnotes.gl

/**
 * A snapshot of what the render thread just did, for the debug HUD. Written on the GL thread and
 * read on the main thread, so it is one immutable value swapped wholesale rather than a set of
 * fields that could be read half updated.
 *
 * The GL canvas has failure modes the paged one does not: buffers that grow without bound, an EGL
 * context silently rebuilt, a cull that stops culling, a draw-call count that quietly climbs as the
 * geometry buffer fragments. All of those are invisible until something is slow or wrong, so they
 * are all here.
 */
data class GlStats(
    val fps: Double = 0.0,
    val frameMs: Double = 0.0,
    /** Bumped on every EGL context; a rise here means everything was rebuilt from the model. */
    val contextGen: Int = 0,
    /** Multisample count the driver granted. 0 or 1 means no MSAA and visibly worse ink edges. */
    val msaaSamples: Int = 0,
    val renderer: String = "",
    val glVersion: String = "",
    /** Items the scene holds geometry for. */
    val items: Int = 0,
    /** Items that survived the cull this frame. */
    val visibleItems: Int = 0,
    /** Draw calls this frame. A climb at a fixed item count means the buffer has fragmented. */
    val drawCalls: Int = 0,
    val vertices: Int = 0,
    val vertexCapacity: Int = 0,
    val indices: Int = 0,
    val indexCapacity: Int = 0,
    /** Bytes the GPU holds for committed geometry, capacity rather than occupancy. */
    val geometryBytes: Long = 0,
    /** Bytes live geometry actually references; the gap to [geometryBytes] is fragmentation. */
    val liveGeometryBytes: Long = 0,
    val wetVertices: Int = 0,
    /** Milliseconds the last tessellation took, on whichever thread produced it. */
    val lastTessellateMs: Double = 0.0,
)
