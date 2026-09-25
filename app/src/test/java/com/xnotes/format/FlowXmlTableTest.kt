package com.xnotes.format

import com.xnotes.core.model.Rgba
import com.xnotes.core.text.CellIndex
import com.xnotes.core.text.CharStyle
import com.xnotes.core.text.FlowTable
import com.xnotes.core.text.ParaAlign
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.Run
import com.xnotes.core.text.TableBorders
import com.xnotes.core.text.TableStyle
import com.xnotes.core.text.TextFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowXmlTableTest {

    private fun para(text: String, style: CharStyle = CharStyle.DEFAULT) =
        Paragraph(if (text.isEmpty()) mutableListOf() else mutableListOf(Run(text, style)))

    private fun roundTrip(flow: TextFlow): TextFlow =
        TextFlow().also { FlowXml.readInto(it, FlowXml.write(flow)) }

    @Test
    fun tablesRoundTripWithTheirLookAndGeometry() {
        val style = TableStyle(
            paddingPt = 6.0,
            lineColor = Rgba(10, 20, 30),
            lineWidthPt = 1.5,
            borders = TableBorders.HORIZONTAL,
            headerRow = true,
            banded = true,
            tint = Rgba(200, 100, 50),
        )
        val table = FlowTable(listOf(0.25, 0.75), listOf(0.0, 30.0), style)
        val flow = TextFlow().apply {
            paragraphs.add(para("intro"))
            paragraphs.add(para("Name", CharStyle(italic = true)).apply { this.table = table; cellStart = true })
            paragraphs.add(para("Notes").apply { this.table = table; cellStart = true; align = ParaAlign.CENTER })
            paragraphs.add(para("Ada").apply { this.table = table; cellStart = true })
            paragraphs.add(para("line one").apply { this.table = table; cellStart = true })
            paragraphs.add(para("line two").apply { this.table = table })
            paragraphs.add(para("outro"))
        }
        val back = roundTrip(flow)
        assertEquals(flow.paragraphs.map { it.plainText() }, back.paragraphs.map { it.plainText() })
        val b = CellIndex(back.paragraphs).tables.single()
        assertEquals(2, b.rows)
        assertEquals(2, b.cols)
        assertEquals(4, b.cellFirstPara(1, 1))
        assertEquals(5, b.cellLastPara(1, 1))
        assertEquals(style, b.table.style)
        assertEquals(0.25, b.table.widths[0], 1e-4)
        assertEquals(listOf(0.0, 30.0), b.table.minHeights)
        assertTrue(back.paragraphs[1].runs.single().style.italic)
        assertEquals(ParaAlign.CENTER, back.paragraphs[2].align)
        assertNull(back.paragraphs.last().table)
    }

    @Test
    fun aNarrowedTableRoundTripsItsOwnWidth() {
        val table = FlowTable(listOf(0.5, 0.5), listOf(0.0), TableStyle(), width = 0.625)
        val flow = TextFlow().apply {
            paragraphs.add(para("intro"))
            paragraphs.add(para("a").apply { this.table = table; cellStart = true })
            paragraphs.add(para("b").apply { this.table = table; cellStart = true })
            paragraphs.add(para("outro"))
        }
        assertTrue(String(FlowXml.write(flow)).contains("style:rel-width=\"62.5%\""))
        assertEquals(0.625, CellIndex(roundTrip(flow).paragraphs).tables.single().table.width, 1e-9)
    }

    @Test
    fun aFullWidthTableWritesNoTableStyle() {
        val table = FlowTable(listOf(0.5, 0.5), listOf(0.0), TableStyle())
        val flow = TextFlow().apply {
            paragraphs.add(para("intro"))
            paragraphs.add(para("a").apply { this.table = table; cellStart = true })
            paragraphs.add(para("b").apply { this.table = table; cellStart = true })
            paragraphs.add(para("outro"))
        }
        assertFalse(String(FlowXml.write(flow)).contains("rel-width"))
        assertEquals(FlowTable.FULL_WIDTH, CellIndex(roundTrip(flow).paragraphs).tables.single().table.width, 1e-9)
    }

    @Test
    fun theTableNamespaceOnlyAppearsWithTables() {
        val plain = TextFlow().apply { paragraphs.add(para("x")) }
        assertFalse(String(FlowXml.write(plain)).contains("xmlns:table"))
    }

    @Test
    fun foreignOdfTablesAreReadForgivingly() {
        val odf = """
            <?xml version="1.0"?>
            <office:document-content xmlns:office="urn:o" xmlns:text="urn:t" xmlns:table="urn:tb">
              <office:body><office:text>
                <table:table>
                  <table:table-column table:number-columns-repeated="3"/>
                  <table:table-header-rows>
                    <table:table-row><table:table-cell><text:p>A</text:p></table:table-cell>
                      <table:table-cell table:number-columns-repeated="2"><text:p>B</text:p></table:table-cell>
                    </table:table-row>
                  </table:table-header-rows>
                  <table:table-row><table:table-cell><text:p>C</text:p></table:table-cell></table:table-row>
                </table:table>
              </office:text></office:body>
            </office:document-content>
        """.trimIndent()
        val flow = TextFlow()
        FlowXml.readInto(flow, odf.toByteArray())
        val b = CellIndex(flow.paragraphs).tables.single()
        assertEquals(3, b.cols)
        assertEquals(2, b.rows)
        assertTrue(b.table.style.headerRow)
        assertEquals(listOf("", "A", "B", "B", "C", "", "", ""), flow.paragraphs.map { it.plainText() })
    }
}
