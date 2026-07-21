package com.xnotes.ui.theme

import com.xnotes.core.model.Rgba

/**
 * The Material 3 tokens a material [Palette] is built from, kept as plain [Rgba]
 * so the mapping stays pure Kotlin and JVM-testable. Filled from the system
 * dynamic colour scheme on Android 12+, or approximated from the accent seed
 * via [seeded] on older devices.
 */
data class MaterialColors(
    val primary: Rgba,
    val onPrimary: Rgba,
    val surface: Rgba,
    val surfaceContainerLowest: Rgba,
    val surfaceContainerLow: Rgba,
    val surfaceContainer: Rgba,
    val surfaceContainerHigh: Rgba,
    val surfaceContainerHighest: Rgba,
    val onSurface: Rgba,
    val onSurfaceVariant: Rgba,
    val outline: Rgba,
    val outlineVariant: Rgba,
) {
    companion object {
        /**
         * Approximate a Material 3 scheme from the accent colour for devices
         * without dynamic colour (pre Android 12): neutral surfaces carry a
         * faint tint of the seed hue, tone-stepped like the real tonal ladder.
         */
        fun seeded(seed: Rgba, dark: Boolean): MaterialColors {
            val h = ColorMath.rgbToHsv(seed)[0]
            fun tone(s: Double, v: Double) = ColorMath.hsvToRgb(h, s, v)
            return if (dark) {
                MaterialColors(
                    primary = tone(0.40, 0.90),
                    onPrimary = tone(0.60, 0.25),
                    surface = tone(0.14, 0.07),
                    surfaceContainerLowest = tone(0.16, 0.05),
                    surfaceContainerLow = tone(0.13, 0.09),
                    surfaceContainer = tone(0.12, 0.11),
                    surfaceContainerHigh = tone(0.11, 0.14),
                    surfaceContainerHighest = tone(0.10, 0.17),
                    onSurface = tone(0.04, 0.90),
                    onSurfaceVariant = tone(0.08, 0.65),
                    outline = tone(0.10, 0.45),
                    outlineVariant = tone(0.12, 0.28),
                )
            } else {
                MaterialColors(
                    primary = tone(0.65, 0.55),
                    onPrimary = Rgba(255, 255, 255, 255),
                    surface = tone(0.02, 0.99),
                    surfaceContainerLowest = Rgba(255, 255, 255, 255),
                    surfaceContainerLow = tone(0.03, 0.96),
                    surfaceContainer = tone(0.04, 0.94),
                    surfaceContainerHigh = tone(0.05, 0.92),
                    surfaceContainerHighest = tone(0.06, 0.90),
                    onSurface = tone(0.10, 0.11),
                    onSurfaceVariant = tone(0.12, 0.30),
                    outline = tone(0.10, 0.50),
                    outlineVariant = tone(0.08, 0.78),
                )
            }
        }
    }
}
