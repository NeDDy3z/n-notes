package com.xnotes.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginatedFlipPolicyTest {
    @Test fun narrowScreenRequiresMinimumPhysicalDrag() {
        assertEquals(150.0, PaginatedFlipPolicy.threshold(900, 2.0), 1e-9)
        assertFalse(PaginatedFlipPolicy.shouldFlip(149.0, 900, 2.0))
        assertTrue(PaginatedFlipPolicy.shouldFlip(150.0, 900, 2.0))
    }

    @Test fun tabletThresholdScalesWithViewport() {
        assertEquals(230.4, PaginatedFlipPolicy.threshold(2560, 2.0), 1e-9)
        assertFalse(PaginatedFlipPolicy.shouldFlip(-229.0, 2560, 2.0))
        assertTrue(PaginatedFlipPolicy.shouldFlip(-231.0, 2560, 2.0))
    }

    @Test fun highZoomSlightlyRaisesFlipThreshold() {
        val base = PaginatedFlipPolicy.threshold(1600, 2.0, 1.0)
        assertEquals(150.0, base, 1e-9)
        assertEquals(base, PaginatedFlipPolicy.threshold(1600, 2.0, 1.25), 1e-9)
        assertEquals(base * 1.10, PaginatedFlipPolicy.threshold(1600, 2.0, 1.875), 1e-9)
        assertEquals(base * 1.20, PaginatedFlipPolicy.threshold(1600, 2.0, 2.5), 1e-9)
        assertEquals(base * 1.20, PaginatedFlipPolicy.threshold(1600, 2.0, 4.0), 1e-9)
    }

    @Test fun zoomAwareReleaseUsesSameThreshold() {
        assertTrue(PaginatedFlipPolicy.shouldCommitOnRelease(
            160.0, 1600, 2.0, -80.0, 0.0, 60L, -1, 1.0))
        assertFalse(PaginatedFlipPolicy.shouldCommitOnRelease(
            160.0, 1600, 2.0, -80.0, 0.0, 60L, -1, 2.5))
        assertTrue(PaginatedFlipPolicy.shouldCommitOnRelease(
            185.0, 1600, 2.0, -80.0, 0.0, 60L, -1, 2.5))
    }

    @Test fun addPageRequiresMoreTravelThanOrdinaryNavigation() {
        assertEquals(220.0, PaginatedFlipPolicy.addPageThreshold(1000, 2.0), 1e-9)
        assertEquals(563.2, PaginatedFlipPolicy.addPageThreshold(2560, 2.0), 1e-9)
        assertTrue(PaginatedFlipPolicy.addPageThreshold(2560, 2.0) >
            PaginatedFlipPolicy.threshold(2560, 2.0))
    }

    @Test fun fastOutwardFlickRequiresMeaningfulEdgeTravel() {
        val normal = PaginatedFlipPolicy.threshold(1600, 2.0) // 150 px
        assertFalse(PaginatedFlipPolicy.shouldFlip(0.0, 1600, 2.0, -4000.0))
        assertFalse(PaginatedFlipPolicy.shouldFlip(normal * 0.5, 1600, 2.0, -4000.0))
        assertTrue(PaginatedFlipPolicy.shouldFlip(normal * 0.56, 1600, 2.0, -1800.0))
        assertTrue(PaginatedFlipPolicy.shouldFlip(-normal * 0.56, 1600, 2.0, 1800.0))
    }

    @Test fun slowWrongWayAndDiagonalFlicksDoNotFlip() {
        val pull = 105.0
        assertFalse(PaginatedFlipPolicy.shouldFlip(pull, 1600, 2.0, -1000.0))
        assertFalse(PaginatedFlipPolicy.shouldFlip(pull, 1600, 2.0, 1800.0))
        assertFalse(PaginatedFlipPolicy.shouldFlip(pull, 1600, 2.0, -2000.0, 1700.0))
        assertFalse(PaginatedFlipPolicy.shouldFlip(0.0, 1600, 2.0, -4000.0))
    }

    @Test fun pausedReleaseDoesNotCommitEvenAfterLongPull() {
        assertFalse(PaginatedFlipPolicy.shouldCommitOnRelease(850.0, 1600, 2.0, -4000.0, 0.0, 220L))
        assertFalse(PaginatedFlipPolicy.shouldCommitOnRelease(850.0, 1600, 2.0, 0.0, 0.0, 0L))
    }

    @Test fun continuousOutwardReleaseCommitsAfterDeliberatePull() {
        assertTrue(PaginatedFlipPolicy.shouldCommitOnRelease(260.0, 1600, 2.0, -500.0, 30.0, 35L, -1))
        assertTrue(PaginatedFlipPolicy.shouldCommitOnRelease(-260.0, 1600, 2.0, 500.0, 30.0, 35L, 1))
    }

    @Test fun naturalReleaseWorksAfterDeceleration() {
        assertTrue(PaginatedFlipPolicy.shouldCommitOnRelease(260.0, 1600, 2.0, -45.0, 12.0, 125L, -1))
        assertTrue(PaginatedFlipPolicy.shouldCommitOnRelease(-260.0, 1600, 2.0, 0.0, 0.0, 150L, 1))
        assertFalse(PaginatedFlipPolicy.shouldCommitOnRelease(260.0, 1600, 2.0, 0.0, 0.0, 220L, -1))
    }

    @Test fun tinyTouchJitterOrReversalCannotCommit() {
        assertFalse(PaginatedFlipPolicy.shouldCommitOnRelease(260.0, 1600, 2.0, 0.0, 0.0, 15L, 1))
        assertFalse(PaginatedFlipPolicy.shouldCommitOnRelease(260.0, 1600, 2.0, 650.0, 0.0, 15L, -1))
        assertFalse(PaginatedFlipPolicy.shouldCommitOnRelease(35.0, 1600, 2.0, 0.0, 0.0, 15L, -1))
    }

    @Test fun reverseOrMostlyVerticalReleaseDoesNotCommit() {
        assertFalse(PaginatedFlipPolicy.shouldCommitOnRelease(600.0, 1600, 2.0, 400.0, 0.0, 10L))
        assertFalse(PaginatedFlipPolicy.shouldCommitOnRelease(600.0, 1600, 2.0, -400.0, 500.0, 10L, -1))
        assertFalse(PaginatedFlipPolicy.shouldCommitOnRelease(35.0, 1600, 2.0, -4000.0, 0.0, 10L))
    }

    @Test fun fullDistanceStillWorksWithoutFlick() {
        assertTrue(PaginatedFlipPolicy.shouldFlip(225.0, 1600, 2.0))
        assertTrue(PaginatedFlipPolicy.shouldFlip(-225.0, 1600, 2.0))
    }
}
