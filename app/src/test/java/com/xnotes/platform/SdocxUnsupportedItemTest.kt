package com.xnotes.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipOutputStream

/**
 * Unported Samsung Notes item types must be dropped on their own, never abort the whole import.
 * These tests build minimal `.page` records (an empty pen stroke plus a voice/audio object) and
 * in-memory `.sdocx` archives, so they need no binary fixture.
 */
class SdocxUnsupportedItemTest {

    // --- little-endian record builders ------------------------------------

    private class Bytes {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) { out.write(v and 0xFF) }
        fun u16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
        fun u32(v: Long) { for (i in 0..3) out.write((v ushr (8 * i)).toInt() and 0xFF) }
        fun i64(v: Long) { for (i in 0..7) out.write((v ushr (8 * i)).toInt() and 0xFF) }
        fun f64(v: Double) { out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(v).array()) }
        fun f32(v: Float) { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()) }
        fun bytes(b: ByteArray) { out.write(b) }
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    /** A base object frame (kind 0) with identity, bbox and flags. */
    private fun baseFrame(): ByteArray {
        val fixed = Bytes().apply {
            u32(5500)                 // format version
            u16(0)                    // object UUID byte count
            i64(0)                    // modified time
            f64(0.0); f64(0.0); f64(1.0); f64(1.0) // bbox
            u32(0)                    // replay timestamp
            u8(0)                     // resize mode
        }.toByteArray()
        val flexOffset = 12 + 1 + fixed.size // property mask byte + mask
        return Bytes().apply {
            u32(flexOffset.toLong())  // frame size
            u16(0)                    // kind 0
            u32(flexOffset.toLong())  // flexible offset
            u8(1)                     // property mask byte count
            u8(0x08)                  // property bit 3 = visible
            u8(0)                     // field mask byte count
            bytes(fixed)
        }.toByteArray()
    }

    /** A stroke frame (kind 1) with zero points and no style fields. */
    private fun emptyStrokeFrame(): ByteArray {
        val fixed = Bytes().apply { u16(0); u16(0) }.toByteArray() // point count, tool/input type
        return Bytes().apply {
            u32((12 + fixed.size).toLong())
            u16(1)
            u32((12 + fixed.size).toLong())
            u8(0)                     // no compressed/stylus properties
            u8(0)                     // no style fields
            bytes(fixed)
        }.toByteArray()
    }

    private fun strokePayload(): ByteArray = baseFrame() + emptyStrokeFrame()

    /** A generic frame: size, kind, flexible offset, empty property mask, field mask, fixed, flexible. */
    private fun frame(kind: Int, fixed: ByteArray, fieldBits: Set<Int> = emptySet(), flexible: ByteArray = ByteArray(0)): ByteArray {
        val fieldMask = ByteArray(fieldBits.maxOrNull()?.let { it / 8 + 1 } ?: 0)
        for (b in fieldBits) fieldMask[b / 8] = (fieldMask[b / 8].toInt() or (1 shl (b % 8))).toByte()
        val flexOffset = 12 + fieldMask.size + fixed.size
        return Bytes().apply {
            u32((flexOffset + flexible.size).toLong())
            u16(kind)
            u32(flexOffset.toLong())
            u8(0) // property mask byte count
            u8(fieldMask.size)
            bytes(fieldMask)
            bytes(fixed)
            bytes(flexible)
        }.toByteArray()
    }

    /** An image object (frame chain 0 + 6 + 7 + 3) whose shape fill names [mediaId]. */
    private fun imagePayload(mediaId: Int): ByteArray {
        val shapeFixed = Bytes().apply {
            u32(4)                                      // shape type: placement rectangle
            f64(0.0); f64(0.0); f64(1.0); f64(1.0)      // geometry bbox
            f32(0.0f)                                   // shape rotation
            u32(0)                                      // path size
            u8(0)                                       // control point count
        }.toByteArray()
        val fillPayload = Bytes().apply {
            u8(0)                                       // fill mode
            u32(mediaId.toLong())                       // media bind id
            repeat(9) { f32(0.0f) }                     // fill settings
            u8(0)                                       // rotatable
            repeat(4) { u32(0) }                        // nine-patch rect
            u32(0)                                      // nine-patch width
        }.toByteArray()
        val fillField = Bytes().apply {
            u32(fillPayload.size.toLong())              // fill payload size
            u8(2)                                       // fill kind: image
            bytes(fillPayload)
        }.toByteArray()
        return baseFrame() +
            frame(6, ByteArray(0)) +
            frame(7, shapeFixed, setOf(5), fillField) +
            frame(3, ByteArray(0))
    }

    /** A physical object record: type, child count, declared size, payload, hash, then children. */
    private fun objectRecord(type: Int, payload: ByteArray): ByteArray {
        val declared = payload.size + 32
        return Bytes().apply {
            u8(type)
            u16(0)                    // child count
            u32(declared.toLong())
            bytes(payload)
            bytes(ByteArray(32))      // integrity trailer
        }.toByteArray()
    }

    /** One page: a header, then a single layer holding [objects]. */
    private fun page(vararg objects: ByteArray): ByteArray {
        val header = Bytes().apply {
            u8(0)                     // header property mask byte count
            u8(0)                     // header field mask byte count
            u32(0)                    // orientation
            u32(1000)                 // width
            u32(1000)                 // height
            u32(0); u32(0)            // page offsets
            val id = "page"
            u16(id.length)
            for (c in id) u16(c.code)
        }.toByteArray()
        val headerEnd = 8 + header.size
        val layer = Bytes().apply {
            u32(20)                   // layer header size (includes this size word)
            bytes(ByteArray(16))      // layer header body
            u32(objects.size.toLong())
            objects.forEach { bytes(it) }
            bytes(ByteArray(32))      // layer integrity trailer
        }.toByteArray()
        return Bytes().apply {
            u32(headerEnd.toLong())   // raw layer offset
            u32(0)                    // property offset
            bytes(header)
            u16(1)                    // layer count
            u16(0)                    // current layer index
            bytes(layer)
        }.toByteArray()
    }

    private fun archive(vararg pages: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            pages.forEachIndexed { i, p ->
                z.putNextEntry(java.util.zip.ZipEntry("${i + 1}.page"))
                z.write(p)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    // --- tests ------------------------------------------------------------

    /** An audio/voice object (type 10) is skipped, but a stroke beside it still decodes. */
    @Test
    fun voiceObjectIsSkippedWithoutDroppingThePage() {
        val page = page(objectRecord(1, strokePayload()), objectRecord(10, ByteArray(0)))
        val decoded = SdocxReader.readPage(page)
        assertEquals(1, decoded.elements.size)
        assertTrue(decoded.elements.first() is SdocxReader.SdocxStroke)
    }

    /** An audio-only page decodes to an empty page rather than throwing. */
    @Test
    fun voiceOnlyPageDecodesEmpty() {
        val decoded = SdocxReader.readPage(page(objectRecord(10, ByteArray(0))))
        assertTrue(decoded.elements.isEmpty())
    }

    /** The image fill's media id is read after the kind byte, not shifted left by 8 bits. */
    @Test
    fun imageFillMediaIdIsNotByteShifted() {
        val decoded = SdocxReader.readPage(page(objectRecord(3, imagePayload(7))))
        assertEquals(1, decoded.elements.size)
        val image = decoded.elements.first() as SdocxReader.SdocxImage
        assertEquals(7L, image.mediaId)
    }

    /** A structurally broken page is skipped; the readable page still imports. */
    @Test
    fun brokenPageDoesNotAbortTheImport() {
        val good = page(objectRecord(1, strokePayload()))
        val broken = ByteArray(8) // too short: no readable page header/layers
        val dir = File("/tmp/sdocx-unsupported-test").apply { mkdirs() }
        val file = File(dir, "mixed.sdocx").apply { writeBytes(archive(good, broken)) }

        val result = SdocxReader.readForProbe(file, dir, SdocxReader.StubTextMeasurer, 160)
        val doc = result.getOrNull()
        assertNotNull("import should survive a broken page: ${result.exceptionOrNull()}", doc)
        assertEquals(1, doc!!.pages.size)
        assertEquals(1, doc.pages[0].items.size)
    }
}