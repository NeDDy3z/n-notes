package com.xnotes.ui.theme

import com.xnotes.core.model.Rgba
import com.xnotes.settings.MaterialStyle
import com.xnotes.vendor.materialcolor.dynamiccolor.ColorSpec.SpecVersion
import com.xnotes.vendor.materialcolor.dynamiccolor.DynamicScheme
import com.xnotes.vendor.materialcolor.dynamiccolor.DynamicScheme.Platform
import com.xnotes.vendor.materialcolor.hct.Hct
import com.xnotes.vendor.materialcolor.scheme.SchemeTonalSpot
import com.xnotes.vendor.materialcolor.scheme.SchemeVibrant
import com.xnotes.vendor.materialcolor.scheme.SchemeExpressive
import com.xnotes.vendor.materialcolor.scheme.SchemeFruitSalad
import com.xnotes.vendor.materialcolor.scheme.SchemeRainbow
import com.xnotes.vendor.materialcolor.scheme.SchemeFidelity
import com.xnotes.vendor.materialcolor.scheme.SchemeNeutral
import com.xnotes.vendor.materialcolor.scheme.SchemeMonochrome
import java.util.Optional

// Plain colour values keep generation and palette mapping JVM-testable.
data class MaterialColors(
    val primary: Rgba,
    val onPrimary: Rgba,
    val primaryContainer: Rgba,
    val onPrimaryContainer: Rgba,
    val inversePrimary: Rgba,
    val secondary: Rgba,
    val onSecondary: Rgba,
    val secondaryContainer: Rgba,
    val onSecondaryContainer: Rgba,
    val tertiary: Rgba,
    val onTertiary: Rgba,
    val tertiaryContainer: Rgba,
    val onTertiaryContainer: Rgba,
    val background: Rgba,
    val onBackground: Rgba,
    val surface: Rgba,
    val onSurface: Rgba,
    val surfaceVariant: Rgba,
    val onSurfaceVariant: Rgba,
    val surfaceTint: Rgba,
    val inverseSurface: Rgba,
    val inverseOnSurface: Rgba,
    val error: Rgba,
    val onError: Rgba,
    val errorContainer: Rgba,
    val onErrorContainer: Rgba,
    val outline: Rgba,
    val outlineVariant: Rgba,
    val scrim: Rgba,
    val surfaceBright: Rgba,
    val surfaceDim: Rgba,
    val surfaceContainerLowest: Rgba,
    val surfaceContainerLow: Rgba,
    val surfaceContainer: Rgba,
    val surfaceContainerHigh: Rgba,
    val surfaceContainerHighest: Rgba,
    val primaryFixed: Rgba,
    val primaryFixedDim: Rgba,
    val onPrimaryFixed: Rgba,
    val onPrimaryFixedVariant: Rgba,
    val secondaryFixed: Rgba,
    val secondaryFixedDim: Rgba,
    val onSecondaryFixed: Rgba,
    val onSecondaryFixedVariant: Rgba,
    val tertiaryFixed: Rgba,
    val tertiaryFixedDim: Rgba,
    val onTertiaryFixed: Rgba,
    val onTertiaryFixedVariant: Rgba,
) {
    companion object {
        private const val STANDARD_CONTRAST = 0.0

        fun seeded(
            seed: Rgba,
            dark: Boolean,
            style: MaterialStyle = MaterialStyle.TONAL_SPOT,
            surfaceSeed: Rgba? = null,
        ): MaterialColors {
            val spec = SpecVersion.SPEC_2021
            val platform = Platform.PHONE
            fun generate(colour: Rgba): DynamicScheme {
                val hct = Hct.fromInt(colour.copy(a = 255).toArgb())
                return when (style) {
                    MaterialStyle.TONAL_SPOT -> SchemeTonalSpot(hct, dark, STANDARD_CONTRAST, spec, platform)
                    MaterialStyle.VIBRANT -> SchemeVibrant(hct, dark, STANDARD_CONTRAST, spec, platform)
                    MaterialStyle.FIDELITY -> SchemeFidelity(hct, dark, STANDARD_CONTRAST, spec, platform)
                    MaterialStyle.EXPRESSIVE -> SchemeExpressive(hct, dark, STANDARD_CONTRAST, spec, platform)
                    MaterialStyle.FRUIT_SALAD -> SchemeFruitSalad(hct, dark, STANDARD_CONTRAST, spec, platform)
                    MaterialStyle.RAINBOW -> SchemeRainbow(hct, dark, STANDARD_CONTRAST, spec, platform)
                    MaterialStyle.NEUTRAL -> SchemeNeutral(hct, dark, STANDARD_CONTRAST, spec, platform)
                    MaterialStyle.MONOCHROME -> SchemeMonochrome(hct, dark, STANDARD_CONTRAST, spec, platform)
                }
            }
            val accent = generate(seed)
            val scheme = if (surfaceSeed == null) accent else {
                val surfaces = generate(surfaceSeed)
                // Combine tonal palettes before Material resolves roles and foreground contrast.
                DynamicScheme(
                    accent.sourceColorHct, accent.variant, dark, STANDARD_CONTRAST, platform, spec,
                    accent.primaryPalette, accent.secondaryPalette, accent.tertiaryPalette,
                    surfaces.neutralPalette, surfaces.neutralVariantPalette, Optional.of(accent.errorPalette),
                )
            }
            return MaterialColors(
                primary = Rgba.fromArgb(scheme.primary),
                onPrimary = Rgba.fromArgb(scheme.onPrimary),
                primaryContainer = Rgba.fromArgb(scheme.primaryContainer),
                onPrimaryContainer = Rgba.fromArgb(scheme.onPrimaryContainer),
                inversePrimary = Rgba.fromArgb(scheme.inversePrimary),
                secondary = Rgba.fromArgb(scheme.secondary),
                onSecondary = Rgba.fromArgb(scheme.onSecondary),
                secondaryContainer = Rgba.fromArgb(scheme.secondaryContainer),
                onSecondaryContainer = Rgba.fromArgb(scheme.onSecondaryContainer),
                tertiary = Rgba.fromArgb(scheme.tertiary),
                onTertiary = Rgba.fromArgb(scheme.onTertiary),
                tertiaryContainer = Rgba.fromArgb(scheme.tertiaryContainer),
                onTertiaryContainer = Rgba.fromArgb(scheme.onTertiaryContainer),
                background = Rgba.fromArgb(scheme.background),
                onBackground = Rgba.fromArgb(scheme.onBackground),
                surface = Rgba.fromArgb(scheme.surface),
                onSurface = Rgba.fromArgb(scheme.onSurface),
                surfaceVariant = Rgba.fromArgb(scheme.surfaceVariant),
                onSurfaceVariant = Rgba.fromArgb(scheme.onSurfaceVariant),
                surfaceTint = Rgba.fromArgb(scheme.surfaceTint),
                inverseSurface = Rgba.fromArgb(scheme.inverseSurface),
                inverseOnSurface = Rgba.fromArgb(scheme.inverseOnSurface),
                error = Rgba.fromArgb(scheme.error),
                onError = Rgba.fromArgb(scheme.onError),
                errorContainer = Rgba.fromArgb(scheme.errorContainer),
                onErrorContainer = Rgba.fromArgb(scheme.onErrorContainer),
                outline = Rgba.fromArgb(scheme.outline),
                outlineVariant = Rgba.fromArgb(scheme.outlineVariant),
                scrim = Rgba.fromArgb(scheme.scrim),
                surfaceBright = Rgba.fromArgb(scheme.surfaceBright),
                surfaceDim = Rgba.fromArgb(scheme.surfaceDim),
                surfaceContainerLowest = Rgba.fromArgb(scheme.surfaceContainerLowest),
                surfaceContainerLow = Rgba.fromArgb(scheme.surfaceContainerLow),
                surfaceContainer = Rgba.fromArgb(scheme.surfaceContainer),
                surfaceContainerHigh = Rgba.fromArgb(scheme.surfaceContainerHigh),
                surfaceContainerHighest = Rgba.fromArgb(scheme.surfaceContainerHighest),
                primaryFixed = Rgba.fromArgb(scheme.primaryFixed),
                primaryFixedDim = Rgba.fromArgb(scheme.primaryFixedDim),
                onPrimaryFixed = Rgba.fromArgb(scheme.onPrimaryFixed),
                onPrimaryFixedVariant = Rgba.fromArgb(scheme.onPrimaryFixedVariant),
                secondaryFixed = Rgba.fromArgb(scheme.secondaryFixed),
                secondaryFixedDim = Rgba.fromArgb(scheme.secondaryFixedDim),
                onSecondaryFixed = Rgba.fromArgb(scheme.onSecondaryFixed),
                onSecondaryFixedVariant = Rgba.fromArgb(scheme.onSecondaryFixedVariant),
                tertiaryFixed = Rgba.fromArgb(scheme.tertiaryFixed),
                tertiaryFixedDim = Rgba.fromArgb(scheme.tertiaryFixedDim),
                onTertiaryFixed = Rgba.fromArgb(scheme.onTertiaryFixed),
                onTertiaryFixedVariant = Rgba.fromArgb(scheme.onTertiaryFixedVariant),
            )
        }
    }
}
