package com.xnotes.core.text

import com.xnotes.core.history.Command

/**
 * Markdown shortcuts applied while typing: the markers convert the paragraph and
 * are then deleted, so the flow itself never holds markdown. Detection mirrors
 * [MarkdownParser], so a typed marker lands the same paragraph that pasting it
 * would, and is pure so the caller can flush its typing burst before [apply]
 * mutates anything: the conversion is then its own undo step and one undo puts
 * the markers back as plain text.
 */
object InputRules {

    /** A rule that matched. Opaque to callers, who only pass it back to [apply]. */
    sealed interface Rule

    /** An applied rule: its command, the caret after it, and the next typing style. */
    class Result(val command: Command?, val caret: FlowPos, val pending: CharStyle? = null)

    // Block markers match the whole text before the caret, so they only fire on a
    // prefix the user just completed with the trigger space.
    private val HEADING = Regex("^(#{1,4}) $")
    private val TASK = Regex("^(\\s*)\\[([ xX]?)] $")
    private val ORDERED = Regex("^(\\s*)\\d{1,9}[.)] $")
    private val BULLET = Regex("^(\\s*)[-*+] $")
    private val QUOTE = Regex("^(>+) $")
    private val FENCE = Regex("^\\s{0,3}```\\s*([A-Za-z0-9+#-]*)\\s*$")

    private enum class Emph(val apply: (CharStyle) -> CharStyle) {
        BOLD({ it.copy(bold = true) }),
        ITALIC({ it.copy(italic = true) }),
        STRIKE({ it.copy(strike = true) }),
        CODE({ it.copy(code = true) }),
    }

    /** Emphasis markers longest first, so ** is tried before *. */
    private val MARKERS = listOf(
        "**" to Emph.BOLD,
        "__" to Emph.BOLD,
        "~~" to Emph.STRIKE,
        "*" to Emph.ITALIC,
        "_" to Emph.ITALIC,
    )

    private class Block(
        val prefix: Int,
        val heading: Int = 0,
        val list: ListKind? = null,
        val checked: Boolean = false,
        val indent: Int? = null,
        val indentDelta: Int = 0,
    ) : Rule

    private class Inline(val open: Int, val marker: String, val emph: Emph) : Rule

    private class Math(val open: Int, val display: Boolean = false) : Rule

    private class Fence(val lang: String) : Rule

    private enum class Strip : Rule { CODE, LIST, LIST_EXIT, HEADING, INDENT }

    // --- detection ---

    /**
     * The rule typing [typed] with the caret now at [pos] triggers, or null. Only
     * plain typing reaches here: a paste builds its paragraphs through
     * [MarkdownParser], and a code line takes markers literally.
     */
    fun forTyped(flow: TextFlow, pos: FlowPos, typed: String): Rule? {
        if (typed.isEmpty() || '\n' in typed) return null
        val para = flow.paragraphs.getOrNull(pos.para) ?: return null
        if (para.codeLang != null) return null
        val text = para.plainText()
        if (pos.offset > text.length) return null
        val before = text.take(pos.offset)
        if (!typed.endsWith(" ")) return inlineRule(before)
        return if (para.table != null) null else blockRule(before)
    }

    /**
     * The rule pressing Enter at [pos] triggers, or null. [markdown] off drops the
     * fence, which is syntax; stepping out of a list is plain list editing and stays.
     */
    fun forEnter(flow: TextFlow, pos: FlowPos, markdown: Boolean = true): Rule? {
        val para = flow.paragraphs.getOrNull(pos.para) ?: return null
        if (para.table != null) return null
        if (markdown && para.codeLang == null) {
            FENCE.matchEntire(para.plainText())?.let {
                return Fence(MarkdownParser.normalizeLang(it.groupValues[1].lowercase()))
            }
        }
        // Enter on an empty list item steps out of it rather than making another.
        if (para.length != 0 || para.list == ListKind.NONE) return null
        return Strip.LIST_EXIT
    }

    /**
     * The rule a backspace at the very start of [pos]'s paragraph triggers: strip
     * one block property (code line, list, heading, then indent) instead of
     * merging into the line above. Null when the key should just delete.
     */
    fun forBackspace(flow: TextFlow, pos: FlowPos): Rule? {
        val para = flow.paragraphs.getOrNull(pos.para) ?: return null
        if (para.table != null) return null
        return when {
            para.codeLang != null -> if (para.length == 0) Strip.CODE else null
            para.list != ListKind.NONE -> Strip.LIST
            para.headingLevel > 0 -> Strip.HEADING
            para.indent > 0 -> Strip.INDENT
            else -> null
        }
    }

    private fun blockRule(before: String): Rule? {
        val n = before.length
        HEADING.matchEntire(before)?.let { return Block(n, heading = it.groupValues[1].length) }
        TASK.matchEntire(before)?.let {
            return Block(
                n,
                list = ListKind.CHECK,
                checked = it.groupValues[2].isNotBlank(),
                indent = indentOf(it.groupValues[1]),
            )
        }
        ORDERED.matchEntire(before)?.let { return Block(n, list = ListKind.ORDERED, indent = indentOf(it.groupValues[1])) }
        BULLET.matchEntire(before)?.let { return Block(n, list = ListKind.BULLET, indent = indentOf(it.groupValues[1])) }
        QUOTE.matchEntire(before)?.let { return Block(n, indentDelta = it.groupValues[1].length) }
        return null
    }

    /** Leading whitespace as an indent level (two spaces a step), or null to keep the current one. */
    private fun indentOf(leading: String): Int? =
        if (leading.isEmpty()) null else (leading.replace("\t", "  ").length / 2).coerceAtMost(Paragraph.MAX_INDENT)

    private fun inlineRule(before: String): Rule? {
        mathRule(before)?.let { return it }
        if (before.endsWith("`")) {
            val close = before.length - 1
            val open = before.lastIndexOf('`', close - 1)
            return if (open >= 0 && close - open > 1) Inline(open, "`", Emph.CODE) else null
        }
        for ((marker, emph) in MARKERS) {
            if (!before.endsWith(marker)) continue
            val open = findOpen(before, before.length - marker.length, marker) ?: continue
            return Inline(open, marker, emph)
        }
        return null
    }

    /**
     * The "$...$" that just closed, or null. Doubled markers are tried first, so
     * "$$x$$" is one display equation and never the inline one hiding inside it.
     */
    private fun mathRule(before: String): Rule? {
        if (!before.endsWith("$")) return null
        if (before.endsWith("$$")) {
            val close = before.length - 2
            val open = before.lastIndexOf("$$", close - 1)
            if (open < 0 || close - open < 3) return null
            return if (pads(before, open + 2, close)) Math(open, display = true) else null
        }
        val close = before.length - 1
        val open = before.lastIndexOf('$', close - 1)
        if (open < 0 || close - open < 2) return null
        if (open > 0 && before[open - 1] == '$') return null
        return if (pads(before, open + 1, close)) Math(open) else null
    }

    /**
     * Whether the markers around [from], [close) are padded the same on both
     * sides, which is what tells a formula from a price. "$x$" and "$ x $" are
     * both somebody writing maths; "$5 and $10" pads only where it closes, which
     * is somebody writing about money, and the asymmetry is the whole signal.
     * A span of nothing but spaces is neither.
     */
    private fun pads(s: String, from: Int, close: Int): Boolean {
        if (from >= close) return false
        if (s[from].isWhitespace() != s[close - 1].isWhitespace()) return false
        return s.substring(from, close).isNotBlank()
    }

    /**
     * The opening [marker] for a run closing at [close], or null. Emphasis never
     * opens or closes on whitespace, so arithmetic like "a * b *" is left alone,
     * and a one-character marker beside its own twin is really half of a
     * two-character one, so "**bold*" does not fire italic mid-word.
     */
    private fun findOpen(s: String, close: Int, marker: String): Int? {
        if (close <= 0 || s[close - 1].isWhitespace()) return null
        var i = s.lastIndexOf(marker, close - 1)
        while (i >= 0) {
            val content = i + marker.length
            if (content < close && opens(s, i, content, marker)) return i
            i = s.lastIndexOf(marker, i - 1)
        }
        return null
    }

    private fun opens(s: String, open: Int, content: Int, marker: String): Boolean {
        if (s[content].isWhitespace()) return false
        if (marker.length == 1 && (s[content] == marker[0] || (open > 0 && s[open - 1] == marker[0]))) return false
        // Intraword underscores (snake_case) are not emphasis.
        return !(marker == "_" && open > 0 && s[open - 1].isLetterOrDigit())
    }

    // --- application ---

    /** Apply [rule], detected at [pos], and return its command, caret and typing style. */
    fun apply(flow: TextFlow, pos: FlowPos, rule: Rule): Result = when (rule) {
        is Block -> applyBlock(flow, pos, rule)
        is Inline -> applyInline(flow, pos, rule)
        is Math -> applyMath(flow, pos, rule)
        is Fence -> applyFence(flow, pos, rule)
        is Strip -> applyStrip(flow, pos, rule)
    }

    private fun applyBlock(flow: TextFlow, pos: FlowPos, b: Block): Result {
        val ed = FlowEditor(flow)
        val caret = FlowPos(pos.para, 0)
        val cmds = mutableListOf<Command>()
        ed.deleteRange(FlowRange(caret, FlowPos(pos.para, b.prefix))).first?.let { cmds += it }
        ed.setParaStyle(FlowRange.caret(caret)) { p ->
            if (b.heading > 0) p.headingLevel = b.heading
            if (b.list != null) {
                p.list = b.list
                p.checked = b.checked
            }
            if (b.indent != null) p.indent = b.indent
            p.indent += b.indentDelta
        }?.let { cmds += it }
        var pending: CharStyle? = null
        if (b.heading > 0) {
            val style = Paragraph.headingStyle(b.heading, flow.defaultSizePt)
            val len = flow.paragraphs[pos.para].length
            if (len > 0) {
                ed.setCharStyle(FlowRange(caret, FlowPos(pos.para, len))) {
                    it.copy(bold = true, sizePt = style.sizePt)
                }?.let { cmds += it }
            }
            pending = style
        }
        return Result(ed.combined(cmds), caret, pending)
    }

    private fun applyInline(flow: TextFlow, pos: FlowPos, r: Inline): Result {
        val p = pos.para
        val ed = FlowEditor(flow)
        // The style outside the markers, so typing on past the run is not emphasized.
        val outside = ed.charStyleAt(FlowPos(p, r.open))
        val close = pos.offset - r.marker.length
        val content = r.open + r.marker.length
        val cmds = mutableListOf<Command>()
        ed.deleteRange(FlowRange(FlowPos(p, close), FlowPos(p, pos.offset))).first?.let { cmds += it }
        ed.deleteRange(FlowRange(FlowPos(p, r.open), FlowPos(p, content))).first?.let { cmds += it }
        val end = r.open + (close - content)
        ed.setCharStyle(FlowRange(FlowPos(p, r.open), FlowPos(p, end))) { r.emph.apply(it) }?.let { cmds += it }
        return Result(ed.combined(cmds), FlowPos(p, end), outside)
    }

    /**
     * Drop both markers and set what was between them. A display equation alone
     * on its line draws centred, but the paragraph's own alignment is left as it
     * was: turning it on here would outlive the equation.
     */
    private fun applyMath(flow: TextFlow, pos: FlowPos, r: Math): Result {
        val p = pos.para
        val marker = if (r.display) 2 else 1
        val ed = FlowEditor(flow)
        val outside = ed.charStyleAt(FlowPos(p, r.open))
        val text = flow.paragraphs[p].plainText()
        // The padding goes with the markers. A run may not begin with a space:
        // the breaker reads one as somewhere to wrap, and the formula carries its
        // whole width on its first character, so the line would hang on nothing.
        var close = pos.offset - marker
        var content = r.open + marker
        while (content < close && text[content].isWhitespace()) content++
        while (close > content && text[close - 1].isWhitespace()) close--
        val cmds = mutableListOf<Command>()
        ed.deleteRange(FlowRange(FlowPos(p, close), FlowPos(p, pos.offset))).first?.let { cmds += it }
        ed.deleteRange(FlowRange(FlowPos(p, r.open), FlowPos(p, content))).first?.let { cmds += it }
        val end = r.open + (close - content)
        ed.setCharStyle(FlowRange(FlowPos(p, r.open), FlowPos(p, end))) {
            it.copy(math = true, mathDisplay = r.display)
        }?.let { cmds += it }
        return Result(ed.combined(cmds), FlowPos(p, end), outside.copy(math = false, mathDisplay = false))
    }

    private fun applyFence(flow: TextFlow, pos: FlowPos, r: Fence): Result {
        val ed = FlowEditor(flow)
        val caret = FlowPos(pos.para, 0)
        val cmds = mutableListOf<Command>()
        ed.deleteRange(FlowRange(caret, FlowPos(pos.para, flow.paragraphs[pos.para].length))).first?.let { cmds += it }
        ed.setParaStyle(FlowRange.caret(caret)) { it.codeLang = r.lang }?.let { cmds += it }
        return Result(ed.combined(cmds), caret, CharStyle.DEFAULT)
    }

    private fun applyStrip(flow: TextFlow, pos: FlowPos, rule: Strip): Result {
        val ed = FlowEditor(flow)
        val caret = FlowPos(pos.para, 0)
        val len = flow.paragraphs[pos.para].length
        return when (rule) {
            Strip.CODE -> Result(ed.setParaStyle(FlowRange.caret(caret)) { it.codeLang = null }, caret, CharStyle.DEFAULT)
            Strip.LIST -> Result(
                ed.setParaStyle(FlowRange.caret(caret)) {
                    it.list = ListKind.NONE
                    it.checked = false
                },
                caret,
            )
            Strip.LIST_EXIT -> Result(
                ed.setParaStyle(FlowRange.caret(caret)) {
                    if (it.indent > 0) {
                        it.indent--
                    } else {
                        it.list = ListKind.NONE
                        it.checked = false
                    }
                },
                caret,
            )
            Strip.HEADING -> {
                val cmds = mutableListOf<Command>()
                ed.setParaStyle(FlowRange.caret(caret)) { it.headingLevel = 0 }?.let { cmds += it }
                if (len > 0) {
                    ed.setCharStyle(FlowRange(caret, FlowPos(pos.para, len))) {
                        it.copy(bold = false, sizePt = null)
                    }?.let { cmds += it }
                }
                Result(ed.combined(cmds), caret, CharStyle.DEFAULT)
            }
            Strip.INDENT -> Result(ed.setParaStyle(FlowRange.caret(caret)) { it.indent-- }, caret)
        }
    }
}
