package com.xnotes.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TableEditorTest {

    private fun para(text: String) = Paragraph(if (text.isEmpty()) mutableListOf() else mutableListOf(Run(text)))

    /** "before", a rows x cols table whose cells read "r,c", "after". */
    private fun flowWithTable(rows: Int = 2, cols: Int = 3): Pair<TextFlow, FlowTable> {
        val table = FlowTable(FlowTable.even(cols), List(rows) { 0.0 })
        val flow = TextFlow()
        flow.paragraphs.add(para("before"))
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                flow.paragraphs.add(para("$r,$c").apply { this.table = table; cellStart = true })
            }
        }
        flow.paragraphs.add(para("after"))
        return flow to table
    }

    private fun cellTexts(flow: TextFlow, table: FlowTable): List<List<String>> {
        val b = CellIndex(flow.paragraphs).blockOf(table)!!
        return List(b.rows) { r ->
            List(b.cols) { c ->
                (b.cellFirstPara(r, c)..b.cellLastPara(r, c)).joinToString("|") { flow.paragraphs[it].plainText() }
            }
        }
    }

    @Test
    fun indexDerivesCellsFromTheFlatList() {
        val (flow, table) = flowWithTable()
        // A second paragraph in cell (0,1).
        flow.paragraphs.add(3, para("more").apply { this.table = table })
        val idx = CellIndex(flow.paragraphs)
        val b = idx.tables.single()
        assertEquals(1, b.first)
        assertEquals(7, b.last)
        assertEquals(2, b.rows)
        assertEquals(3, b.cols)
        assertEquals(2, b.cellFirstPara(0, 1))
        assertEquals(3, b.cellLastPara(0, 1))
        assertEquals(1, idx.colOf(3))
        assertEquals(1, idx.rowOf(6))
        assertFalse(idx.inTable(0))
        assertTrue(idx.sameContainer(2, 3))
        assertFalse(idx.sameContainer(3, 4))
    }

    @Test
    fun insertSplitsTheParagraphAroundTheTable() {
        val flow = TextFlow().apply { paragraphs.add(para("helloworld")) }
        val (cmd, caret) = TableEditor(flow).insertTable(FlowPos(0, 5), 2, 2, TableStyle())!!
        assertEquals(listOf("hello", "", "", "", "", "world"), flow.paragraphs.map { it.plainText() })
        assertEquals(FlowPos(1, 0), caret)
        val b = CellIndex(flow.paragraphs).tables.single()
        assertEquals(1, b.first)
        assertEquals(4, b.last)
        cmd.undo()
        assertEquals("helloworld", flow.plainText())
    }

    @Test
    fun insertOnAnEmptyLineReplacesItAndKeepsBodyOnBothSides() {
        val flow = TextFlow().apply { paragraphs.add(para("")) }
        TableEditor(flow).insertTable(FlowPos(0, 0), 1, 2, TableStyle())!!
        val ps = flow.paragraphs
        assertNull(ps.first().table)
        assertNull(ps.last().table)
        assertEquals(4, ps.size)
    }

    @Test
    fun insertIsRefusedInsideATable() {
        val (flow, _) = flowWithTable()
        assertNull(TableEditor(flow).insertTable(FlowPos(2, 0), 2, 2, TableStyle()))
    }

    @Test
    fun rowAndColumnOpsRebuildTheGridAndUndo() {
        val (flow, table) = flowWithTable()
        val ed = TableEditor(flow)
        val (ins, _) = ed.insertRow(table, 1)!!
        assertEquals(listOf(listOf("0,0", "0,1", "0,2"), listOf("", "", ""), listOf("1,0", "1,1", "1,2")), cellTexts(flow, table))
        assertEquals(3, table.minHeights.size)
        val (col, _) = ed.insertCol(table, 3)!!
        assertEquals(4, table.cols)
        assertEquals(1.0, table.widths.sum(), 1e-9)
        assertEquals(listOf("0,0", "0,1", "0,2", ""), cellTexts(flow, table)[0])
        val move = ed.moveCol(table, 0, 2)!!
        assertEquals(listOf("0,1", "0,2", "0,0", ""), cellTexts(flow, table)[0])
        val del = ed.deleteRow(table, 0)!!.first
        assertEquals(listOf("", "", "", ""), cellTexts(flow, table)[0])
        del.undo()
        move.undo()
        col.undo()
        ins.undo()
        assertEquals(listOf(listOf("0,0", "0,1", "0,2"), listOf("1,0", "1,1", "1,2")), cellTexts(flow, table))
        assertEquals(3, table.cols)
        assertEquals(2, table.minHeights.size)
    }

    @Test
    fun deletingTheLastColumnDeletesTheTable() {
        val (flow, table) = flowWithTable(rows = 2, cols = 1)
        val (cmd, caret) = TableEditor(flow).deleteCol(table, 0)!!
        assertEquals(listOf("before", "after"), flow.paragraphs.map { it.plainText() })
        assertEquals(FlowPos(1, 0), caret)
        cmd.undo()
        assertEquals(4, flow.paragraphs.size)
    }

    @Test
    fun aCellRectangleSelectionClearsOnlyItsCells() {
        val (flow, table) = flowWithTable(rows = 2, cols = 3)
        // From cell (0,2) to cell (1,1): the rectangle is rows 0..1, cols 1..2.
        val range = FlowRange(FlowPos(3, 1), FlowPos(5, 2))
        val shape = CellIndex(flow.paragraphs).shapeOf(range)
        assertTrue(shape is SelShape.Cells)
        val (cmd, caret) = FlowEditor(flow).replaceRange(range, "X")
        assertEquals(listOf(listOf("0,0", "X", ""), listOf("1,0", "", "")), cellTexts(flow, table))
        assertEquals(FlowPos(2, 1), caret)
        cmd!!.undo()
        assertEquals(listOf(listOf("0,0", "0,1", "0,2"), listOf("1,0", "1,1", "1,2")), cellTexts(flow, table))
    }

    @Test
    fun aMixedSelectionTakesWholeRowsAndKeepsTheRest() {
        val (flow, table) = flowWithTable(rows = 3, cols = 2)
        // From "be|fore" into cell (1,0): rows 0..1 go, row 2 stays.
        val (cmd, caret) = FlowEditor(flow).replaceRange(FlowRange(FlowPos(0, 2), FlowPos(3, 1)), "")
        assertEquals(listOf("be", "2,0", "2,1", "after"), flow.paragraphs.map { it.plainText() })
        assertEquals(FlowPos(0, 2), caret)
        assertEquals(listOf(0.0), table.minHeights)
        cmd!!.undo()
        assertEquals(8, flow.paragraphs.size)
        assertEquals(3, table.minHeights.size)
    }

    @Test
    fun aSelectionSpanningAWholeTableMergesItsBodyEnds() {
        val (flow, _) = flowWithTable()
        FlowEditor(flow).replaceRange(FlowRange(FlowPos(0, 3), FlowPos(7, 2)), "")
        assertEquals(listOf("befter"), flow.paragraphs.map { it.plainText() })
    }

    @Test
    fun enterInsideACellAddsALineToThatCell() {
        val (flow, table) = flowWithTable(rows = 1, cols = 2)
        FlowEditor(flow).insertText(FlowPos(1, 3), "\n")
        val second = flow.paragraphs[2]
        assertSame(table, second.table)
        assertFalse(second.cellStart)
        assertEquals(listOf(listOf("0,0|", "0,1")), cellTexts(flow, table))
    }

    @Test
    fun cellParagraphsRefuseListsAndCode() {
        val (flow, _) = flowWithTable(rows = 1, cols = 1)
        FlowEditor(flow).setParaStyle(FlowRange.caret(FlowPos(1, 0))) {
            it.list = ListKind.BULLET
            it.codeLang = ""
            it.align = ParaAlign.CENTER
        }
        val cell = flow.paragraphs[1]
        assertEquals(ListKind.NONE, cell.list)
        assertNull(cell.codeLang)
        assertEquals(ParaAlign.CENTER, cell.align)
    }

    @Test
    fun charStyleOverACellRectangleSkipsCellsOutsideIt() {
        val (flow, _) = flowWithTable(rows = 2, cols = 3)
        FlowEditor(flow).setCharStyle(FlowRange(FlowPos(3, 0), FlowPos(5, 1))) { it.copy(bold = true) }
        val bold = flow.paragraphs.map { p -> p.runs.any { it.style.bold } }
        // Cells (0,1),(0,2),(1,1),(1,2) are bold; (0,0),(1,0) are not.
        assertEquals(listOf(false, false, true, true, false, true, true, false), bold)
    }

    @Test
    fun clipboardTextIsTabSeparatedPerRow() {
        val (flow, _) = flowWithTable(rows = 2, cols = 3)
        val idx = CellIndex(flow.paragraphs)
        assertEquals("0,1\t0,2\n1,1\t1,2", idx.textOf(flow.paragraphs, FlowRange(FlowPos(3, 0), FlowPos(5, 1))))
        assertEquals("ore\n0,0\t0,1\t0,2\n1,0\t1,1\t1,2\naf", idx.textOf(flow.paragraphs, FlowRange(FlowPos(0, 3), FlowPos(7, 2))))
    }

    @Test
    fun normalizeRepairsRowsSeparatorsAndSharedTables() {
        val table = FlowTable(listOf(0.0, 3.0))
        val flow = TextFlow()
        repeat(3) { i -> flow.paragraphs.add(para("x").apply { this.table = table; cellStart = i > 0; list = ListKind.BULLET }) }
        val other = FlowTable(listOf(1.0))
        repeat(2) { flow.paragraphs.add(para("y").apply { this.table = other; cellStart = true }) }
        assertTrue(normalizeTables(flow))
        val idx = CellIndex(flow.paragraphs)
        assertEquals(2, idx.tables.size)
        val first = idx.tables[0]
        assertEquals(2, first.rows)
        assertEquals(2, first.table.minHeights.size)
        assertEquals(1.0, first.table.widths.sum(), 1e-9)
        assertTrue(first.table.widths[0] > 0.0)
        assertNull(flow.paragraphs.first().table)
        assertNull(flow.paragraphs.last().table)
        assertNull(flow.paragraphs[first.last + 1].table)
        assertEquals(ListKind.NONE, flow.paragraphs[first.first].list)
        assertFalse(normalizeTables(flow))
    }

    @Test
    fun deepCopyGivesTheCopyItsOwnTables() {
        val (flow, table) = flowWithTable()
        val copy = flow.deepCopy()
        val copied = copy.paragraphs[1].table
        assertNotNull(copied)
        assertTrue(copied !== table)
        assertSame(copied, copy.paragraphs[6].table)
        assertEquals(table.widths, copied!!.widths)
    }
}
