package com.xnotes.core.model

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class CloneTest {

    private fun denseDoc(): Document {
        val doc = Document(dpi = 150)
        repeat(3) { p ->
            val page = Page(100.0, 200.0)
            repeat(4) { i ->
                page.items.add(
                    Stroke(
                        Tool.PEN,
                        ToolConfig(),
                        mutableListOf(Sample(p + i + 0.5, 2.0, 1.0), Sample(p + i + 1.5, 3.0, 0.5)),
                    ),
                )
            }
            page.items.add(TextItem(Pt(1.0, 2.0), text = "t$p", measurer = FakeTextMeasurer()))
            doc.pages.add(page)
        }
        doc.bookmarks.add(Bookmark(1, "b"))
        return doc
    }

    @Test fun yieldingCopyMatchesTheAtomicCopy() {
        val doc = denseDoc()
        val copy = runBlocking { doc.deepCopyYielding(FakeTextMeasurer()) }

        assertEquals(doc.pages.size, copy.pages.size)
        for (pi in doc.pages.indices) {
            val a = doc.pages[pi]
            val b = copy.pages[pi]
            assertEquals(a.items.size, b.items.size)
            for (ii in a.items.indices) {
                val sa = a.items[ii] as? Stroke ?: continue
                val sb = b.items[ii] as Stroke
                assertNotSame(sa, sb) // items are cloned...
                assertEquals(sa.samples, sb.samples) // ...carrying the same sample values...
                // ...over storage of their own: clearing the original leaves the copy intact.
                val copied = sb.samples.toList()
                sa.setSamples(emptyList())
                assertEquals(copied, sb.samples)
            }
        }
        assertEquals(1, copy.bookmarks.size)
        assertEquals("b", copy.bookmarks[0].label)
    }

    @Test fun yieldingCopyIsIndependentOfLaterEdits() {
        val doc = denseDoc()
        val copy = runBlocking { doc.deepCopyYielding(FakeTextMeasurer()) }

        (doc.pages[0].items[0] as Stroke).addSample(Sample(9.0, 9.0, 1.0))
        doc.pages[1].items.clear()
        doc.pages.removeAt(2)

        assertEquals(3, copy.pages.size)
        assertEquals(2, (copy.pages[0].items[0] as Stroke).samples.size)
        assertEquals(5, copy.pages[1].items.size)
    }
}
