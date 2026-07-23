package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontFace
import com.xnotes.core.text.CharStyle
import com.xnotes.core.text.FlowMargins
import com.xnotes.core.text.ListKind
import com.xnotes.core.text.ParaAlign
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.Run
import com.xnotes.core.text.TextFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class FlowXmlTest {

    private fun roundTrip(flow: TextFlow): TextFlow =
        TextFlow().also { FlowXml.readInto(it, FlowXml.write(flow)) }

    @Test
    fun fullRoundTrip() {
        val flow = TextFlow().apply {
            margins = FlowMargins(15.0, 12.5, 15.0, 10.0)
            defaultFace = FontFace.SERIF
            defaultSizePt = 14.0
            defaultColor = Rgba(10, 20, 30, 255)
            monoFace = FontFace("JetBrains Mono")
            paragraphs.add(
                Paragraph(
                    mutableListOf(
                        Run("plain "),
                        Run("bold red", CharStyle(bold = true, color = Rgba(255, 0, 0, 255))),
                        Run(" tail", CharStyle(italic = true, underline = true, strike = true, sizePt = 18.0)),
                        Run(" named", CharStyle(face = FontFace("Playfair Display"))),
                    ),
                    align = ParaAlign.JUSTIFY,
                ),
            )
            paragraphs.add(
                Paragraph(mutableListOf(Run("todo item")), indent = 2, list = ListKind.CHECK, checked = true),
            )
            paragraphs.add(Paragraph(mutableListOf(Run("second")), list = ListKind.CHECK))
            paragraphs.add(Paragraph(mutableListOf(Run("numbered")), list = ListKind.ORDERED))
            paragraphs.add(Paragraph())
            paragraphs.add(
                Paragraph(
                    mutableListOf(Run("    if (x)\treturn", CharStyle(code = true, highlight = Rgba(40, 40, 40, 255)))),
                    codeLang = "kotlin",
                ),
            )
        }
        val back = roundTrip(flow)
        assertEquals(flow.margins, back.margins)
        assertEquals(FontFace.SERIF, back.defaultFace)
        assertEquals(FontFace("JetBrains Mono"), back.monoFace)
        assertEquals(14.0, back.defaultSizePt, 1e-9)
        assertEquals(Rgba(10, 20, 30, 255), back.defaultColor)
        assertEquals(flow.plainText(), back.plainText())
        assertEquals(flow.paragraphs.size, back.paragraphs.size)
        for (i in flow.paragraphs.indices) {
            val a = flow.paragraphs[i]
            val b = back.paragraphs[i]
            assertEquals("para $i align", a.align, b.align)
            assertEquals("para $i indent", a.indent, b.indent)
            assertEquals("para $i list", a.list, b.list)
            assertEquals("para $i checked", a.checked, b.checked)
            assertEquals("para $i lang", a.codeLang, b.codeLang)
            assertEquals("para $i runs", a.runs.map { it.text to it.style }, b.runs.map { it.text to it.style })
        }
    }

    @Test
    fun defaultColorReadsLegacyNearWhiteAsAuto() {
        assertNull(roundTrip(TextFlow()).defaultColor)
        val legacy = TextFlow().apply { defaultColor = TextFlow.DEFAULT_COLOR }
        assertNull(roundTrip(legacy).defaultColor)
    }

    @Test
    fun whitespaceSurvivesOdfEncoding() {
        val flow = TextFlow().apply {
            paragraphs.add(Paragraph(mutableListOf(Run("  lead, three   in, trail  "))))
            paragraphs.add(Paragraph(mutableListOf(Run("\t\ttabs\tinside"))))
        }
        assertEquals(flow.plainText(), roundTrip(flow).plainText())
    }

    @Test
    fun plainContentStaysUnstyledInTheMarkup() {
        val flow = TextFlow().apply { paragraphs.add(Paragraph(mutableListOf(Run("just words")))) }
        val xml = String(FlowXml.write(flow), Charsets.UTF_8)
        assertFalse(xml.contains("<text:span"))
        assertFalse(xml.contains("<style:style"))
        assertTrue(xml.contains("<text:p>just words</text:p>"))
    }

    @Test
    fun malformedAndForeignContentIsForgiven() {
        val untouched = TextFlow()
        FlowXml.readInto(untouched, "not xml at all".toByteArray())
        assertTrue(untouched.isEmpty)

        val exotic = """
            <?xml version="1.0"?>
            <office:document-content xmlns:office="urn:o" xmlns:text="urn:t" xmlns:future="urn:f"
                xnotes:margin-left-mm="7" xmlns:xnotes="urn:xnotes:flow:1.0">
              <future:mystery>outside the body, ignored</future:mystery>
              <office:body><office:text>
                <text:p future:attr="x">hi<future:inline> there</future:inline></text:p>
              </office:text></office:body>
            </office:document-content>
        """.trimIndent()
        val flow = TextFlow()
        FlowXml.readInto(flow, exotic.toByteArray())
        assertEquals(7.0, flow.margins.leftMm, 1e-9)
        assertEquals(1, flow.paragraphs.size)
        assertEquals("hi there", flow.paragraphs[0].plainText())
    }

    @Test
    fun bundleCarriesTheFlowOnlyWhenNonEmpty() {
        val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())

        val bare = Document(pages = mutableListOf(Page(100.0, 100.0)))
        val bareOut = ByteArrayOutputStream()
        codec.write(bare, bareOut)
        assertNull(zipEntryNames(bareOut.toByteArray()).find { it == FlowXml.ENTRY_NAME })

        val doc = Document(pages = mutableListOf(Page(100.0, 100.0)))
        doc.flow.paragraphs.add(Paragraph(mutableListOf(Run("flowing", CharStyle(bold = true)))))
        val out = ByteArrayOutputStream()
        codec.write(doc, out)
        assertTrue(zipEntryNames(out.toByteArray()).contains(FlowXml.ENTRY_NAME))
        val back = codec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals("flowing", back.flow.plainText())
        assertTrue(back.flow.paragraphs[0].runs[0].style.bold)
    }

    @Test
    fun bundleKeepsCustomDefaultsWithNoTypedText() {
        val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
        val doc = Document(pages = mutableListOf(Page(100.0, 100.0)))
        doc.flow.defaultColor = Rgba(200, 40, 40, 255)
        doc.flow.defaultSizePt = 16.0
        val out = ByteArrayOutputStream()
        codec.write(doc, out)
        assertTrue(zipEntryNames(out.toByteArray()).contains(FlowXml.ENTRY_NAME))
        val back = codec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(Rgba(200, 40, 40, 255), back.flow.defaultColor)
        assertEquals(16.0, back.flow.defaultSizePt, 1e-9)
        assertTrue(back.flow.isEmpty)
    }

    private fun zipEntryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                names.add(e.name)
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        return names
    }
}
