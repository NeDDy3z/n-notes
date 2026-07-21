package com.xnotes.ui.theme

import com.xnotes.core.model.Rgba
import kotlin.math.abs

/**
 * The design tokens for one appearance (spec 11 §1). An [accent] overrides the
 * palette's shipped accent and survives a dark/light switch.
 */
data class Palette(
    val bg: Rgba,
    val panel: Rgba,
    val paper: Rgba,
    val paperBorder: Rgba,
    val accent: Rgba,
    val accentDim: Rgba,
    val border: Rgba,
    val text: Rgba,
    val textDim: Rgba,
    val surface: Rgba,
    val surfaceHi: Rgba,
    val menuBg: Rgba,
    val isDark: Boolean,
    /** True when the chrome is built from a Material 3 scheme (rounded card corners etc.). */
    val isMaterial: Boolean = false,
) {
    /** The accent lightened ~28% (hover highlights). */
    val accentLight: Rgba get() = ColorMath.lighten(accent, 0.28)

    fun accentAlpha(alpha: Int): Rgba = accent.withAlpha(alpha)

    companion object {
        val DEFAULT_ACCENT = Rgba(0, 230, 118) // #00e676
        val DISABLED_ICON = Rgba(58, 58, 58)    // #3a3a3a

        fun dark(accent: Rgba = DEFAULT_ACCENT): Palette = Palette(
            bg = hex(0x0a0a0a),
            panel = hex(0x0d0d0d),
            paper = hex(0x161616),
            paperBorder = hex(0x2c2c2c),
            accent = accent,
            accentDim = ColorMath.dim(accent),
            border = hex(0x242424),
            text = hex(0xc8c8c8),
            textDim = hex(0x6f6f6f),
            surface = hex(0x151515),
            surfaceHi = hex(0x1c1c1c),
            menuBg = hex(0x111111),
            isDark = true,
        )

        fun light(accent: Rgba = Rgba(0, 161, 82)): Palette {
            // A pale accent on the pale light background reads poorly, so deepen
            // bright accents until they're legible (dark mode keeps them as-is).
            val a = ColorMath.darkenForLight(accent)
            return Palette(
                bg = hex(0xe8e8e8),
                panel = hex(0xf4f4f4),
                paper = hex(0xffffff),
                paperBorder = hex(0xc4c4c4),
                accent = a,
                accentDim = ColorMath.dim(a),
                border = hex(0xd0d0d0),
                text = hex(0x1c1c1c),
                textDim = hex(0x6f6f6f),
                surface = hex(0xe4e4e4),
                surfaceHi = hex(0xd8d8d8),
                menuBg = hex(0xfbfbfb),
                isDark = false,
            )
        }

        /**
         * OLED: pitch-black ([bg]/[panel]/[paper]/[menuBg] = #000000) for power saving
         * and true black on OLED panels. Counts as a dark variant ([isDark] = true).
         * Big surfaces are pure black; small controls keep a micro-lift so tap targets
         * stay visible, and the page is located only by a faint [paperBorder].
         */
        fun oled(accent: Rgba = DEFAULT_ACCENT): Palette = Palette(
            bg = hex(0x000000),
            panel = hex(0x000000),
            paper = hex(0x000000),
            paperBorder = hex(0x242424),
            accent = accent,
            accentDim = ColorMath.dim(accent),
            border = hex(0x242424),
            text = hex(0xc8c8c8),
            textDim = hex(0x6f6f6f),
            surface = hex(0x0d0d0d),
            surfaceHi = hex(0x161616),
            menuBg = hex(0x000000),
            isDark = true,
        )

        fun forAppearance(appearance: String, accent: Rgba): Palette = when (appearance) {
            "light" -> light(accent)
            "oled" -> oled(accent)
            else -> dark(accent)
        }

        /** Build the full chrome from a Material 3 scheme's tonal surfaces. */
        fun material(appearance: String, m: MaterialColors): Palette = when (appearance) {
            "light" -> materialLight(m)
            "oled" -> materialOled(m)
            else -> materialDark(m)
        }

        /** Light sits a few tone steps below white so panels and the backstage keep visible
         *  depth against white paper instead of washing out near the top of the ladder. */
        fun materialLight(m: MaterialColors): Palette = Palette(
            bg = m.surfaceContainerHighest,
            panel = m.surfaceContainerHigh,
            paper = m.surfaceContainerLowest,
            paperBorder = m.outline,
            accent = m.primary,
            accentDim = ColorMath.dim(m.primary),
            border = m.outlineVariant,
            text = m.onSurface,
            textDim = m.onSurfaceVariant,
            surface = m.surfaceDim,
            surfaceHi = ColorMath.darken(m.surfaceDim, 0.06),
            menuBg = m.surfaceContainer,
            isDark = false,
            isMaterial = true,
        )

        fun materialDark(m: MaterialColors): Palette = Palette(
            bg = m.surfaceContainerLowest,
            panel = m.surface,
            paper = m.surfaceContainer,
            paperBorder = m.outlineVariant,
            accent = m.primary,
            accentDim = ColorMath.dim(m.primary),
            border = m.outlineVariant,
            text = m.onSurface,
            textDim = m.onSurfaceVariant,
            surface = m.surfaceContainerHigh,
            surfaceHi = m.surfaceContainerHighest,
            menuBg = m.surfaceContainerLow,
            isDark = true,
            isMaterial = true,
        )

        /** Material OLED: the big surfaces drop to pure black, small lifts step down one slot. */
        fun materialOled(m: MaterialColors): Palette {
            val black = hex(0x000000)
            return materialDark(m).copy(
                bg = black,
                panel = black,
                paper = black,
                menuBg = black,
                surface = m.surfaceContainerLow,
                surfaceHi = m.surfaceContainer,
            )
        }

        private fun hex(rgb: Int): Rgba =
            Rgba((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, 255)
    }
}

/** Small pure colour math for accent derivations (spec 11 §1). */
object ColorMath {
    /** ACCENT_DIM = the accent's hue at 72% saturation and 55% value. */
    fun dim(c: Rgba): Rgba {
        val hsv = rgbToHsv(c)
        return hsvToRgb(hsv[0], 0.72, 0.55, c.a)
    }

    /**
     * Cap an accent's perceived luminance for the light theme. Colours brighter
     * than [maxLuminance] are scaled down in place (hue and saturation kept),
     * so a bright yellow deepens to gold; colours already dark enough pass through.
     */
    fun darkenForLight(c: Rgba, maxLuminance: Double = 0.35): Rgba {
        val lum = (0.299 * c.r + 0.587 * c.g + 0.114 * c.b) / 255.0
        if (lum <= maxLuminance) return c
        val k = maxLuminance / lum
        fun ch(v: Int) = (v * k).toInt().coerceIn(0, 255)
        return Rgba(ch(c.r), ch(c.g), ch(c.b), c.a)
    }

    /** Lighten toward white by [amount] (0..1). */
    fun lighten(c: Rgba, amount: Double): Rgba {
        val a = amount.coerceIn(0.0, 1.0)
        fun mix(v: Int) = (v + (255 - v) * a).toInt().coerceIn(0, 255)
        return Rgba(mix(c.r), mix(c.g), mix(c.b), c.a)
    }

    /** Darken toward black by [amount] (0..1). */
    fun darken(c: Rgba, amount: Double): Rgba {
        val a = amount.coerceIn(0.0, 1.0)
        fun mix(v: Int) = (v * (1 - a)).toInt().coerceIn(0, 255)
        return Rgba(mix(c.r), mix(c.g), mix(c.b), c.a)
    }

    fun rgbToHsv(c: Rgba): DoubleArray {
        val r = c.r / 255.0
        val g = c.g / 255.0
        val b = c.b / 255.0
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        var h = when {
            delta < 1e-9 -> 0.0
            max == r -> 60.0 * (((g - b) / delta) % 6.0)
            max == g -> 60.0 * (((b - r) / delta) + 2.0)
            else -> 60.0 * (((r - g) / delta) + 4.0)
        }
        if (h < 0) h += 360.0
        val s = if (max < 1e-9) 0.0 else delta / max
        return doubleArrayOf(h, s, max)
    }

    fun hsvToRgb(h: Double, s: Double, v: Double, alpha: Int = 255): Rgba {
        val c = v * s
        val x = c * (1 - abs((h / 60.0) % 2 - 1))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        fun ch(v0: Double) = ((v0 + m) * 255).toInt().coerceIn(0, 255)
        return Rgba(ch(r1), ch(g1), ch(b1), alpha)
    }
}
