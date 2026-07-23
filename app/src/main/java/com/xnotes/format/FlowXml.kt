package com.xnotes.format

import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontFace
import com.xnotes.core.text.CharStyle
import com.xnotes.core.text.FlowMargins
import com.xnotes.core.text.ListKind
import com.xnotes.core.text.ParaAlign
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.Run
import com.xnotes.core.text.TextFlow
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Reads/writes the flow as `flow.xml` inside the `.xnote` bundle, speaking the
 * ODF text vocabulary (text:p / text:span / text:list, fo:* style properties in
 * automatic styles) so the dialect is interchange-friendly; xnotes-specific
 * facts an ODF reader has no slot for (list kind, checkbox state, code language,
 * indent level, flow margins/defaults) ride `xnotes:*` foreign attributes, which
 * conformant readers ignore. Writing is a hand-rolled deterministic serializer;
 * reading is DOM-based and forgiving (unknown elements/attributes are skipped,
 * malformed XML loads as an empty flow). Colours serialize as #rrggbb (the flow
 * only holds opaque colours). Spaces and tabs are ODF-encoded (text:s /
 * text:tab) so code indentation survives whitespace-collapsing readers.
 */
object FlowXml {
    const val ENTRY_NAME = "flow.xml"

    // --- write ---

    fun write(flow: TextFlow): ByteArray = buildString {
        val charStyles = LinkedHashMap<CharStyle, String>()
        val paraStyles = LinkedHashMap<Pair<ParaAlign, Int>, String>()
        for (para in flow.paragraphs) {
            if (para.align != ParaAlign.LEFT || para.indent != 0) {
                paraStyles.getOrPut(para.align to para.indent) { "P${paraStyles.size + 1}" }
            }
            for (run in para.runs) {
                if (run.style != CharStyle.DEFAULT) {
                    charStyles.getOrPut(run.style) { "T${charStyles.size + 1}" }
                }
            }
        }

        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<office:document-content")
        append(" xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\"")
        append(" xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\"")
        append(" xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\"")
        append(" xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\"")
        append(" xmlns:xnotes=\"urn:xnotes:flow:1.0\"")
        append(" office:version=\"1.2\"")
        append(" xnotes:margin-left-mm=\"${flow.margins.leftMm}\"")
        append(" xnotes:margin-top-mm=\"${flow.margins.topMm}\"")
        append(" xnotes:margin-right-mm=\"${flow.margins.rightMm}\"")
        append(" xnotes:margin-bottom-mm=\"${flow.margins.bottomMm}\"")
        append(" xnotes:default-face=\"${escapeAttr(flow.defaultFace.id)}\"")
        append(" xnotes:default-size-pt=\"${flow.defaultSizePt}\"")
        flow.defaultColor?.let { append(" xnotes:default-color=\"${hex(it)}\"") }
        if (flow.monoFace != FontFace.MONO) append(" xnotes:mono-face=\"${escapeAttr(flow.monoFace.id)}\"")
        append(">\n")

        append(" <office:automatic-styles>\n")
        for ((style, name) in charStyles) appendTextStyle(name, style)
        for ((key, name) in paraStyles) appendParaStyle(name, key.first, key.second)
        append(" </office:automatic-styles>\n")

        append(" <office:body>\n  <office:text>\n")
        var i = 0
        val paras = flow.paragraphs
        while (i < paras.size) {
            val kind = paras[i].list
            if (kind == ListKind.NONE) {
                appendParagraph(paras[i], paraStyles, charStyles, "   ")
                i++
            } else {
                append("   <text:list xnotes:list=\"${kind.id}\">\n")
                while (i < paras.size && paras[i].list == kind) {
                    append("    <text:list-item>\n")
                    appendParagraph(paras[i], paraStyles, charStyles, "     ")
                    append("    </text:list-item>\n")
                    i++
                }
                append("   </text:list>\n")
            }
        }
        append("  </office:text>\n </office:body>\n</office:document-content>\n")
    }.toByteArray(Charsets.UTF_8)

    private fun StringBuilder.appendTextStyle(name: String, s: CharStyle) {
        append("  <style:style style:name=\"$name\" style:family=\"text\">\n   <style:text-properties")
        if (s.bold) append(" fo:font-weight=\"bold\"")
        if (s.italic) append(" fo:font-style=\"italic\"")
        if (s.underline) append(" style:text-underline-style=\"solid\"")
        if (s.strike) append(" style:text-line-through-style=\"solid\"")
        if (s.code) append(" xnotes:code=\"true\"")
        s.face?.let { append(" style:font-name=\"${escapeAttr(it.id)}\"") }
        s.color?.let { append(" fo:color=\"${hex(it)}\"") }
        s.highlight?.let { append(" fo:background-color=\"${hex(it)}\"") }
        s.sizePt?.let { append(" fo:font-size=\"${it}pt\"") }
        append("/>\n  </style:style>\n")
    }

    private fun StringBuilder.appendParaStyle(name: String, align: ParaAlign, indent: Int) {
        append("  <style:style style:name=\"$name\" style:family=\"paragraph\">\n   <style:paragraph-properties")
        if (align != ParaAlign.LEFT) append(" fo:text-align=\"${align.id}\"")
        if (indent != 0) append(" fo:margin-left=\"${indent * INDENT_MM_PER_LEVEL}mm\" xnotes:indent=\"$indent\"")
        append("/>\n  </style:style>\n")
    }

    private fun StringBuilder.appendParagraph(
        para: Paragraph,
        paraStyles: Map<Pair<ParaAlign, Int>, String>,
        charStyles: Map<CharStyle, String>,
        pad: String,
    ) {
        append(pad).append("<text:p")
        paraStyles[para.align to para.indent]?.let { append(" text:style-name=\"$it\"") }
        if (para.list != ListKind.NONE) append(" xnotes:list=\"${para.list.id}\"")
        if (para.checked) append(" xnotes:checked=\"true\"")
        para.codeLang?.let { append(" xnotes:code-lang=\"${escapeAttr(it)}\"") }
        append(">")
        for (run in para.runs) {
            val styleName = charStyles[run.style]
            if (styleName == null) {
                appendEncodedText(run.text)
            } else {
                append("<text:span text:style-name=\"$styleName\">")
                appendEncodedText(run.text)
                append("</text:span>")
            }
        }
        append("</text:p>\n")
    }

    /** Escape markup and ODF-encode whitespace (lone interior spaces stay literal). */
    private fun StringBuilder.appendEncodedText(text: String) {
        var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                '\t' -> { append("<text:tab/>"); i++ }
                ' ' -> {
                    var j = i
                    while (j < text.length && text[j] == ' ') j++
                    val n = j - i
                    when {
                        i == 0 || j == text.length -> append("<text:s text:c=\"$n\"/>")
                        n == 1 -> append(' ')
                        else -> append(' ').append("<text:s text:c=\"${n - 1}\"/>")
                    }
                    i = j
                }
                '&' -> { append("&amp;"); i++ }
                '<' -> { append("&lt;"); i++ }
                '>' -> { append("&gt;"); i++ }
                else -> { append(c); i++ }
            }
        }
    }

    private fun escapeAttr(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    private fun hex(c: Rgba): String = "#%02x%02x%02x".format(c.r, c.g, c.b)

    // --- read ---

    /** Populate [flow] from [bytes]; malformed XML leaves it untouched (loads empty). */
    fun readInto(flow: TextFlow, bytes: ByteArray) {
        val root = try {
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(bytes))
                .documentElement
        } catch (_: Exception) {
            return
        }

        flow.paragraphs.clear()
        flow.margins = FlowMargins(
            leftMm = attrDouble(root, "margin-left-mm", FlowMargins.DEFAULT_MM),
            topMm = attrDouble(root, "margin-top-mm", FlowMargins.DEFAULT_MM),
            rightMm = attrDouble(root, "margin-right-mm", FlowMargins.DEFAULT_MM),
            bottomMm = attrDouble(root, "margin-bottom-mm", FlowMargins.DEFAULT_MM),
        )
        attr(root, "default-face")?.let { flow.defaultFace = FontFace.fromId(it) }
        flow.defaultSizePt = attrDouble(root, "default-size-pt", TextFlow.DEFAULT_SIZE_PT)
        // Legacy files always wrote the (then ignored) built-in near-white; read it back as auto.
        flow.defaultColor = parseHex(attr(root, "default-color"))?.takeUnless { it == TextFlow.DEFAULT_COLOR }
        attr(root, "mono-face")?.let { flow.monoFace = FontFace.fromId(it) }

        val charStyles = HashMap<String, CharStyle>()
        val paraStyles = HashMap<String, Pair<ParaAlign, Int>>()
        for (styleEl in descendants(root, "style")) {
            val name = attr(styleEl, "name") ?: continue
            when (attr(styleEl, "family")) {
                "text" -> descendants(styleEl, "text-properties").firstOrNull()
                    ?.let { charStyles[name] = parseCharStyle(it) }
                "paragraph" -> descendants(styleEl, "paragraph-properties").firstOrNull()
                    ?.let { paraStyles[name] = parseParaProps(it) }
            }
        }

        val body = descendants(root, "body").firstOrNull() ?: root
        for (p in descendants(body, "p")) {
            flow.paragraphs.add(parseParagraph(p, charStyles, paraStyles))
        }
        flow.touch()
    }

    private fun parseCharStyle(props: Element): CharStyle = CharStyle(
        bold = attr(props, "font-weight") == "bold",
        italic = attr(props, "font-style") == "italic",
        underline = attr(props, "text-underline-style").let { it != null && it != "none" },
        strike = attr(props, "text-line-through-style").let { it != null && it != "none" },
        code = attr(props, "code") == "true",
        color = parseHex(attr(props, "color")),
        highlight = parseHex(attr(props, "background-color")),
        sizePt = attr(props, "font-size")?.removeSuffix("pt")?.toDoubleOrNull(),
        face = attr(props, "font-name")?.takeIf { it.isNotEmpty() }?.let { FontFace(it) },
    )

    private fun parseParaProps(props: Element): Pair<ParaAlign, Int> {
        val align = when (attr(props, "text-align")) {
            "center" -> ParaAlign.CENTER
            "right", "end" -> ParaAlign.RIGHT
            "justify" -> ParaAlign.JUSTIFY
            else -> ParaAlign.LEFT
        }
        val indent = attr(props, "indent")?.toIntOrNull()
            ?: attr(props, "margin-left")?.removeSuffix("mm")?.toDoubleOrNull()
                ?.let { (it / INDENT_MM_PER_LEVEL).toInt() }
            ?: 0
        return align to indent.coerceIn(0, Paragraph.MAX_INDENT)
    }

    private fun parseParagraph(
        p: Element,
        charStyles: Map<String, CharStyle>,
        paraStyles: Map<String, Pair<ParaAlign, Int>>,
    ): Paragraph {
        val (align, indent) = paraStyles[attr(p, "style-name")] ?: (ParaAlign.LEFT to 0)
        val para = Paragraph(
            align = align,
            indent = indent,
            list = ListKind.fromId(attr(p, "list")),
            checked = attr(p, "checked") == "true",
            codeLang = attr(p, "code-lang"),
        )
        collectRuns(p, CharStyle.DEFAULT, charStyles, para.runs)
        mergeAdjacent(para.runs)
        return para
    }

    private fun collectRuns(
        node: Element,
        style: CharStyle,
        charStyles: Map<String, CharStyle>,
        out: MutableList<Run>,
    ) {
        var child = node.firstChild
        while (child != null) {
            when {
                child.nodeType == Node.TEXT_NODE || child.nodeType == Node.CDATA_SECTION_NODE ->
                    appendText(out, child.nodeValue.orEmpty(), style)
                child is Element -> when (child.localName ?: child.nodeName) {
                    "span" -> collectRuns(
                        child,
                        charStyles[attr(child, "style-name")] ?: style,
                        charStyles,
                        out,
                    )
                    "s" -> appendText(out, " ".repeat(attr(child, "c")?.toIntOrNull() ?: 1), style)
                    "tab" -> appendText(out, "\t", style)
                    else -> collectRuns(child, style, charStyles, out)
                }
            }
            child = child.nextSibling
        }
    }

    private fun appendText(out: MutableList<Run>, text: String, style: CharStyle) {
        if (text.isEmpty()) return
        val last = out.lastOrNull()
        if (last != null && last.style == style) last.text += text else out.add(Run(text, style))
    }

    private fun mergeAdjacent(runs: MutableList<Run>) {
        runs.removeAll { it.text.isEmpty() }
        var i = 0
        while (i < runs.size - 1) {
            if (runs[i].style == runs[i + 1].style) {
                runs[i].text += runs[i + 1].text
                runs.removeAt(i + 1)
            } else {
                i++
            }
        }
    }

    // --- forgiving DOM helpers (match by local name, any namespace/prefix) ---

    private fun descendants(parent: Element, local: String): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(n: Node) {
            var child = n.firstChild
            while (child != null) {
                if (child is Element) {
                    if ((child.localName ?: child.nodeName) == local) out.add(child)
                    walk(child)
                }
                child = child.nextSibling
            }
        }
        walk(parent)
        return out
    }

    private fun attr(el: Element, local: String): String? {
        val attrs = el.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            if ((a.localName ?: a.nodeName) == local) return a.nodeValue
        }
        return null
    }

    private fun attrDouble(el: Element, local: String, fallback: Double): Double =
        attr(el, local)?.toDoubleOrNull() ?: fallback

    private fun parseHex(s: String?): Rgba? {
        if (s == null || !s.startsWith("#") || s.length != 7) return null
        val v = s.substring(1).toIntOrNull(16) ?: return null
        return Rgba((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF, 255)
    }

    /** Indent level to fo:margin-left millimetres, for ODF readers only. */
    private const val INDENT_MM_PER_LEVEL = 10.0
}
