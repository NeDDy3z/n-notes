package com.xnotes.ui

import com.xnotes.R
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ConnectorEnd
import com.xnotes.core.model.ConnectorItem
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.MarkupType
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Routing
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TableItem
import com.xnotes.core.model.TapeItem
import com.xnotes.core.model.TapeMode
import com.xnotes.core.model.TapeShape
import com.xnotes.core.model.TextItem
import com.xnotes.core.model.TextMarkup
import com.xnotes.core.pdf.TextQuad
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

/** What the side panel's Items tab lists, and in what order. */
class ItemsTabTest {

    private val ink = Rgba(0, 0, 0, 255)
    private val measurer = FakeTextMeasurer()

    private fun page(vararg items: CanvasItem): Page = Page(400.0, 400.0, items.toMutableList())

    private fun stroke(tool: Tool): Stroke =
        Stroke(tool, ToolDefaults.configFor(tool), listOf(Sample(0.0, 0.0, 1.0), Sample(10.0, 10.0, 1.0)))

    private fun text(value: String, math: Boolean = false): TextItem =
        TextItem(Pt(10.0, 10.0), width = 100.0, text = value, measurer = measurer, math = math)

    private fun shape(): ShapeItem =
        ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(20.0, 20.0), ink)

    private fun markup(type: MarkupType, value: String): TextMarkup =
        TextMarkup(
            TextMarkup.newId(), type, Rgba(255, 230, 0), 0.5,
            listOf(TextQuad(0f, 0f, 20f, 10f, 0)), value, null, 1L, 1L,
        )

    @Test
    fun pagesAndTheirItemsAreListedInOrderWithMarkupsAfterEachPagesItems() {
        val first = stroke(Tool.PEN)
        val second = shape()
        val mark = markup(MarkupType.HIGHLIGHT, "marked")
        val p0 = page(first, second).apply { markups = listOf(mark) }
        val third = text("on page two")
        val p1 = page(third)

        val rows = collectItemRows(listOf(p0, p1))

        assertEquals(
            listOf(R.string.item_stroke, R.string.item_shape, R.string.item_markup_highlight, R.string.item_text_box),
            rows.map { it.label },
        )
        assertEquals(listOf(0, 0, 0, 1), rows.map { it.page })
        assertSame(first, rows[0].item)
        assertSame(second, rows[1].item)
        assertSame(mark, rows[2].markup)
        assertNull(rows[2].item)
        assertSame(third, rows[3].item)
    }

    @Test
    fun everyKindHasItsOwnLabel() {
        val rows = collectItemRows(
            listOf(
                page(
                    stroke(Tool.PEN),
                    stroke(Tool.HIGHLIGHTER),
                    text("box"),
                    text("x^2", math = true),
                    TapeItem(TapeShape(TapeMode.STRAIGHT, listOf(Pt(0.0, 0.0), Pt(10.0, 0.0)), 8.0), ink),
                    ImageItem(ImageData(File("image.png"), 1, 1), Rect(0.0, 0.0, 10.0, 10.0)),
                    shape(),
                    TableItem.create(Rect(0.0, 0.0, 40.0, 30.0), 2, 2, ink),
                    ConnectorItem(Routing.STRAIGHT, ConnectorEnd(Pt(0.0, 0.0)), ConnectorEnd(Pt(10.0, 10.0)), ink),
                ).apply {
                    markups = listOf(
                        markup(MarkupType.HIGHLIGHT, "h"),
                        markup(MarkupType.UNDERLINE, "u"),
                        markup(MarkupType.STRIKEOUT, "s"),
                        markup(MarkupType.SQUIGGLY, "q"),
                    )
                },
            ),
        )

        assertEquals(
            listOf(
                R.string.item_stroke,
                R.string.item_highlight,
                R.string.item_text_box,
                R.string.item_formula,
                R.string.item_tape,
                R.string.item_image,
                R.string.item_shape,
                R.string.item_table,
                R.string.item_connector,
                R.string.item_markup_highlight,
                R.string.item_markup_underline,
                R.string.item_markup_strikeout,
                R.string.item_markup_squiggly,
            ),
            rows.map { it.label },
        )
    }

    @Test
    fun detailsCarryTheItemsOwnWordsAndGrid() {
        val rows = collectItemRows(
            listOf(
                page(
                    text("First line\nsecond line"),
                    TableItem.create(Rect(0.0, 0.0, 40.0, 30.0), 3, 4, ink),
                ).apply { markups = listOf(markup(MarkupType.HIGHLIGHT, "marked words")) },
            ),
        )

        assertEquals("First line", rows[0].detail)
        assertEquals("3 × 4", rows[1].detail)
        
        assertEquals("marked words", rows[2].detail)
    }

    @Test
    fun anEmptyTextHasNoDetail() {
        assertEquals(R.string.item_text_box, collectItemRows(listOf(page(text("")))).single().label)
        assertNull(collectItemRows(listOf(page(text("")))).single().detail)
    }
}
