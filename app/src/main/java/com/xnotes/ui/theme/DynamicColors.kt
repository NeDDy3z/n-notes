package com.xnotes.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.xnotes.core.model.Rgba

/** Snapshot the system Material You scheme as [MaterialColors]; null below Android 12. */
fun dynamicMaterialColors(context: Context, dark: Boolean): MaterialColors? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val s = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    fun rgba(c: Color): Rgba {
        val argb = c.toArgb()
        return Rgba((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, (argb ushr 24) and 0xFF)
    }
    return MaterialColors(
        primary = rgba(s.primary),
        onPrimary = rgba(s.onPrimary),
        primaryContainer = rgba(s.primaryContainer),
        onPrimaryContainer = rgba(s.onPrimaryContainer),
        inversePrimary = rgba(s.inversePrimary),
        secondary = rgba(s.secondary),
        onSecondary = rgba(s.onSecondary),
        secondaryContainer = rgba(s.secondaryContainer),
        onSecondaryContainer = rgba(s.onSecondaryContainer),
        tertiary = rgba(s.tertiary),
        onTertiary = rgba(s.onTertiary),
        tertiaryContainer = rgba(s.tertiaryContainer),
        onTertiaryContainer = rgba(s.onTertiaryContainer),
        background = rgba(s.background),
        onBackground = rgba(s.onBackground),
        surface = rgba(s.surface),
        onSurface = rgba(s.onSurface),
        surfaceVariant = rgba(s.surfaceVariant),
        onSurfaceVariant = rgba(s.onSurfaceVariant),
        surfaceTint = rgba(s.surfaceTint),
        inverseSurface = rgba(s.inverseSurface),
        inverseOnSurface = rgba(s.inverseOnSurface),
        error = rgba(s.error),
        onError = rgba(s.onError),
        errorContainer = rgba(s.errorContainer),
        onErrorContainer = rgba(s.onErrorContainer),
        outline = rgba(s.outline),
        outlineVariant = rgba(s.outlineVariant),
        scrim = rgba(s.scrim),
        surfaceBright = rgba(s.surfaceBright),
        surfaceDim = rgba(s.surfaceDim),
        surfaceContainerLowest = rgba(s.surfaceContainerLowest),
        surfaceContainerLow = rgba(s.surfaceContainerLow),
        surfaceContainer = rgba(s.surfaceContainer),
        surfaceContainerHigh = rgba(s.surfaceContainerHigh),
        surfaceContainerHighest = rgba(s.surfaceContainerHighest),
        primaryFixed = rgba(s.primaryFixed),
        primaryFixedDim = rgba(s.primaryFixedDim),
        onPrimaryFixed = rgba(s.onPrimaryFixed),
        onPrimaryFixedVariant = rgba(s.onPrimaryFixedVariant),
        secondaryFixed = rgba(s.secondaryFixed),
        secondaryFixedDim = rgba(s.secondaryFixedDim),
        onSecondaryFixed = rgba(s.onSecondaryFixed),
        onSecondaryFixedVariant = rgba(s.onSecondaryFixedVariant),
        tertiaryFixed = rgba(s.tertiaryFixed),
        tertiaryFixedDim = rgba(s.tertiaryFixedDim),
        onTertiaryFixed = rgba(s.onTertiaryFixed),
        onTertiaryFixedVariant = rgba(s.onTertiaryFixedVariant),
    )
}
