package com.xnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageStyle
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/**
 * The infinite canvas's background styles: pattern, spacing, pattern colour and paper colour.
 *
 * Unlike the paged [StylesPopup] there is no inheritance to express, because a canvas has no page
 * level under it, so every control sets a real value rather than choosing between "default" and an
 * override. Everything here is per canvas and saved with it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CanvasStylesPopup(editor: InfiniteEditor, onDismiss: () -> Unit) {
    var background by remember { mutableStateOf(editor.document.background) }

    fun apply(next: CanvasBackground) {
        background = next
        editor.setBackground(next)
    }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(286.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle("STYLES")

            StyleCaption("PATTERN")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ModeChip("None", background.pattern == PagePattern.NONE) {
                    apply(background.copy(pattern = PagePattern.NONE))
                }
                ModeChip("Lines", background.pattern == PagePattern.LINES) {
                    apply(background.copy(pattern = PagePattern.LINES))
                }
                ModeChip("Dots", background.pattern == PagePattern.DOTS) {
                    apply(background.copy(pattern = PagePattern.DOTS))
                }
                ModeChip("Grid", background.pattern == PagePattern.GRID) {
                    apply(background.copy(pattern = PagePattern.GRID))
                }
            }

            Spacer(Modifier.size(12.dp))
            SliderRow(
                "SPACING",
                background.clampedSpacing.toFloat(),
                PageStyle.MIN_SPACING.toFloat()..PageStyle.MAX_SPACING.toFloat(),
                enabled = background.pattern != PagePattern.NONE,
            ) { apply(background.copy(spacing = it.toDouble())) }

            Spacer(Modifier.size(8.dp))
            StyleCaption("PATTERN COLOUR")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ColorPickerDot(
                    background.patternColor.copy(a = 255), // the hue at full strength; opacity is its own control
                    custom = true,
                    onPick = { apply(background.copy(patternColor = it.copy(a = background.patternColor.a))) },
                    dismissOnPick = false,
                ) { d, p -> PageColorGridPopup(background.patternColor.copy(a = 255), d, p) }
            }
            SliderRow(
                "OPACITY",
                background.patternColor.a / 255f * 100f,
                5f..100f,
                enabled = background.pattern != PagePattern.NONE,
            ) { pct ->
                val alpha = (pct / 100f * 255f).roundToInt().coerceIn(0, 255)
                apply(background.copy(patternColor = background.patternColor.copy(a = alpha)))
            }

            Spacer(Modifier.size(8.dp))
            StyleCaption("PAPER")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ModeChip("Theme", background.paperColor == null) { apply(background.copy(paperColor = null)) }
                pageColorPresets.forEach { c ->
                    ColorDot(c.toComposeColor(), background.paperColor == c) {
                        apply(background.copy(paperColor = c))
                    }
                }
                ColorPickerDot(
                    background.paperColor,
                    custom = background.paperColor != null && background.paperColor !in pageColorPresets,
                    onPick = { apply(background.copy(paperColor = it)) },
                    dismissOnPick = false,
                ) { d, p -> PageColorGridPopup(background.paperColor, d, p) }
            }

            Spacer(Modifier.size(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip("Reset", selected = false) { apply(CanvasBackground()) }
            }
        }
    }
}
