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
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConversions

/**
 * Stroke-tool settings: width, pressure, the tool's own signature control, and the neon toggle.
 * Mirrors the paged canvas's popup, minus the controls that only mean something on a page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CanvasPenPopup(editor: InfiniteEditor, tool: Tool, onDismiss: () -> Unit) {
    var config by remember { mutableStateOf(editor.configFor(tool)) }
    fun apply(next: com.xnotes.core.tools.ToolConfig) {
        config = next
        editor.setToolConfig(tool, next)
    }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(258.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(tool.name)

            val range = ToolConversions.widthRange(tool)
            SliderRow("WIDTH", config.baseWidth.toFloat(), range.start.toFloat()..range.endInclusive.toFloat()) {
                apply(config.copy(baseWidth = it.toDouble()))
            }

            if (tool != Tool.HIGHLIGHTER && tool != Tool.DASHED) {
                SliderRow(
                    "SENSITIVITY",
                    ToolConversions.minFactorToSensitivity(config.pressureMinFactor).toFloat(),
                    0f..100f,
                    enabled = config.pressureEnabled,
                ) { apply(config.copy(pressureMinFactor = ToolConversions.sensitivityToMinFactor(it.toDouble()))) }
            }

            when (tool) {
                Tool.CALLIGRAPHY -> SliderRow(
                    "MULTIPLIER",
                    ToolConversions.directionStrengthToMultiplier(config.directionStrength).toFloat(),
                    1f..8f,
                ) { apply(config.copy(directionStrength = ToolConversions.multiplierToDirectionStrength(it.toDouble()))) }
                Tool.SPEED -> SliderRow(
                    "SPEED",
                    ToolConversions.strengthToSpeed(config.speedStrength).toFloat(),
                    0f..100f,
                ) { apply(config.copy(speedStrength = ToolConversions.speedToStrength(it.toDouble()))) }
                Tool.TAPER -> SliderRow(
                    "TIP WIDTH",
                    (config.taperMinFactor * 100).toFloat(),
                    0f..100f,
                ) { apply(config.copy(taperMinFactor = (it / 100f).toDouble())) }
                Tool.DASHED -> {
                    SliderRow("DASH", config.dashLength.toFloat(), 2f..40f) {
                        apply(config.copy(dashLength = it.toDouble()))
                    }
                    SliderRow("GAP", config.dashGap.toFloat(), 2f..40f) {
                        apply(config.copy(dashGap = it.toDouble()))
                    }
                }
                Tool.HIGHLIGHTER -> SliderRow(
                    "INTENSITY",
                    ToolConversions.highlighterAlphaToIntensity(config.highlighterAlpha).toFloat(),
                    10f..90f,
                ) { apply(config.copy(highlighterAlpha = ToolConversions.intensityToHighlighterAlpha(it.toDouble()))) }
                else -> Unit
            }

            Spacer(Modifier.size(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip("Pressure", config.pressureEnabled) {
                    apply(config.copy(pressureEnabled = !config.pressureEnabled))
                }
                // The highlighter never glows: a translucent marker with a halo reads as a smudge.
                if (tool != Tool.HIGHLIGHTER) {
                    ModeChip("Neon", config.neon) { apply(config.copy(neon = !config.neon)) }
                }
                ModeChip("Scale with zoom", config.scale) { apply(config.copy(scale = !config.scale)) }
            }
            if (config.neon && tool != Tool.HIGHLIGHTER) {
                SliderRow(
                    "GLOW",
                    ToolConversions.neonStrengthToIntensity(config.neonStrength).toFloat(),
                    0f..100f,
                ) { apply(config.copy(neonStrength = ToolConversions.intensityToNeonStrength(it.toDouble()))) }
            }
        }
    }
}
