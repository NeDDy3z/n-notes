package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A stroke's samples are published as one immutable tuple, so a reader that took the sample view
 * (the autosave writer, off the main thread) keeps reading the stroke as it was when it started,
 * whatever the pen does to it meanwhile. Every one of these edits used to mutate the arrays the
 * reader was walking. That guarantee is what lets the autosave share the live items instead of
 * deep-copying the document.
 */
class StrokeSamplePinTest {

    private fun stroke(vararg pts: Pair<Double, Double>, straight: Boolean = false) = Stroke(
        Tool.PEN,
        ToolConfig(),
        pts.map { Sample(it.first, it.second, 1.0) },
        straight = straight,
    )

    @Test fun anAppendDoesNotGrowAViewAlreadyTaken() {
        val s = stroke(0.0 to 0.0, 1.0 to 1.0)
        val pinned = s.samples

        s.addSample(Sample(2.0, 2.0, 1.0))

        assertEquals(2, pinned.size)
        assertEquals(3, s.samples.size)
    }

    /** The doubling path: enough appends to reallocate the arrays under the pinned view. */
    @Test fun growingPastTheArraysDoesNotDisturbAViewAlreadyTaken() {
        val s = stroke(0.0 to 0.0)
        val pinned = s.samples
        repeat(200) { s.addSample(Sample(it + 1.0, 0.0, 1.0)) }

        assertEquals(1, pinned.size)
        assertEquals(0.0, pinned[0].x, 1e-9)
        assertEquals(201, s.samples.size)
    }

    @Test fun aStraightLineEndDoesNotMoveUnderAViewAlreadyTaken() {
        val s = stroke(0.0 to 0.0, straight = true)
        s.setStraightEnd(Sample(5.0, 0.0, 1.0))
        val pinned = s.samples

        s.setStraightEnd(Sample(9.0, 0.0, 1.0))

        assertEquals(5.0, pinned[1].x, 1e-9)
        assertEquals(9.0, s.samples[1].x, 1e-9)
    }

    @Test fun aTransformDoesNotMoveAViewAlreadyTaken() {
        val s = stroke(0.0 to 0.0, 2.0 to 0.0)
        val pinned = s.samples

        s.applyTransform(Affine.scaleAbout(Pt.ZERO, 3.0, 3.0))

        assertEquals(2.0, pinned[1].x, 1e-9)
        assertEquals(6.0, s.samples[1].x, 1e-9)
    }

    @Test fun aTranslateDoesNotMoveAViewAlreadyTaken() {
        val s = stroke(0.0 to 0.0, 2.0 to 0.0)
        val pinned = s.samples

        s.translate(10.0, 0.0)

        assertEquals(0.0, pinned[0].x, 1e-9)
        assertEquals(10.0, s.samples[0].x, 1e-9)
    }

    @Test fun replacingEverySampleDoesNotEmptyAViewAlreadyTaken() {
        val s = stroke(0.0 to 0.0, 2.0 to 0.0)
        val pinned = s.samples

        s.setSamples(emptyList())

        assertEquals(2, pinned.size)
        assertEquals(0, s.samples.size)
    }

    @Test fun trimmingTheSlackDoesNotDisturbAViewAlreadyTaken() {
        val s = stroke(0.0 to 0.0)
        repeat(5) { s.addSample(Sample(it + 1.0, 0.0, 1.0)) }
        val pinned = s.samples

        s.trimToSize()
        s.addSample(Sample(99.0, 0.0, 1.0))

        assertEquals(6, pinned.size)
        assertEquals(7, s.samples.size)
    }
}
