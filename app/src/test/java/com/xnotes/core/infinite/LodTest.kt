package com.xnotes.core.infinite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LodTest {

    @Test fun losslessLevelIsAlwaysAvailable() {
        assertEquals(0.0, Lod.toleranceFor(0), 1e-12)
        assertEquals(Double.POSITIVE_INFINITY, Lod.maxZoomFor(0), 0.0)
    }

    @Test fun deepZoomUsesTheExactGeometry() {
        assertEquals(0, Lod.levelFor(64.0))
        assertEquals(0, Lod.levelFor(1.0))
    }

    @Test fun zoomingOutMovesToCoarserLevels() {
        val far = Lod.levelFor(0.02)
        val near = Lod.levelFor(1.0)
        assertTrue("far out must be at least as coarse as 1x", far >= near)
        assertEquals(Lod.LEVELS - 1, far)
    }

    @Test fun levelIsMonotonicInZoom() {
        var last = Lod.levelFor(100.0)
        var z = 100.0
        while (z > 0.001) {
            val level = Lod.levelFor(z)
            assertTrue("level must not get finer as we zoom out (z=$z)", level >= last)
            last = level
            z /= 1.3
        }
    }

    @Test fun everyChosenLevelStaysWithinTheScreenErrorBudget() {
        var z = 0.01
        while (z <= 128.0) {
            val level = Lod.levelFor(z)
            assertTrue(
                "level $level at zoom $z exceeds the budget",
                Lod.toleranceFor(level) * z <= Lod.SCREEN_ERROR_PX + 1e-12,
            )
            z *= 1.17
        }
    }

    @Test fun theNextLevelUpWouldBreakTheBudget() {
        var z = 0.05
        while (z <= 64.0) {
            val level = Lod.levelFor(z)
            if (level < Lod.LEVELS - 1) {
                assertTrue(
                    "level ${level + 1} should have been rejected at zoom $z",
                    Lod.toleranceFor(level + 1) * z > Lod.SCREEN_ERROR_PX,
                )
            }
            z *= 1.23
        }
    }

    @Test fun maxZoomForMatchesLevelFor() {
        for (level in 1 until Lod.LEVELS) {
            val limit = Lod.maxZoomFor(level)
            assertTrue(Lod.levelFor(limit * 0.999) >= level)
            assertTrue(Lod.levelFor(limit * 1.001) < level)
        }
    }

    @Test fun degenerateZoomFallsBackToLossless() {
        assertEquals(0, Lod.levelFor(0.0))
        assertEquals(0, Lod.levelFor(-1.0))
        assertEquals(0, Lod.levelFor(Double.NaN))
    }

    @Test fun clampedWidthNeverFallsBelowAPixel() {
        assertEquals(Lod.MIN_SCREEN_WIDTH_PX, Lod.clampedScreenWidth(3.0, 0.001), 1e-12)
        assertEquals(6.0, Lod.clampedScreenWidth(3.0, 2.0), 1e-12)
    }

    @Test fun subPixelAlphaPaysBackTheWidthTheClampAdded() {
        // A 3px stroke at 0.1x is 0.3px wide: drawn at 1px it must lose 70% of its alpha.
        assertEquals(0.3, Lod.subPixelAlpha(3.0, 0.1), 1e-12)
        val drawnInk = Lod.clampedScreenWidth(3.0, 0.1) * Lod.subPixelAlpha(3.0, 0.1)
        assertEquals("ink laid down must match the true width", 0.3, drawnInk, 1e-12)
    }

    @Test fun subPixelAlphaIsOneOnceTheStrokeIsWideEnough() {
        assertEquals(1.0, Lod.subPixelAlpha(3.0, 1.0), 1e-12)
        assertEquals(1.0, Lod.subPixelAlpha(1.0, 1.0), 1e-12)
        assertEquals(1.0, Lod.subPixelAlpha(0.5, 8.0), 1e-12)
    }

    @Test fun aZeroWidthStrokeDrawsNothing() {
        assertEquals(0.0, Lod.subPixelAlpha(0.0, 1.0), 1e-12)
    }
}
