package com.xnotes.ui.theme

import com.xnotes.core.model.Rgba
import com.xnotes.settings.MaterialStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class MaterialSchemeTest {
    private val seeds = listOf(0x800000, 0xf2b8b5, 0xff5733, 0x00e676, 0x0000ff, 0xffff00, 0x000000, 0xffffff, 0x808080)
    private fun rgb(value: Int) = Rgba.fromArgb(value or (0xff shl 24))

    private fun luminance(c: Rgba): Double {
        fun linear(v: Int): Double = (v / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * linear(c.r) + 0.7152 * linear(c.g) + 0.0722 * linear(c.b)
    }

    private fun contrast(a: Rgba, b: Rgba): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    @Test fun fidelityMoodExamplesKeepTheirContainers() {
        val expected = listOf(
            Triple(0x800000, 0x570000, 0xffb4a8),
            Triple(0xf2b8b5, 0x805351, 0xffdad7),
            Triple(0xff5733, 0xb72301, 0xffb4a4),
        )
        for ((seed, lightPrimary, darkPrimary) in expected) {
            for (dark in listOf(false, true)) {
                val m = MaterialColors.seeded(rgb(seed), dark, MaterialStyle.FIDELITY)
                assertEquals(rgb(if (dark) darkPrimary else lightPrimary), m.primary)
                assertEquals(rgb(seed), m.primaryContainer)
            }
        }
    }

    @Test fun foregroundPairsRemainReadableAfterPaletteMapping() {
        for (seed in seeds) for (style in MaterialStyle.entries) for (surfaceSeed in listOf(null, 0x0000ff, 0xffaa00, 0xffffff, 0x000000)) {
            for (appearance in listOf("light", "dark", "oled")) {
                val m = MaterialColors.seeded(rgb(seed), appearance != "light", style, surfaceSeed?.let(::rgb))
                val p = Palette.material(appearance, m)
                val context = "$seed $surfaceSeed $style $appearance"
                for ((fg, bg) in listOf(p.onAccent to p.accent, p.selectionForeground to p.selectionBackground)) {
                    assertTrue("$context foreground ${contrast(fg, bg)}", contrast(fg, bg) >= 4.45)
                }
                for (bg in listOf(p.bg, p.panel, p.menuBg, p.surface, p.surfaceHi)) {
                    assertTrue("$context text ${contrast(p.text, bg)}", contrast(p.text, bg) >= 4.45)
                    assertTrue("$context secondary text ${contrast(p.textDim, bg)}", contrast(p.textDim, bg) >= 4.45)
                    assertTrue("$context accent icon ${contrast(p.accent, bg)}", contrast(p.accent, bg) >= 2.95)
                }
            }
        }
    }

    @Test fun composeReceivesGeneratedPairsInsteadOfDefaultPurple() {
        val m = MaterialColors.seeded(rgb(0x800000), true, MaterialStyle.FIDELITY)
        val p = Palette.material("oled", m)
        val scheme = p.composeColorScheme()
        assertEquals(m.onPrimary.toComposeColor(), scheme.onPrimary)
        assertEquals(m.primaryContainer.toComposeColor(), scheme.primaryContainer)
        assertEquals(m.onPrimaryContainer.toComposeColor(), scheme.onPrimaryContainer)
        assertEquals(m.secondary.toComposeColor(), scheme.secondary)
        assertEquals(m.onSecondary.toComposeColor(), scheme.onSecondary)
        assertEquals(m.tertiaryContainer.toComposeColor(), scheme.tertiaryContainer)
        assertEquals(m.onTertiaryContainer.toComposeColor(), scheme.onTertiaryContainer)
        assertEquals(m.error.toComposeColor(), scheme.error)
        assertEquals(m.onError.toComposeColor(), scheme.onError)
        assertEquals(m.primaryFixed.toComposeColor(), scheme.primaryFixed)
        assertEquals(p.menuBg.toComposeColor(), scheme.surface)
        assertEquals(rgb(0).toComposeColor(), scheme.background)
    }

    @Test fun grayscaleIgnoresSeedAndDoesNotRegainHsvTint() {
        val a = MaterialColors.seeded(rgb(0xff0000), true, MaterialStyle.MONOCHROME)
        val b = MaterialColors.seeded(rgb(0x0000ff), true, MaterialStyle.MONOCHROME)
        assertEquals(a, b)
        val p = Palette.material("dark", a)
        for (color in listOf(p.accent, p.accentDim, p.selectionBackground, p.selectionForeground, p.paper)) {
            assertEquals(color.r, color.g)
            assertEquals(color.g, color.b)
        }
    }

    @Test fun fidelityDistinguishesSameHueSeeds() {
        val darkRed = MaterialColors.seeded(rgb(0x800000), true, MaterialStyle.FIDELITY)
        val paleRed = MaterialColors.seeded(rgb(0xffaaaa), true, MaterialStyle.FIDELITY)
        assertNotEquals(darkRed.primaryContainer, paleRed.primaryContainer)
    }

    @Test fun cleanKeepsSurfacesNeutralAcrossSeedsAndAppearances() {
        for (seed in seeds) for (appearance in listOf("light", "dark", "oled")) {
            val m = MaterialColors.seeded(rgb(seed), appearance != "light", MaterialStyle.RAINBOW)
            val p = Palette.material(appearance, m)
            for (color in listOf(p.bg, p.panel, p.paper, p.menuBg, p.surface, p.surfaceHi)) {
                assertEquals(color.r, color.g)
                assertEquals(color.g, color.b)
            }
        }
    }

    @Test fun seedAlphaCannotMakeChromeTransparent() {
        assertEquals(MaterialColors.seeded(rgb(0xff5733), false), MaterialColors.seeded(rgb(0xff5733).copy(a = 0), false))
    }

    @Test fun matchingDualToneSeedsReproduceSingleToneForEveryStyle() {
        for (style in MaterialStyle.entries) for (dark in listOf(false, true)) for (seed in seeds) {
            val colour = rgb(seed)
            assertEquals(MaterialColors.seeded(colour, dark, style), MaterialColors.seeded(colour, dark, style, colour))
        }
    }

    @Test fun dualToneKeepsAccentAndSurfaceFamiliesIndependent() {
        fun accents(m: MaterialColors) = listOf(
            m.primary, m.onPrimary, m.primaryContainer, m.onPrimaryContainer,
            m.secondary, m.onSecondary, m.secondaryContainer, m.onSecondaryContainer,
            m.tertiary, m.onTertiary, m.tertiaryContainer, m.onTertiaryContainer, m.error,
        )
        fun surfaces(m: MaterialColors) = listOf(
            m.background, m.surface, m.surfaceDim, m.surfaceBright, m.onSurface, m.onSurfaceVariant,
            m.surfaceContainerLowest, m.surfaceContainerLow, m.surfaceContainer, m.surfaceContainerHigh,
            m.surfaceContainerHighest, m.outline, m.outlineVariant,
        )
        for (style in MaterialStyle.entries) for (dark in listOf(false, true)) {
            val red = MaterialColors.seeded(rgb(0x800000), dark, style)
            val blue = MaterialColors.seeded(rgb(0x0000ff), dark, style)
            val dual = MaterialColors.seeded(rgb(0x800000), dark, style, rgb(0x0000ff))
            val reverse = MaterialColors.seeded(rgb(0x0000ff), dark, style, rgb(0x800000))
            assertEquals(accents(red), accents(dual))
            assertEquals(surfaces(blue), surfaces(dual))
            assertEquals(accents(blue), accents(reverse))
            assertEquals(surfaces(red), surfaces(reverse))
            if (style != MaterialStyle.MONOCHROME && style != MaterialStyle.RAINBOW) {
                assertNotEquals(surfaces(red), surfaces(dual))
            } else {
                assertEquals(red, dual)
            }
            assertEquals(dual, MaterialColors.seeded(rgb(0x800000).copy(a = 0), dark, style, rgb(0x0000ff).copy(a = 0)))
        }
    }
}
