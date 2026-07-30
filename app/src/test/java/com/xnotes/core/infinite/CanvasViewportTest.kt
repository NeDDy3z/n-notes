package com.xnotes.core.infinite

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasViewportTest {

    private fun viewport(w: Int = 1000, h: Int = 800): CanvasViewport =
        CanvasViewport().apply {
            widthPx = w
            heightPx = h
        }

    @Test fun identityViewMapsContentToItself() {
        val v = viewport()
        assertEquals(Pt(10.0, 20.0), v.contentToViewport(Pt(10.0, 20.0)))
        assertEquals(Pt(10.0, 20.0), v.viewportToContent(Pt(10.0, 20.0)))
    }

    @Test fun transformsRoundTrip() {
        val v = viewport()
        v.zoom = 3.7
        v.scrollX = -412.5
        v.scrollY = 98.25
        val back = v.viewportToContent(v.contentToViewport(Pt(42.0, -17.0)))
        assertEquals(42.0, back.x, 1e-9)
        assertEquals(-17.0, back.y, 1e-9)
    }

    @Test fun rectTransformsRoundTrip() {
        val v = viewport()
        v.zoom = 0.25
        v.scrollX = 1000.0
        v.scrollY = -2000.0
        val r = Rect(12.0, 34.0, 56.0, 78.0)
        val back = v.viewportToContent(v.contentToViewport(r))
        assertEquals(r.x, back.x, 1e-9)
        assertEquals(r.y, back.y, 1e-9)
        assertEquals(r.w, back.w, 1e-9)
        assertEquals(r.h, back.h, 1e-9)
    }

    @Test fun visibleRectShrinksAsZoomRises() {
        val v = viewport(1000, 800)
        v.zoom = 2.0
        v.scrollX = 100.0
        v.scrollY = 50.0
        val vis = v.visibleContentRect()
        assertEquals(100.0, vis.x, 1e-9)
        assertEquals(50.0, vis.y, 1e-9)
        assertEquals(500.0, vis.w, 1e-9)
        assertEquals(400.0, vis.h, 1e-9)
    }

    @Test fun cullRectGrowsByDeviceMargin() {
        val v = viewport(1000, 800)
        v.zoom = 4.0
        val cull = v.cullRect(margin = 80.0) // 80 device px = 20 content px at 4x
        assertEquals(-20.0, cull.x, 1e-9)
        assertEquals(250.0 + 40.0, cull.w, 1e-9)
    }

    @Test fun panFollowsTheFinger() {
        val v = viewport()
        v.zoom = 2.0
        v.panByViewport(100.0, -40.0)
        // Dragging right moves content right, so the left edge steps back by 100/2 content px.
        assertEquals(-50.0, v.scrollX, 1e-9)
        assertEquals(20.0, v.scrollY, 1e-9)
    }

    @Test fun zoomAroundHoldsTheFocalPoint() {
        val v = viewport()
        v.zoom = 1.0
        v.scrollX = 300.0
        v.scrollY = 400.0
        val focusV = Pt(250.0, 610.0)
        val focusC = v.viewportToContent(focusV)
        v.zoomAround(focusV.x, focusV.y, 7.3)
        val after = v.contentToViewport(focusC)
        assertEquals(focusV.x, after.x, 1e-6)
        assertEquals(focusV.y, after.y, 1e-6)
    }

    @Test fun zoomAroundCenterHoldsTheCenter() {
        val v = viewport(1000, 800)
        v.zoom = 0.5
        val before = v.centerContent
        v.zoomAroundCenter(12.0)
        assertEquals(before.x, v.centerContent.x, 1e-6)
        assertEquals(before.y, v.centerContent.y, 1e-6)
    }

    @Test fun zoomClampsToTheConfiguredRange() {
        val v = viewport()
        v.zoom = 1e9
        assertEquals(CanvasViewport.MAX_ZOOM, v.zoom, 1e-12)
        v.zoom = 1e-9
        assertEquals(CanvasViewport.MIN_ZOOM, v.zoom, 1e-12)
    }

    @Test fun zoomAroundReturnsTheClampedZoomAndStillHoldsThePoint() {
        val v = viewport()
        v.minZoom = 0.5
        v.maxZoom = 2.0
        val focusV = Pt(100.0, 100.0)
        val focusC = v.viewportToContent(focusV)
        val reached = v.zoomAround(focusV.x, focusV.y, 100.0)
        assertEquals(2.0, reached, 1e-12)
        val after = v.contentToViewport(focusC)
        assertEquals(focusV.x, after.x, 1e-6)
        assertEquals(focusV.y, after.y, 1e-6)
    }

    @Test fun narrowingTheRangeThenClampingPullsZoomBackIn() {
        val v = viewport()
        v.zoom = 30.0
        v.maxZoom = 8.0
        v.clampZoom()
        assertEquals(8.0, v.zoom, 1e-12)
    }

    @Test fun centerOnPutsThePointInTheMiddle() {
        val v = viewport(1000, 800)
        v.zoom = 4.0
        v.centerOn(-250.0, 375.0)
        val mid = v.contentToViewport(Pt(-250.0, 375.0))
        assertEquals(500.0, mid.x, 1e-9)
        assertEquals(400.0, mid.y, 1e-9)
    }

    @Test fun fitFramesTheContentWithMargin() {
        val v = viewport(1000, 800)
        val content = Rect(0.0, 0.0, 2000.0, 1000.0)
        v.fit(content, padPx = 100.0)
        // Width binds: 800 available px over 2000 content px.
        assertEquals(800.0 / 2000.0, v.zoom, 1e-9)
        val vis = v.visibleContentRect()
        assertTrue(vis.x <= content.left)
        assertTrue(vis.right >= content.right)
        assertTrue(vis.y <= content.top)
        assertTrue(vis.bottom >= content.bottom)
        assertEquals(content.centerX, v.centerContent.x, 1e-6)
        assertEquals(content.centerY, v.centerContent.y, 1e-6)
    }

    @Test fun fitOnADegenerateRectJustCentresIt() {
        val v = viewport(1000, 800)
        v.zoom = 3.0
        v.fit(Rect(50.0, 60.0, 0.0, 0.0))
        assertEquals(3.0, v.zoom, 1e-12)
        assertEquals(50.0, v.centerContent.x, 1e-6)
        assertEquals(60.0, v.centerContent.y, 1e-6)
    }

    @Test fun fitBeforeLayoutIsANoOp() {
        val v = CanvasViewport()
        v.zoom = 2.0
        v.fit(Rect(0.0, 0.0, 100.0, 100.0))
        assertEquals(2.0, v.zoom, 1e-12)
    }

    @Test fun waypointRoundTripsAcrossAScreenSizeChange() {
        val v = viewport(1000, 800)
        v.zoom = 5.5
        v.centerOn(1234.0, -5678.0)
        val w = v.toWaypoint("desk")
        val other = viewport(600, 1200)
        other.apply(w)
        assertEquals(5.5, other.zoom, 1e-12)
        assertEquals(1234.0, other.centerContent.x, 1e-6)
        assertEquals(-5678.0, other.centerContent.y, 1e-6)
    }

    @Test fun canvasRunsInEveryDirection() {
        val v = viewport()
        v.panByViewport(1e6, 1e6)
        v.panByViewport(-4e6, -4e6)
        assertTrue(v.scrollX > 0)
        assertTrue(v.scrollY > 0)
        val back = v.viewportToContent(v.contentToViewport(Pt(-1e7, 1e7)))
        assertEquals(-1e7, back.x, 1e-3)
        assertEquals(1e7, back.y, 1e-3)
    }
}
