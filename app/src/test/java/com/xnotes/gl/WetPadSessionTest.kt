package com.xnotes.gl

import com.xnotes.core.infinite.PixelRect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pad's view, across the gap between one stroke and the next.
 *
 * A stroke that starts while the last one is still being handed over lays itself over the ink
 * already on the pad instead of wiping it, which is only sound when it is painted through exactly
 * the view that ink was drawn through.
 */
class WetPadSessionTest {

    private fun ink() = GlWetPadInk().apply {
        assertTrue(begin(10.0, 20.0, 2.0, 800, 600, CLIP))
    }

    @Test
    fun `a stroke on the same view picks the frozen one back up`() {
        val ink = ink()
        ink.end()
        assertFalse("frozen, so nothing is being drawn", ink.active)
        assertTrue(ink.extend(10.0, 20.0, 2.0, 800, 600, CLIP))
        assertTrue("the same session is live again", ink.active)
    }

    @Test
    fun `a view that moved at all cannot be picked up`() {
        for (case in listOf<(GlWetPadInk) -> Boolean>(
            { it.extend(10.5, 20.0, 2.0, 800, 600, CLIP) },
            { it.extend(10.0, 19.5, 2.0, 800, 600, CLIP) },
            { it.extend(10.0, 20.0, 2.01, 800, 600, CLIP) },
            { it.extend(10.0, 20.0, 2.0, 801, 600, CLIP) },
            { it.extend(10.0, 20.0, 2.0, 800, 601, CLIP) },
            { it.extend(10.0, 20.0, 2.0, 800, 600, PixelRect(0, 0, 400, 500)) },
            { it.extend(10.0, 20.0, 2.0, 800, 600, null) },
        )) {
            val ink = ink()
            ink.end()
            assertFalse(case(ink))
            assertFalse(ink.active)
        }
    }

    @Test
    fun `a stroke still under the pen has nothing to pick up`() {
        val ink = ink()
        assertFalse(ink.extend(10.0, 20.0, 2.0, 800, 600, CLIP))
    }

    @Test
    fun `freezing twice keeps the view the first one put down`() {
        val ink = ink()
        ink.end()
        ink.end()
        assertTrue(ink.extend(10.0, 20.0, 2.0, 800, 600, CLIP))
    }

    @Test
    fun `a stroke that took the pad outright leaves nothing to pick up`() {
        val ink = ink()
        ink.end()
        assertTrue(ink.begin(10.0, 20.0, 2.0, 800, 600, CLIP))
        ink.end()
        // The second begin wiped the pad, so only the view it installed may be picked up, and it
        // is the one just frozen.
        assertTrue(ink.extend(10.0, 20.0, 2.0, 800, 600, CLIP))
    }

    @Test
    fun `a wipe takes the frozen view with it`() {
        val ink = ink()
        ink.end()
        ink.forgetFrozen()
        assertFalse(ink.extend(10.0, 20.0, 2.0, 800, 600, CLIP))
    }

    @Test
    fun `a surface that went away takes both`() {
        val ink = ink()
        ink.forget()
        assertFalse(ink.active)
        assertFalse(ink.extend(10.0, 20.0, 2.0, 800, 600, CLIP))
    }

    @Test
    fun `a clip is compared as the surface cut it down`() {
        val ink = GlWetPadInk()
        assertTrue(ink.begin(0.0, 0.0, 1.0, 800, 600, PixelRect(-50, -50, 900, 700)))
        ink.end()
        // Both clips clamp to the whole surface, so the second stroke is on the same view.
        assertTrue(ink.extend(0.0, 0.0, 1.0, 800, 600, PixelRect(0, 0, 800, 600)))
    }

    private companion object {
        val CLIP: PixelRect get() = PixelRect(40, 60, 700, 520)
    }
}
