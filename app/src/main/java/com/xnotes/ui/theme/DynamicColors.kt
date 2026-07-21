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
        surface = rgba(s.surface),
        surfaceContainerLowest = rgba(s.surfaceContainerLowest),
        surfaceContainerLow = rgba(s.surfaceContainerLow),
        surfaceContainer = rgba(s.surfaceContainer),
        surfaceContainerHigh = rgba(s.surfaceContainerHigh),
        surfaceContainerHighest = rgba(s.surfaceContainerHighest),
        onSurface = rgba(s.onSurface),
        onSurfaceVariant = rgba(s.onSurfaceVariant),
        outline = rgba(s.outline),
        outlineVariant = rgba(s.outlineVariant),
    )
}
