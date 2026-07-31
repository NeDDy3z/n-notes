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
import com.xnotes.core.tools.EraseMode
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConversions

/**
 * Eraser settings for the infinite canvas: how big it is, and whether it takes whole strokes or
 * only the part it passes over.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CanvasEraserPopup(editor: InfiniteEditor, onDismiss: () -> Unit) {
    var config by remember { mutableStateOf(editor.configFor(Tool.ERASER)) }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(252.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle("ERASER")

            StyleCaption("MODE")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip("Whole stroke", config.eraseMode == EraseMode.STROKE) {
                    config = config.copy(eraseMode = EraseMode.STROKE)
                    editor.setToolConfig(Tool.ERASER, config)
                }
                ModeChip("Area", config.eraseMode == EraseMode.AREA) {
                    config = config.copy(eraseMode = EraseMode.AREA)
                    editor.setToolConfig(Tool.ERASER, config)
                }
            }

            Spacer(Modifier.size(10.dp))
            val range = ToolConversions.widthRange(Tool.ERASER)
            SliderRow("SIZE", config.baseWidth.toFloat(), range.start.toFloat()..range.endInclusive.toFloat()) {
                config = config.copy(baseWidth = it.toDouble())
                editor.setToolConfig(Tool.ERASER, config)
            }
        }
    }
}
