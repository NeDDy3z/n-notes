package com.xnotes.format

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Bookmark
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.Page
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextItem
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.stroke.Sample
import com.xnotes.core.stroke.StrokeSimplify
import com.xnotes.core.tools.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.util.Svg
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Thrown when a file is not a valid `.xnote` bundle. */
class XNoteFormatException(message: String) : Exception(message)

/**
 * Reads and writes the native `.xnote` bundle (spec 08): a ZIP with a deflated
 * `manifest.json` plus stored binary assets (`assets/image-NNN.png`,
 * `assets/source.pdf`). Strokes are kept as editable vector samples; nothing is
 * flattened. Loading is forgiving — unknown item kinds are skipped and missing
 * fields take model defaults.
 */
class DocumentCodec(
    private val imageCodec: ImageCodec,
    private val textMeasurer: TextMeasurer,
) {

    /** Thrown out of [write] when [isCancelled] turns true mid-copy, so the caller can discard the partial file. */
    class WriteCancelled : Exception()

    fun write(doc: Document, out: OutputStream, isCancelled: () -> Boolean = { false }) {
        val assets = ArrayList<Pair<String, File>>()
        ZipOutputStream(out).use { zos ->
            // The manifest streams straight into the deflater: a dense note's JSON is never
            // materialized as an org.json DOM, a String, or a byte[] (three copies per save).
            zos.putNextEntry(ZipEntry("manifest.json").apply { method = ZipEntry.DEFLATED })
            val w = java.io.BufferedWriter(java.io.OutputStreamWriter(zos, Charsets.UTF_8), 32 * 1024)
            writeManifest(JsonWrite(w), doc, assets)
            w.flush()
            zos.closeEntry()
            // The flow lives in its own ODF entry, written only when non-empty (or carrying
            // custom defaults) so untouched notes stay byte-identical to old readers.
            if (!doc.flow.isEmpty || !com.xnotes.core.text.FlowDefaults.of(doc.flow).isEmpty) {
                zos.putDeflated(FlowXml.ENTRY_NAME, FlowXml.write(doc.flow))
            }
            // Stream each image straight from its temp file into the bundle, never as a byte[], so a
            // note full of large images doesn't materialize them all in the heap on every save.
            for ((name, file) in assets) zos.putStored(name, file, isCancelled)
            // Stream the source PDF straight from disk into the bundle so a big PDF is never
            // materialized as a byte[]. [isCancelled] lets a long copy abort (e.g. import cancel).
            doc.pdfFile?.let { zos.putStored("assets/source.pdf", it, isCancelled) }
        }
    }

    // --- model -> streaming json ---

    private fun writeManifest(j: JsonWrite, doc: Document, assets: MutableList<Pair<String, File>>) {
        j.beginObject()
        j.name("format").value(FORMAT)
        j.name("version").value(VERSION)
        j.name("writer").value(WRITER)
        j.name("dpi").value(doc.dpi)
        j.name("has_pdf").value(doc.pdfFile != null)
        j.name("bookmarks").beginArray()
        for (b in doc.bookmarks) {
            j.beginObject()
            j.name("page").value(b.page)
            j.name("label").value(b.label)
            j.endObject()
        }
        j.endArray()
        j.name("pages").beginArray()
        for (page in doc.pages) writePage(j, page, assets)
        j.endArray()
        writeStyle(j, doc.style)
        j.endObject()
    }

    private fun writePage(j: JsonWrite, page: Page, assets: MutableList<Pair<String, File>>) {
        j.beginObject()
        j.name("width").value(page.width)
        j.name("height").value(page.height)
        j.name("pdf_page")
        page.pdfPage?.let { j.value(it) } ?: j.nullValue()
        j.name("items").beginArray()
        for (item in page.items) {
            when (item) {
                is Stroke -> writeStroke(j, item)
                is ImageItem -> {
                    // Readers match assets by manifest name (any extension); .svg keeps the
                    // bundle honest and older readers skip the item they can't decode.
                    val ext = if (Svg.isSvgFile(item.image.file)) "svg" else "png"
                    val name = "assets/image-%03d.%s".format(assets.size, ext)
                    assets.add(name to item.image.file)
                    writeImage(j, item, name)
                }
                is TextItem -> writeText(j, item)
                is ShapeItem -> writeShape(j, item)
                else -> {} // unrecognized kind: not written
            }
        }
        j.endArray()
        writeStyle(j, page.style)
        j.endObject()
    }

    private fun writeStroke(j: JsonWrite, s: Stroke) {
        // Per-sample time is only meaningful to the speed pen, so it's written as an
        // optional 4th element only then — every other stroke serializes unchanged.
        val withTime = s.config.speedStrength > 0.0
        j.beginObject()
        j.name("kind").value(Stroke.KIND)
        j.name("tool").value(s.tool.id)
        j.name("config").beginObject()
        j.name("base_width").value(s.config.baseWidth)
        j.name("pressure_enabled").value(s.config.pressureEnabled)
        j.name("pressure_min_factor").value(s.config.pressureMinFactor)
        j.name("direction_strength").value(s.config.directionStrength)
        j.name("rgba")
        writeRgba(j, s.config.rgba)
        // New style fields are written only when set, so a plain pen/calligraphy
        // stroke's config is byte-for-byte what older versions wrote.
        if (s.config.speedStrength != 0.0) j.name("speed_strength").value(s.config.speedStrength)
        if (s.config.taperEnabled) {
            j.name("taper_enabled").value(true)
            j.name("taper_min_factor").value(s.config.taperMinFactor)
        }
        if (s.config.neon) {
            j.name("neon").value(true)
            j.name("neon_strength").value(s.config.neonStrength)
        }
        // Dash runs matter only to the dashed pen, so a plain stroke's config is unchanged.
        if (s.tool == Tool.DASHED) {
            j.name("dash_length").value(s.config.dashLength)
            j.name("dash_gap").value(s.config.dashGap)
        }
        // Strength matters only to the highlighter; baked per-stroke so a note reopens unchanged.
        if (s.tool == Tool.HIGHLIGHTER) j.name("highlighter_alpha").value(s.config.highlighterAlpha)
        j.endObject()
        // Samples are ~97% of a dense manifest's bytes, so they serialize rounded: 0.01
        // content px (2dp) and 0.001 pressure (3dp) are far below anything visible, and a
        // 500 Hz note shrinks ~2.3x. Rounding is idempotent, so re-saves stay byte-stable.
        j.name("samples").beginArray()
        for (sm in s.samples) {
            j.beginArray().value(round2(sm.x)).value(round2(sm.y)).value(round3(sm.pressure))
            if (withTime) j.value(sm.t)
            j.endArray()
        }
        j.endArray()
        // The speed pen's gesture-speed scale (zoom ÷ density at pen-down) reconstructs its
        // width on reload; written alongside the per-sample times, only for that tool.
        if (withTime) j.name("speed_scale").value(s.speedScale)
        // Straight-line strokes must reload un-smoothed, else the EMA pulls their far end inward.
        if (s.straight) j.name("straight").value(true)
        j.endObject()
    }

    private fun writeImage(j: JsonWrite, item: ImageItem, assetName: String) {
        j.beginObject()
        j.name("kind").value(ImageItem.KIND)
        j.name("asset").value(assetName)
        j.name("rect").beginArray().value(item.rect.x).value(item.rect.y).value(item.rect.w).value(item.rect.h).endArray()
        j.name("src_w").value(item.image.width)
        j.name("src_h").value(item.image.height)
        // Additive field: written only when rotated, so older readers stay compatible.
        if (item.orientation != 0) j.name("orientation").value(item.orientation)
        j.endObject()
    }

    private fun writeText(j: JsonWrite, t: TextItem) {
        j.beginObject()
        j.name("kind").value(TextItem.KIND)
        j.name("pos").beginArray().value(t.pos.x).value(t.pos.y).endArray()
        j.name("width").value(t.width)
        j.name("text").value(t.text)
        j.name("rgba")
        writeRgba(j, t.rgba)
        j.name("point_size").value(t.pointSize)
        // Additive fields: written only when set, so older readers stay compatible
        // and notes that use neither serialize exactly as before.
        if (t.height > 0.0) j.name("height").value(t.height)
        if (t.face != TextItem.DEFAULT_FACE) j.name("font_face").value(t.face.id)
        j.endObject()
    }

    private fun writeShape(j: JsonWrite, s: ShapeItem) {
        j.beginObject()
        j.name("kind").value(ShapeItem.KIND)
        j.name("shape").value(s.shape.id)
        j.name("start").beginArray().value(s.start.x).value(s.start.y).endArray()
        j.name("end").beginArray().value(s.end.x).value(s.end.y).endArray()
        j.name("stroke_rgba")
        writeRgba(j, s.strokeRgba)
        j.name("stroke_width").value(s.strokeWidth)
        j.name("fill_rgba")
        s.fillRgba?.let { writeRgba(j, it) } ?: j.nullValue()
        // Polygon/polyline carry their vertices (absolute content px); other kinds omit them.
        s.vertices()?.let { verts ->
            j.name("points").beginArray()
            for (p in verts) j.beginArray().value(p.x).value(p.y).endArray()
            j.endArray()
        }
        // Glow is additive: a plain shape serializes exactly as before.
        if (s.neon) {
            j.name("neon").value(true)
            j.name("neon_strength").value(s.neonStrength)
        }
        // Dash is additive too: written only on dashed shapes.
        if (s.dashed) {
            j.name("dashed").value(true)
            j.name("dash_length").value(s.dashLength)
            j.name("dash_gap").value(s.dashGap)
        }
        j.endObject()
    }

    private fun writeRgba(j: JsonWrite, c: Rgba) {
        j.beginArray().value(c.r).value(c.g).value(c.b).value(c.a).endArray()
    }

    private fun round2(v: Double): Double = if (v.isFinite()) Math.round(v * 100.0) / 100.0 else v

    private fun round3(v: Double): Double = if (v.isFinite()) Math.round(v * 1000.0) / 1000.0 else v

    /** A page/document style, written only when something is overridden (forgiving: fields are optional). */
    private fun writeStyle(j: JsonWrite, s: PageStyle) {
        if (s.isEmpty) return
        j.name("style").beginObject()
        s.pageColor?.let {
            j.name("page_color")
            writeRgba(j, it)
        }
        s.pattern?.let { j.name("pattern").value(it.id) }
        s.patternColor?.let {
            j.name("pattern_color")
            writeRgba(j, it)
        }
        s.spacing?.let { j.name("spacing").value(it) }
        j.endObject()
    }

    /**
     * Read a `.xnote` from [input]. When [pdfDir] is non-null an embedded source PDF, and when
     * [imageDir] is non-null the inserted images, are streamed out to fresh temp files in those dirs
     * (so neither is ever held in RAM) and the caller owns those files' lifetime. When a dir is null
     * that asset is skipped, which is what validation-only reads want.
     */
    fun read(input: InputStream, pdfDir: File? = null, imageDir: File? = null): Document {
        var manifest: ParsedManifest? = null
        var flowBytes: ByteArray? = null
        val imageFiles = HashMap<String, File>()
        var pdfFile: File? = null
        ZipInputStream(input).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name
                    if (name == "manifest.json") {
                        // Parsed straight off the zip stream: a dense note's manifest is never
                        // materialized as bytes, a String, or an org.json DOM (which held a boxed
                        // wrapper per number and made big notes cost minutes and ~3x their heap).
                        if (manifest == null) {
                            manifest = try {
                                parseManifest(JsonPull(InputStreamReader(zis, Charsets.UTF_8)))
                            } catch (_: JsonPullException) {
                                throw XNoteFormatException(NOT_XNOTE)
                            }
                        }
                    } else if (name == "assets/source.pdf") {
                        // Never slurp the PDF into memory: stream it to disk (or skip it).
                        if (pdfDir != null) {
                            val f = File.createTempFile("src", ".pdf", pdfDir)
                            FileOutputStream(f).use { zis.copyTo(it) }
                            pdfFile = f
                        }
                    } else if (name.startsWith("assets/image-")) {
                        // Stream images to disk too (or skip): a note full of large images must never
                        // load all their encoded bytes into the heap at once.
                        if (imageDir != null) {
                            val f = File.createTempFile("img", null, imageDir)
                            FileOutputStream(f).use { zis.copyTo(it) }
                            imageFiles[name] = f
                        }
                    } else if (name == FlowXml.ENTRY_NAME) {
                        flowBytes = zis.readBytes()
                    }
                    // Anything else is an asset from a newer version: skipped, never buffered.
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val m = manifest ?: throw XNoteFormatException(NOT_XNOTE)
        if (!m.formatOk) throw XNoteFormatException(NOT_XNOTE)

        val doc = Document(dpi = m.dpi)
        doc.style = m.style

        if (m.hasPdf) {
            doc.pdfFile = pdfFile
        } else {
            pdfFile?.delete() // a stray PDF with no manifest flag: don't leak the temp file
        }

        doc.bookmarks.addAll(m.bookmarks)
        flowBytes?.let { FlowXml.readInto(doc.flow, it) }

        if (m.pages.isEmpty()) {
            doc.pages.add(Page.blank(PageSize.A4, Orientation.PORTRAIT, m.dpi))
            return doc
        }
        doc.pages.addAll(m.pages)

        // Image entries stream out of the zip after the manifest, so image items materialize only
        // now that their files exist; the recorded index restores each one's z-order slot.
        for ((page, specs) in m.pageImages) {
            var dropped = 0
            for (spec in specs) {
                val item = materializeImage(spec, imageFiles)
                if (item == null) dropped++ else page.items.add(spec.index - dropped, item)
            }
        }

        // Ink written before pen-up sample reduction shipped (writer < 43, or no writer field at
        // all) carries far more samples than the ribbon needs; compact it once at load. In-memory
        // only — the file shrinks whenever the user next edits and saves.
        if (m.writer < SIMPLIFIED_SINCE) {
            doc.compactedOnLoad = true
            for (page in doc.pages) {
                for (item in page.items) {
                    if (item !is Stroke || item.straight) continue
                    val slim = StrokeSimplify.simplify(item.samples, item.geometry().halfWidths, StrokeSimplify.LEGACY_EPS)
                    if (slim.size != item.samples.size) {
                        item.samples.clear()
                        item.samples.addAll(slim)
                    }
                    item.invalidate() // also frees the geometry built for the width channel
                }
            }
        }
        return doc
    }

    // --- streaming json -> model ---

    private class ParsedManifest {
        var formatOk = false
        var writer = 0
        var dpi = PageSize.DEFAULT_DPI
        var hasPdf = false
        var style = PageStyle()
        val bookmarks = ArrayList<Bookmark>()
        val pages = ArrayList<Page>()
        val pageImages = ArrayList<Pair<Page, List<PendingImage>>>()
    }

    /** An image item parsed before its asset entry has streamed out of the zip. */
    private class PendingImage(
        val index: Int,
        val asset: String,
        val rect: Rect?,
        val srcW: Int,
        val srcH: Int,
        val orientation: Int,
    )

    private fun parseManifest(p: JsonPull): ParsedManifest {
        val m = ParsedManifest()
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "format" -> {
                    if (stringOr(p, "") != FORMAT) throw XNoteFormatException(NOT_XNOTE)
                    m.formatOk = true
                }
                "writer" -> m.writer = intOr(p, 0)
                "dpi" -> m.dpi = intOr(p, PageSize.DEFAULT_DPI)
                "has_pdf" -> m.hasPdf = boolOr(p, false)
                "style" -> m.style = parseStyle(p)
                "bookmarks" -> parseBookmarks(p, m.bookmarks)
                "pages" -> parsePages(p, m)
                else -> p.skipValue()
            }
        }
        p.endObject()
        return m
    }

    private fun parseBookmarks(p: JsonPull, out: MutableList<Bookmark>) {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) return p.skipValue()
        p.beginArray()
        while (p.hasNext()) {
            if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
                p.skipValue()
                continue
            }
            var page = 0
            var label = ""
            p.beginObject()
            while (p.hasNext()) {
                when (p.nextName()) {
                    "page" -> page = intOr(p, 0)
                    "label" -> label = stringOr(p, "")
                    else -> p.skipValue()
                }
            }
            p.endObject()
            out.add(Bookmark(page, label))
        }
        p.endArray()
    }

    private fun parsePages(p: JsonPull, m: ParsedManifest) {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) return p.skipValue()
        val (fallbackW, fallbackH) = PageSize.A4.pixels(Orientation.PORTRAIT, m.dpi)
        p.beginArray()
        while (p.hasNext()) {
            if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
                p.skipValue()
                continue
            }
            var width = fallbackW
            var height = fallbackH
            var pdfPage: Int? = null
            var style = PageStyle()
            val items = mutableListOf<CanvasItem>()
            val pending = ArrayList<PendingImage>()
            p.beginObject()
            while (p.hasNext()) {
                when (p.nextName()) {
                    "width" -> width = doubleOr(p, fallbackW)
                    "height" -> height = doubleOr(p, fallbackH)
                    "pdf_page" -> pdfPage = intOrNull(p)
                    "style" -> style = parseStyle(p)
                    "items" -> parseItems(p, items, pending)
                    else -> p.skipValue()
                }
            }
            p.endObject()
            val page = Page(width = width, height = height, items = items, pdfPage = pdfPage, style = style)
            m.pages.add(page)
            if (pending.isNotEmpty()) m.pageImages.add(page to pending)
        }
        p.endArray()
    }

    private fun parseItems(p: JsonPull, items: MutableList<CanvasItem>, pending: MutableList<PendingImage>) {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) return p.skipValue()
        p.beginArray()
        while (p.hasNext()) parseItem(p, items, pending)
        p.endArray()
    }

    /** Union of every kind's fields, so an item parses in one pass whatever its key order. */
    private class ItemScratch {
        var kind: String? = null
        var tool: String? = null
        var config: ConfigScratch? = null
        var samples: MutableList<Sample>? = null
        var speedScale = 1.0
        var straight = false
        var asset: String? = null
        var rect: Rect? = null
        var srcW = 0
        var srcH = 0
        var orientation = 0
        var pos: Pt? = null
        var width = TextItem.DEFAULT_WIDTH
        var height = 0.0
        var text = ""
        var rgba: Rgba? = null
        var pointSize = TextItem.DEFAULT_POINT_SIZE
        var fontFace = ""
        var shape: String? = null
        var start: Pt? = null
        var end: Pt? = null
        var strokeRgba: Rgba? = null
        var strokeWidth = 3.0
        var fillRgba: Rgba? = null
        var points: List<Pt>? = null
        var neon = false
        var neonStrength = 0.6
        var dashed = false
        var dashLength = 10.0
        var dashGap = 8.0
    }

    /** Stroke config fields as written; null = absent, so defaults resolve exactly as before. */
    private class ConfigScratch {
        var baseWidth: Double? = null
        var pressureEnabled: Boolean? = null
        var pressureMinFactor: Double? = null
        var directionStrength: Double? = null
        var rgba: Rgba? = null
        var speedStrength: Double? = null
        var taperEnabled: Boolean? = null
        var taperLength: Double? = null
        var taperMinFactor: Double? = null
        var neon: Boolean? = null
        var neonStrength: Double? = null
        var dashLength: Double? = null
        var dashGap: Double? = null
        var highlighterAlpha: Double? = null
    }

    private fun parseItem(p: JsonPull, items: MutableList<CanvasItem>, pending: MutableList<PendingImage>) {
        if (p.peek() != JsonPull.Token.BEGIN_OBJECT) return p.skipValue()
        val s = ItemScratch()
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "kind" -> s.kind = stringOr(p, "")
                "tool" -> s.tool = stringOr(p, "")
                "config" -> s.config = parseConfig(p)
                "samples" -> s.samples = parseSamples(p)
                "speed_scale" -> s.speedScale = doubleOr(p, 1.0)
                "straight" -> s.straight = boolOr(p, false)
                "asset" -> s.asset = stringOr(p, "")
                "rect" -> s.rect = rectOrNull(p)
                "src_w" -> s.srcW = intOr(p, 0)
                "src_h" -> s.srcH = intOr(p, 0)
                "orientation" -> s.orientation = intOr(p, 0)
                "pos" -> s.pos = ptOrNull(p)
                "width" -> s.width = doubleOr(p, TextItem.DEFAULT_WIDTH)
                "height" -> s.height = doubleOr(p, 0.0)
                "text" -> s.text = stringOr(p, "")
                "rgba" -> s.rgba = rgbaOrNull(p)
                "point_size" -> s.pointSize = doubleOr(p, TextItem.DEFAULT_POINT_SIZE)
                "font_face" -> s.fontFace = stringOr(p, "")
                "shape" -> s.shape = stringOr(p, "")
                "start" -> s.start = ptOrNull(p)
                "end" -> s.end = ptOrNull(p)
                "stroke_rgba" -> s.strokeRgba = rgbaOrNull(p)
                "stroke_width" -> s.strokeWidth = doubleOr(p, 3.0)
                "fill_rgba" -> s.fillRgba = rgbaOrNull(p)
                "points" -> s.points = pointsOrNull(p)
                "neon" -> s.neon = boolOr(p, false)
                "neon_strength" -> s.neonStrength = doubleOr(p, 0.6)
                "dashed" -> s.dashed = boolOr(p, false)
                "dash_length" -> s.dashLength = doubleOr(p, 10.0)
                "dash_gap" -> s.dashGap = doubleOr(p, 8.0)
                else -> p.skipValue()
            }
        }
        p.endObject()
        when (s.kind) {
            Stroke.KIND -> items.add(buildStroke(s))
            ImageItem.KIND -> {
                val asset = s.asset
                if (!asset.isNullOrEmpty()) {
                    pending.add(PendingImage(items.size + pending.size, asset, s.rect, s.srcW, s.srcH, s.orientation))
                }
            }
            TextItem.KIND -> items.add(
                TextItem(
                    pos = s.pos ?: Pt.ZERO,
                    width = s.width,
                    height = s.height,
                    text = s.text,
                    rgba = s.rgba ?: TextItem.DEFAULT_COLOR,
                    pointSize = s.pointSize,
                    face = FontFace.fromId(s.fontFace),
                    measurer = textMeasurer,
                ),
            )
            ShapeItem.KIND -> items.add(buildShape(s))
            else -> {} // unrecognized kind: skipped (forgiving)
        }
    }

    private fun buildStroke(s: ItemScratch): Stroke {
        val tool = Tool.fromId(s.tool) ?: Tool.PEN
        val c = s.config
        val def = ToolConfig()
        val config = ToolConfig(
            baseWidth = c?.baseWidth ?: def.baseWidth,
            pressureEnabled = c?.pressureEnabled ?: def.pressureEnabled,
            pressureMinFactor = c?.pressureMinFactor ?: def.pressureMinFactor,
            directionStrength = c?.directionStrength ?: def.directionStrength,
            rgba = c?.rgba ?: def.rgba,
            speedStrength = c?.speedStrength ?: def.speedStrength,
            taperEnabled = c?.let { it.taperEnabled ?: ((it.taperLength ?: 0.0) > 0.0) } ?: def.taperEnabled,
            // Absent on legacy taper strokes -> the current default tip, so old tapers reload
            // tapered rather than as a sharp point.
            taperMinFactor = c?.taperMinFactor ?: ToolDefaults.DEFAULT_TAPER_TIP,
            neon = c?.neon ?: def.neon,
            neonStrength = c?.neonStrength ?: def.neonStrength,
            dashLength = c?.dashLength ?: def.dashLength,
            dashGap = c?.dashGap ?: def.dashGap,
            // Absent on legacy highlighter strokes -> the historical 0.35, so they reload unchanged.
            highlighterAlpha = c?.highlighterAlpha ?: def.highlighterAlpha,
        )
        return Stroke(tool, config, s.samples ?: mutableListOf(), s.speedScale, s.straight)
    }

    private fun buildShape(s: ItemScratch): ShapeItem {
        val kind = ShapeKind.fromId(s.shape)
        val strokeRgba = s.strokeRgba ?: Rgba(0, 230, 118, 255)
        s.points?.let { verts ->
            return ShapeItem.poly(
                kind, verts, strokeRgba, s.strokeWidth, s.fillRgba, s.neon, s.neonStrength,
                s.dashed, s.dashLength, s.dashGap,
            )
        }
        return ShapeItem(
            shape = kind,
            start = s.start ?: Pt.ZERO,
            end = s.end ?: Pt.ZERO,
            strokeRgba = strokeRgba,
            strokeWidth = s.strokeWidth,
            fillRgba = s.fillRgba,
            neon = s.neon,
            neonStrength = s.neonStrength,
            dashed = s.dashed,
            dashLength = s.dashLength,
            dashGap = s.dashGap,
        )
    }

    private fun parseConfig(p: JsonPull): ConfigScratch? {
        if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
            p.skipValue()
            return null
        }
        val c = ConfigScratch()
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "base_width" -> c.baseWidth = doubleOrNull(p)
                "pressure_enabled" -> c.pressureEnabled = boolOrNull(p)
                "pressure_min_factor" -> c.pressureMinFactor = doubleOrNull(p)
                "direction_strength" -> c.directionStrength = doubleOrNull(p)
                "rgba" -> c.rgba = rgbaOrNull(p)
                "speed_strength" -> c.speedStrength = doubleOrNull(p)
                "taper_enabled" -> c.taperEnabled = boolOrNull(p)
                "taper_length" -> c.taperLength = doubleOrNull(p)
                "taper_min_factor" -> c.taperMinFactor = doubleOrNull(p)
                "neon" -> c.neon = boolOrNull(p)
                "neon_strength" -> c.neonStrength = doubleOrNull(p)
                "dash_length" -> c.dashLength = doubleOrNull(p)
                "dash_gap" -> c.dashGap = doubleOrNull(p)
                "highlighter_alpha" -> c.highlighterAlpha = doubleOrNull(p)
                else -> p.skipValue()
            }
        }
        p.endObject()
        return c
    }

    private fun parseSamples(p: JsonPull): MutableList<Sample>? {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
            p.skipValue()
            return null
        }
        val out = ArrayList<Sample>()
        p.beginArray()
        while (p.hasNext()) {
            if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
                p.skipValue()
                continue
            }
            p.beginArray()
            val x = if (p.hasNext()) doubleOr(p, 0.0) else 0.0
            val y = if (p.hasNext()) doubleOr(p, 0.0) else 0.0
            val pressure = if (p.hasNext()) doubleOr(p, 1.0) else 1.0
            // 4th element (relative ms) is present only for speed-pen strokes; absent ⇒ 0.
            val t = if (p.hasNext()) doubleOr(p, 0.0) else 0.0
            while (p.hasNext()) p.skipValue()
            p.endArray()
            out.add(Sample(x, y, pressure, t))
        }
        p.endArray()
        return out
    }

    private fun parseStyle(p: JsonPull): PageStyle {
        if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
            p.skipValue()
            return PageStyle()
        }
        var pageColor: Rgba? = null
        var pattern: PagePattern? = null
        var patternColor: Rgba? = null
        var spacing: Double? = null
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "page_color" -> pageColor = rgbaOrNull(p)
                "pattern" -> pattern = PagePattern.fromId(stringOrNull(p))
                "pattern_color" -> patternColor = rgbaOrNull(p)
                "spacing" -> spacing = doubleOrNull(p)
                else -> p.skipValue()
            }
        }
        p.endObject()
        return PageStyle(pageColor = pageColor, pattern = pattern, patternColor = patternColor, spacing = spacing)
    }

    private fun materializeImage(spec: PendingImage, imageFiles: Map<String, File>): ImageItem? {
        val file = imageFiles[spec.asset] ?: return null
        var w = spec.srcW
        var h = spec.srcH
        if (w <= 0 || h <= 0) {
            // Legacy notes (and any without stored dims): read the native size without decoding pixels.
            val probed = imageCodec.probeFile(file.path) ?: return null
            w = probed.width
            h = probed.height
        }
        val rect = spec.rect ?: Rect(0.0, 0.0, w.toDouble(), h.toDouble())
        return ImageItem(ImageData(file, w, h), rect, spec.orientation)
    }

    // --- streaming value helpers (mirroring org.json's forgiving opt* coercions) ---

    private fun doubleOr(p: JsonPull, def: Double): Double = doubleOrNull(p) ?: def

    private fun doubleOrNull(p: JsonPull): Double? = when (p.peek()) {
        JsonPull.Token.NUMBER -> p.nextDouble()
        JsonPull.Token.STRING -> p.nextString().toDoubleOrNull()
        else -> {
            p.skipValue()
            null
        }
    }

    private fun intOr(p: JsonPull, def: Int): Int = intOrNull(p) ?: def

    private fun intOrNull(p: JsonPull): Int? = when (p.peek()) {
        JsonPull.Token.NUMBER -> p.nextInt()
        JsonPull.Token.STRING -> p.nextString().let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
        else -> {
            p.skipValue()
            null
        }
    }

    private fun boolOr(p: JsonPull, def: Boolean): Boolean = boolOrNull(p) ?: def

    private fun boolOrNull(p: JsonPull): Boolean? = when (p.peek()) {
        JsonPull.Token.BOOLEAN -> p.nextBoolean()
        JsonPull.Token.STRING -> when (p.nextString().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> {
            p.skipValue()
            null
        }
    }

    private fun stringOr(p: JsonPull, def: String): String = stringOrNull(p) ?: def

    private fun stringOrNull(p: JsonPull): String? = when (p.peek()) {
        JsonPull.Token.STRING -> p.nextString()
        else -> {
            p.skipValue()
            null
        }
    }

    private fun rgbaOrNull(p: JsonPull): Rgba? {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
            p.skipValue()
            return null
        }
        val channels = ArrayList<Int>(4)
        p.beginArray()
        while (p.hasNext()) channels.add(intOr(p, 0))
        p.endArray()
        return Rgba.fromList(channels)
    }

    private fun ptOrNull(p: JsonPull): Pt? {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
            p.skipValue()
            return null
        }
        var count = 0
        var x = 0.0
        var y = 0.0
        p.beginArray()
        while (p.hasNext()) {
            when (count) {
                0 -> x = doubleOr(p, 0.0)
                1 -> y = doubleOr(p, 0.0)
                else -> p.skipValue()
            }
            count++
        }
        p.endArray()
        return if (count >= 2) Pt(x, y) else null
    }

    private fun rectOrNull(p: JsonPull): Rect? {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
            p.skipValue()
            return null
        }
        val v = DoubleArray(4)
        var count = 0
        p.beginArray()
        while (p.hasNext()) {
            if (count < 4) v[count] = doubleOr(p, 0.0) else p.skipValue()
            count++
        }
        p.endArray()
        return if (count >= 4) Rect(v[0], v[1], v[2], v[3]) else null
    }

    private fun pointsOrNull(p: JsonPull): List<Pt>? {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
            p.skipValue()
            return null
        }
        val out = ArrayList<Pt>()
        p.beginArray()
        while (p.hasNext()) ptOrNull(p)?.let { out.add(it) }
        p.endArray()
        return if (out.size >= 2) out else null
    }

    companion object {
        const val FORMAT = "xnote"
        const val VERSION = 1

        /** The com.xnotes versionCode stamped into manifests this build writes ("writer"), kept
         *  in step with the release that carries it. Old readers ignore the unknown key. */
        const val WRITER = 43

        /** Writers at/after this versionCode reduce ink samples at pen-up; ink from older
         *  writers (or files with no "writer" at all) is compacted once at load instead. */
        private const val SIMPLIFIED_SINCE = 43

        private const val NOT_XNOTE = "Not an xnotes document"
    }
}

private fun ZipOutputStream.putDeflated(name: String, data: ByteArray) {
    val entry = ZipEntry(name).apply { method = ZipEntry.DEFLATED }
    putNextEntry(entry)
    write(data)
    closeEntry()
}

/**
 * Stream [file] into a STORED (uncompressed) zip entry without ever holding it whole in memory.
 * STORED entries need size+CRC up front, so the file is read twice — once to checksum, once to
 * copy — both in small buffers. [isCancelled] is polled per buffer so a long copy can abort by
 * throwing [DocumentCodec.WriteCancelled] (the entry is left unfinished for the caller to discard).
 */
private fun ZipOutputStream.putStored(name: String, file: File, isCancelled: () -> Boolean) {
    val crc = CRC32()
    val buf = ByteArray(64 * 1024)
    FileInputStream(file).use { input ->
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            crc.update(buf, 0, n)
        }
    }
    val size = file.length()
    val entry = ZipEntry(name).apply {
        method = ZipEntry.STORED
        this.size = size
        compressedSize = size
        this.crc = crc.value
    }
    putNextEntry(entry)
    FileInputStream(file).use { input ->
        while (true) {
            if (isCancelled()) throw DocumentCodec.WriteCancelled()
            val n = input.read(buf)
            if (n < 0) break
            write(buf, 0, n)
        }
    }
    closeEntry()
}
