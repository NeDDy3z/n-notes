package com.xnotes.canvas

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.PageSize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Which on-ruler control a tap landed on. */
enum class RulerButton { LOCK_POS, LOCK_ANGLE }

/**
 * The on-screen straightedge: an **infinite band** fixed in screen space. It spans
 * the whole viewport along its length (so it has no ends), has a finite [thicknessPx],
 * and does not scroll or scale with the page; its graduations are scaled by the live
 * zoom at draw time so they always measure true content distance.
 *
 * All geometry is in **viewport pixels / radians**. Because the band is infinite,
 * hit-testing and snapping depend only on the perpendicular offset from the centre
 * line (the "across"), never on a length. Pure Kotlin (no Android, no rendering) so
 * the geometry is unit-tested on the JVM, like [ResizeMath].
 */
class Ruler {
    var visible = false
    var center = Pt(0.0, 0.0)   // a point on the centre line; the graduation origin
    var angleRad = 0.0          // 0 = horizontal, +x along the length
    var thicknessPx = 0.0
    var lockPosition = false
    var lockAngle = false
    var initialized = false     // false until first placed in a viewport

    /** Unit vector along the length. */
    fun direction(): Pt = Pt(cos(angleRad), sin(angleRad))

    /** Unit vector across the thickness (the [direction] normal). */
    fun normal(): Pt = direction().perp()

    /** Signed perpendicular offset of [v] from the centre line (+ on the [normal] side). */
    fun signedAcross(v: Pt): Double = Geometry.dot(v - center, normal())

    /** Signed distance of [v] along the band from [center] (the graduation coordinate). */
    fun along(v: Pt): Double = Geometry.dot(v - center, direction())

    /** True if [v] lies on the band body. The band is infinitely long, so only the across matters. */
    fun bodyContains(v: Pt): Boolean = abs(signedAcross(v)) <= thicknessPx / 2.0

    /**
     * Clamp [v] onto one long edge ([topSide] = the +normal edge), keeping its position
     * along the band — i.e. project perpendicularly onto that edge's infinite line.
     */
    fun projectToEdge(v: Pt, topSide: Boolean): Pt {
        val target = if (topSide) thicknessPx / 2.0 else -thicknessPx / 2.0
        return v - normal() * (signedAcross(v) - target)
    }

    /** The body quad covering the along-range [sMin]..[sMax] (distance from [center] along [direction]). */
    fun bodyQuad(sMin: Double, sMax: Double): List<Pt> {
        val d = direction()
        val n = normal() * (thicknessPx / 2.0)
        val a = center + d * sMin
        val b = center + d * sMax
        return listOf(a + n, b + n, b - n, a - n)
    }

    /** Visual radius of a control button (sized to sit within the body thickness). */
    fun buttonRadiusPx(): Double = thicknessPx * 0.17

    /** Centres of the control buttons, in order, straddling [center] along the centre line. */
    fun buttonCenters(): List<Pair<RulerButton, Pt>> {
        val step = buttonRadiusPx() * 2.6
        val order = listOf(RulerButton.LOCK_POS, RulerButton.LOCK_ANGLE)
        val mid = (order.size - 1) / 2.0
        val d = direction()
        return order.mapIndexed { i, b -> b to (center + d * ((i - mid) * step)) }
    }

    /** The control button within [tolerancePx] of [v], or null. */
    fun hitButton(v: Pt, tolerancePx: Double): RulerButton? =
        buttonCenters().firstOrNull { it.second.distanceTo(v) <= tolerancePx }?.first

    /** Visual radius of a rotation handle. */
    fun handleRadiusPx(): Double = thicknessPx * 0.20

    /** The two rotation handles, [distPx] along the band each side of [center] (+direction first). */
    fun handleCenters(distPx: Double): List<Pt> {
        val d = direction() * distPx
        return listOf(center + d, center - d)
    }

    /** Index (0 = +direction, 1 = −direction) of the handle within [tolerancePx] of [v], or null. */
    fun hitHandle(v: Pt, distPx: Double, tolerancePx: Double): Int? {
        val hs = handleCenters(distPx)
        for (i in hs.indices) if (hs[i].distanceTo(v) <= tolerancePx) return i
        return null
    }

    /** Place a sensible default ruler the first time it is shown. */
    fun placeDefault(viewportW: Double, viewportH: Double, density: Double) {
        center = Pt(viewportW / 2.0, viewportH * 0.45)
        thicknessPx = 112.0 * density
        angleRad = 0.0
        initialized = true
    }

    companion object {
        /** Magnetic pull of the horizontal/vertical orientations, in degrees. */
        const val AXIS_SNAP_DEG = 4.0

        /**
         * Snap [raw] (radians, any winding) to the nearest horizontal/vertical orientation
         * (0/90/180/270) when within [AXIS_SNAP_DEG]; otherwise return it unchanged.
         */
        fun snapToAxes(raw: Double): Double {
            val quarter = Math.PI / 2.0
            val nearest = Math.round(raw / quarter) * quarter
            return if (abs(raw - nearest) <= Math.toRadians(AXIS_SNAP_DEG)) nearest else raw
        }
    }
}

/** Pure unit conversions for the ruler's graduations and length readout. */
object RulerMath {
    /** Content-space pixels per centimetre at [dpi]. */
    fun contentPxPerCm(dpi: Int): Double = PageSize.mmToPx(10.0, dpi)

    /** Content-space pixel length to centimetres at [dpi]. */
    fun contentPxToCm(px: Double, dpi: Int): Double = PageSize.pxToMm(px, dpi) / 10.0

    /**
     * Centimetres spanned by a screen-fixed [viewportPx] length at [zoom]: the ruler
     * stays a constant size on screen, so the same screen span covers fewer page
     * centimetres the further you zoom in.
     */
    fun viewportLenToCm(viewportPx: Double, zoom: Double, dpi: Int): Double =
        contentPxToCm(viewportPx / zoom, dpi)
}
