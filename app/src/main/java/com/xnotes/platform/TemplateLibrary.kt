package com.xnotes.platform

import android.content.Context
import android.content.res.AssetManager
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageTemplates
import com.xnotes.core.model.marginStrips
import com.xnotes.core.model.resolvedAccentColor
import com.xnotes.core.model.resolvedColors
import com.xnotes.core.model.resolvedParams
import com.xnotes.core.model.resolvedPatternColor
import com.xnotes.core.model.resolvedSpacing
import com.xnotes.core.model.resolvedTemplate
import com.xnotes.core.pal.Renderer
import com.xnotes.core.template.Template
import com.xnotes.core.template.TemplateEval
import com.xnotes.core.template.TemplateLayoutCache
import com.xnotes.core.template.TemplatePage
import com.xnotes.core.template.TemplatePainter
import com.xnotes.core.template.TemplateValues
import com.xnotes.format.TemplateFormatException
import com.xnotes.format.TemplateReader
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The page templates the app knows: the three built-in rulings, the bundled examples, and the
 * user's imports (`filesDir/templates`). Built-ins go by their ruling id (`lines`); every other
 * template goes by a key hashed from its text, so the copy a note carries in its bundle and the one
 * in the library are the same template exactly when their bytes are. Also paints a page's template,
 * shared by the canvas caches, thumbnails and PDF export.
 */
object TemplateLibrary {

    enum class Source { BUILT_IN, BUNDLED, IMPORTED }

    class Entry(val key: String, val template: Template, val text: String, val source: Source)

    private const val ASSET_DIR = "templates"
    private const val EXAMPLES_DIR = "templates/examples"
    private const val EXT = ".xtemplate"

    @Volatile
    private var importDir: File? = null
    @Volatile
    private var bundled: List<Entry> = emptyList()
    private val imported = ConcurrentHashMap<String, Entry>()
    private val parsed = ConcurrentHashMap<String, Template>()

    private val eval = TemplateEval(AndroidGlyphOutliner())
    val layouts = TemplateLayoutCache(eval)

    fun init(context: Context) {
        val app = context.applicationContext
        importDir = File(app.filesDir, "templates")
        bundled = loadBundled(app.assets)
        reloadImported()
    }

    /** Every template to offer, built-ins first, then the bundled examples, then imports. */
    fun all(): List<Entry> = bundled + imported.values.sortedBy { it.template.name.lowercase() }

    fun entry(key: String): Entry? = bundled.firstOrNull { it.key == key } ?: imported[key]

    /** The key of a template that is not built in: a hash of its text. */
    fun keyOf(text: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return d.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * The template [key] names for [doc]: the note's own embedded copy first, so a note keeps
     * drawing what it was drawn with, then the library.
     */
    fun template(doc: Document, key: String): Template? {
        if (key == PageTemplates.NONE) return null
        if (!PageTemplates.isBuiltIn(key)) doc.templates[key]?.let { return parse(key, it) }
        return entry(key)?.template
    }

    /** [doc] with [key]'s text embedded, when it is a library template the note does not carry yet. */
    fun embed(doc: Document, key: String?) {
        if (key == null || key == PageTemplates.NONE || PageTemplates.isBuiltIn(key) || doc.templates.containsKey(key)) return
        val text = entry(key)?.text ?: return
        doc.templates = doc.templates + (key to text)
    }

    /** Adopt [text] as a user template; throws [TemplateFormatException] when it is not one. */
    fun import(text: String): Entry {
        val t = TemplateReader.read(text)
        val key = keyOf(text)
        entry(key)?.let { return it }
        val dir = importDir ?: throw TemplateFormatException("template storage is unavailable")
        dir.mkdirs()
        File(dir, key + EXT).writeText(text, Charsets.UTF_8)
        return Entry(key, t, text, Source.IMPORTED).also { imported[key] = it; parsed[key] = t }
    }

    fun remove(key: String) {
        imported.remove(key) ?: return
        File(importDir ?: return, key + EXT).delete()
    }

    private fun parse(key: String, text: String): Template? =
        parsed[key] ?: runCatching { TemplateReader.read(text) }.getOrNull()?.also { parsed[key] = it }

    private fun loadBundled(am: AssetManager): List<Entry> {
        fun read(path: String) = runCatching { am.open(path).use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull()
        val out = ArrayList<Entry>()
        for (id in listOf("lines", "dots", "grid")) {
            val text = read("$ASSET_DIR/$id$EXT") ?: continue
            val t = runCatching { TemplateReader.read(text) }.getOrNull() ?: continue
            out += Entry(id, t, text, Source.BUILT_IN)
        }
        val examples = (runCatching { am.list(EXAMPLES_DIR) }.getOrNull() ?: emptyArray()).filter { it.endsWith(EXT) }
        out += examples.mapNotNull { name ->
            val text = read("$EXAMPLES_DIR/$name") ?: return@mapNotNull null
            val t = runCatching { TemplateReader.read(text) }.getOrNull() ?: return@mapNotNull null
            Entry(keyOf(text), t, text, Source.BUNDLED)
        }.sortedBy { it.template.name.lowercase() }
        return out
    }

    private fun reloadImported() {
        imported.clear()
        val files = importDir?.listFiles() ?: return
        for (f in files) {
            if (!f.isFile || !f.name.endsWith(EXT)) continue
            val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            val t = runCatching { TemplateReader.read(text) }.getOrNull() ?: continue
            val key = keyOf(text)
            imported[key] = Entry(key, t, text, Source.IMPORTED)
        }
    }

    // --- painting ---

    /** [page]'s parameter values from its style chain, lengths converted to template mm. */
    fun values(t: Template, doc: Document, page: Page): TemplateValues {
        val numbers = HashMap(page.resolvedParams(doc))
        val spacing = t.spacingParam
        page.resolvedSpacing(doc)?.let { px -> if (spacing != null) numbers[spacing.name] = px * 25.4 / doc.dpi }
        return TemplateValues(numbers, page.resolvedColors(doc))
    }

    /**
     * Paint [page]'s template into [r] in page-local content px. [cover] is the page's whole paper,
     * margins included; [region] is the part being painted. A page showing an imported PDF gets its
     * template in the margins only, so the PDF itself is never drawn over.
     */
    fun paint(r: Renderer, doc: Document, page: Page, cover: Rect, region: Rect) {
        val key = page.resolvedTemplate(doc)
        val t = template(doc, key) ?: return
        val mm = doc.dpi / 25.4
        val content = Rect(0.0, 0.0, page.width, page.height)
        val papers = if (page.pdfPage == null) listOf(cover) else marginStrips(cover, content)
        val values = values(t, doc, page)
        val ink = page.resolvedPatternColor(doc)
        val accent = page.resolvedAccentColor(doc)
        for (paper in papers) {
            if (!paper.intersects(region)) continue
            val tp = TemplatePage(
                page.width / mm, page.height / mm,
                Rect(paper.x / mm, paper.y / mm, paper.w / mm, paper.h / mm),
                ink, accent, values,
            )
            TemplatePainter.paint(r, layouts.layout(t, tp), mm, region)
        }
    }
}
