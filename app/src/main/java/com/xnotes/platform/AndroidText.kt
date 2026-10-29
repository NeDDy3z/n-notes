package com.xnotes.platform

import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.text.ParaDir

/**
 * Shared text layout so measuring and drawing produce identical results
 * (spec 01 §12). A "point size" is converted to page-space pixels at the
 * document authoring DPI (150), so 13pt renders as true 13pt on a 150-DPI page.
 */
object AndroidText {
    /** points -> page pixels at 150 DPI (1pt = 1/72 inch). */
    const val POINTS_TO_PX = 150f / 72f

    fun textPaint(font: FontSpec, argb: Int = 0xFF000000.toInt()): TextPaint =
        TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            val r = FontCatalog.resolve(font.face, font.bold, font.italic)
            typeface = r.typeface
            if (r.fakeBold) isFakeBoldText = true
            if (r.fakeItalic) textSkewX = -0.25f
            textSize = (font.pointSize * POINTS_TO_PX).toFloat()
            color = argb
        }

    fun layout(text: CharSequence, widthPx: Int, paint: TextPaint, flags: TextFlags = TextFlags()): StaticLayout =
        StaticLayout.Builder
            .obtain(text, 0, text.length, paint, widthPx.coerceAtLeast(1))
            .setAlignment(alignmentOf(flags))
            .setIncludePad(false)
            .apply {
                // A direction choice turns the builder's first-strong heuristic on, so each
                // paragraph takes its side from its own first strong letter (AUTO) or from the
                // pinned choice. NORMAL and OPPOSITE are the leading and trailing edges, so the
                // alignment follows the paragraph's direction without a mapping here.
                when (flags.paraDir) {
                    ParaDir.RTL -> setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
                    ParaDir.AUTO -> setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                    ParaDir.LTR, null -> Unit // the builder's own default, which is what old boxes drew with
                }
            }
            .build()

    private fun alignmentOf(flags: TextFlags): Layout.Alignment = when {
        !flags.alignLeft || flags.hAlign == com.xnotes.core.pal.HAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
        flags.hAlign == com.xnotes.core.pal.HAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        else -> Layout.Alignment.ALIGN_NORMAL
    }

    /** The height [text] actually occupies laid out at [widthPx]: one line height per wrapped line. */
    fun blockHeight(text: CharSequence, widthPx: Int, font: FontSpec): Double =
        layout(text, widthPx, textPaint(font)).height.toDouble()

    fun lineHeight(font: FontSpec): Double {
        val fm = textPaint(font).fontMetrics
        return (fm.descent - fm.ascent).toDouble()
    }
}
