package com.xnotes.platform

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.AudioData
import com.xnotes.core.model.AudioItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextItem
import com.xnotes.core.tools.Tool
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.LineMetrics
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.ToolConfig
import java.io.File
import java.util.zip.ZipFile

/**
 * A reader for Samsung Notes `.sdocx` archives (zip; optionally with Samsung's
 * appended end tag, which this reader ignores).
 *
 * A faithful port of the reference decoder (sdocx_src/crates/sdocx): the archive
 * holds `note.note` (the flowing-text store that carries *all* content of typed
 * notes), `pageIdInfo.dat` (ordered page ids), `media/` assets bound by
 * `media/mediaInfo.dat` or filename prefixes, and one uncompressed `.page`
 * record per page. A `.page` is a header, a layer list and a tree of objects,
 * each object a `frame` chain — a length-prefixed block carrying a type, two
 * bit masks, and fixed/flexible payloads. Everything unknown is skipped by its
 * own declared size, so a newer writer degrades to "fewer objects" rather than
 * a corrupt note.
 *
 * What maps into the note: pen strokes (points, colour, width; compressed
 * points are accumulated at f32 precision exactly as Samsung's writer does),
 * text boxes (whole-box colour/size/bold/italic/underline plus per-character
 * colour/highlight/decoration spans), placed images (resolving bind ids
 * through the media manifest, falling back to filename prefixes), voice
 * recordings (note metadata field 13 + the page voice object, written beside
 * the note and played from its audio pill) and flowing text: when pages carry
 * no objects, slices of the `note.note` body flow onto the empty pages, with
 * its spans (colour/highlight/bold/italic/underline/strike) and paragraph
 * attributes (alignment/indent/list/heading). Shapes, lines, formulas and
 * tables are skipped — an unported item is dropped on its own, so the rest of
 * the note still imports.
 */
object SdocxReader {

    // --- object type ids (S Pen SDK store) ---

    private const val TYPE_STROKE = 1
    private const val TYPE_TEXT_BOX = 2
    private const val TYPE_IMAGE = 3
    private const val TYPE_CONTAINER = 4
    private const val TYPE_VOICE = 10

    // --- decoding ---------------------------------------------------------

    /** Bounds-checked little-endian reader over one uncompressed record. */
    private class R(val data: ByteArray, var pos: Int = 0) {
        val remaining: Int get() = data.size - pos

        fun bytes(n: Int): ByteArray {
            require(n >= 0 && n <= remaining) { "truncated record: need $n at $pos of ${data.size}" }
            val out = data.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        fun u8(): Int = bytes(1)[0].toInt() and 0xFF
        fun u16(): Int = (u8() or (u8() shl 8))
        fun u32(): Long {
            var v = 0L
            repeat(4) { v = v or ((u8().toLong() and 0xFF) shl (it * 8)) }
            return v
        }

        fun i32(): Int {
            var v = 0
            repeat(4) { v = v or ((u8() and 0xFF) shl (it * 8)) }
            return v
        }

        fun i64(): Long {
            var v = 0L
            repeat(8) { v = v or ((u8().toLong() and 0xFF) shl (it * 8)) }
            return v
        }

        fun f32(): Float = Float.fromBits(i32())
        fun f64(): Double = Double.fromBits(i64())

        /** u16 unit-count-prefixed UTF-16; 0xFFFF units means the string is absent. */
        fun utf16(): String? {
            val units = u16()
            if (units == 0xFFFF) return null
            return utf16Units(units)
        }

        /** u16 unit-count-prefixed UTF-16 without the null sentinel. */
        fun utf16U16(): String = utf16Units(u16())

        /** u32 unit-count-prefixed UTF-16 (rich text carries its length as a u32). */
        fun utf16U32(): String {
            val units = u32()
            require(units <= MAX_TEXT_UNITS) { "text is too long: $units units" }
            return utf16Units(units.toInt())
        }

        private fun utf16Units(units: Int): String = String(bytes(units * 2), Charsets.UTF_16LE)

        /** u16 byte-length-prefixed UTF-8 (object ids are these). */
        fun utf8(): String = String(bytes(u16()), Charsets.UTF_8)

        fun bbox(): DoubleArray {
            val out = doubleArrayOf(f64(), f64(), f64(), f64())
            require(out.all { it.isFinite() }) { "non-finite bounding rectangle" }
            return out
        }

        fun mask(): Mask = Mask(bytes(u8()))

        fun skip(n: Int) {
            require(n >= 0 && n <= remaining) { "skip past end at $pos (+$n)" }
            pos += n
        }
    }

    private const val MAX_TEXT_UNITS = 250_000

    /** A length-prefixed bit mask; all bytes are kept so newer bits cannot shift what follows. */
    private class Mask(val bytes: ByteArray) {
        fun contains(bit: Int): Boolean =
            bit / 8 < bytes.size && (bytes[bit / 8].toInt() and (1 shl (bit % 8))) != 0

        fun lowU32(): Long {
            var v = 0L
            for (i in 0 until minOf(4, bytes.size)) v = v or ((bytes[i].toLong() and 0xFF) shl (i * 8))
            return v
        }

        fun byteCount(): Int = bytes.size

        fun isEmpty(): Boolean = bytes.all { it == 0.toByte() }

        fun hasOtherBits(known: Long): Boolean =
            (lowU32() and known.inv()) != 0L || bytes.drop(4).any { it != 0.toByte() }

        companion object {
            fun read(r: R) = Mask(r.bytes(r.u8()))
        }
    }

    private class Frame(
        val kind: Int,
        val properties: Mask,
        val fields: Mask,
        val fixed: ByteArray,
        val flexible: ByteArray,
    )

    private fun readFrame(r: R): Frame {
        val size = r.u32()
        require(size >= 12) { "frame size $size" }
        val body = r.bytes((size - 4).toInt())
        val head = R(body)
        val kind = head.u16()
        val flexOffset = head.u32()
        val properties = head.mask()
        val fields = head.mask()
        // The flexible offset counts from the frame's own start (the size word included),
        // so in body coordinates the boundary sits four bytes earlier.
        val fixedEnd = if (flexOffset == 0L && fields.isEmpty()) body.size + 4 else flexOffset.toInt()
        require(fixedEnd - 4 >= head.pos && fixedEnd - 4 <= body.size) {
            "frame $kind: flexible offset $flexOffset is outside its data"
        }
        return Frame(kind, properties, fields, body.copyOfRange(head.pos, fixedEnd - 4), body.copyOfRange(fixedEnd - 4, body.size))
    }

    /** The base frame (kind 0) every object opens with: identity, bbox, flags. */
    private class ObjectMeta(
        val bbox: DoubleArray,
        val rotationDegrees: Double?,
        val visible: Boolean,
        val fieldMask: ByteArray,
        val flexibleTrailing: ByteArray,
    )

    private fun readObjectMeta(r: R): ObjectMeta {
        val f = readFrame(r)
        require(f.kind == 0) { "expected base frame 0, got ${f.kind}" }
        val fixed = R(f.fixed)
        fixed.u32() // format version
        fixed.utf8() // object UUID
        fixed.i64() // modified time
        val bbox = fixed.bbox()
        fixed.i32() // replay timestamp
        fixed.u8() // resize mode
        val flex = R(f.flexible)
        var rotation: Double? = null
        if (f.fields.contains(0)) {
            val value = flex.f32()
            require(value.isFinite()) { "non-finite object rotation" }
            rotation = value.toDouble()
        }
        return ObjectMeta(bbox, rotation, f.properties.contains(3), f.fields.bytes, flex.bytes(flex.remaining))
    }

    // --- stroke (decode.rs) -----------------------------------------------

    internal class SdocxStroke(
        val bbox: DoubleArray,
        val xs: DoubleArray,
        val ys: DoubleArray,
        val colorArgb: Int?,
        val penSize: Float,
    )

    private fun signedDelta(raw: Int, divisor: Double): Double {
        val magnitude = (raw and 0x7FFF) / divisor
        return if (raw and 0x8000 == 0) magnitude else -magnitude
    }

    /** One f32 channel: a leading f32 then u16 deltas (compressed) or f32s, added at f32 precision. */
    private fun floatChannel(r: R, count: Int, compressed: Boolean): FloatArray {
        val out = FloatArray(count)
        if (count == 0) return out
        var value = r.f32()
        out[0] = value
        for (i in 1 until count) {
            value = if (compressed) value + signedDelta(r.u16(), 4096.0).toFloat() else r.f32()
            require(value.isFinite()) { "stroke contains non-finite channel values" }
            out[i] = value
        }
        return out
    }

    private fun decodeStroke(data: ByteArray): SdocxStroke? {
        val frames = R(data)
        val base = readObjectMeta(frames)
        val f = readFrame(frames)
        require(f.kind == 1) { "expected stroke frame 1, got ${f.kind}" }

        val fixed = R(f.fixed)
        val count = fixed.u16()
        val compressed = f.properties.contains(0)
        val stylus = f.properties.contains(2)
        val channelBytes = when {
            count == 0 -> 0
            compressed -> 16 + (count - 1) * 4 + (2 + (if (stylus) 1 else 0) * 2) * (4 + (count - 1) * 2)
            else -> count * (24 + (if (stylus) 1 else 0) * 8)
        }
        require(fixed.remaining == channelBytes + 2) {
            "stroke channels: $count points require ${channelBytes + 2} fixed bytes, found ${fixed.remaining}"
        }
        val channels = R(fixed.bytes(channelBytes))
        fixed.u16() // tool/input type

        // NewApplyBinary stores the initial doubles natively, then rounds each compressed
        // delta addition at float precision — so points accumulate in Float, not Double.
        val xs = DoubleArray(count)
        val ys = DoubleArray(count)
        if (count != 0 && compressed) {
            var x = channels.f64().toFloat()
            var y = channels.f64().toFloat()
            xs[0] = x.toDouble(); ys[0] = y.toDouble()
            for (i in 1 until count) {
                x += signedDelta(channels.u16(), 32.0).toFloat()
                y += signedDelta(channels.u16(), 32.0).toFloat()
                xs[i] = x.toDouble(); ys[i] = y.toDouble()
            }
        } else {
            for (i in 0 until count) {
                xs[i] = channels.f64()
                ys[i] = channels.f64()
            }
        }
        require(xs.indices.all { xs[it].isFinite() && ys[it].isFinite() }) { "stroke contains non-finite coordinates" }

        floatChannel(channels, count, compressed) // pressure
        if (count != 0) {
            var timestamp = channels.i32().toLong()
            for (i in 1 until count) {
                timestamp = if (compressed) timestamp + channels.u16() else channels.i32().toLong()
            }
        }
        if (stylus) {
            floatChannel(channels, count, compressed) // tilt
            floatChannel(channels, count, compressed) // orientation
        }

        val style = readStrokeStyle(f, base)
        return SdocxStroke(base.bbox, xs, ys, style.first, style.second ?: 0.8f)
    }
    /**
     * The stroke's flexible style fields behind a mask; an unknown bit stops the walk and the
     * rest is trailing data (mirroring StrokeStyle::read).
     */
    private fun readStrokeStyle(f: Frame, base: ObjectMeta): Pair<Int?, Float?> {
        val style = R(f.flexible)
        var colorArgb: Int? = null
        var penSize: Float? = null
        if (f.fields.contains(0)) style.i32() // legacy pen name id
        if (f.fields.contains(1)) style.i32() // pen settings id
        if (f.fields.contains(2)) colorArgb = style.i32()
        if (f.fields.contains(3)) {
            val size = style.f32()
            require(size.isFinite() && size >= 0f) { "invalid stroke pen size" }
            penSize = size
        }
        var entries = 0
        for (bit in 4 until f.fields.byteCount() * 8) {
            if (!f.fields.contains(bit)) continue
            when (bit) {
                4 -> style.u8()
                5 -> {
                    // Legacy partial rectangles: the count lives in the base's flexible tail,
                    // but each record costs 4 bytes here (the base's own copies are 16).
                    var rectCount = 0
                    if (base.fieldMask.firstOrNull()?.toInt()?.and(2) != 0) {
                        val rects = R(base.flexibleTrailing)
                        rectCount = rects.u16()
                        rects.skip(rectCount * 16)
                    }
                    require(entries + rectCount <= MAX_METADATA_ENTRIES) { "too many stroke metadata entries" }
                    entries += rectCount
                    require(rectCount <= style.remaining / 4) { "stroke metadata records exceed their frame" }
                    style.skip(rectCount * 4)
                }
                7 -> style.i32()
                8 -> finiteF32(style)
                9 -> style.i32()
                10 -> style.i32()
                11 -> style.i32()
                12 -> style.i32()
                13 -> finiteF32(style)
                14 -> style.u16()
                15 -> finiteF32(style)
                16 -> style.u16()
                17 -> finiteF32(style)
                18 -> finiteF32(style)
                19 -> style.i32()
                20 -> finiteF32(style)
                21 -> style.i32()
                22 -> style.i32()
                23 -> finiteF32(style)
                24 -> {
                    val gradientCount = style.u16()
                    require(entries + gradientCount <= MAX_METADATA_ENTRIES) { "too many stroke metadata entries" }
                    entries += gradientCount
                    require(gradientCount <= style.remaining / 4) { "stroke metadata records exceed their frame" }
                    style.skip(gradientCount * 4)
                }
                25 -> style.u16()
                else -> return colorArgb to penSize
            }
        }
        return colorArgb to penSize
    }

    private const val MAX_METADATA_ENTRIES = 10_000

    private fun finiteF32(r: R): Float {
        val value = r.f32()
        require(value.isFinite()) { "non-finite float field" }
        return value
    }

    // --- text (note.rs) -----------------------------------------------------

    private class TextSpan(val kind: Long, val start: Long, val end: Long, val payload: ByteArray) {
        /** A boolean span stores a u16 that is 1 when set. */
        fun booleanValue(): Boolean? =
            if (payload.size >= 2) ((payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)) == 1 else null

        fun argbValue(): Int? = if (payload.size >= 4) {
            (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8) or
                ((payload[2].toInt() and 0xFF) shl 16) or ((payload[3].toInt() and 0xFF) shl 24)
        } else null
    }

    private class ParagraphRecord(val kind: Long, val start: Long, val end: Long, val payload: ByteArray)

    /** The shared rich-text record behind page text boxes and note.note's title/body. */
    private class TextCommon(
        val text: String,
        val spans: List<TextSpan>,
        val paragraphs: List<ParagraphRecord>,
        val sections: List<Pair<Int, Int>>,
        val objectSpans: List<ObjectSpan>,
    )

    private class ObjectSpan(val objectType: Long, val objectData: ByteArray, val textIndex: Long)

    /** Span type ids (RichTextSpanType). */
    private const val SPAN_FOREGROUND = 1L
    private const val SPAN_FONT_SIZE = 3L
    private const val SPAN_BOLD = 5L
    private const val SPAN_ITALIC = 6L
    private const val SPAN_UNDERLINE = 7L
    private const val SPAN_HIGHLIGHT = 17L
    private const val SPAN_STRIKE = 20L

    private fun parseTextCommon(r: R): TextCommon {
        val common = R(r.bytes(r.u32().toInt()))
        val text = common.utf16U32()
        val spanCount = common.u32().toInt()
        require(spanCount <= MAX_TEXT_SPANS) { "too many text spans" }
        val spans = ArrayList<TextSpan>(spanCount)
        repeat(spanCount) {
            val record = R(common.bytes(common.u16()))
            val kind = record.u32()
            val start = record.u32()
            val end = record.u32()
            record.u32() // interval type
            spans.add(TextSpan(kind, start, end, record.bytes(record.remaining)))
        }
        val paragraphCount = common.u32().toInt()
        require(paragraphCount <= MAX_TEXT_PARAGRAPHS) { "too many text paragraphs" }
        val paragraphs = ArrayList<ParagraphRecord>(paragraphCount)
        repeat(paragraphCount) {
            val record = R(common.bytes(common.u16()))
            val kind = record.u32()
            val start = record.u32()
            val end = record.u32()
            paragraphs.add(ParagraphRecord(kind, start, end, record.bytes(record.remaining)))
        }
        repeat(4) { require(common.f32().isFinite()) { "non-finite text margin" } }
        common.u8() // gravity
        val sections = ArrayList<Pair<Int, Int>>()
        if (common.remaining >= 2) {
            val sectionCount = common.u16()
            repeat(sectionCount) { sections.add(common.u32().toInt() to common.u32().toInt()) }
        }
        val objectSpans = ArrayList<ObjectSpan>()
        if (common.remaining >= 8) {
            val flags = common.u32()
            common.u32() // reserved
            if (flags and 1L != 0L) {
                val count = common.u32().toInt()
                require(count <= MAX_TEXT_SPANS) { "too many text object spans" }
                repeat(count) {
                    val span = R(common.bytes(common.u32().toInt()))
                    val objectSize = span.u32()
                    require(objectSize <= span.remaining - 16L) { "embedded object exceeds its span" }
                    val objectType = span.u32()
                    val objectData = span.bytes(objectSize.toInt())
                    val textIndex = span.u32()
                    span.u32() // layout option
                    span.u32() // layout constraint
                    objectSpans.add(ObjectSpan(objectType, objectData, textIndex))
                }
            }
        }
        return TextCommon(text, spans, paragraphs, sections, objectSpans)
    }

    private const val MAX_TEXT_SPANS = 10_000
    private const val MAX_TEXT_PARAGRAPHS = 10_000

    /** One text frame chain: base frame, shape frame (6), shape-text frame (7), common record. */
    private class DecodedText(val meta: ObjectMeta, val common: TextCommon)

    private fun parseTextFrames(r: R): DecodedText {
        val meta = readObjectMeta(r)
        val shape = readFrame(r)
        require(shape.kind == 6) { "text shape frame" }
        val shapeText = readFrame(r)
        require(shapeText.kind == 7) { "text content frame" }
        val common = if (shapeText.fields.contains(0)) {
            parseTextCommon(R(shapeText.flexible))
        } else {
            TextCommon("", emptyList(), emptyList(), emptyList(), emptyList())
        }
        return DecodedText(meta, common)
    }

    internal class SdocxTextBox(
        val bbox: DoubleArray,
        val text: String,
        val color: Rgba?,
        val fontSize: Float?,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        /** Images embedded in the text's object spans, decoded with their own bounding boxes. */
        val embedded: List<SdocxImage>,
        /** Per-character colour/highlight/decoration overrides, merged into non-overlapping ranges. */
        val spans: List<com.xnotes.core.text.RunSpan>,
    )

    private fun decodeTextBox(data: ByteArray): SdocxTextBox? {
        val r = R(data)
        val decoded = parseTextFrames(r)
        // The border frame (kind 2) and any extension frames follow; both are skipped by size.
        if (r.remaining > 0) {
            val tail = readFrame(r)
            require(tail.kind == 2) { "text box border frame" }
        }
        while (r.remaining > 0) readFrame(r)
        val embedded = decoded.common.objectSpans.mapNotNull { span ->
            if (span.objectType != 3L) return@mapNotNull null
            runCatching { decodeImage(span.objectData) }.getOrNull()?.takeIf { it.visible }
        }
        if (decoded.common.text.isEmpty() && embedded.isEmpty()) return null
        val utf16Length = decoded.common.text.length
        fun wholeSpan(kind: Long): TextSpan? =
            decoded.common.spans.lastOrNull { it.kind == kind && it.start == 0L && it.end == utf16Length.toLong() && utf16Length > 0 }
        val color = wholeSpan(SPAN_FOREGROUND)?.argbValue()?.toRgba()
        val fontSize = wholeSpan(SPAN_FONT_SIZE)
            ?.let { if (it.payload.size >= 4) Float.fromBits(it.argbValue() ?: return@let null) else null }
            ?.takeIf { it.isFinite() }
        val bold = wholeSpan(SPAN_BOLD)?.booleanValue() == true
        val italic = wholeSpan(SPAN_ITALIC)?.booleanValue() == true
        val underline = wholeSpan(SPAN_UNDERLINE)?.booleanValue() == true
        return SdocxTextBox(decoded.meta.bbox, decoded.common.text, color, fontSize, bold, italic, underline, embedded, textRunSpans(decoded.common))
    }

    /**
     * The per-character styles of a rich-text record as non-overlapping [RunSpan]s over its text,
     * the same rule [com.xnotes.core.model.TextItem.spans] follows: each span replaces the box's own
     * style on its range. Overlapping Samsung spans (colour + bold + highlight) are flattened into
     * one style per character range.
     */
    private fun textRunSpans(common: TextCommon): List<com.xnotes.core.text.RunSpan> {
        val text = common.text
        if (text.isEmpty() || common.spans.isEmpty()) return emptyList()
        val n = text.length
        val bold = BooleanArray(n)
        val italic = BooleanArray(n)
        val underline = BooleanArray(n)
        val strike = BooleanArray(n)
        val color = arrayOfNulls<Rgba>(n)
        val highlight = arrayOfNulls<Rgba>(n)
        for (span in common.spans) {
            val start = span.start.toInt().coerceIn(0, n)
            val end = span.end.toInt().coerceIn(0, n)
            if (end <= start) continue
            when (span.kind) {
                SPAN_BOLD -> for (i in start until end) bold[i] = span.booleanValue() == true
                SPAN_ITALIC -> for (i in start until end) italic[i] = span.booleanValue() == true
                SPAN_UNDERLINE -> for (i in start until end) underline[i] = span.booleanValue() == true
                SPAN_STRIKE -> for (i in start until end) strike[i] = span.booleanValue() == true
                SPAN_FOREGROUND -> span.argbValue()?.toRgba()?.let { c -> for (i in start until end) color[i] = c }
                SPAN_HIGHLIGHT -> span.argbValue()?.toRgba()?.let { c -> for (i in start until end) highlight[i] = c }
            }
        }
        val out = ArrayList<com.xnotes.core.text.RunSpan>()
        var at = 0
        while (at < n) {
            var end = at + 1
            while (end < n &&
                bold[end] == bold[at] && italic[end] == italic[at] && underline[end] == underline[at] &&
                strike[end] == strike[at] && color[end] == color[at] && highlight[end] == highlight[at]
            ) end++
            val style = com.xnotes.core.text.CharStyle(
                bold = bold[at],
                italic = italic[at],
                underline = underline[at],
                strike = strike[at],
                color = color[at],
                highlight = highlight[at],
            )
            if (style != com.xnotes.core.text.CharStyle.DEFAULT) out.add(com.xnotes.core.text.RunSpan(at, end, style))
            at = end
        }
        return out
    }

    private fun Int.toRgba(): Rgba? = Rgba((this shr 16) and 0xFF, (this shr 8) and 0xFF, this and 0xFF)

    // --- image (image.rs) ---------------------------------------------------

    internal class SdocxImage(val bbox: DoubleArray, val mediaId: Long, val visible: Boolean)

    /** A voice recording's placement: the audio is resolved from the note's voice metadata by [mediaId]. */
    internal class SdocxVoice(val bbox: DoubleArray, val mediaId: Long, val visible: Boolean)

    private fun decodeImage(data: ByteArray): SdocxImage? {
        val r = R(data)
        val base = readObjectMeta(r)
        val shapeBase = readFrame(r)
        require(shapeBase.kind == 6) { "image shape frame" }
        val shape = readFrame(r)
        require(shape.kind == 7) { "image geometry frame" }
        val tail = readFrame(r)
        require(tail.kind == 3) { "image settings frame" }

        val fixed = R(shape.fixed)
        fixed.u32() // shape type
        fixed.bbox() // geometry
        finiteF32(fixed) // shape rotation
        val pathSize = fixed.u32().toInt()
        fixed.skip(pathSize)
        val pointCount = fixed.u8()
        fixed.skip(pointCount * 16)

        val fields = R(shape.flexible)
        var mediaId = -1L
        if (shape.fields.contains(0)) fields.skip(fields.u32().toInt()) // text content
        if (shape.fields.contains(1)) fields.u8() // text area type
        if (shape.fields.contains(2)) fields.i32() // pen name id
        if (shape.fields.contains(3)) {
            return null // unknown field before the fill; media id untrusted
        }
        if (shape.fields.contains(4)) fields.i32() // pen settings id
        if (shape.fields.contains(5)) {
            // Fill record: u32 payload size, u8 kind (2 = image), then the fill payload whose first
            // byte is the fill mode and whose next i32 is the media bind id. Reading the size and
            // then `size` bytes (instead of reading the kind first) shifted every id left a byte.
            val size = fields.u32().toInt()
            val kind = fields.u8()
            val fill = R(fields.bytes(size))
            if (kind == 2) { // image fill
                fill.u8() // fill mode
                // Bind id 0 is valid: the manifest, not positivity, decides the binding.
                val id = fill.i32()
                if (id >= 0) mediaId = id.toLong() and 0xFFFFFFFFL
            }
        }
        if (mediaId < 0) return null
        return SdocxImage(base.bbox, mediaId, base.visible)
    }

    /**
     * A voice object (type 10): the base frame's box, and — when present — the own frame's
     * flexible bit 0 audio media id. The id is matched against the note's voice metadata; a voice
     * whose own frame carries no id falls back to the note voice at the same position.
     */
    private fun decodeVoice(data: ByteArray): SdocxVoice {
        val r = R(data)
        val base = readObjectMeta(r)
        var mediaId = -1L
        if (r.remaining > 0) {
            runCatching {
                val own = readFrame(r)
                if (own.kind == TYPE_VOICE) {
                    val fields = R(own.flexible)
                    for (bit in 0 until own.fields.byteCount() * 8) {
                        if (!own.fields.contains(bit)) continue
                        when (bit) {
                            0 -> mediaId = fields.i32().toLong() and 0xFFFFFFFFL
                            6 -> fields.i32() // thumbnail media id
                            else -> break
                        }
                    }
                }
            }
        }
        return SdocxVoice(base.bbox, mediaId, base.visible)
    }

    // --- media (media.rs) ---------------------------------------------------

    /**
     * Media assets by bind id; [inferred] is true when ids came from filename prefixes.
     * [byIdExt] keeps each asset's file extension so imported audio keeps a sensible sidecar name.
     */
    internal class MediaIndex(
        internal val byId: Map<Long, ByteArray>,
        internal val byIdExt: Map<Long, String>,
        internal val inferred: Boolean,
    )

    /**
     * Modern `media/mediaInfo.dat` (format versions newer than 3001) binds ids to filenames;
     * older archives encode the id in each filename's `N@name` prefix.
     */
    private fun readMediaIndex(zip: ZipFile): MediaIndex {
        val assets = HashMap<String, ByteArray>()
        val prefixIds = HashMap<Long, String>()
        for (e in zip.entries()) {
            if (e.isDirectory || !e.name.startsWith("media/") || e.name.endsWith(".dat")) continue
            // One unreadable asset (a corrupt or unsupported audio payload, for instance) must not
            // sink the import: skip it and let the remaining assets resolve.
            runCatching { zip.getInputStream(e).use { it.readBytes() } }.getOrNull()?.let { assets[e.name] = it }
            e.name.substringAfterLast('/').substringBefore('@').toLongOrNull()?.let { prefixIds.putIfAbsent(it, e.name) }
        }
        fun lookup(name: String): ByteArray? = assets[name]
        val manifest = zip.getEntry("media/mediaInfo.dat")?.let { entry ->
            runCatching {
                val r = R(zip.getInputStream(entry).use { it.readBytes() })
                val version = r.u32()
                require(version > 3001) { "legacy media manifest" }
                val count = r.u16()
                val bindings = HashMap<Long, String>()
                repeat(count) {
                    val record = R(r.bytes(r.u32().toInt()))
                    val bindId = record.u32()
                    val fileName = record.utf16U16()
                    val hashPrefix = record.bytes(2)
                    if (hashPrefix[0].toInt() != 0 || hashPrefix[1].toInt() != 0) record.skip(62)
                    record.u16() // reference count
                    record.i64() // modified time
                    record.u8() // attached flag
                    bindings.putIfAbsent(bindId, "media/$fileName")
                }
                require(String(r.bytes(4), Charsets.US_ASCII) == "EOFX") { "media manifest: invalid EOFX marker" }
                bindings
            }.getOrNull()
        }
        fun extOf(name: String): String =
            name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() && it.length <= 5 } ?: "m4a"
        if (manifest != null) {
            val byId = HashMap<Long, ByteArray>()
            val byIdExt = HashMap<Long, String>()
            for ((id, name) in manifest) {
                val bytes = lookup(name) ?: continue
                byId[id] = bytes
                byIdExt[id] = extOf(name)
            }
            return MediaIndex(byId, byIdExt, inferred = false)
        }
        val byId = HashMap<Long, ByteArray>()
        val byIdExt = HashMap<Long, String>()
        for ((id, name) in prefixIds) {
            val bytes = lookup(name) ?: continue
            byId[id] = bytes
            byIdExt[id] = extOf(name)
        }
        return MediaIndex(byId, byIdExt, inferred = true)
    }

    // --- note.note ----------------------------------------------------------

    /** The flowing-text store `note.note`: typed notes keep all of their content here. */
    internal class SdocxNote(
        val title: String,
        /** The body as flow paragraphs, one per line, with the body spans' character styles. */
        val bodyParagraphs: List<com.xnotes.core.text.Paragraph>,
        val width: Long,
        val height: Long,
        val paddingX: Long,
        val paddingY: Long,
        /** Voice recordings named by the note metadata (field 13), resolved against the media index. */
        val voices: List<SdocxNoteVoice>,
    )

    /** One note-level voice record: its media id, display name and length. */
    internal class SdocxNoteVoice(
        val mediaId: Long,
        val name: String,
        val playTime: String,
        val createdTime: Long,
        val recordingTime: Long?,
    )

    internal fun readNoteForProbe(data: ByteArray) = runCatching { parseNoteImpl(data) }

    private fun parseNote(data: ByteArray): SdocxNote? = runCatching { parseNoteImpl(data) }.getOrNull()

    private fun parseNoteImpl(data: ByteArray): SdocxNote {
        val r = R(data)
        val fixedEnd = r.u32().toInt()
        require(fixedEnd in 4..data.size) { "note flexible-data offset is outside the record" }
        val fixed = R(data.copyOf(fixedEnd), 4)
        fixed.mask() // header properties
        val fields = fixed.mask() // note field mask
        fixed.u32() // format version
        fixed.utf16U16() // note id
        fixed.u32() // file revision
        fixed.i64() // created
        fixed.i64() // modified
        val width = fixed.u32()
        val height = fixed.u32()
        val paddingX = fixed.u32()
        val paddingY = fixed.u32()
        fixed.u32() // minimum format version
        val title = parseTextFrames(R(fixed.bytes(fixed.u32().toInt())))
        val body = parseTextFrames(R(fixed.bytes(fixed.u32().toInt())))
        // The flexible data follows the fixed record and ends before the 32-byte hash trailer.
        val flexibleEnd = (data.size - 32).coerceAtMost(data.size)
        val voices = runCatching {
            if (fixedEnd in 4..flexibleEnd) {
                parseNoteVoices(R(data.copyOfRange(fixedEnd, flexibleEnd)), fields)
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())
        return SdocxNote(
            title.common.text,
            flowParagraphs(body.common),
            width,
            height,
            paddingX,
            paddingY,
            voices,
        )
    }

    private const val MAX_VOICES = 4096
    private const val MAX_VOICE_EVENTS = 65536

    /**
     * The note metadata's flexible fields, up to and including the voice list (field bit 13).
     * Fields before it are skipped exactly as Samsung writes them; an unknown bit stops the walk,
     * matching the reference parser's `first_unparsed_field`.
     */
    private fun parseNoteVoices(r: R, fields: Mask): List<SdocxNoteVoice> {
        val voices = ArrayList<SdocxNoteVoice>()
        for (bit in 0 until fields.byteCount() * 8) {
            if (!fields.contains(bit)) continue
            when (bit) {
                0 -> r.utf16U16() // application name
                1 -> { r.i32(); r.i32(); r.utf16U16() } // application version
                2 -> { r.nullableUtf16(); r.nullableUtf16(); r.nullableUtf16(); r.u32() } // author
                3 -> { r.f64(); r.f64() } // location
                6 -> r.utf16U16() // template URI
                7 -> r.i32() // last-edited page index
                9 -> { r.u32(); r.i64() } // last-edited page
                10 -> r.skip(r.u32().toInt()) // string table
                11 -> r.i32() // body font size delta
                12 -> skipPen(r, modern = false)
                13 -> voices.addAll(parseVoiceList(r))
                else -> break
            }
        }
        return voices
    }

    /** A u16 unit count, or null when the count is the 0xFFFF sentinel. */
    private fun R.nullableUtf16(): String? {
        val units = u16()
        if (units == 0xFFFF) return null
        return String(bytes(units * 2), Charsets.UTF_16LE)
    }

    private fun skipPen(r: R, modern: Boolean) {
        r.utf16U16() // name
        r.f32() // size
        r.u32() // colour
        r.u32() // curvable
        r.utf16U16() // advanced setting
        r.u32() // eraser enabled
        r.i32() // size level
        r.i32() // particle density
        if (modern) { r.f32(); r.u32() } // particle size, fixed width
        r.f32(); r.f32(); r.f32() // hue, saturation, value
        r.u32() // colour UI info
    }

    private fun parseVoiceList(r: R): List<SdocxNoteVoice> {
        val count = r.u32().toInt()
        require(count <= MAX_VOICES) { "too many voices" }
        val out = ArrayList<SdocxNoteVoice>(count)
        repeat(count) {
            val size = r.u32().toInt()
            val voice = R(r.bytes(size))
            val mediaId = voice.u32()
            val name = voice.utf16U16()
            val playTime = voice.utf16U16()
            val created = voice.i64()
            val eventCount = voice.u32().toInt()
            require(eventCount <= MAX_VOICE_EVENTS) { "too many voice events" }
            repeat(eventCount) { voice.i32(); voice.i64() } // action, time
            val recordingTime = if (voice.remaining > 0) voice.i64() else null
            out.add(SdocxNoteVoice(mediaId, name, playTime, created, recordingTime))
        }
        return out
    }

    /**
     * The body's rich text as flow paragraphs: one per line, with the spans' bold/italic/
     * underline/colour/highlight mapped per character range (Samsung's font sizes stay at the
     * flow's own base size, so its small body units don't shrink imported text).
     */
    private fun flowParagraphs(common: TextCommon): List<com.xnotes.core.text.Paragraph> {
        val text = common.text
        if (text.isEmpty()) return emptyList()
        val bold = BooleanArray(text.length)
        val italic = BooleanArray(text.length)
        val underline = BooleanArray(text.length)
        val strike = BooleanArray(text.length)
        val color = arrayOfNulls<Rgba>(text.length)
        val highlight = arrayOfNulls<Rgba>(text.length)
        for (span in common.spans) {
            val start = span.start.toInt().coerceIn(0, text.length)
            val end = span.end.toInt().coerceIn(0, text.length)
            if (end <= start) continue
            when (span.kind) {
                SPAN_BOLD -> for (i in start until end) bold[i] = span.booleanValue() == true
                SPAN_ITALIC -> for (i in start until end) italic[i] = span.booleanValue() == true
                SPAN_UNDERLINE -> for (i in start until end) underline[i] = span.booleanValue() == true
                SPAN_STRIKE -> for (i in start until end) strike[i] = span.booleanValue() == true
                SPAN_FOREGROUND -> span.argbValue()?.toRgba()?.let { c -> for (i in start until end) color[i] = c }
                SPAN_HIGHLIGHT -> span.argbValue()?.toRgba()?.let { c -> for (i in start until end) highlight[i] = c }
            }
        }
        val paragraphs = ArrayList<com.xnotes.core.text.Paragraph>()
        var lineStart = 0
        while (lineStart <= text.length) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val runs = ArrayList<com.xnotes.core.text.Run>()
            var at = lineStart
            while (at < lineEnd) {
                var end = at + 1
                while (end < lineEnd &&
                    bold[end] == bold[at] && italic[end] == italic[at] && underline[end] == underline[at] &&
                    strike[end] == strike[at] &&
                    color[end] == color[at] && highlight[end] == highlight[at]
                ) end++
                val style = com.xnotes.core.text.CharStyle(
                    bold = bold[at],
                    italic = italic[at],
                    underline = underline[at],
                    strike = strike[at],
                    color = color[at],
                    highlight = highlight[at],
                )
                runs.add(com.xnotes.core.text.Run(text.substring(at, end), style))
                at = end
            }
            paragraphs.add(com.xnotes.core.text.Paragraph(runs))
            if (lineEnd == text.length) break
            lineStart = lineEnd + 1
        }
        // Paragraph attribute records: alignment, indent/direction, list/checkbox and heading.
        // Their start/end are paragraph ordinals, matching the line-split paragraphs above.
        for (record in common.paragraphs) {
            val endIdx = record.end.toInt().coerceAtMost(paragraphs.size)
            if (record.start.toInt() >= endIdx) continue
            val values = when (record.kind) {
                2L -> R(record.payload).let { listOf(it.u32(), it.u32()) }
                3L -> R(record.payload).let { listOf(it.u32()) }
                5L -> R(record.payload).let { listOf(it.u32(), it.u32(), it.u32(), it.u32()) }
                10L -> R(record.payload).let { listOf(it.u32(), it.u32()) }
                else -> emptyList()
            }
            for (idx in record.start.toInt() until endIdx) {
                val p = paragraphs.getOrNull(idx) ?: continue
                when (record.kind) {
                    2L -> { // indent level + writing direction
                        p.indent = values[0].toInt()
                        p.dir = when (values[1]) {
                            1L -> com.xnotes.core.text.ParaDir.LTR
                            2L -> com.xnotes.core.text.ParaDir.RTL
                            else -> com.xnotes.core.text.ParaDir.AUTO
                        }
                    }
                    3L -> p.align = when (values[0]) {
                        0L -> com.xnotes.core.text.ParaAlign.LEFT
                        1L -> com.xnotes.core.text.ParaAlign.RIGHT
                        2L -> com.xnotes.core.text.ParaAlign.CENTER
                        3L -> com.xnotes.core.text.ParaAlign.JUSTIFY
                        else -> com.xnotes.core.text.ParaAlign.LEFT
                    }
                    5L -> { // bullet, numbered list or checkbox
                        val bullet = values[0]
                        p.checked = values[2] != 0L
                        p.list = when (bullet) {
                            0L -> com.xnotes.core.text.ListKind.NONE
                            2L -> com.xnotes.core.text.ListKind.CHECK
                            4L, 5L, 6L, 7L, 10L -> com.xnotes.core.text.ListKind.ORDERED
                            else -> com.xnotes.core.text.ListKind.BULLET
                        }
                        if (values[3] > 0L) p.listStart = values[3].toInt()
                    }
                    10L -> p.headingLevel = when (values[0]) {
                        0L -> 1
                        1L -> 2
                        2L -> 3
                        else -> 0
                    }
                }
            }
        }
        return paragraphs
    }

    // --- page & archive -----------------------------------------------------

    private class StoredObject(val type: Int, val payload: ByteArray, val children: List<StoredObject>)

    internal class SdocxPage(
        val uuid: String,
        val width: Int,
        val height: Int,
        val colorArgb: Int?,
        val elements: List<Any>, // SdocxStroke | SdocxTextBox | SdocxImage
    )

    internal fun readPage(data: ByteArray): SdocxPage = parseStoredPage(data).let { stored ->
        val elements = ArrayList<Any>()
        decodeLayerObjects(stored.layers[stored.currentLayerIndex], elements)
        SdocxPage(stored.uuid, stored.width, stored.height, stored.colorArgb, elements)
    }

    private class StoredPageData(
        val uuid: String,
        val width: Int,
        val height: Int,
        val colorArgb: Int?,
        val currentLayerIndex: Int,
        val layers: List<List<StoredObject>>,
    )

    private fun parseStoredPage(data: ByteArray): StoredPageData {
        val header = R(data)
        val rawLayerOffset = header.u32()
        val propertyOffset = header.u32()
        val headerEnd = (if (propertyOffset == 0L) rawLayerOffset else propertyOffset).toInt()
        require(headerEnd <= rawLayerOffset && rawLayerOffset <= data.size) { "page header offsets are outside the page" }
        val head = R(data.copyOf(headerEnd), 8)
        head.mask() // header properties
        val fields = head.mask() // property mask for the page's flexible fields
        head.u32() // orientation
        val width = head.u32()
        val height = head.u32()
        head.u32(); head.u32() // page offsets
        val uuid = head.utf16U16()
        if (head.pos + 8 <= headerEnd) head.i64() // modified time
        if (head.pos + 4 <= headerEnd) head.u32() // format version
        if (head.pos + 4 <= headerEnd) head.u32() // minimum format version

        var colorArgb: Int? = null
        if (propertyOffset != 0L) {
            val props = R(data.copyOfRange(propertyOffset.toInt(), rawLayerOffset.toInt()))
            for (bit in 0..9) {
                if (fields.lowU32() and (1L shl bit) == 0L) continue
                when (bit) {
                    0 -> props.bbox()
                    1 -> repeat(props.u16()) { props.utf16U16() }
                    2 -> props.utf16U16() // template URI
                    3 -> props.u32() // background image id
                    4 -> props.u32() // background image mode
                    5 -> colorArgb = props.i32()
                    6 -> props.u32() // background width
                    7 -> props.u32() // background rotation
                    8 -> {
                        // PDF paper records: media id, page index and a 16-byte rectangle each;
                        // version 2034+ stores the rectangle as ints, older as float bits.
                        val count = props.u16()
                        val rectReader = R(props.bytes(count * 24))
                        repeat(count) {
                            rectReader.i32(); rectReader.i32()
                            rectReader.skip(16)
                        }
                    }
                    9 -> props.u32() // template type
                }
            }
        }

        val layers = R(data)
        layers.pos = rawLayerOffset.toInt()
        val layerCount = layers.u16()
        require(layerCount > 0) { "page layers: layer count is zero" }
        val currentLayer = layers.u16()
        require(currentLayer < layerCount) { "page layers: current layer index outside layers" }
        val layerObjects = ArrayList<List<StoredObject>>(layerCount)
        repeat(layerCount) { layerObjects.add(parseLayer(layers)) }
        return StoredPageData(uuid, width.toInt(), height.toInt(), colorArgb, currentLayer, layerObjects)
    }

    private fun parseLayer(r: R): List<StoredObject> {
        val headerSize = r.u32()
        require(headerSize in 14..65536L) { "page layers: invalid layer header size $headerSize" }
        r.skip((headerSize - 4).toInt())
        val objectCount = r.u32().toInt()
        val objects = ArrayList<StoredObject>(objectCount)
        repeat(objectCount) { objects.add(parseObjectRecord(r, 0)) }
        r.skip(32) // layer integrity trailer
        return objects
    }

    /** Objects are stored as type byte + child count + declared size + payload + 32-byte hash. */
    private fun parseObjectRecord(r: R, depth: Int): StoredObject {
        require(depth < 64) { "object nesting too deep" }
        val type = r.u8()
        val childCount = r.u16()
        val declaredSize = r.u32()
        require(declaredSize >= 32) { "object smaller than its trailer" }
        val payload = r.bytes((declaredSize - 32).toInt())
        r.skip(32)
        val children = ArrayList<StoredObject>(childCount)
        repeat(childCount) { children.add(parseObjectRecord(r, depth + 1)) }
        return StoredObject(type, payload, children)
    }

    /**
     * Decodes the objects of one layer into [out]. Every object is isolated: an item this port
     * does not decode — a voice recording / audio clip (type 10), shape (7), line (8), formula
     * (11), container (4), or any future/unknown type — is skipped instead of aborting the page.
     * Only the unsupported item is omitted; the rest of the note still imports.
     */
    private fun decodeLayerObjects(objects: List<StoredObject>, out: MutableList<Any>) {
        for (obj in objects) {
            runCatching {
                when (obj.type) {
                    TYPE_STROKE -> decodeStroke(obj.payload)?.let(out::add)
                    TYPE_TEXT_BOX -> decodeTextBox(obj.payload)?.let { textBox ->
                        out.add(textBox)
                        out.addAll(textBox.embedded)
                    }
                    TYPE_IMAGE -> decodeImage(obj.payload)?.takeIf { it.visible }?.let(out::add)
                    TYPE_VOICE -> out.add(decodeVoice(obj.payload))
                    // Unported item types (shape 7, line 8, formula 11, …) and
                    // unknown future types are intentionally ignored.
                    else -> Unit
                }
            }
            runCatching { decodeLayerObjects(obj.children, out) }
        }
    }

    /** `pageIdInfo.dat`: 32-byte integrity header, then ordered UTF-16 page ids with hashes. */
    private fun pageOrder(data: ByteArray): List<String> = runCatching {
        val r = R(data)
        r.skip(32)
        val count = r.u16()
        val ids = ArrayList<String>(count)
        repeat(count) {
            ids.add(r.utf16U16())
            r.skip(32)
        }
        ids.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    // --- mapping into the note ----------------------------------------------

    /**
     * Reads [file] into a paged [Document], writing the archive's embedded images out beside
     * [imageDir]'s other picture files. Null when it is not a readable sdocx.
     */
    fun read(file: File, imageDir: File, measurer: TextMeasurer, dpi: Int): Document? =
        runCatching { readImpl(file, imageDir, measurer, dpi) }.getOrNull()

    internal fun readForProbe(file: File, imageDir: File, measurer: TextMeasurer, dpi: Int) =
        runCatching { readImpl(file, imageDir, measurer, dpi) }

    private fun readImpl(file: File, imageDir: File, measurer: TextMeasurer, dpi: Int): Document? {
        ZipFile(file).use { zip ->
            fun entryBytes(name: String): ByteArray = runCatching {
                zip.getEntry(name)?.let { zip.getInputStream(it).use { s -> s.readBytes() } } ?: ByteArray(0)
            }.getOrDefault(ByteArray(0))

            // A broken media manifest or an unreadable asset (Samsung's audio/video payloads, for
            // instance) must not abort the import: fall back to an empty index so those media are
            // simply absent and the rest of the note still lands.
            val media = runCatching { readMediaIndex(zip) }
                .getOrDefault(MediaIndex(emptyMap(), emptyMap(), inferred = true))
            val note = entryBytes("note.note").takeIf { it.isNotEmpty() }?.let { parseNote(it) }
            val manifest = entryBytes("pageIdInfo.dat").takeIf { it.isNotEmpty() }?.let { pageOrder(it) }

            val allPages = runCatching {
                zip.entries().asSequence().map { it.name }.filter { it.endsWith(".page") }.sorted().toList()
            }.getOrDefault(emptyList())
            // Listed pages keep manifest order; unlisted ones follow in name order.
            val listed = manifest?.map { "$it.page" }?.filter { it in allPages.toSet() } ?: emptyList()
            val pageNames = (listed + allPages.filter { it !in listed.toSet() }).distinct()

            val doc = Document(dpi = dpi)
            if (note != null && note.bodyParagraphs.isNotEmpty()) {
                // Typed Samsung notes keep their text in note.note; it joins the note's own flow
                // layer, which the importer paginates over the page stack after this returns.
                doc.flow.paragraphs.clear()
                doc.flow.paragraphs.addAll(note.bodyParagraphs)
            }
            val noteVoices = note?.voices ?: emptyList()
            val voiceCursor = IntArray(1)
            val audioRid = intArrayOf(1)
            for (name in pageNames) {
                // One unreadable page must not sink the whole import: a page that only holds an
                // unsupported item (or is otherwise malformed) is skipped, and the pages that do
                // decode still land.
                val page = runCatching { readPage(entryBytes(name)) }.getOrNull() ?: continue
                runCatching { doc.pages.add(pageToPage(page, media, imageDir, measurer, noteVoices, voiceCursor, audioRid)) }
            }
            if (doc.pages.isEmpty() && note != null) {
                // A text-only archive (or one whose every page held only unsupported items): one
                // base page at the note's own dimensions, which the importer's flow layout then
                // grows into as many pages as the body needs.
                doc.pages.add(Page(note.width.toDouble().coerceAtLeast(100.0), note.height.toDouble().coerceAtLeast(100.0)))
            }
            if (doc.pages.isEmpty()) return null
            return doc
        }
    }

    /** Maps one decoded page into a note page; public to the module so JVM tests can run the parser. */
    internal fun pageToPage(
        page: SdocxPage,
        media: MediaIndex,
        imageDir: File,
        measurer: TextMeasurer? = null,
        noteVoices: List<SdocxNoteVoice> = emptyList(),
        voiceCursor: IntArray = IntArray(1),
        audioRid: IntArray = IntArray(1),
    ): Page {
        val p = Page(page.width.toDouble(), page.height.toDouble())
        page.colorArgb?.let { argb ->
            p.style = PageStyle(pageColor = Rgba((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF))
        }
        for (element in page.elements) {
            // Isolate each element: a single unmappable item is dropped, not the whole page.
            runCatching {
                when (element) {
                    is SdocxStroke -> p.items.add(strokeItem(element))
                    is SdocxTextBox -> p.items.add(textItem(element, measurer ?: StubTextMeasurer))
                    is SdocxImage -> media.byId[element.mediaId]?.let { bytes ->
                        imageItem(bytes, element, imageDir)?.let { p.items.add(it) }
                    }
                    is SdocxVoice -> {
                        val index = voiceCursor[0]++
                        val noteVoice = noteVoices.getOrNull(index)
                        audioItem(element, noteVoice, media, imageDir, measurer ?: StubTextMeasurer, audioRid[0]++)
                            ?.let { p.items.add(it) }
                    }
                }
            }
        }
        return p
    }

    /**
     * An imported voice as an [AudioItem]: the media bytes written beside the note, the container's
     * duration probed, and the pill placed at the page voice's box (falling back to the metadata's
     * "mm:ss" play time when the platform cannot probe, e.g. in JVM tests).
     */
    private fun audioItem(
        voice: SdocxVoice,
        noteVoice: SdocxNoteVoice?,
        media: MediaIndex,
        imageDir: File,
        measurer: TextMeasurer,
        rid: Int,
    ): AudioItem? {
        val mediaId = if (voice.mediaId >= 0) voice.mediaId else noteVoice?.mediaId ?: -1L
        if (mediaId < 0) return null
        val bytes = media.byId[mediaId] ?: return null
        val ext = media.byIdExt[mediaId] ?: "m4a"
        val file = runCatching { File.createTempFile("sdocx-audio", ".$ext", imageDir) }.getOrNull() ?: return null
        if (!runCatching { file.writeBytes(bytes) }.isSuccess) { file.delete(); return null }
        val duration = runCatching { AudioFiles.durationOf(file) }.getOrNull()
            ?: playTimeMs(noteVoice?.playTime)?.takeIf { it > 0 }
            ?: 0
        if (duration <= 0) { file.delete(); return null }
        val b = voice.bbox
        val w = (b[2] - b[0]).coerceAtLeast(AudioItem.MIN_HEIGHT * AudioItem.MIN_ASPECT)
        val h = (b[3] - b[1]).coerceAtLeast(AudioItem.MIN_HEIGHT)
        val rect = Rect(b[0], b[1], w, h)
        val name = noteVoice?.name?.ifBlank { null } ?: "Recording"
        return AudioItem(AudioData(file, ext, duration, ByteArray(0)), rect, name, rid, measurer)
    }

    /** Samsung's voice "mm:ss" (or "hh:mm:ss") play time as milliseconds, or null. */
    private fun playTimeMs(playTime: String?): Int? {
        val parts = playTime?.split(':') ?: return null
        if (parts.size < 2) return null
        val seconds = parts.takeLast(2).mapNotNull { it.trim().toIntOrNull() }
        if (seconds.size < 2) return null
        val total = seconds[0] * 60 + seconds[1]
        return total * 1000
    }

    private fun strokeItem(s: SdocxStroke): Stroke {
        val color = s.colorArgb?.toRgba() ?: Rgba(0, 0, 0)
        val config = ToolConfig(
            baseWidth = s.penSize.toDouble().coerceIn(0.5, 200.0),
            pressureEnabled = false,
            pressureMinFactor = 1.0,
            directionStrength = 0.0,
            rgba = color,
        )
        val samples = s.xs.indices.map { Sample(s.xs[it], s.ys[it], 1.0, 0.0) }
        return Stroke(Tool.PEN, config, samples)
    }

    private fun textItem(t: SdocxTextBox, measurer: TextMeasurer): TextItem {
        val x = t.bbox[0]
        val y = t.bbox[1]
        val w = (t.bbox[2] - t.bbox[0]).coerceAtLeast(40.0)
        return TextItem(
            pos = Pt(x, y),
            width = w,
            height = t.bbox[3] - t.bbox[1],
            text = t.text,
            rgba = t.color ?: TextItem.DEFAULT_COLOR,
            pointSize = (t.fontSize?.toDouble() ?: TextItem.DEFAULT_POINT_SIZE).coerceIn(6.0, 96.0),
            face = TextItem.DEFAULT_FACE,
            measurer = measurer,
            bold = t.bold,
            italic = t.italic,
            underline = t.underline,
            spans = t.spans,
        )
    }

    private fun imageItem(bytes: ByteArray, image: SdocxImage, imageDir: File): ImageItem? {
        val tmp = File.createTempFile("sdocx", ".jpg", imageDir)
        tmp.writeBytes(bytes)
        // One unreadable asset (Samsung's .spi snapshots, for instance) must not sink the import.
        val size = runCatching { ImageDecoder.probeFile(tmp.path) }.getOrNull() ?: run { tmp.delete(); return null }
        val w = (image.bbox[2] - image.bbox[0]).coerceAtLeast(8.0)
        val h = (image.bbox[3] - image.bbox[1]).coerceAtLeast(8.0)
        return ImageItem(ImageData(tmp, size.width, size.height), Rect(image.bbox[0], image.bbox[1], w, h))
    }

    // --- JVM test hooks -----------------------------------------------------

    internal fun emptyMediaIndexForProbe() = MediaIndex(emptyMap(), emptyMap(), inferred = true)

    /**
     * A rough measurer for JVM tests that import notes without a real text host: fixed-metric
     * wrapping that keeps [TextItem] geometry plausible, never pixel-exact.
     */
    object StubTextMeasurer : TextMeasurer {
        private const val ASPECT = 0.6

        override fun measure(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags, spans: List<com.xnotes.core.text.RunSpan>): Rect {
            val lh = lineHeight(font)
            var x = 0.0
            var lines = 1
            for (word in text.split(' ')) {
                val w = word.length * font.pointSize * ASPECT
                if (x > 0 && x + w > wrapWidth) { lines++; x = w } else x += w + font.pointSize * ASPECT
            }
            return Rect(0.0, 0.0, wrapWidth, lines * lh)
        }

        override fun lineHeight(font: FontSpec) = font.pointSize * 1.3

        override fun metrics(font: FontSpec) = LineMetrics(font.pointSize * 1.0, font.pointSize * 0.3)

        override fun advances(text: String, font: FontSpec) =
            DoubleArray(text.length) { font.pointSize * ASPECT }
    }
}
