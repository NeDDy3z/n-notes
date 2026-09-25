package com.xnotes.core.model

/**
 * A page background ruling. [NONE] is an **explicit** "off" — distinct from a null
 * [PageStyle.pattern], which means *inherit from the level below*. Serialized by [id].
 */
enum class PagePattern(val id: String) {
    NONE("none"),
    LINES("lines"),
    DOTS("dots"),
    GRID("grid");

    companion object {
        fun fromId(id: String?): PagePattern? = entries.firstOrNull { it.id == id }
    }
}

/** The page template keys that need no embedded file: none, and the three built-in rulings. */
object PageTemplates {
    const val NONE = "none"
    val BUILT_IN = setOf(PagePattern.LINES.id, PagePattern.DOTS.id, PagePattern.GRID.id)

    fun isBuiltIn(key: String?) = key != null && key in BUILT_IN

    /** Whether a spacing or parameter set for template [a] still means something under [b]. */
    fun compatible(a: String, b: String) = a == b || (isBuiltIn(a) && isBuiltIn(b))
}

/**
 * A per-field-inheritable page style override, held on both [Page] (current page) and [Document]
 * ("all pages" of this note). Each field is **independently** nullable: a null field inherits from
 * the next level down — page → document → global preference (page colour) or a built-in default
 * (template/colours/spacing). This per-field nullability is what gives the UI its tri-state controls
 * (Default / explicit value / — for the template — an explicit None). Immutable: callers replace it
 * with a [copy]; sharing the reference across a [Page.deepCopy] is safe.
 *
 * [spacing] and [params] belong to a template, so they only inherit while the level holding them
 * has no template of its own or the same one ([PageTemplates.compatible]).
 */
data class PageStyle(
    val pageColor: Rgba? = null,
    /** [PageTemplates.NONE], a built-in ruling id, or an embedded template's key ([Document.templates]). */
    val template: String? = null,
    val patternColor: Rgba? = null,
    /** The template's spacing parameter, in content pixels. */
    val spacing: Double? = null,
    /** The template's `accent` colour. */
    val accentColor: Rgba? = null,
    /** The template's other parameters by name: numbers, and lengths in mm. */
    val params: Map<String, Double>? = null,
    /** The template's colour parameters by name. */
    val colors: Map<String, Rgba>? = null,
) {
    /**
     * This style set to template [key] (null inherits), dropping the spacing and parameters when
     * they belonged to a different template. [inherited] is what the level shows with no template
     * of its own.
     */
    fun withTemplate(key: String?, inherited: String): PageStyle {
        val from = template ?: inherited
        val to = key ?: inherited
        return if (PageTemplates.compatible(from, to)) copy(template = key) else copy(template = key, spacing = null, params = null, colors = null)
    }

    /** True when nothing is overridden — the codec writes no `style` object in this case. */
    val isEmpty: Boolean
        get() = pageColor == null && template == null && patternColor == null && spacing == null &&
            accentColor == null && params.isNullOrEmpty() && colors.isNullOrEmpty()

    companion object {
        const val DEFAULT_SPACING = 64.0 // content px (~10.8 mm at 150 dpi)
        const val MIN_SPACING = 16.0
        const val MAX_SPACING = 200.0
        val DEFAULT_PATTERN_COLOR = Rgba(150, 150, 150, 64) // grey at ~25% opacity by default
        val DEFAULT_ACCENT_COLOR = Rgba(214, 72, 72, 150)

        /** Fixed (non-configurable) ruling thickness / dot radius, in content pixels. */
        const val LINE_THICKNESS = 1.5
        const val DOT_RADIUS = 2.0
    }
}

// --- resolution: a page's own override -> its document's ("all pages") override -> a caller default ---
// Shared by the live canvas ([CanvasState]) and PDF export so both honour the same hierarchy, and so
// each resolves against the document actually being drawn/exported (not necessarily the open one).

/** Resolved paper colour, or null to fall back to the theme paper. [global] is the app-wide preference. */
fun Page.resolvedPageColor(doc: Document, global: Rgba?): Rgba? =
    style.pageColor ?: doc.style.pageColor ?: global

fun Page.resolvedTemplate(doc: Document): String =
    style.template ?: doc.style.template ?: PageTemplates.NONE

fun Page.resolvedPatternColor(doc: Document): Rgba =
    style.patternColor ?: doc.style.patternColor ?: PageStyle.DEFAULT_PATTERN_COLOR

fun Page.resolvedAccentColor(doc: Document): Rgba =
    style.accentColor ?: doc.style.accentColor ?: PageStyle.DEFAULT_ACCENT_COLOR

/** The levels whose template parameters apply to this page, nearest first. */
private fun Page.paramLevels(doc: Document): List<PageStyle> {
    val key = resolvedTemplate(doc)
    return listOf(style, doc.style).filter { s -> s.template.let { it == null || PageTemplates.compatible(it, key) } }
}

/** The template's spacing in content px, or null to use the template's own default. */
fun Page.resolvedSpacing(doc: Document): Double? = paramLevels(doc).firstNotNullOfOrNull { it.spacing }

/** The template's parameter values set anywhere in the chain, nearest level winning. */
fun Page.resolvedParams(doc: Document): Map<String, Double> {
    val out = HashMap<String, Double>()
    for (s in paramLevels(doc).asReversed()) s.params?.let { out.putAll(it) }
    return out
}

fun Page.resolvedColors(doc: Document): Map<String, Rgba> {
    val out = HashMap<String, Rgba>()
    for (s in paramLevels(doc).asReversed()) s.colors?.let { out.putAll(it) }
    return out
}
