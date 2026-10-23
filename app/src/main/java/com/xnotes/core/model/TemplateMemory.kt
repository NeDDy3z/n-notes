package com.xnotes.core.model

/**
 * What the Styles popup remembers while it is open: each level's settings for every template it
 * showed, so trying another template loses nothing until the popup closes. The note's level and
 * every page's are kept apart.
 */
class TemplateMemory {
    private val note = HashMap<String, TemplateSettings>()
    private val perPage = HashMap<Page, HashMap<String, TemplateSettings>>()

    /** A change to the note's style, and to the own styles of the pages that follow it. */
    class NoteChange(val style: PageStyle, val pages: Map<Page, PageStyle>)

    /**
     * [doc]'s style set to template [key]: the template it leaves keeps its settings here and
     * [key] gets back what it had, and every page following the note swaps its own likewise.
     */
    fun pickForNote(doc: Document, key: String): NoteChange {
        val from = doc.style.template ?: PageTemplates.NONE
        if (from == key) return NoteChange(doc.style.copy(template = key), emptyMap())
        return NoteChange(swap(note, doc.style, key, from, key), followers(doc, from, key))
    }

    /** [doc]'s style back to all Default, forgetting everything; the pages following the note lose their settings. */
    fun resetNote(doc: Document): NoteChange {
        note.clear()
        perPage.clear()
        val pages = doc.pages.filter { it.style.template == null && !it.style.settings.isEmpty }
        return NoteChange(PageStyle(), pages.associateWith { it.style.withSettings(TemplateSettings()) })
    }

    /** [page]'s style set to template [key], null following the note, keeping and restoring its settings likewise. */
    fun pickForPage(doc: Document, page: Page, key: String?): PageStyle {
        val inherited = doc.style.template ?: PageTemplates.NONE
        val from = page.style.template ?: inherited
        val to = key ?: inherited
        if (from == to) return page.style.copy(template = key)
        return swap(perPage.getOrPut(page) { HashMap() }, page.style, key, from, to)
    }

    /** What the note, or [page] when given, last had for template [key] while it showed another. */
    fun recall(page: Page?, key: String): TemplateSettings? = (if (page == null) note else perPage[page])?.get(key)

    private fun swap(kept: HashMap<String, TemplateSettings>, style: PageStyle, key: String?, from: String, to: String): PageStyle {
        keep(kept, from, style.settings)
        return style.copy(template = key).withSettings(kept[to] ?: TemplateSettings())
    }

    private fun followers(doc: Document, from: String, to: String): Map<Page, PageStyle> {
        val out = LinkedHashMap<Page, PageStyle>()
        for (p in doc.pages) {
            if (p.style.template != null) continue
            val own = p.style.settings
            if (own.isEmpty && p !in perPage) continue
            val kept = perPage.getOrPut(p) { HashMap() }
            keep(kept, from, own)
            val next = kept[to] ?: TemplateSettings()
            if (next != own) out[p] = p.style.withSettings(next)
        }
        return out
    }

    private fun keep(kept: HashMap<String, TemplateSettings>, key: String, s: TemplateSettings) {
        if (s.isEmpty) kept.remove(key) else kept[key] = s
    }
}
