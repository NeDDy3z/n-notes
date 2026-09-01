package com.xnotes.canvas

import com.xnotes.core.FakeRenderer
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.SurfaceFactory
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the baked-flow contract: the flow paints onto the ink layer before the
 * items (text under ink), a lifted flow leaves the screen caches, and region
 * repairs repaint the flow beneath surviving ink.
 */
class FlowInkBakeTest {

    private class RecordingSurface(override val width: Int, override val height: Int) : RasterSurface {
        override val devicePixelRatio = 1.0
        val recorder = FakeRenderer()
        override fun fill(color: Rgba) {}
        override fun renderer(): Renderer = recorder
    }

    private class RecordingFactory : SurfaceFactory {
        val surfaces = mutableListOf<RecordingSurface>()
        override fun create(widthPx: Int, heightPx: Int, devicePixelRatio: Double): RasterSurface =
            RecordingSurface(widthPx, heightPx).also { surfaces.add(it) }
    }

    private fun dot(x: Double, y: Double): Stroke =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(x, y, 1.0)))

    private val factory = RecordingFactory()

    private fun state(): CanvasState {
        val page = Page(200.0, 200.0, mutableListOf(dot(50.0, 50.0)))
        val doc = Document(mutableListOf(page))
        return CanvasState(doc, factory, Palette.forAppearance("dark", Rgba(0, 230, 118))).apply {
            viewportW = 800
            viewportH = 1000
            relayout()
            paintFlow = { _, r, region ->
                r.drawTextRun("FLOW", region.left, region.top, FontSpec(12.0), Rgba(255, 255, 255, 255))
            }
        }
    }

    private fun List<String>.flowIndex(): Int = indexOfFirst { it.startsWith("drawTextRun:FLOW@") }

    private fun List<String>.inkIndex(): Int = indexOfFirst { it == "fillCircle" || it == "fillPolygon" }

    @Test
    fun screenCacheBakesFlowUnderTheInk() {
        val st = state()
        st.cacheFor(st.document.pages[0])
        val ops = factory.surfaces.last().recorder.ops
        assertTrue(ops.flowIndex() >= 0)
        assertTrue(ops.inkIndex() > ops.flowIndex())
    }

    @Test
    fun liftedFlowSkipsTheScreenCache() {
        val st = state()
        st.flowLifted = true
        st.cacheFor(st.document.pages[0])
        assertEquals(-1, factory.surfaces.last().recorder.ops.flowIndex())
    }

    @Test
    fun repairRepaintsTheFlowInsideTheDirtyRect() {
        val st = state()
        val page = st.document.pages[0]
        st.cacheFor(page)
        val ops = factory.surfaces.last().recorder.ops
        val before = ops.count { it.startsWith("drawTextRun:FLOW@") }
        assertTrue(st.repairRegion(page, Rect(0.0, 0.0, 100.0, 100.0)))
        assertEquals(before + 1, ops.count { it.startsWith("drawTextRun:FLOW@") })

        st.flowLifted = true
        st.repairRegion(page, Rect(0.0, 0.0, 100.0, 100.0))
        assertEquals(before + 1, ops.count { it.startsWith("drawTextRun:FLOW@") })
    }

    @Test
    fun liftedStateIsSnapshottedPerBuild() {
        val st = state()
        st.flowLifted = true
        st.cacheFor(st.document.pages[0])
        assertFalse(factory.surfaces.last().recorder.ops.any { it.startsWith("drawTextRun:FLOW@") })
        st.flowLifted = false
        st.invalidatePage(st.document.pages[0])
        st.cacheFor(st.document.pages[0])
        assertTrue(factory.surfaces.last().recorder.ops.any { it.startsWith("drawTextRun:FLOW@") })
    }
}
