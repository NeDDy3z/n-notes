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
import com.xnotes.core.tools.ShapeKind

/**
 * Shape-tool settings: which shape a drag draws, whether it fills, and how its outline looks.
 * Also carries the hold-to-snap toggle, since the recognizer produces shapes too.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CanvasShapePopup(editor: InfiniteEditor, onDismiss: () -> Unit) {
    var config by remember { mutableStateOf(editor.shapeConfig) }
    fun apply(next: com.xnotes.core.tools.ShapeConfig) {
        config = next
        editor.armShapeConfig(next)
    }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(268.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle("SHAPE")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (kind in ShapeKind.DRAW_TOOL_KINDS) {
                    ModeChip(kind.id.replaceFirstChar { it.uppercase() }, config.shape == kind) {
                        apply(config.copy(shape = kind))
                    }
                }
            }

            Spacer(Modifier.size(10.dp))
            SliderRow("WIDTH", config.strokeWidth.toFloat(), 1f..20f) {
                apply(config.copy(strokeWidth = it.toDouble()))
            }

            Spacer(Modifier.size(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip("Fill", config.fill) { apply(config.copy(fill = !config.fill)) }
                ModeChip("Dashed", config.dashed) { apply(config.copy(dashed = !config.dashed)) }
                ModeChip("Snap freehand", editor.detectShapes) {
                    editor.armDetectShapes(!editor.detectShapes)
                }
            }
        }
    }
}
