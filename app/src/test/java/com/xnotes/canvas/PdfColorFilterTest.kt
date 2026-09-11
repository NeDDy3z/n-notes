package com.xnotes.canvas

import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfColorFilterTest {

    /** Apply a 4x5 ColorMatrix-layout matrix to an rgb triple (alpha assumed 255, no clamping). */
    private fun apply(m: FloatArray, r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        fun row(i: Int) = m[i * 5] * r + m[i * 5 + 1] * g + m[i * 5 + 2] * b + m[i * 5 + 3] * 255f + m[i * 5 + 4]
        return Triple(row(0), row(1), row(2))
    }

    @Test fun identityLeavesPixelsAlone() {
        assertTrue(PdfColorFilter.isIdentity(100, 0, 100, 0))
        val (r, g, b) = apply(PdfColorFilter.matrix(100, 0, 100, 0), 12f, 200f, 99f)
        assertEquals(12f, r, 1e-4f)
        assertEquals(200f, g, 1e-4f)
        assertEquals(99f, b, 1e-4f)
    }

    @Test fun fullInvertFlipsChannels() {
        val (r, g, b) = apply(PdfColorFilter.matrix(100, 100, 100, 0), 255f, 0f, 60f)
        assertEquals(0f, r, 1e-3f)
        assertEquals(255f, g, 1e-3f)
        assertEquals(195f, b, 1e-3f)
    }

    @Test fun halfInvertMeetsInTheMiddle() {
        // CSS invert(50%) maps every channel to 127.5.
        val (r, g, b) = apply(PdfColorFilter.matrix(100, 50, 100, 0), 255f, 0f, 40f)
        assertEquals(127.5f, r, 1e-3f)
        assertEquals(127.5f, g, 1e-3f)
        assertEquals(127.5f, b, 1e-3f)
    }

    @Test fun brightnessScalesLinearly() {
        val (r, _, _) = apply(PdfColorFilter.matrix(100, 0, 50, 0), 200f, 200f, 200f)
        assertEquals(100f, r, 1e-3f)
    }

    @Test fun contrastPivotsOnMidGrey() {
        val m = PdfColorFilter.matrix(200, 0, 100, 0)
        val (mid, _, _) = apply(m, 127.5f, 127.5f, 127.5f)
        assertEquals(127.5f, mid, 1e-3f) // mid-grey is the fixed point
        val (r, _, _) = apply(m, 100f, 100f, 100f)
        assertEquals(72.5f, r, 1e-3f) // (100 - 127.5) * 2 + 127.5
    }

    @Test fun fullSepiaMatchesTheSpecMatrix() {
        val (r, g, b) = apply(PdfColorFilter.matrix(100, 0, 100, 100), 100f, 100f, 100f)
        assertEquals(135.1f, r, 0.1f)
        assertEquals(120.3f, g, 0.1f)
        assertEquals(93.7f, b, 0.1f)
    }

    @Test fun sepiaPastFullKeepsPushingTheTint() {
        // CSS clamps sepia at 100%; 200% extrapolates the same matrix, so mid-grey goes warmer.
        val (r1, _, b1) = apply(PdfColorFilter.matrix(100, 0, 100, 100), 128f, 128f, 128f)
        val (r2, _, b2) = apply(PdfColorFilter.matrix(100, 0, 100, 200), 128f, 128f, 128f)
        assertTrue(r2 > r1)
        assertTrue(b2 < b1)
    }

    @Test fun multiplyScalesEachChannelByTheBlendColour() {
        val m = PdfColorFilter.matrix(100, 0, 100, 0, multiply = Rgba(255, 255, 0))
        val (r, g, b) = apply(m, 200f, 200f, 200f)
        assertEquals(200f, r, 1e-3f)
        assertEquals(200f, g, 1e-3f)
        assertEquals(0f, b, 1e-3f) // blue channel multiplied by 0
    }

    @Test fun multiplyLeavesWhiteAloneAndBlackBlack() {
        val m = PdfColorFilter.matrix(100, 0, 100, 0, multiply = Rgba(255, 230, 120))
        val (wr, wg, wb) = apply(m, 255f, 255f, 255f)
        assertEquals(255f, wr, 1e-3f)
        assertEquals(230f, wg, 1e-3f)
        assertEquals(120f, wb, 1e-3f) // white takes the blend colour, like a highlighter on paper
        val (br, bg, bb) = apply(m, 0f, 0f, 0f)
        assertEquals(0f, br, 1e-3f)
        assertEquals(0f, bg, 1e-3f)
        assertEquals(0f, bb, 1e-3f) // black ink stays readable
    }

    @Test fun screenIsTheInverseHighlighter() {
        val m = PdfColorFilter.matrix(100, 0, 100, 0, screen = Rgba(40, 80, 160))
        val (br, bg, bb) = apply(m, 0f, 0f, 0f)
        assertEquals(40f, br, 1e-3f)
        assertEquals(80f, bg, 1e-3f)
        assertEquals(160f, bb, 1e-3f) // black takes the blend colour
        val (wr, wg, wb) = apply(m, 255f, 255f, 255f)
        assertEquals(255f, wr, 1e-3f)
        assertEquals(255f, wg, 1e-3f)
        assertEquals(255f, wb, 1e-3f) // white is unreachable by SCREEN
    }

    @Test fun screenMatchesTheBlendFormula() {
        // out = 255 - (255 - in)(255 - c) / 255
        val m = PdfColorFilter.matrix(100, 0, 100, 0, screen = Rgba(64, 64, 64))
        val (r, _, _) = apply(m, 128f, 128f, 128f)
        assertEquals(255f - (255f - 128f) * (255f - 64f) / 255f, r, 1e-3f)
    }

    @Test fun blendIdentityColoursAreNoOps() {
        assertTrue(
            PdfColorFilter.isIdentity(
                100, 0, 100, 0, multiply = Rgba(255, 255, 255), screen = Rgba(0, 0, 0),
            ),
        )
        assertSame(
            PdfPageFilter.NONE,
            PdfPageFilter.of(100, 0, 100, 0, Rgba(255, 255, 255), Rgba(0, 0, 0), keepImages = true),
        )
        assertFalse(PdfColorFilter.isIdentity(100, 0, 100, 0, multiply = Rgba(255, 255, 254)))
        assertFalse(PdfColorFilter.isIdentity(100, 0, 100, 0, screen = Rgba(0, 0, 1)))
    }

    @Test fun blendAlphaIsIgnored() {
        // A blend colour's alpha is meaningless; a translucent white must still read as "off".
        assertTrue(PdfColorFilter.isIdentity(100, 0, 100, 0, multiply = Rgba(255, 255, 255, 7)))
    }

    @Test fun multiplyAppliesAfterInvert() {
        // invert(100%) turns 255 into 0, so a later multiply cannot bring it back.
        val (r, _, _) = apply(
            PdfColorFilter.matrix(100, 100, 100, 0, multiply = Rgba(255, 0, 0)),
            255f, 255f, 255f,
        )
        assertEquals(0f, r, 1e-3f)
    }

    @Test fun screenAppliesAfterMultiply() {
        // Multiply by black kills the page; screen then lifts it to exactly the screen colour.
        val m = PdfColorFilter.matrix(
            100, 0, 100, 0, multiply = Rgba(0, 0, 0), screen = Rgba(30, 60, 90),
        )
        val (r, g, b) = apply(m, 200f, 100f, 50f)
        assertEquals(30f, r, 1e-3f)
        assertEquals(60f, g, 1e-3f)
        assertEquals(90f, b, 1e-3f)
    }

    @Test fun contrastAppliesBeforeInvert() {
        // contrast(200%) turns 100 into 72.5; invert(100%) then yields 182.5.
        val (r, _, _) = apply(PdfColorFilter.matrix(200, 100, 100, 0), 100f, 100f, 100f)
        assertEquals(182.5f, r, 1e-3f)
    }

    @Test fun pageFilterIsNoneAtDefaults() {
        assertSame(PdfPageFilter.NONE, PdfPageFilter.of(100, 0, 100, 0, keepImages = true))
        assertNull(PdfPageFilter.NONE.pageMatrix)
        assertFalse(PdfPageFilter.NONE.stampImages)
    }

    @Test fun imagesStampRawUnderAnyFilter() {
        // "Don't filter images" exempts them from the whole chain, not just the invert.
        val inverted = PdfPageFilter.of(100, 100, 100, 0, keepImages = true)
        assertNotNull(inverted.pageMatrix)
        assertTrue(inverted.stampImages)
        val sepiaOnly = PdfPageFilter.of(100, 0, 100, 30, keepImages = true)
        assertNotNull(sepiaOnly.pageMatrix)
        assertTrue(sepiaOnly.stampImages)
        val multiplyOnly = PdfPageFilter.of(100, 0, 100, 0, multiply = Rgba(255, 240, 160), keepImages = true)
        assertNotNull(multiplyOnly.pageMatrix)
        assertTrue(multiplyOnly.stampImages)
    }

    @Test fun noStampingWhenImagesFollowTheFilter() {
        val f = PdfPageFilter.of(100, 100, 100, 0, keepImages = false)
        assertNotNull(f.pageMatrix)
        assertFalse(f.stampImages)
    }
}
