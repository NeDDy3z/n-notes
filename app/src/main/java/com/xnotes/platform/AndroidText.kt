package com.xnotes.platform

import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.text.CharStyle
import com.xnotes.core.text.ParaDir
import com.xnotes.core.text.RunSpan

/**
 * Shared text layout so measuring and drawing produce identical results
 * (spec 01 §12). A "point size" is converted to page-space pixels at the
 * document authoring DPI (150), so 13pt renders as true 13pt on a 150-DPI page.
 */
object AndroidText {
    /** points -> page pixels at 150 DPI (1pt = 1/72 inch). */
    const val POINTS_TO_PX = 150f / 72f

    /** The colour a hyperlink's text takes unless it picks its own, as the flow painter draws it. */
    val LINK_COLOR = com.xnotes.core.model.Rgba(100, 160, 255, 255)

    fun textPaint(font: FontSpec, argb: Int = 0xFF000000.toInt()): TextPaint =
        TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            val r = FontCatalog.resolve(font.face, font.bold, font.italic)
            typeface = r.typeface
            if (r.fakeBold) isFakeBoldText = true
            if (r.fakeItalic) textSkewX = -0.25f
            textSize = (font.pointSize * POINTS_TO_PX).toFloat()
            color = argb
        }

    /**
     * [text] with a box's style spans laid over its base [font]: each [RunSpan]'s
     * overrides replace the base on its range, the rest inherit. Highlight draws as a
     * background, link text takes the link colour and underline, and face, size and the
     * bold/italic pair (resolved together, as [textPaint] does) ride one metric span so
     * the shaper sees exactly one typeface per run.
     */
    fun spanned(text: String, spans: List<RunSpan>, font: FontSpec, baseColor: Int): Spanned {
        if (spans.isEmpty()) return SpannableString(text)
        val out = SpannableString(text)
        for (span in spans) {
            val a = span.start.coerceIn(0, text.length)
            val b = span.endExclusive.coerceIn(a, text.length)
            if (b <= a) continue
            val st = span.style
            out.setSpan(object : MetricAffectingSpan() {
                override fun updateDrawState(tp: TextPaint) = apply(tp)
                override fun updateMeasureState(tp: TextPaint) = apply(tp)
                private fun apply(tp: TextPaint) {
                    val face = st.face ?: font.face
                    val bold = st.bold || font.bold
                    val italic = st.italic || font.italic
                    val r = FontCatalog.resolve(face, bold, italic)
                    tp.typeface = r.typeface
                    tp.isFakeBoldText = r.fakeBold
                    tp.textSkewX = if (r.fakeItalic) -0.25f else 0f
                    st.sizePt?.let { tp.textSize = (it * POINTS_TO_PX).toFloat() }
                }
            }, a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            st.color?.let { out.setSpan(ForegroundColorSpan(it.toArgb()), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            if (st.link != null && st.color == null) out.setSpan(ForegroundColorSpan(LINK_COLOR.toArgb()), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (st.highlight != null) out.setSpan(BackgroundColorSpan(st.highlight.toArgb()), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (st.underline || st.link != null) out.setSpan(UnderlineSpan(), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (st.strike) out.setSpan(StrikethroughSpan(), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    fun layout(text: CharSequence, widthPx: Int, paint: TextPaint, flags: TextFlags = TextFlags()): StaticLayout =
        layout(text, widthPx, paint, flags, emptyList(), null)

    fun layout(
        text: CharSequence,
        widthPx: Int,
        paint: TextPaint,
        flags: TextFlags,
        spans: List<RunSpan>,
        baseFont: FontSpec?,
    ): StaticLayout {
        val laid: CharSequence = if (spans.isEmpty() || baseFont == null || text !is String) text
        else spanned(text, spans, baseFont, paint.color)
        return StaticLayout.Builder
            .obtain(laid, 0, laid.length, paint, widthPx.coerceAtLeast(1))
            .setAlignment(alignmentOf(text, flags))
            .setIncludePad(false)
            .apply {
                // AUTO turns the builder's first-strong heuristic on, so each paragraph takes
                // its side from its own first strong letter. The pins force their side outright —
                // a FIRSTSTRONG heuristic would still read the text and leave a line whose first
                // strong letter disagrees with the pin on the other side. ALIGN_NORMAL/OPPOSITE
                // resolve against each paragraph's own side, which [alignmentOf] accounts for.
                when (flags.paraDir) {
                    ParaDir.RTL -> setTextDirection(TextDirectionHeuristics.RTL)
                    ParaDir.AUTO -> setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                    ParaDir.LTR -> setTextDirection(TextDirectionHeuristics.LTR)
                    null -> Unit // the builder's own default, which is what old boxes drew with
                }
            }
            .build()
    }

    private fun alignmentOf(text: CharSequence, flags: TextFlags): Layout.Alignment = when {
        !flags.alignLeft || flags.hAlign == com.xnotes.core.pal.HAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
        // LEFT and RIGHT name the box's absolute edges, but NORMAL and OPPOSITE name the
        // paragraph's own leading and trailing sides — which a right-to-left paragraph
        // swaps. So the box's side decides the mapping: its pin, or for AUTO the first
        // strong letter of its text, the same rule that laid its first paragraph out.
        (if (rtlSide(text, flags) && text is String) flags.hAlign == com.xnotes.core.pal.HAlign.LEFT else flags.hAlign == com.xnotes.core.pal.HAlign.RIGHT) ->
            Layout.Alignment.ALIGN_OPPOSITE
        else -> Layout.Alignment.ALIGN_NORMAL
    }

    /** Whether the box's lines run right to left: an explicit pin, or AUTO read off the text. */
    fun rtlSide(text: CharSequence, dir: com.xnotes.core.text.ParaDir?): Boolean = when (dir) {
        com.xnotes.core.text.ParaDir.RTL -> true
        com.xnotes.core.text.ParaDir.LTR -> false
        com.xnotes.core.text.ParaDir.AUTO -> text is String && TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(text, 0, text.length)
        null -> false
    }

    private fun rtlSide(text: CharSequence, flags: TextFlags): Boolean = rtlSide(text, flags.paraDir)

    /**
     * The text offset whose boundary is nearest to ([x], [y]) in the box's own space, or
     * null when [y] is outside every line: how a tap finds which span's link it hit.
     */
    fun offsetAt(text: String, spans: List<RunSpan>, font: FontSpec, flags: TextFlags, width: Double, x: Double, y: Double): Int? {
        val layout = layout(text, width.toInt(), textPaint(font), flags, spans, font)
        for (l in 0 until layout.lineCount) {
            if (y >= layout.getLineTop(l) && y < layout.getLineBottom(l)) {
                return layout.getOffsetForHorizontal(l, x.toFloat())
            }
        }
        return null
    }

    /** The height [text] actually occupies laid out at [widthPx]: one line height per wrapped line. */
    fun blockHeight(text: CharSequence, widthPx: Int, font: FontSpec): Double =
        layout(text, widthPx, textPaint(font)).height.toDouble()

    fun lineHeight(font: FontSpec): Double {
        val fm = textPaint(font).fontMetrics
        return (fm.descent - fm.ascent).toDouble()
    }
}
