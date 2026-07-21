package com.xnotes.ui.theme

import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialPaletteTest {

    private val seed = Rgba(0, 230, 118) // the default green accent

    private fun luminance(c: Rgba): Double =
        (0.299 * c.r + 0.587 * c.g + 0.114 * c.b) / 255.0

    @Test fun classicPalettesAreNotMaterial() {
        assertFalse(Palette.dark().isMaterial)
        assertFalse(Palette.light().isMaterial)
        assertFalse(Palette.oled().isMaterial)
    }

    @Test fun materialPalettesFlagThemselves() {
        val m = MaterialColors.seeded(seed, dark = true)
        assertTrue(Palette.material("dark", m).isMaterial)
        assertTrue(Palette.material("oled", m).isMaterial)
        assertTrue(Palette.material("light", MaterialColors.seeded(seed, dark = false)).isMaterial)
    }

    @Test fun materialAppearanceMapping() {
        val dark = MaterialColors.seeded(seed, dark = true)
        val light = MaterialColors.seeded(seed, dark = false)
        assertTrue(Palette.material("dark", dark).isDark)
        assertTrue(Palette.material("oled", dark).isDark)
        assertFalse(Palette.material("light", light).isDark)
    }

    @Test fun seededLightSurfacesStepDownFromWhitePaper() {
        val m = MaterialColors.seeded(seed, dark = false)
        assertEquals(Rgba(255, 255, 255, 255), m.surfaceContainerLowest)
        val p = Palette.materialLight(m)
        assertEquals(m.surfaceContainerLowest, p.paper)
        assertTrue(luminance(p.paper) > luminance(p.menuBg))
        assertTrue(luminance(p.menuBg) > luminance(p.panel))
        assertTrue(luminance(p.panel) > luminance(p.bg))
        assertTrue(luminance(p.bg) > luminance(p.surface))
        assertTrue(luminance(p.surface) > luminance(p.surfaceHi))
        assertTrue(luminance(p.text) < 0.3)
        // The backstage background must sit visibly below white paper, not wash out.
        assertTrue(luminance(p.menuBg) < 0.95)
    }

    @Test fun seededDarkSurfacesStayDarkWithPastelPrimary() {
        val m = MaterialColors.seeded(seed, dark = true)
        val p = Palette.materialDark(m)
        assertTrue(luminance(p.bg) < 0.1)
        assertTrue(luminance(p.paper) < 0.2)
        assertTrue(luminance(p.accent) > 0.5)
        assertTrue(luminance(p.text) > 0.7)
    }

    @Test fun materialOledBigSurfacesArePitchBlack() {
        val p = Palette.materialOled(MaterialColors.seeded(seed, dark = true))
        val black = Rgba(0, 0, 0, 255)
        assertEquals(black, p.bg)
        assertEquals(black, p.panel)
        assertEquals(black, p.paper)
        assertEquals(black, p.menuBg)
        assertTrue(p.isDark)
        assertTrue(luminance(p.surface) > 0.0)
    }

    @Test fun seededIsDeterministic() {
        assertEquals(MaterialColors.seeded(seed, true), MaterialColors.seeded(seed, true))
        assertEquals(MaterialColors.seeded(seed, false), MaterialColors.seeded(seed, false))
    }
}
