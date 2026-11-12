package com.xnotes.platform

import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Flow text (note.note) must carry per-character colour and highlight into the flow. */
class SdocxFlowTextTest {

    private class B {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) { out.write(v and 0xFF) }
        fun u16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
        fun u32(v: Long) { for (i in 0..3) out.write((v ushr (8 * i)).toInt() and 0xFF) }
        fun i64(v: Long) { for (i in 0..7) out.write((v ushr (8 * i)).toInt() and 0xFF) }
        fun f32(v: Float) { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()) }
        fun f64(v: Double) { out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(v).array()) }
        fun bytes(b: ByteArray) { out.write(b) }
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private fun baseFrame(): ByteArray {
        val fixed = B().apply {
            u32(5500); u16(0); i64(0)
            f64(0.0); f64(0.0); f64(1.0); f64(1.0)
            u32(0); u8(0)
        }.toByteArray()
        val flexOffset = 12 + 1 + fixed.size
        return B().apply {
            u32(flexOffset.toLong()); u16(0); u32(flexOffset.toLong())
            u8(1); u8(0x08); u8(0); bytes(fixed)
        }.toByteArray()
    }

    private fun frame(kind: Int, fixed: ByteArray, fieldBits: Set<Int> = emptySet(), flexible: ByteArray = ByteArray(0)): ByteArray {
        val fieldMask = ByteArray(fieldBits.maxOrNull()?.let { it / 8 + 1 } ?: 0)
        for (b in fieldBits) fieldMask[b / 8] = (fieldMask[b / 8].toInt() or (1 shl (b % 8))).toByte()
        val flexOffset = 12 + fieldMask.size + fixed.size
        return B().apply {
            u32((flexOffset + flexible.size).toLong()); u16(kind); u32(flexOffset.toLong())
            u8(0); u8(fieldMask.size); bytes(fieldMask); bytes(fixed); bytes(flexible)
        }.toByteArray()
    }

    private fun span(kind: Int, start: Int, end: Int, argb: Int): ByteArray {
        val payload = B().apply { u32(argb.toLong() and 0xFFFFFFFFL) }.toByteArray()
        return B().apply {
            u16(16 + payload.size); u32(kind.toLong()); u32(start.toLong()); u32(end.toLong()); u32(0); bytes(payload)
        }.toByteArray()
    }

    private fun paraRecord(kind: Int, start: Int, end: Int, payload: ByteArray): ByteArray =
        B().apply { u16(12 + payload.size); u32(kind.toLong()); u32(start.toLong()); u32(end.toLong()); bytes(payload) }.toByteArray()

    private fun textFrame(text: String, spans: List<ByteArray>, paragraphs: List<ByteArray> = emptyList()): ByteArray {
        val common = B().apply {
            val units = text.map { it.code }
            u32(units.size.toLong()); for (u in units) u16(u)
            u32(spans.size.toLong()); spans.forEach { bytes(it) }
            u32(paragraphs.size.toLong()); paragraphs.forEach { bytes(it) }
            repeat(4) { f32(0f) }     // text margins
            u8(0)                     // gravity
        }.toByteArray()
        val flexible = B().apply { u32(common.size.toLong()); bytes(common) }.toByteArray()
        return baseFrame() + frame(6, ByteArray(0)) + frame(7, ByteArray(0), setOf(0), flexible)
    }

    private fun emptyTextFrame(): ByteArray =
        baseFrame() + frame(6, ByteArray(0)) + frame(7, ByteArray(0))

    private fun noteFile(body: ByteArray): ByteArray {
        val title = emptyTextFrame()
        val header = B().apply {
            u8(0); u8(0)              // header property + field masks
            u32(5500); u16(0); u32(0); i64(0); i64(0)
            u32(1220); u32(8733); u32(0); u32(27); u32(0)
            u32(title.size.toLong()); bytes(title)
            u32(body.size.toLong()); bytes(body)
        }.toByteArray()
        return B().apply { u32((4 + header.size).toLong()); bytes(header) }.toByteArray()
    }

    private fun sdocx(note: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("note.note")); z.write(note); z.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun objectRecord(type: Int, payload: ByteArray): ByteArray {
        val declared = payload.size + 32
        return B().apply {
            u8(type); u16(0); u32(declared.toLong()); bytes(payload); bytes(ByteArray(32))
        }.toByteArray()
    }

    private fun page(vararg objects: ByteArray): ByteArray {
        val header = B().apply {
            u8(0); u8(0); u32(0); u32(1000); u32(1000); u32(0); u32(0)
            val id = "page"; u16(id.length); for (c in id) u16(c.code)
        }.toByteArray()
        val headerEnd = 8 + header.size
        val layer = B().apply {
            u32(20); bytes(ByteArray(16)); u32(objects.size.toLong()); objects.forEach { bytes(it) }; bytes(ByteArray(32))
        }.toByteArray()
        return B().apply {
            u32(headerEnd.toLong()); u32(0); bytes(header); u16(1); u16(0); bytes(layer)
        }.toByteArray()
    }

    @Test
    fun textBoxCarriesPerCharacterStyles() {
        val payload = textFrame("Red", listOf(span(1, 0, 3, 0xFFFF0000.toInt()))) + frame(2, ByteArray(0))
        val decoded = SdocxReader.readPage(page(objectRecord(2, payload)))
        val box = decoded.elements.filterIsInstance<SdocxReader.SdocxTextBox>().firstOrNull() ?: error("no text box")
        assertTrue("text box should keep its colour span", box.spans.isNotEmpty())
        assertEquals(Rgba(255, 0, 0), box.spans.first().style.color)
    }

    @Test
    fun flowParagraphsCarryAlignmentAndList() {
        val center = B().apply { u32(2) }.toByteArray()
        val check = B().apply { u32(2); u32(0); u32(0); u32(0) }.toByteArray()
        val body = textFrame(
            "Item",
            emptyList(),
            listOf(paraRecord(3, 0, 1, center), paraRecord(5, 0, 1, check)),
        )
        val dir = File("/tmp/sdocx-flow-test").apply { mkdirs() }
        val file = File(dir, "para.sdocx").apply { writeBytes(sdocx(noteFile(body))) }

        val doc = SdocxReader.readForProbe(file, dir, SdocxReader.StubTextMeasurer, 160).getOrThrow()
            ?: error("flow import failed")
        val p = doc.flow.paragraphs.firstOrNull() ?: error("no paragraph")
        assertEquals(com.xnotes.core.text.ParaAlign.CENTER, p.align)
        assertEquals(com.xnotes.core.text.ListKind.CHECK, p.list)
    }

    @Test
    fun flowTextCarriesColorAndHighlight() {
        val body = textFrame("Hi", listOf(span(1, 0, 2, 0xFFFF0000.toInt()), span(17, 0, 2, 0xFFFFFF00.toInt())))
        val dir = File("/tmp/sdocx-flow-test").apply { mkdirs() }
        val file = File(dir, "flow.sdocx").apply { writeBytes(sdocx(noteFile(body))) }

        val doc = SdocxReader.readForProbe(file, dir, SdocxReader.StubTextMeasurer, 160).getOrThrow()
            ?: error("flow import failed")
        val runs = doc.flow.paragraphs.firstOrNull()?.runs ?: emptyList()
        assertTrue("flow should have a run", runs.isNotEmpty())
        assertEquals(Rgba(255, 0, 0), runs.first().style.color)
        assertEquals(Rgba(255, 255, 0), runs.first().style.highlight)
    }
}