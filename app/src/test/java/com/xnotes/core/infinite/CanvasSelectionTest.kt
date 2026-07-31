package com.xnotes.core.infinite

import com.xnotes.canvas.HandleId
import com.xnotes.canvas.SelectionMath
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CanvasSelectionTest {

    private fun box(x: Double, y: Double, w: Double = 40.0, h: Double = 30.0): ImageItem =
        ImageItem(ImageData(File("none"), 10, 10), Rect(x, y, w, h))

    private fun line(x: Double, y: Double): Stroke = Stroke(
        Tool.PEN,
        ToolDefaults.configFor(Tool.PEN),
        mutableListOf(Sample(x, y, 1.0), Sample(x + 40.0, y, 1.0)),
    )

    private fun docOf(vararg items: CanvasItem) = InfiniteDocument().apply { addAll(items.toList()) }

    // --- membership ---

    @Test fun aBandTakesWhateverItOverlaps() {
        val inside = box(10.0, 10.0)
        val outside = box(500.0, 500.0)
        val hits = SelectionMath.bandMembers(listOf(inside, outside), Rect(0.0, 0.0, 100.0, 100.0))
        assertEquals(listOf<CanvasItem>(inside), hits)
    }

    @Test fun aBandTakesAnItemItOnlyClips() {
        val straddling = box(90.0, 10.0)
        val hits = SelectionMath.bandMembers(listOf(straddling), Rect(0.0, 0.0, 100.0, 100.0))
        assertEquals(1, hits.size)
    }

    @Test fun aLassoTakesWhateverItEncloses() {
        val inside = box(40.0, 40.0, 10.0, 10.0)
        val outside = box(400.0, 400.0, 10.0, 10.0)
        val loop = listOf(Pt(0.0, 0.0), Pt(200.0, 0.0), Pt(200.0, 200.0), Pt(0.0, 200.0))
        val hits = SelectionMath.lassoMembers(listOf(inside, outside), loop)
        assertEquals(listOf<CanvasItem>(inside), hits)
    }

    @Test fun aDegenerateLassoSelectsNothing() {
        assertTrue(SelectionMath.lassoMembers(listOf(box(0.0, 0.0)), listOf(Pt(0.0, 0.0), Pt(1.0, 1.0))).isEmpty())
    }

    // --- the box ---

    @Test fun theBoxWrapsEverythingSelected() {
        val doc = docOf()
        val sel = CanvasSelection(doc)
        sel.select(listOf(box(0.0, 0.0, 10.0, 10.0), box(90.0, 40.0, 10.0, 10.0)))
        val b = sel.box!!
        assertEquals(50.0, b.center.x, 1e-9)
        assertEquals(25.0, b.center.y, 1e-9)
        assertEquals(50.0, b.halfW, 1e-9)
        assertEquals(25.0, b.halfH, 1e-9)
        assertEquals(0.0, b.angle, 1e-12)
    }

    @Test fun anEmptySelectionHasNoBox() {
        val sel = CanvasSelection(docOf())
        assertTrue(sel.isEmpty)
        assertNull(sel.box)
        assertTrue(sel.handles().isEmpty())
        assertNull(sel.rotateGrip(30.0))
    }

    @Test fun aPressInsideTheBoxGrabsIt() {
        val sel = CanvasSelection(docOf())
        sel.select(listOf(box(0.0, 0.0, 100.0, 100.0)))
        assertTrue(sel.contains(Pt(50.0, 50.0)))
        assertTrue(!sel.contains(Pt(500.0, 50.0)))
    }

    @Test fun everyCornerAndEdgeHasAHandle() {
        val sel = CanvasSelection(docOf())
        sel.select(listOf(box(0.0, 0.0, 100.0, 60.0)))
        assertEquals(8, sel.handles().size)
        assertEquals(HandleId.TL, sel.hitHandle(Pt(0.0, 0.0), 5.0))
        assertEquals(HandleId.BR, sel.hitHandle(Pt(100.0, 60.0), 5.0))
        assertNull(sel.hitHandle(Pt(50.0, 30.0), 5.0))
    }

    @Test fun theRotateGripSitsAboveTheBox() {
        val sel = CanvasSelection(docOf())
        sel.select(listOf(box(0.0, 0.0, 100.0, 60.0)))
        val grip = sel.rotateGrip(30.0)!!
        assertEquals(50.0, grip.x, 1e-9)
        assertEquals(-30.0, grip.y, 1e-9)
    }

    // --- moving ---

    @Test fun aMoveShiftsEveryItemTogether() {
        val a = box(0.0, 0.0)
        val b = box(100.0, 0.0)
        val doc = docOf(a, b)
        val sel = CanvasSelection(doc)
        sel.select(listOf(a, b))
        sel.beginTransform()
        sel.moveLive(25.0, 10.0)
        assertEquals(25.0, a.rect.x, 1e-9)
        assertEquals(125.0, b.rect.x, 1e-9)
        assertEquals(10.0, a.rect.y, 1e-9)
    }

    @Test fun aMoveDragDoesNotCompound() {
        val a = box(0.0, 0.0)
        val sel = CanvasSelection(docOf(a))
        sel.select(listOf(a))
        sel.beginTransform()
        sel.moveLive(10.0, 0.0)
        sel.moveLive(20.0, 0.0)
        sel.moveLive(30.0, 0.0)
        assertEquals("each frame is measured from the gesture start", 30.0, a.rect.x, 1e-9)
    }

    @Test fun theBoxFollowsAMove() {
        val a = box(0.0, 0.0, 100.0, 100.0)
        val sel = CanvasSelection(docOf(a))
        sel.select(listOf(a))
        sel.beginTransform()
        sel.moveLive(40.0, 0.0)
        assertEquals(90.0, sel.box!!.center.x, 1e-9)
    }

    @Test fun aMoveIsUndoable() {
        val a = box(0.0, 0.0)
        val doc = docOf(a)
        val sel = CanvasSelection(doc)
        sel.select(listOf(a))
        sel.beginTransform()
        sel.moveLive(25.0, 10.0)
        val cmd = sel.buildCommand(movedOnly = true, dx = 25.0, dy = 10.0)!!
        cmd.undo()
        assertEquals(0.0, a.rect.x, 1e-9)
        cmd.redo()
        assertEquals(25.0, a.rect.x, 1e-9)
    }

    @Test fun aMoveOfNothingRecordsNothing() {
        val a = box(0.0, 0.0)
        val sel = CanvasSelection(docOf(a))
        sel.select(listOf(a))
        sel.beginTransform()
        assertNull(sel.buildCommand(movedOnly = true, dx = 0.0, dy = 0.0))
    }

    @Test fun aMoveRefilesTheSpatialIndex() {
        val a = box(0.0, 0.0)
        val doc = docOf(a)
        val sel = CanvasSelection(doc)
        sel.select(listOf(a))
        sel.beginTransform()
        sel.moveLive(9000.0, 9000.0)
        assertTrue(doc.itemsIn(Rect(-10.0, -10.0, 100.0, 100.0)).isEmpty())
        assertSame(a, doc.itemsIn(Rect(8990.0, 8990.0, 100.0, 100.0)).single())
    }

    // --- resizing ---

    @Test fun aCornerDragScalesTheSelection() {
        val a = box(0.0, 0.0, 100.0, 100.0)
        val sel = CanvasSelection(docOf(a))
        sel.select(listOf(a))
        sel.beginTransform()
        sel.resizeLive(HandleId.BR, Pt(200.0, 200.0))
        assertTrue("the box must have grown", sel.box!!.halfW > 50.0)
        assertTrue(a.rect.w > 100.0)
    }

    @Test fun aResizeAnchorsAtTheOppositeCorner() {
        val a = box(0.0, 0.0, 100.0, 100.0)
        val sel = CanvasSelection(docOf(a))
        sel.select(listOf(a))
        sel.beginTransform()
        sel.resizeLive(HandleId.BR, Pt(200.0, 200.0))
        assertEquals("the far corner stays put", 0.0, a.rect.x, 1e-6)
        assertEquals(0.0, a.rect.y, 1e-6)
    }

    @Test fun aResizeDragDoesNotCompound() {
        val a = box(0.0, 0.0, 100.0, 100.0)
        val sel = CanvasSelection(docOf(a))
        sel.select(listOf(a))
        sel.beginTransform()
        sel.resizeLive(HandleId.BR, Pt(200.0, 200.0))
        val once = a.rect.w
        sel.resizeLive(HandleId.BR, Pt(200.0, 200.0))
        assertEquals("the same pointer must give the same size", once, a.rect.w, 1e-9)
    }

    @Test fun aResizeIsUndoable() {
        val a = box(0.0, 0.0, 100.0, 100.0)
        val sel = CanvasSelection(docOf(a))
        sel.select(listOf(a))
        sel.beginTransform()
        sel.resizeLive(HandleId.BR, Pt(200.0, 200.0))
        val cmd = sel.buildCommand(movedOnly = false)!!
        cmd.undo()
        assertEquals(100.0, a.rect.w, 1e-6)
        cmd.redo()
        assertTrue(a.rect.w > 100.0)
    }

    @Test fun scalingAStrokeScalesItsWidthToo() {
        val s = line(0.0, 0.0)
        val before = s.config.baseWidth
        val sel = CanvasSelection(docOf(s))
        sel.select(listOf(s))
        sel.beginTransform()
        sel.resizeLive(HandleId.BR, Pt(200.0, 200.0))
        assertTrue("a resized stroke should look zoomed, not just longer", s.config.baseWidth > before)
    }

    // --- rotating ---

    @Test fun aRotationTurnsTheBox() {
        val a = box(0.0, 0.0, 100.0, 60.0)
        val sel = CanvasSelection(docOf(a))
        sel.select(listOf(a))
        sel.beginTransform()
        sel.rotateLive(Pt(200.0, 30.0)) // pointer to the right of centre
        assertTrue("the box must have turned", kotlin.math.abs(sel.box!!.angle) > 1e-6)
    }

    @Test fun aRotationLeavesTheCentreWhereItWas() {
        val s = line(0.0, 0.0)
        val sel = CanvasSelection(docOf(s))
        sel.select(listOf(s))
        val centre = sel.box!!.center
        sel.beginTransform()
        sel.rotateLive(Pt(centre.x, centre.y - 100.0))
        assertEquals(centre.x, sel.box!!.center.x, 1e-6)
        assertEquals(centre.y, sel.box!!.center.y, 1e-6)
    }

    @Test fun aRotationIsUndoable() {
        val s = line(10.0, 10.0)
        val sel = CanvasSelection(docOf(s))
        sel.select(listOf(s))
        val before = s.samples.map { it.x to it.y }
        sel.beginTransform()
        sel.rotateLive(Pt(500.0, 500.0))
        val cmd = sel.buildCommand(movedOnly = false)!!
        cmd.undo()
        assertEquals(before, s.samples.map { it.x to it.y })
        cmd.redo()
        assertTrue(before != s.samples.map { it.x to it.y })
    }

    @Test fun aRotationDragDoesNotCompound() {
        val s = line(0.0, 0.0)
        val sel = CanvasSelection(docOf(s))
        sel.select(listOf(s))
        sel.beginTransform()
        sel.rotateLive(Pt(0.0, -100.0))
        val once = s.samples.map { it.x to it.y }
        sel.rotateLive(Pt(0.0, -100.0))
        assertEquals(once, s.samples.map { it.x to it.y })
    }

    @Test fun grabbingTheGripOffCentreDoesNotSnapTheSelection() {
        val a = box(0.0, 0.0, 100.0, 60.0)
        val sel = CanvasSelection(docOf(a))
        sel.select(listOf(a))
        val grip = sel.rotateGrip(34.0)!!
        // A press at the far edge of the grip's touch target, then no movement at all.
        val grab = Pt(grip.x + 22.0, grip.y)
        sel.beginTransform(grab)
        sel.rotateLive(grab)
        assertEquals("a press alone must not turn anything", 0.0, sel.box!!.angle, 1e-9)
    }

    @Test fun aRotationTurnsByWhatTheFingerSwept() {
        val a = box(0.0, 0.0, 100.0, 60.0)
        val sel = CanvasSelection(docOf(a))
        sel.select(listOf(a))
        val centre = sel.box!!.center
        val grab = Pt(centre.x + 22.0, centre.y - 100.0)
        sel.beginTransform(grab)
        // A quarter turn of the grab point about the centre must turn the box a quarter turn.
        val swept = Pt(centre.x + 100.0, centre.y + 22.0)
        sel.rotateLive(swept)
        assertEquals(Math.PI / 2.0, sel.box!!.angle, 1e-9)
    }

    @Test fun aRotatedBoxKeepsItsOwnShape() {
        val s = line(0.0, 0.0)
        val sel = CanvasSelection(docOf(s))
        sel.select(listOf(s))
        val wide = sel.box!!.halfW
        val thin = sel.box!!.halfH
        val centre = sel.box!!.center
        sel.beginTransform(Pt(centre.x, centre.y - 100.0))
        sel.rotateLive(Pt(centre.x + 100.0, centre.y))
        assertEquals("a turn must not resize the box", wide, sel.box!!.halfW, 1e-9)
        assertEquals(thin, sel.box!!.halfH, 1e-9)
    }

    @Test fun refreshingTheBoxBringsItBackUpright() {
        val s = line(0.0, 0.0)
        val sel = CanvasSelection(docOf(s))
        sel.select(listOf(s))
        val centre = sel.box!!.center
        sel.beginTransform(Pt(centre.x, centre.y - 100.0))
        sel.rotateLive(Pt(centre.x + 100.0, centre.y))
        sel.refreshBox()
        assertEquals("item bounds cannot say what angle the ink is at", 0.0, sel.box!!.angle, 1e-9)
    }

    // --- overlay geometry ---

    @Test fun theOverlayScalesItsOutlineWithTheZoom() {
        val sel = CanvasSelection(docOf())
        sel.select(listOf(box(0.0, 0.0, 100.0, 100.0)))
        val zoomedIn = OverlayTessellator.selection(sel.box!!, 8.0, com.xnotes.core.model.Rgba(0, 255, 0, 255), 0.01)
        val zoomedOut = OverlayTessellator.selection(sel.box!!, 0.5, com.xnotes.core.model.Rgba(0, 255, 0, 255), 0.01)
        assertNotNull(zoomedIn.firstOrNull())
        assertNotNull(zoomedOut.firstOrNull())
        // A constant device width means the content-space geometry is wider when zoomed out.
        val inSpan = span(zoomedIn[0].mesh)
        val outSpan = span(zoomedOut[0].mesh)
        assertTrue("the outline must stay one thickness on screen", outSpan > inSpan)
    }

    @Test fun theBandAndLassoProduceGeometry() {
        val accent = com.xnotes.core.model.Rgba(0, 255, 0, 255)
        assertTrue(OverlayTessellator.band(Rect(0.0, 0.0, 50.0, 40.0), 1.0, accent, 0.01).isNotEmpty())
        val loop = listOf(Pt(0.0, 0.0), Pt(50.0, 0.0), Pt(25.0, 40.0))
        assertTrue(OverlayTessellator.lasso(loop, 1.0, accent, 0.01).isNotEmpty())
        assertTrue(OverlayTessellator.lasso(listOf(Pt(0.0, 0.0)), 1.0, accent, 0.01).isEmpty())
    }

    /** How far the mesh reaches beyond the box it outlines, which is its content-space thickness. */
    private fun span(mesh: MeshData): Double {
        var maxX = Double.NEGATIVE_INFINITY
        for (i in 0 until mesh.vertexCount) maxX = maxOf(maxX, mesh.positions[2 * i])
        return maxX - 100.0
    }
}
