package com.xnotes.platform

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.measure.LatexMeasurerState
import com.hrm.latex.renderer.model.LatexConfig
import com.xnotes.core.pal.MathBox
import com.xnotes.core.pal.MathTypesetter

/**
 * [MathTypesetter] over the KaTeX-metric LaTeX renderer. [state] has to be built
 * in a composition (its font resolution comes from there), so the host makes one
 * and hands it here; measuring itself is a plain synchronous call and caches
 * internally, which is what lets the flow ask for sizes while it breaks lines.
 */
class ComposeMathTypesetter(
    private val state: LatexMeasurerState,
    private val density: Density,
) : MathTypesetter {

    // One sp in px, which is what the measurer's own density will multiply back
    // out: dividing by it first asks for a size in page px, not screen px, so an
    // equation is the same size on the page whatever the display or font scale.
    private val spPx: Float = with(density) { 1.sp.toPx() }

    override fun measure(latex: String, sizePt: Double): MathBox? {
        if (latex.isBlank() || spPx <= 0f) return null
        val px = sizePt * AndroidText.POINTS_TO_PX
        val d = state.measure(latex, LatexConfig(fontSize = (px / spPx).sp)) ?: return null
        return MathBox(
            width = d.widthPx.toDouble(),
            ascent = d.baselinePx.toDouble(),
            descent = (d.heightPx - d.baselinePx).toDouble(),
        )
    }
}
