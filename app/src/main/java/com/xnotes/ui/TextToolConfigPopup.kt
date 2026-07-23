package com.xnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.core.pal.FontFace
import com.xnotes.core.text.FlowDefaults
import com.xnotes.core.text.FlowMargins
import com.xnotes.platform.FontCatalog
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/**
 * The inline text tool's config popup (re-tap the armed tool): the flow's page
 * margins as millimetre spinfields, plus the document's default face, size and
 * colour (Auto = follow the theme). Changes apply immediately (live reflow) and
 * are not undoable, matching the page-style precedent. Like the styles popup,
 * the config can be saved as the default stamped onto new notes, or Reset.
 */
@Composable
internal fun TextToolConfigPopup(editor: Editor, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    var config by remember { mutableStateOf(editor.flowConfigValue()) }
    // The new-note row shows once the config differs from the saved default and
    // stays for the popup session; an all-default config hides it (see StylesPopup).
    var showNewNoteRow by remember { mutableStateOf(editor.flowConfigValue() != editor.newNoteFlow) }

    fun apply(next: FlowDefaults) {
        config = next
        editor.setFlowConfig(next)
        if (next != editor.newNoteFlow) showNewNoteRow = true
    }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text("Text", color = palette.text.toComposeColor(), fontSize = 15.sp)

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Font", color = palette.textDim.toComposeColor(), fontSize = 13.sp, modifier = Modifier.width(74.dp))
                FaceDropdown(config.face) { apply(config.copy(face = it)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mono font", color = palette.textDim.toComposeColor(), fontSize = 13.sp, modifier = Modifier.width(74.dp))
                FaceDropdown(config.monoFace, monoOnly = true) { apply(config.copy(monoFace = it)) }
            }
            SpinField("Size (pt)", config.sizePt, min = 6.0, max = 96.0) { apply(config.copy(sizePt = it)) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text("Colour", color = palette.textDim.toComposeColor(), fontSize = 13.sp, modifier = Modifier.width(74.dp))
                ModeChip("Auto", config.color == null) { apply(config.copy(color = null)) }
                Spacer(Modifier.width(8.dp))
                ColorPickerDot(
                    config.color,
                    custom = config.color != null,
                    onPick = { apply(config.copy(color = it)) },
                    dismissOnPick = false,
                ) { d, p -> ColorPickerPopup(config.color ?: editor.flowDefaultColor(), editor.recentColors, d, p) }
            }

            Text(
                "Margins (mm)",
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
            )
            val m = config.margins
            SpinField("Left", m.leftMm, FlowMargins.MIN_MM, FlowMargins.MAX_MM) { apply(config.copy(margins = m.copy(leftMm = it))) }
            SpinField("Right", m.rightMm, FlowMargins.MIN_MM, FlowMargins.MAX_MM) { apply(config.copy(margins = m.copy(rightMm = it))) }
            SpinField("Top", m.topMm, FlowMargins.MIN_MM, FlowMargins.MAX_MM) { apply(config.copy(margins = m.copy(topMm = it))) }
            SpinField("Bottom", m.bottomMm, FlowMargins.MIN_MM, FlowMargins.MAX_MM) { apply(config.copy(margins = m.copy(bottomMm = it))) }

            Spacer(Modifier.size(8.dp))
            if (showNewNoteRow && !config.isEmpty) {
                // The checkbox's 48dp touch frame insets the drawn box; pull the row back to align it.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.offset(x = (-14).dp)) {
                    Checkbox(
                        checked = !editor.newNoteFlow.isEmpty && config == editor.newNoteFlow,
                        onCheckedChange = { on -> editor.saveNewNoteFlow(if (on) config else FlowDefaults()) },
                    )
                    Text(
                        "Default for new notes",
                        color = palette.text.toComposeColor(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.size(4.dp))
            }
            Row(Modifier.align(Alignment.End)) {
                ModeChip("Reset", false) { apply(FlowDefaults()) }
            }
        }
    }
}

@Composable
private fun FaceDropdown(current: FontFace, monoOnly: Boolean = false, onPick: (FontFace) -> Unit) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clickable { open = true }.padding(vertical = 6.dp, horizontal = 4.dp)) {
            Text(
                FontCatalog.label(current),
                color = palette.text.toComposeColor(),
                style = TextStyle(fontFamily = current.toComposeFamily(), fontSize = 14.sp),
            )
            Text(" ▾", color = palette.textDim.toComposeColor(), fontSize = 11.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FontMenuItems(current = current, monoOnly = monoOnly) {
                if (it != null) onPick(it)
                open = false
            }
        }
    }
}

/** A labelled numeric spinfield: minus / value / plus, stepping by 1. */
@Composable
private fun SpinField(label: String, value: Double, min: Double, max: Double, onChange: (Double) -> Unit) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = palette.textDim.toComposeColor(), fontSize = 13.sp, modifier = Modifier.width(74.dp))
        Box(Modifier.size(34.dp).clickable { onChange((value - 1.0).coerceIn(min, max)) }, contentAlignment = Alignment.Center) {
            Text("−", color = palette.text.toComposeColor(), fontSize = 18.sp)
        }
        Text(
            value.roundToInt().toString(),
            color = palette.text.toComposeColor(),
            fontSize = 14.sp,
            modifier = Modifier.width(30.dp),
            style = TextStyle(fontFamily = FontFamily.Monospace),
        )
        Box(Modifier.size(34.dp).clickable { onChange((value + 1.0).coerceIn(min, max)) }, contentAlignment = Alignment.Center) {
            Text("+", color = palette.text.toComposeColor(), fontSize = 18.sp)
        }
    }
}
