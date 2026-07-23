package com.xnotes.core.text

import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontFace

/** Paragraph alignment. [id] is the stable serialized token. */
enum class ParaAlign(val id: String) {
    LEFT("left"),
    CENTER("center"),
    RIGHT("right"),
    JUSTIFY("justify");

    companion object {
        fun fromId(id: String?): ParaAlign = entries.firstOrNull { it.id == id } ?: LEFT
    }
}

/** What kind of list item a paragraph is, if any. [id] is the stable serialized token. */
enum class ListKind(val id: String) {
    NONE("none"),
    BULLET("bullet"),
    ORDERED("ordered"),
    CHECK("check");

    companion object {
        fun fromId(id: String?): ListKind = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * Immutable character style for a run. Nullable fields inherit the flow defaults
 * ([TextFlow.defaultColor]/[TextFlow.defaultSizePt]/[TextFlow.defaultFace]);
 * [code] renders in [TextFlow.monoFace] with a subtle background. Equality
 * drives run merging, so keep this a value type.
 */
data class CharStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val color: Rgba? = null,
    val highlight: Rgba? = null,
    val sizePt: Double? = null,
    val face: FontFace? = null,
) {
    companion object {
        val DEFAULT = CharStyle()
    }
}

/** A maximal stretch of same-styled text inside a paragraph. Mutable, identity-compared. */
class Run(var text: String, var style: CharStyle = CharStyle.DEFAULT) {
    fun deepCopy(): Run = Run(text, style)
}

/**
 * One paragraph of the flow (one line of a code block). Mutable and compared by
 * identity, like [com.xnotes.core.model.CanvasItem]s, so undo commands can hold
 * stable references. All mutation goes through FlowEditor, which bumps [rev].
 */
class Paragraph(
    val runs: MutableList<Run> = mutableListOf(),
    var align: ParaAlign = ParaAlign.LEFT,
    var indent: Int = 0,
    var list: ListKind = ListKind.NONE,
    var checked: Boolean = false,
    /** Non-null marks a code line; "" means plain/unhighlighted code. */
    var codeLang: String? = null,
) {
    /** Bumped on any content or style mutation; layout caches key on it. */
    var rev: Int = 0
        internal set

    internal fun touch() {
        rev++
    }

    val length: Int get() = runs.sumOf { it.text.length }

    fun plainText(): String = runs.joinToString("") { it.text }

    /** True when every paragraph-level property is at its default (runs not considered). */
    fun isDefaultStyle(): Boolean =
        align == ParaAlign.LEFT && indent == 0 && list == ListKind.NONE && !checked && codeLang == null

    fun deepCopy(): Paragraph =
        Paragraph(runs.mapTo(mutableListOf()) { it.deepCopy() }, align, indent, list, checked, codeLang)
            .also { it.rev = rev }

    companion object {
        const val MAX_INDENT = 6
    }
}

/** Flow page margins in millimetres (converted to content px at the document dpi). */
data class FlowMargins(
    val leftMm: Double = DEFAULT_MM,
    val topMm: Double = DEFAULT_MM,
    val rightMm: Double = DEFAULT_MM,
    val bottomMm: Double = DEFAULT_MM,
) {
    companion object {
        const val DEFAULT_MM = 20.0
        const val MIN_MM = 0.0
        const val MAX_MM = 80.0
    }
}

/** A caret position: paragraph index + character offset within that paragraph. */
data class FlowPos(val para: Int, val offset: Int) : Comparable<FlowPos> {
    override fun compareTo(other: FlowPos): Int =
        if (para != other.para) para.compareTo(other.para) else offset.compareTo(other.offset)

    companion object {
        val START = FlowPos(0, 0)
    }
}

/** A (possibly collapsed, possibly reversed) span of the flow between two positions. */
data class FlowRange(val start: FlowPos, val end: FlowPos) {
    val collapsed: Boolean get() = start == end

    /** The same range with [start] <= [end] (selection drags can be backwards). */
    fun normalized(): FlowRange = if (start <= end) this else FlowRange(end, start)

    companion object {
        fun caret(pos: FlowPos): FlowRange = FlowRange(pos, pos)
    }
}

/**
 * The document-wide flowing rich text: an ordered list of paragraphs that lays
 * out across all pages inside [margins] (like an .odt body), plus the default
 * text style paragraphs inherit. Owned by [com.xnotes.core.model.Document]; one
 * per document, empty until the user types.
 */
class TextFlow {
    val paragraphs: MutableList<Paragraph> = mutableListOf()
    var margins: FlowMargins = FlowMargins()
    var defaultFace: FontFace = FontFace.SANS
    var defaultSizePt: Double = DEFAULT_SIZE_PT

    /** Explicit base text colour, or null to follow the host theme (auto). */
    var defaultColor: Rgba? = null

    /** The face code paragraphs and inline code render in. */
    var monoFace: FontFace = FontFace.MONO

    /** Bumped when the paragraph list itself changes (insert/remove/reorder). */
    var rev: Int = 0
        internal set

    internal fun touch() {
        rev++
    }

    /** True when there is nothing worth persisting (no text, no styled remnants). */
    val isEmpty: Boolean
        get() = paragraphs.isEmpty() ||
            (paragraphs.size == 1 && paragraphs[0].length == 0 && paragraphs[0].isDefaultStyle())

    /** The whole flow as plain text, paragraphs joined by '\n' (the IME mirror shape). */
    fun plainText(): String = paragraphs.joinToString("\n") { it.plainText() }

    /** Character offset of [pos] into [plainText] (newline separators count one each). */
    fun globalOffset(pos: FlowPos): Int {
        var total = 0
        for (i in 0 until pos.para) total += paragraphs[i].length + 1
        return total + pos.offset
    }

    /** Inverse of [globalOffset]; clamps past-the-end offsets to the flow end. */
    fun posAtGlobal(offset: Int): FlowPos {
        if (paragraphs.isEmpty()) return FlowPos.START
        var remaining = offset.coerceAtLeast(0)
        for (i in paragraphs.indices) {
            val len = paragraphs[i].length
            if (remaining <= len) return FlowPos(i, remaining)
            remaining -= len + 1
        }
        return FlowPos(paragraphs.size - 1, paragraphs.last().length)
    }

    /** Force re-shaping of every paragraph (font resolution changed under us). */
    fun reshapeAll() {
        paragraphs.forEach { it.touch() }
        touch()
    }

    /** The position after the last character of the flow. */
    fun endPos(): FlowPos =
        if (paragraphs.isEmpty()) FlowPos.START else FlowPos(paragraphs.size - 1, paragraphs.last().length)

    fun deepCopy(): TextFlow {
        val copy = TextFlow()
        paragraphs.mapTo(copy.paragraphs) { it.deepCopy() }
        copy.margins = margins
        copy.defaultFace = defaultFace
        copy.defaultSizePt = defaultSizePt
        copy.defaultColor = defaultColor
        copy.monoFace = monoFace
        copy.rev = rev
        return copy
    }

    companion object {
        const val DEFAULT_SIZE_PT = 12.0

        /** Matches TextItem's historical default so text is visible on the default paper. */
        val DEFAULT_COLOR = Rgba(236, 236, 236, 255)
    }
}

/**
 * The flow's document-level defaults as one value: what the text tool's config
 * popup edits, what "Default for new notes" saves, and what new notes are
 * stamped with. Empty (all built-ins) means nothing to stamp or persist.
 */
data class FlowDefaults(
    val face: FontFace = FontFace.SANS,
    val monoFace: FontFace = FontFace.MONO,
    val sizePt: Double = TextFlow.DEFAULT_SIZE_PT,
    val color: Rgba? = null,
    val margins: FlowMargins = FlowMargins(),
) {
    val isEmpty: Boolean get() = this == FlowDefaults()

    fun applyTo(flow: TextFlow) {
        flow.defaultFace = face
        flow.monoFace = monoFace
        flow.defaultSizePt = sizePt
        flow.defaultColor = color
        flow.margins = margins
    }

    companion object {
        fun of(flow: TextFlow): FlowDefaults =
            FlowDefaults(flow.defaultFace, flow.monoFace, flow.defaultSizePt, flow.defaultColor, flow.margins)
    }
}
