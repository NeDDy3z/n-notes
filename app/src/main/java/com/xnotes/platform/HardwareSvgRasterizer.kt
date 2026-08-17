package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Rasterizes into an offscreen GPU surface and hands the pixels back as a software bitmap, so a
 * heavy vector paint runs on the GPU instead of Skia's CPU backend. API 29 and up.
 *
 * The page cache is a software bitmap, so the result has to cross back over the bus. That readback
 * is the whole cost of this path, which is why [ImageDecoder] times it against the software raster
 * per document and only keeps it where it wins.
 *
 * One renderer and one reader are kept alive between calls, since building the EGL context and the
 * ImageReader dwarfs a single frame; they are rebuilt when the wanted size changes. Slice sizes are
 * grid-quantized, so a pan re-uses the same pair. Calls are serialized because HardwareRenderer
 * wants one owning thread and the callers are canvas cache threads.
 */
object HardwareSvgRasterizer {

    /** True when this device can rasterize offscreen on the GPU at all. */
    val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private var reader: ImageReader? = null
    private var renderer: HardwareRenderer? = null
    private var node: RenderNode? = null
    private var sizeW = 0
    private var sizeH = 0
    private var failures = 0

    /**
     * Draw [w]×[h] pixels through the GPU and return them as an ARGB_8888 bitmap, or null when the
     * GPU path is unavailable or fails (the caller falls back to Skia's CPU raster). Repeated
     * failures stop it being tried again, so a device that cannot do this pays for it twice.
     */
    @Synchronized
    fun render(w: Int, h: Int, draw: (Canvas) -> Unit): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (w <= 0 || h <= 0 || failures >= MAX_FAILURES) return null
        val bmp = runCatching { renderQ(w, h, draw) }.getOrNull()
        if (bmp == null) {
            failures++
            release()
        } else {
            failures = 0
        }
        return bmp
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun renderQ(w: Int, h: Int, draw: (Canvas) -> Unit): Bitmap? {
        ensure(w, h)
        val r = renderer ?: return null
        val rd = reader ?: return null
        val n = node ?: return null
        val c = n.beginRecording(w, h)
        try {
            // The reader recycles buffers, so start from a known-empty frame.
            c.drawColor(0, PorterDuff.Mode.CLEAR)
            draw(c)
        } finally {
            n.endRecording()
        }
        r.setContentRoot(n)
        r.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        val image = rd.acquireLatestImage() ?: return null
        return try {
            val buffer = image.hardwareBuffer ?: return null
            try {
                val wrapped = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))
                wrapped?.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                buffer.close()
            }
        } finally {
            image.close()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun ensure(w: Int, h: Int) {
        if (sizeW == w && sizeH == h && renderer != null && reader != null && node != null) return
        release()
        val rd = ImageReader.newInstance(
            w, h, PixelFormat.RGBA_8888, MAX_IMAGES,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        reader = rd
        renderer = HardwareRenderer().apply {
            setSurface(rd.surface)
            setOpaque(false)
        }
        node = RenderNode("svg-slice").apply { setPosition(0, 0, w, h) }
        sizeW = w
        sizeH = h
    }

    private fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { renderer?.destroy() }
            runCatching { reader?.close() }
        }
        renderer = null
        reader = null
        node = null
        sizeW = 0
        sizeH = 0
    }

    private const val MAX_IMAGES = 2
    private const val MAX_FAILURES = 2
}
