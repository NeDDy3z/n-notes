package com.xnotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** Which picture an empty state shows. */
internal enum class EmptyArt { PAGES, SEARCH, RECENT, TRASH }

private const val ART_W = 168f
private const val ART_H = 128f

/** A small line drawing for an empty state, painted from the scheme so it suits every theme. */
@Composable
internal fun EmptyIllustration(art: EmptyArt, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val m = palette.materialColors
    val ink = ArtInk(
        blob = when (art) {
            EmptyArt.PAGES, EmptyArt.TRASH -> m.primaryContainer
            EmptyArt.SEARCH -> m.tertiaryContainer
            EmptyArt.RECENT -> m.secondaryContainer
        }.toComposeColor(),
        paper = palette.paper.toComposeColor(),
        edge = m.outline.toComposeColor(),
        rule = m.outlineVariant.toComposeColor(),
        accent = m.primary.toComposeColor(),
        spark = m.tertiary.toComposeColor(),
        text = m.onSurfaceVariant.toComposeColor(),
    )
    Canvas(modifier.size(ART_W.dp, ART_H.dp)) {
        val u = size.width / ART_W
        drawCircle(ink.blob, 54 * u, Offset(84 * u, 64 * u))
        when (art) {
            EmptyArt.PAGES -> pages(ink, u)
            EmptyArt.SEARCH -> search(ink, u)
            EmptyArt.RECENT -> recent(ink, u)
            EmptyArt.TRASH -> trash(ink, u)
        }
    }
}

private class ArtInk(val blob: Color, val paper: Color, val edge: Color, val rule: Color, val accent: Color, val spark: Color, val text: Color)

/** A ruled sheet centred on ([cx], [cy]) and turned [degrees]; [written] scribbles a line of ink on it. */
private fun DrawScope.sheet(ink: ArtInk, u: Float, cx: Float, cy: Float, degrees: Float, written: Boolean = false) {
    val w = 58 * u
    val h = 74 * u
    val topLeft = Offset(cx * u - w / 2, cy * u - h / 2)
    rotate(degrees, Offset(cx * u, cy * u)) {
        drawRoundRect(ink.paper, topLeft, Size(w, h), CornerRadius(4 * u))
        drawRoundRect(ink.edge, topLeft, Size(w, h), CornerRadius(4 * u), style = Stroke(1.5f * u))
        for (i in 1..4) {
            val y = topLeft.y + h * (0.18f + i * 0.15f)
            drawLine(ink.rule, Offset(topLeft.x + 9 * u, y), Offset(topLeft.x + w - 9 * u, y), 1.2f * u)
        }
        if (written) {
            val path = Path()
            val y = topLeft.y + h * 0.26f
            path.moveTo(topLeft.x + 9 * u, y)
            var x = topLeft.x + 9 * u
            var up = true
            while (x < topLeft.x + w - 14 * u) {
                path.quadraticTo(x + 3 * u, y + (if (up) -5 else 5) * u, x + 6 * u, y)
                x += 6 * u
                up = !up
            }
            drawPath(path, ink.accent, style = Stroke(2 * u, cap = StrokeCap.Round))
        }
    }
}

/** A four-point twinkle of radius [r] at ([cx], [cy]). */
private fun DrawScope.spark(ink: ArtInk, u: Float, cx: Float, cy: Float, r: Float) {
    val c = Offset(cx * u, cy * u)
    val path = Path().apply {
        moveTo(c.x, c.y - r * u)
        quadraticTo(c.x, c.y, c.x + r * u, c.y)
        quadraticTo(c.x, c.y, c.x, c.y + r * u)
        quadraticTo(c.x, c.y, c.x - r * u, c.y)
        quadraticTo(c.x, c.y, c.x, c.y - r * u)
        close()
    }
    drawPath(path, ink.spark)
}

private fun DrawScope.pages(ink: ArtInk, u: Float) {
    sheet(ink, u, 72f, 68f, -9f)
    sheet(ink, u, 92f, 62f, 5f, written = true)
    // The pen lies across the corner of the top sheet, nib down.
    drawLine(ink.text, Offset(121 * u, 101 * u), Offset(117 * u, 110 * u), 3 * u, StrokeCap.Round)
    drawLine(ink.accent, Offset(123 * u, 96 * u), Offset(145 * u, 40 * u), 8 * u, StrokeCap.Round)
    spark(ink, u, 36f, 30f, 6f)
    spark(ink, u, 146f, 104f, 4f)
}

private fun DrawScope.search(ink: ArtInk, u: Float) {
    sheet(ink, u, 76f, 66f, -5f)
    val lens = Offset(106 * u, 58 * u)
    drawCircle(ink.blob.copy(alpha = 0.7f), 19 * u, lens)
    drawCircle(ink.accent, 19 * u, lens, style = Stroke(6 * u))
    drawLine(ink.accent, Offset(120 * u, 72 * u), Offset(137 * u, 89 * u), 9 * u, StrokeCap.Round)
    spark(ink, u, 40f, 28f, 5f)
    spark(ink, u, 140f, 32f, 4f)
}

private fun DrawScope.recent(ink: ArtInk, u: Float) {
    sheet(ink, u, 74f, 68f, -6f)
    val face = Offset(110 * u, 54 * u)
    drawCircle(ink.paper, 22 * u, face)
    drawCircle(ink.accent, 22 * u, face, style = Stroke(5 * u))
    drawLine(ink.text, face, Offset(110 * u, 41 * u), 3.5f * u, StrokeCap.Round)
    drawLine(ink.text, face, Offset(119 * u, 59 * u), 3.5f * u, StrokeCap.Round)
    spark(ink, u, 38f, 32f, 5f)
    spark(ink, u, 142f, 96f, 4f)
}

private fun DrawScope.trash(ink: ArtInk, u: Float) {
    val body = Path().apply {
        moveTo(62 * u, 48 * u)
        lineTo(106 * u, 48 * u)
        lineTo(101 * u, 104 * u)
        quadraticTo(100 * u, 108 * u, 96 * u, 108 * u)
        lineTo(72 * u, 108 * u)
        quadraticTo(68 * u, 108 * u, 67 * u, 104 * u)
        close()
    }
    drawPath(body, ink.paper)
    drawPath(body, ink.edge, style = Stroke(2 * u))
    for (x in listOf(75f, 84f, 93f)) drawLine(ink.rule, Offset(x * u, 58 * u), Offset(x * u, 98 * u), 2 * u, StrokeCap.Round)
    drawLine(ink.accent, Offset(56 * u, 44 * u), Offset(112 * u, 44 * u), 6 * u, StrokeCap.Round)
    drawLine(ink.accent, Offset(76 * u, 36 * u), Offset(92 * u, 36 * u), 5 * u, StrokeCap.Round)
    // All clear: it sparkles.
    spark(ink, u, 126f, 40f, 8f)
    spark(ink, u, 40f, 56f, 5f)
    spark(ink, u, 128f, 90f, 4f)
}
