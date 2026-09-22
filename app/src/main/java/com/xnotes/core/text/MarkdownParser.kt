package com.xnotes.core.text

import com.xnotes.core.model.Rgba

/**
 * A deliberately small, line-based markdown reader for the explicit "Paste as
 * Markdown" action (no AST, no indented code blocks). Headings map to
 * size multipliers over the flow's default size, fenced blocks become code
 * paragraphs (one per line, language from the fence info), lists/tasks/
 * blockquotes map to paragraph properties, and the inline pass handles
 * ** __ * _ ~~ `code` plus links (styled text, URL dropped) and images (alt
 * text). GitHub pipe tables become flow tables with a header row and the
 * delimiter row's column alignments. Unclosed markers fall out as literal text.
 */
object MarkdownParser {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val FENCE = Regex("^\\s{0,3}```\\s*([A-Za-z0-9+#-]*)\\s*$")
    private val TASK = Regex("^(\\s*)[-*+]\\s+\\[([ xX])\\]\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)\\d{1,9}[.)]\\s+(.*)$")
    private val BLOCKQUOTE = Regex("^(>+)\\s?(.*)$")
    private val LINK = Regex("^\\[([^\\]]*)\\]\\(([^)]*)\\)")
    private val IMAGE = Regex("^!\\[([^\\]]*)\\]\\(([^)]*)\\)")
    private val TABLE_DELIM = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

    /** Inert link styling: the text keeps a link look, the URL is dropped. */
    val LINK_COLOR = Rgba(100, 160, 255, 255)

    private val LANG_ALIASES = mapOf(
        "js" to "javascript", "jsx" to "javascript", "ts" to "javascript",
        "py" to "python", "kt" to "kotlin", "kts" to "kotlin",
        "sh" to "bash", "shell" to "bash", "zsh" to "bash",
        "c++" to "cpp", "cc" to "cpp", "cxx" to "cpp", "hpp" to "cpp",
    )

    /** Common fence-info aliases to the bundled grammar ids. */
    fun normalizeLang(id: String): String = LANG_ALIASES[id] ?: id

    /**
     * Parse [text] into flow paragraphs; [baseSizePt] anchors the heading sizes and
     * pipe tables take [tableStyle] (with its header row on).
     */
    fun parse(text: String, baseSizePt: Double, tableStyle: TableStyle = TableStyle()): List<Paragraph> {
        val out = mutableListOf<Paragraph>()
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val tableEnd = tableAt(lines, i)
            if (tableEnd > i) {
                out.addAll(table(lines.subList(i, tableEnd), tableStyle))
                i = tableEnd
                continue
            }
            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                val lang = normalizeLang(fence.groupValues[1].lowercase())
                i++
                while (i < lines.size && !FENCE.matches(lines[i])) {
                    out.add(codeLine(lines[i], lang))
                    i++
                }
                if (i < lines.size) i++ // swallow the closing fence
                continue
            }
            out.add(parseLine(line, baseSizePt))
            i++
        }
        return out
    }

    /** The end (exclusive) of a pipe table starting at line [i], or [i] when there is none. */
    private fun tableAt(lines: List<String>, i: Int): Int {
        val head = lines[i]
        if ('|' !in head || i + 1 >= lines.size || !TABLE_DELIM.matches(lines[i + 1])) return i
        if (!lines[i + 1].contains('|') && cellsOf(head).size < 2) return i
        if (cellsOf(lines[i + 1]).size != cellsOf(head).size) return i
        var end = i + 2
        while (end < lines.size && lines[end].isNotBlank() && '|' in lines[end]) end++
        return end
    }

    private fun table(lines: List<String>, style: TableStyle): List<Paragraph> {
        val header = cellsOf(lines[0])
        val cols = header.size
        val aligns = cellsOf(lines[1]).map {
            val l = it.startsWith(':')
            val r = it.endsWith(':')
            when {
                l && r -> ParaAlign.CENTER
                r -> ParaAlign.RIGHT
                else -> ParaAlign.LEFT
            }
        }
        val rows = listOf(header) + lines.drop(2).map { cellsOf(it) }
        val table = FlowTable(FlowTable.even(cols), List(rows.size) { 0.0 }, style.copy(headerRow = true))
        return rows.flatMap { row ->
            List(cols) { c ->
                Paragraph(
                    inline(row.getOrElse(c) { "" }, CharStyle.DEFAULT),
                    align = aligns.getOrElse(c) { ParaAlign.LEFT },
                    table = table,
                    cellStart = true,
                )
            }
        }
    }

    /** A table line's cells: outer pipes dropped, split on unescaped pipes, trimmed. */
    private fun cellsOf(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        val cells = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            when {
                c == '\\' && i + 1 < t.length && t[i + 1] == '|' -> {
                    cur.append('|')
                    i += 2
                    continue
                }
                c == '|' -> {
                    cells.add(cur.toString().trim())
                    cur.clear()
                }
                else -> cur.append(c)
            }
            i++
        }
        cells.add(cur.toString().trim())
        return cells
    }

    private fun codeLine(line: String, lang: String): Paragraph = Paragraph(
        if (line.isEmpty()) mutableListOf() else mutableListOf(Run(line)),
        codeLang = lang,
    )

    private fun parseLine(line: String, baseSizePt: Double): Paragraph {
        HEADING.matchEntire(line)?.let { m ->
            val level = m.groupValues[1].length
            return Paragraph(inline(m.groupValues[2], Paragraph.headingStyle(level, baseSizePt)), headingLevel = level)
        }
        TASK.matchEntire(line)?.let { m ->
            return Paragraph(
                inline(m.groupValues[3], CharStyle.DEFAULT),
                indent = indentOf(m.groupValues[1]),
                list = ListKind.CHECK,
                checked = m.groupValues[2].isNotBlank(),
            )
        }
        BULLET.matchEntire(line)?.let { m ->
            return Paragraph(
                inline(m.groupValues[2], CharStyle.DEFAULT),
                indent = indentOf(m.groupValues[1]),
                list = ListKind.BULLET,
            )
        }
        ORDERED.matchEntire(line)?.let { m ->
            return Paragraph(
                inline(m.groupValues[2], CharStyle.DEFAULT),
                indent = indentOf(m.groupValues[1]),
                list = ListKind.ORDERED,
            )
        }
        BLOCKQUOTE.matchEntire(line)?.let { m ->
            return Paragraph(
                inline(m.groupValues[2], CharStyle.DEFAULT),
                indent = m.groupValues[1].length.coerceAtMost(Paragraph.MAX_INDENT),
            )
        }
        return Paragraph(inline(line, CharStyle.DEFAULT))
    }

    private fun indentOf(leading: String): Int =
        (leading.replace("\t", "  ").length / 2).coerceAtMost(Paragraph.MAX_INDENT)

    // --- inline emphasis ---

    private fun inline(text: String, base: CharStyle): MutableList<Run> {
        val out = mutableListOf<Run>()
        inlineInto(text, base, out)
        return out
    }

    private fun inlineInto(text: String, base: CharStyle, out: MutableList<Run>) {
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) {
                appendRun(out, literal.toString(), base)
                literal.clear()
            }
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> {
                    val close = text.indexOf('`', i + 1)
                    if (close > i + 1) {
                        flush()
                        appendRun(out, text.substring(i + 1, close), base.copy(code = true))
                        i = close + 1
                    } else {
                        literal.append(c)
                        i++
                    }
                }
                text.startsWith("**", i) -> i = emphasis(text, i, "**", base.copy(bold = true), base, out, literal) { flush() }
                text.startsWith("__", i) -> i = emphasis(text, i, "__", base.copy(bold = true), base, out, literal) { flush() }
                text.startsWith("~~", i) -> i = emphasis(text, i, "~~", base.copy(strike = true), base, out, literal) { flush() }
                c == '*' -> i = emphasis(text, i, "*", base.copy(italic = true), base, out, literal) { flush() }
                // Intraword underscores (snake_case) are not emphasis.
                c == '_' && (i == 0 || !text[i - 1].isLetterOrDigit()) ->
                    i = emphasis(text, i, "_", base.copy(italic = true), base, out, literal) { flush() }
                c == '!' && IMAGE.containsMatchIn(text.substring(i)) -> {
                    val m = IMAGE.find(text.substring(i))!!
                    flush()
                    inlineInto(m.groupValues[1], base, out)
                    i += m.value.length
                }
                c == '[' && LINK.containsMatchIn(text.substring(i)) -> {
                    val m = LINK.find(text.substring(i))!!
                    flush()
                    inlineInto(m.groupValues[1], base.copy(underline = true, color = LINK_COLOR), out)
                    i += m.value.length
                }
                else -> {
                    literal.append(c)
                    i++
                }
            }
        }
        flush()
    }

    /** Consume a [marker]-delimited span (recursing with [styled]); unmatched emits literally. */
    private inline fun emphasis(
        text: String,
        at: Int,
        marker: String,
        styled: CharStyle,
        base: CharStyle,
        out: MutableList<Run>,
        literal: StringBuilder,
        flush: () -> Unit,
    ): Int {
        val close = text.indexOf(marker, at + marker.length)
        if (close <= at + marker.length - 1 || close == at + marker.length) {
            literal.append(marker)
            return at + marker.length
        }
        flush()
        inlineInto(text.substring(at + marker.length, close), styled, out)
        return close + marker.length
    }

    private fun appendRun(out: MutableList<Run>, text: String, style: CharStyle) {
        if (text.isEmpty()) return
        val last = out.lastOrNull()
        if (last != null && last.style == style) last.text += text else out.add(Run(text, style))
    }
}
