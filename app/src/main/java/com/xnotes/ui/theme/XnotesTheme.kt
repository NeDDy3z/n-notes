package com.xnotes.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.xnotes.core.model.Rgba

/** Convert a core [Rgba] to a Compose [Color]. */
fun Rgba.toComposeColor(): Color = Color(r, g, b, a)

val LocalPalette = staticCompositionLocalOf { Palette.dark() }

@Composable
fun XnotesTheme(palette: Palette, content: @Composable () -> Unit) {
    val accent = palette.accent.toComposeColor()
    // The surfaceContainer* roles must come from the palette too: components read them
    // directly (menus draw surfaceContainer), and the darkColorScheme()/lightColorScheme()
    // baselines are purple-seeded constants that ignore the palette entirely.
    val scheme = if (palette.isDark) {
        darkColorScheme(
            primary = accent,
            onPrimary = palette.bg.toComposeColor(),
            secondary = accent,
            background = palette.bg.toComposeColor(),
            onBackground = palette.text.toComposeColor(),
            surface = palette.menuBg.toComposeColor(),
            onSurface = palette.text.toComposeColor(),
            surfaceVariant = palette.surface.toComposeColor(),
            onSurfaceVariant = palette.textDim.toComposeColor(),
            outline = palette.border.toComposeColor(),
            surfaceDim = palette.bg.toComposeColor(),
            surfaceBright = palette.surfaceHi.toComposeColor(),
            surfaceContainerLowest = palette.bg.toComposeColor(),
            surfaceContainerLow = palette.panel.toComposeColor(),
            surfaceContainer = palette.menuBg.toComposeColor(),
            surfaceContainerHigh = palette.surface.toComposeColor(),
            surfaceContainerHighest = palette.surfaceHi.toComposeColor(),
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            secondary = accent,
            background = palette.bg.toComposeColor(),
            onBackground = palette.text.toComposeColor(),
            surface = palette.menuBg.toComposeColor(),
            onSurface = palette.text.toComposeColor(),
            surfaceVariant = palette.surface.toComposeColor(),
            onSurfaceVariant = palette.textDim.toComposeColor(),
            outline = palette.border.toComposeColor(),
            surfaceDim = palette.surface.toComposeColor(),
            surfaceBright = palette.paper.toComposeColor(),
            surfaceContainerLowest = palette.paper.toComposeColor(),
            surfaceContainerLow = palette.menuBg.toComposeColor(),
            surfaceContainer = palette.menuBg.toComposeColor(),
            surfaceContainerHigh = palette.panel.toComposeColor(),
            surfaceContainerHighest = palette.bg.toComposeColor(),
        )
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
