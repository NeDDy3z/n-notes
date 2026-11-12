package com.xnotes.platform

import com.xnotes.core.model.AudioItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Voice recordings (Samsung object type 10 + note metadata field 13) become audio pills. */
class SdocxAudioTest {

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

    private fun emptyTextFrame(): ByteArray =
        baseFrame() + frame(6, ByteArray(0)) + frame(7, ByteArray(0))

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

    private fun str(s: String): ByteArray {
        val b = B()
        b.u16(s.length)
        for (c in s) b.u16(c.code)
        return b.toByteArray()
    }

    private fun voicePayload(mediaId: Int): ByteArray {
        val voice = B().apply {
            u32(mediaId.toLong())
            bytes(str("recording"))
            bytes(str("00:03"))
            i64(-999)
            u32(1)                    // one event
            u32(4); i64(1234)         // action, time
        }.toByteArray()
        return B().apply { u32(1); u32(voice.size.toLong()); bytes(voice) }.toByteArray()
    }

    private fun noteFile(voices: ByteArray): ByteArray {
        val title = emptyTextFrame()
        val body = emptyTextFrame()
        val fieldMask = byteArrayOf(0x00, 0x20) // bit 13 = voices
        val fixedHeader = B().apply {
            u8(0); u8(fieldMask.size); bytes(fieldMask)
            u32(5500); u16(0); u32(0); i64(0); i64(0)
            u32(1220); u32(8733); u32(0); u32(27); u32(0)
            u32(title.size.toLong()); bytes(title)
            u32(body.size.toLong()); bytes(body)
        }.toByteArray()
        return B().apply {
            u32((4 + fixedHeader.size).toLong())
            bytes(fixedHeader)
            bytes(voices)
            bytes(ByteArray(32))
        }.toByteArray()
    }

    private fun mediaManifest(mediaId: Int, name: String): ByteArray {
        val record = B().apply {
            u32(mediaId.toLong())
            bytes(str(name))
            u8(0); u8(0)              // hash prefix
            u16(1)                    // reference count
            i64(0)                    // modified
            u8(1)                     // attached
        }.toByteArray()
        return B().apply {
            u32(4000)                 // version > 3001
            u16(1)
            u32(record.size.toLong()); bytes(record)
            bytes("EOFX".toByteArray(Charsets.US_ASCII))
        }.toByteArray()
    }

    private fun sdocx(note: ByteArray, page: ByteArray, manifest: ByteArray, audio: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(name: String, data: ByteArray) {
                z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry()
            }
            put("note.note", note)
            put("1.page", page)
            put("media/mediaInfo.dat", manifest)
            put("media/audio.m4a", audio)
        }
        return bos.toByteArray()
    }

    @Test
    fun noteMetadataVoiceIsParsed() {
        val note = SdocxReader.readNoteForProbe(noteFile(voicePayload(31))).getOrThrow()
        assertNotNull(note)
        assertEquals(1, note!!.voices.size)
        val v = note.voices.first()
        assertEquals(31L, v.mediaId)
        assertEquals("recording", v.name)
        assertEquals("00:03", v.playTime)
    }

    @Test
    fun pageVoiceObjectDecodesMediaId() {
        val payload = baseFrame() + frame(10, ByteArray(0), setOf(0), B().apply { u32(31) }.toByteArray())
        val decoded = SdocxReader.readPage(page(objectRecord(10, payload)))
        val voice = decoded.elements.filterIsInstance<SdocxReader.SdocxVoice>().firstOrNull() ?: error("no voice")
        assertEquals(31L, voice.mediaId)
    }

    @Test
    fun voiceBecomesAudioItem() {
        val audio = ByteArray(64) { it.toByte() }
        val voicePayload = baseFrame() + frame(10, ByteArray(0), setOf(0), B().apply { u32(31) }.toByteArray())
        val dir = File("/tmp/sdocx-audio-test").apply { mkdirs() }
        val file = File(dir, "voice.sdocx")
        file.writeBytes(
            sdocx(
                note = noteFile(voicePayload(31)),
                page = page(objectRecord(10, voicePayload)),
                manifest = mediaManifest(31, "audio.m4a"),
                audio = audio,
            )
        )

        val doc = SdocxReader.readForProbe(file, dir, SdocxReader.StubTextMeasurer, 160).getOrThrow()
            ?: error("import failed")
        val item = doc.pages.first().items.filterIsInstance<AudioItem>().firstOrNull() ?: error("no audio item")
        assertEquals("recording", item.name)
        assertEquals(3000, item.audio.durationMs)
        assertEquals("m4a", item.audio.ext)
        assertTrue(item.rect.w > 0.0 && item.rect.h > 0.0)
    }
}