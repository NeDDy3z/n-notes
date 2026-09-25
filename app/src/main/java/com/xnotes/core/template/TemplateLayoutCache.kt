package com.xnotes.core.template

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba

/**
 * Recently laid-out templates, keyed by everything a layout depends on. A note's pages are mostly
 * the same size with the same style, so one layout serves all of them, and a deep-zoom repaint of
 * a page reuses its layout instead of walking the template again. Thread-safe; layouts run outside
 * the lock, so two threads may occasionally both compute the same one.
 */
class TemplateLayoutCache(private val eval: TemplateEval, private val capacity: Int = 16) {

    private data class Key(
        val template: Template,
        val width: Double,
        val height: Double,
        val paper: Rect,
        val ink: Rgba,
        val accent: Rgba,
        val numbers: Map<String, Double>,
        val colors: Map<String, Rgba>,
    )

    private val map = object : LinkedHashMap<Key, TemplateOutput>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, TemplateOutput>) = size > capacity
    }

    fun layout(t: Template, page: TemplatePage): TemplateOutput {
        val key = Key(t, page.width, page.height, page.paper, page.ink, page.accent, page.values.numbers, page.values.colors)
        synchronized(map) { map[key]?.let { return it } }
        val out = eval.evaluate(t, page)
        synchronized(map) { map[key] = out }
        return out
    }
}
