package com.xnotes.core.pal

/** Native pixel dimensions of an encoded image, returned by [ImageCodec.probeFile]. */
data class ImageSize(val width: Int, val height: Int)

/**
 * Reads an encoded image's size (spec 01 §3). Inserted-image pixels are never decoded through
 * here: they live as files and the renderer decodes them on demand (see
 * [com.xnotes.platform.ImageDecoder]), so a note full of large images never fills the heap.
 */
interface ImageCodec {
    /** Native pixel size of the image at [path] without decoding the pixels, or null if unreadable. */
    fun probeFile(path: String): ImageSize?
}
