package com.xnotes.core.infinite

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ItemMesherTest {

    private fun stroke(tool: Tool, config: ToolConfig = ToolDefaults.configFor(tool)): Stroke =
        Stroke(tool, config, mutableListOf(Sample(0.0, 0.0, 1.0), Sample(20.0, 5.0, 1.0), Sample(40.0, 0.0, 1.0)))

    // --- pass selection ---

    @Test fun opaqueInkGoesStraightIntoTheBatch() {
        assertEquals(InkPass.OPAQUE, ItemMesher.passFor(stroke(Tool.PEN)))
        assertEquals(InkPass.OPAQUE, ItemMesher.passFor(stroke(Tool.CALLIGRAPHY)))
        assertEquals(InkPass.OPAQUE, ItemMesher.passFor(stroke(Tool.TAPER)))
    }

    @Test fun theHighlighterMultiplies() {
        assertEquals(InkPass.MULTIPLY, ItemMesher.passFor(stroke(Tool.HIGHLIGHTER)))
    }

    @Test fun theHighlighterMultipliesWhateverItsAlpha() {
        val opaqueish = ToolDefaults.configFor(Tool.HIGHLIGHTER).copy(highlighterAlpha = 0.9)
        assertEquals(InkPass.MULTIPLY, ItemMesher.passFor(stroke(Tool.HIGHLIGHTER, opaqueish)))
    }

    @Test fun translucentPenInkIsMaskedRatherThanDrawnTwice() {
        val faded = ToolDefaults.configFor(Tool.PEN).copy(rgba = Rgba(20, 30, 40, 128))
        assertEquals(InkPass.TRANSLUCENT, ItemMesher.passFor(stroke(Tool.PEN, faded)))
    }

    @Test fun fullyOpaquePenInkIsNotMasked() {
        val solid = ToolDefaults.configFor(Tool.PEN).copy(rgba = Rgba(20, 30, 40, 255))
        assertEquals(InkPass.OPAQUE, ItemMesher.passFor(stroke(Tool.PEN, solid)))
    }

    // --- meshing ---

    @Test fun aStrokeMeshesWithItsRenderColourAndPaintBounds() {
        val s = stroke(Tool.PEN)
        val m = ItemMesher.mesh(s)!!
        assertEquals(1, m.parts.size)
        assertEquals(s.renderColor, m.parts[0].color)
        assertEquals(s.paintBounds(), m.bounds)
        assertEquals(InkPass.OPAQUE, m.parts[0].pass)
        assertTrue(!m.parts[0].mesh.isEmpty)
    }

    @Test fun aHighlighterMeshesWithItsScaledAlpha() {
        val s = stroke(Tool.HIGHLIGHTER)
        val m = ItemMesher.mesh(s)!!
        assertTrue("the highlighter must arrive translucent", m.parts[0].color.a < 255)
        assertEquals(InkPass.MULTIPLY, m.parts[0].pass)
    }

    @Test fun aShapeMeshesAsFillThenOutline() {
        val s = com.xnotes.core.model.ShapeItem(
            com.xnotes.core.tools.ShapeKind.RECTANGLE,
            com.xnotes.core.geometry.Pt(0.0, 0.0),
            com.xnotes.core.geometry.Pt(40.0, 20.0),
            Rgba(1, 2, 3, 255), 3.0, Rgba(9, 9, 9, 64),
        )
        val m = ItemMesher.mesh(s)!!
        assertEquals(2, m.parts.size)
        assertEquals(Rgba(9, 9, 9, 64), m.parts[0].color)
        assertEquals(Rgba(1, 2, 3, 255), m.parts[1].color)
    }

    @Test fun aNeonStrokeReportsBoundsWiderThanItsInk() {
        val glow = ToolDefaults.configFor(Tool.PEN).copy(neon = true)
        val s = stroke(Tool.PEN, glow)
        val m = ItemMesher.mesh(s)!!
        assertTrue("the halo must be inside the culled bounds", m.bounds.w > s.bounds().w)
    }

    @Test fun aDashedStrokeMeshesAsDashesNotASolidRibbon() {
        val s = dashedStroke()
        val m = ItemMesher.mesh(s)!!
        val dashes = StrokeTessellator.tessellateDashed(
            s.geometry(), s.config.dashLength, s.config.dashGap, s.config.baseWidth / 2.0,
        )
        assertEquals(dashes.vertexCount, m.parts[0].mesh.vertexCount)
        assertTrue(
            "a dashed line must not be the solid ribbon",
            m.parts[0].mesh.vertexCount != StrokeTessellator.tessellate(s.geometry()).vertexCount,
        )
        assertEquals(s.config.baseWidth / 2.0, m.minHalfWidth, 1e-9)
    }

    @Test fun aDashedStrokeNeverGlows() {
        val s = dashedStroke(ToolDefaults.configFor(Tool.DASHED).copy(neon = true))
        val m = ItemMesher.mesh(s)!!
        assertEquals(1, m.parts.size)
        assertEquals(InkPass.OPAQUE, m.parts[0].pass)
        assertNull(m.parts[0].glow)
    }

    private fun dashedStroke(config: ToolConfig = ToolDefaults.configFor(Tool.DASHED)): Stroke =
        Stroke(Tool.DASHED, config, (0..30).map { Sample(it * 5.0, 0.0, 1.0) }.toMutableList())

    @Test fun anEmptyStrokeMeshesToNothing() {
        assertNull(ItemMesher.mesh(Stroke(Tool.PEN, ToolConfig(), mutableListOf())))
    }

    @Test fun imagesTakeTheirOwnPathRatherThanTheMeshOne() {
        val image = ImageItem(ImageData(File("none"), 4, 4), Rect(0.0, 0.0, 4.0, 4.0))
        assertNull(ItemMesher.mesh(image))
    }

    @Test fun aDotStillMeshes() {
        val dot = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(3.0, 4.0, 1.0)))
        assertNotNull(ItemMesher.mesh(dot))
    }

    // --- multiply colour ---

    @Test fun aMultiplyColourAtFullAlphaIsTheInkItself() {
        val ink = Rgba(30, 60, 90, 255)
        assertEquals(ink, ItemMesher.multiplyColor(ink, 1.0))
    }

    @Test fun aMultiplyColourAtZeroAlphaIsWhiteAndChangesNothing() {
        assertEquals(Rgba(255, 255, 255, 255), ItemMesher.multiplyColor(Rgba(0, 0, 0, 255), 0.0))
    }

    @Test fun aMultiplyColourFadesTowardWhiteWithAlpha() {
        val half = ItemMesher.multiplyColor(Rgba(0, 0, 0, 255), 0.5)
        assertEquals(127, half.r)
        assertEquals(255, half.a)
    }

    @Test fun theMultiplyColourStaysInRangeForAnyAlpha() {
        for (a in listOf(-1.0, 0.0, 0.37, 1.0, 2.0, Double.NaN)) {
            val c = ItemMesher.multiplyColor(Rgba(10, 200, 90, 255), if (a.isNaN()) 0.0 else a)
            assertTrue(c.r in 0..255 && c.g in 0..255 && c.b in 0..255)
        }
    }
}
