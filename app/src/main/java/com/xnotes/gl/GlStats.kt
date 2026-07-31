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
    /** Frames actually drawn in the last second. A count, not a smoothed interval. */
    val fps: Double = 0.0,
    /** Milliseconds of work inside the frame, excluding the wait for the display. */
    val frameMs: Double = 0.0,
    /** Longest gap between drawn frames in the last second, in milliseconds. */
    val worstFrameMs: Double = 0.0,
    /** Frames in the last second that arrived more than one and a half refreshes late. */
    val jankFrames: Int = 0,
    /** The panel's refresh rate, so a frame count at the cap is recognizable as the cap. */
    val displayHz: Double = 0.0,
    /** Publish requests collapsed into this frame. Above one is work the display cannot show. */
    val requestsPerFrame: Double = 0.0,
    /**
     * How evenly the content moved between frames over the last second, as the spread of the
     * per-frame step divided by its mean. Zero is perfectly even motion; this is what a pan feels
     * like, and a high frame count says nothing about it.
     */
    val stepJitter: Double = 0.0,
    /** Mean distance the content moved per frame over the last second, in device pixels. */
    val stepPx: Double = 0.0,
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
    /** Image textures resident on the GPU. */
    val textures: Int = 0,
    val textureBytes: Long = 0,
    /** Images whose decode has been asked for and has not arrived yet. */
    val texturesPending: Int = 0,
    /** Milliseconds the last tessellation took, on whichever thread produced it. */
    val lastTessellateMs: Double = 0.0,
)
