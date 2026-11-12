package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatTextdirectionLToR
import androidx.compose.material.icons.filled.FormatTextdirectionRToL
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.HAlign
import com.xnotes.core.text.ParaDir
import com.xnotes.platform.FontCatalog
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/**
 * Floating font/size bar for the active text box (the one being edited, or a lone
 * selected one). Anchored above the box; colour is driven by the toolbar swatches,
 * so this carries only the family picker and a point-size stepper. When the box is
 * merely selected (not editing) the generic selection menu also sits above it, so
 * this bar stacks one row higher. A note's box being edited is styled from the bottom
 * [TextBoxFormatBar] instead.
 */
@Composable
fun TextStyleBar(editor: TextEditHost) {
    val bar = editor.textBar ?: return
    if (bar.editing && editor is Editor) return
    val palette = LocalPalette.current
    val density = LocalDensity.current

    // The bar wraps its content so a long font name never clips the trailing controls;
    // its width is known only after layout, so centre by an estimate then the measured value.
    var measuredWidthPx by remember { mutableStateOf<Float?>(null) }
    val estWidth = if (bar.editing) 240.dp else 196.dp
    val barHeightPx = with(density) { 44.dp.toPx() }
    val barWidthPx = measuredWidthPx ?: with(density) { estWidth.toPx() }
    val gapPx = with(density) { 8.dp.toPx() }
    // When the box is only selected (not editing), the generic selection menu also sits above it,
    // so raise this bar by that menu's height + a gap to stack cleanly above it.
    val stackPx = if (!bar.editing) with(density) { 56.dp.toPx() } else 0f
    val minX = with(density) { 8.dp.toPx() }

    val rect = bar.rect
    val centerX = ((rect.left + rect.right) / 2.0).toFloat()
    val xPx = (centerX - barWidthPx / 2f).coerceAtLeast(minX)
    val above = rect.top.toFloat() - barHeightPx - gapPx - stackPx
    val yPx = if (above > 0f) above else rect.bottom.toFloat() + gapPx + stackPx

    val xDp = with(density) { xPx.toDp() }
    val yDp = with(density) { yPx.toDp() }

    // Tips open upward so they don't cover the box being styled.
    CompositionLocalProvider(LocalTipSide provides TipSide.ABOVE) {
        Row(
            modifier = Modifier
                .offset(xDp, yDp)
                .height(44.dp)
                .onSizeChanged { measuredWidthPx = it.width.toFloat() }
                .clip(MaterialTheme.shapes.medium)
                .background(palette.menuBg.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FacePicker(current = bar.face) { editor.setTextFace(it) }
            Box(
                Modifier.width(1.dp).fillMaxHeight().padding(vertical = 8.dp)
                    .background(palette.border.toComposeColor()),
            )
            SizeStepper(
                size = bar.pointSize,
                onDelta = { editor.setTextPointSize(bar.pointSize + it) },
            )
            // A commit (✓) button so an edit can be finished without tapping off the box.
            if (bar.editing) {
                Box(
                    Modifier.width(1.dp).fillMaxHeight().padding(vertical = 8.dp)
                        .background(palette.border.toComposeColor()),
                )
                Tooltip(stringResource(R.string.done)) {
                    Box(
                        modifier = Modifier.size(44.dp).clickable { editor.commitText() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            XnotesIcons.check,
                            contentDescription = stringResource(R.string.done),
                            tint = palette.text.toComposeColor(),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The bottom styling strip for a text box being edited, docked as the last child of the
 * editor column so the adjustResize window floats it directly above the soft keyboard, the
 * same slot the inline text tool's [TextFormatBar] uses. Carries font colour, family, size,
 * and a commit (✓) button.
 */
@Composable
fun TextBoxFormatBar(editor: Editor) {
    val bar = editor.textBar ?: return
    if (!bar.editing) return
    val palette = LocalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(palette.panel.toComposeColor())
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextColorButton(editor, bar.rgba)
        BarHighlightButton(editor, bar.selActive, bar.span?.highlight)
        BarSeparator()
        FacePicker(current = bar.face) { editor.setTextFace(it) }
        BarSeparator()
        SizeStepper(size = bar.pointSize, onDelta = { editor.setTextPointSize(bar.pointSize + it) })
        BarSeparator()
        StyleToggle(Icons.Filled.FormatBold, stringResource(R.string.bold), bar.bold) { editor.toggleTextBold() }
        StyleToggle(Icons.Filled.FormatItalic, stringResource(R.string.italic), bar.italic) { editor.toggleTextItalic() }
        StyleToggle(Icons.Filled.FormatUnderlined, stringResource(R.string.underline), bar.underline) { editor.toggleTextUnderline() }
        StyleToggle(Icons.Filled.FormatStrikethrough, stringResource(R.string.strikethrough), bar.strike) { editor.toggleTextStrike() }
        BarLinkButton(editor, bar.selActive, bar.span?.link)
        StyleToggle(alignIcon(bar.align), stringResource(R.string.alignment), bar.align != HAlign.LEFT) { editor.cycleTextAlign() }
        // The glyph names the mode, as on the flow bar: two opposing arrows for automatic —
        // each paragraph takes its side from its first strong letter — and the pinned side
        // otherwise, lit because a pin is the non-default the toggle can undo.
        if (!bar.label) {
            StyleToggle(
                when (bar.dir) {
                    ParaDir.AUTO -> XnotesIcons.dirAuto
                    ParaDir.LTR -> Icons.Filled.FormatTextdirectionLToR
                    ParaDir.RTL -> Icons.Filled.FormatTextdirectionRToL
                },
                stringResource(R.string.text_direction),
                bar.dir != ParaDir.AUTO,
            ) { editor.cycleTextDir() }
        }
        BarSeparator()
        Box(
            modifier = Modifier.size(44.dp).clickable { editor.commitText() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                XnotesIcons.check,
                contentDescription = stringResource(R.string.done),
                tint = palette.text.toComposeColor(),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private fun alignIcon(align: HAlign): ImageVector = when (align) {
    HAlign.LEFT -> Icons.Filled.FormatAlignLeft
    HAlign.CENTER -> Icons.Filled.FormatAlignCenter
    HAlign.RIGHT -> Icons.Filled.FormatAlignRight
}

/**
 * Highlight the field's selection: an "A" on a swatch, as the flow bar's does. A tap on
 * an active one clears it, and with no selection there is nothing to hang the override
 * on, so the button rests.
 */
@Composable
private fun BarHighlightButton(editor: Editor, selActive: Boolean, current: Rgba?) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        Tooltip(stringResource(if (current != null) R.string.remove_highlight else R.string.markup_highlight)) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(enabled = selActive) { if (current != null) editor.setTextHighlight(null) else open = true },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background((current ?: palette.panel).toComposeColor())
                        .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "A",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = current?.let { swatchInk(it) } ?: palette.textDim.toComposeColor(),
                    )
                }
            }
        }
        if (open) {
            ColorPickerPopup(
                initial = Rgba(255, 235, 59, 255),
                recents = editor.recentColors,
                onDismiss = { open = false },
                onPick = { editor.setTextHighlight(it); open = false },
            )
        }
    }
}

/** Black or white, whichever reads against the swatch colour. */
private fun swatchInk(c: Rgba): androidx.compose.ui.graphics.Color =
    if (0.299 * c.r + 0.587 * c.g + 0.114 * c.b > 150) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White

/** Link the field's selection: a URL dialog over the span, or a Remove for an active link. */
@Composable
private fun BarLinkButton(editor: Editor, selActive: Boolean, current: String?) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        Tooltip("Link") {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(enabled = selActive) { open = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Link,
                    contentDescription = "Link",
                    tint = (if (current != null) palette.accent else palette.textDim).toComposeColor(),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (open) {
            var text by remember { mutableStateOf(current ?: "") }
            AlertDialog(
                onDismissRequest = { open = false },
                title = { Text("Link") },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        placeholder = { Text("https://example.com") },
                    )
                },
                confirmButton = {
                    TextButton(onClick = { editor.setTextCharLink(text.trim()); open = false }) { Text(stringResource(R.string.done)) }
                },
                dismissButton = {
                    if (current != null) {
                        TextButton(onClick = { editor.setTextCharLink(null); open = false }) { Text(stringResource(R.string.remove)) }
                    } else {
                        TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) }
                    }
                },
            )
        }
    }
}

@Composable
private fun StyleToggle(icon: ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(
            icon,
            contentDescription = description,
            tint = (if (active) palette.accent else palette.textDim).toComposeColor(),
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun BarSeparator() {
    Box(
        Modifier.width(1.dp).height(28.dp)
            .background(LocalPalette.current.border.toComposeColor()),
    )
}

@Composable
private fun TextColorButton(editor: Editor, current: Rgba) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("A", color = palette.text.toComposeColor(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Box(
                    Modifier.width(16.dp).height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(current.toComposeColor()),
                )
            }
        }
        if (open) {
            ColorPickerPopup(
                initial = current,
                recents = editor.recentColors,
                onDismiss = { open = false },
                onPick = { editor.setTextColor(it); open = false },
            )
        }
    }
}

@Composable
private fun FacePicker(current: FontFace, onPick: (FontFace) -> Unit) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        Tooltip(stringResource(R.string.font)) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .clickable { open = true }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    fontLabel(current),
                    color = palette.text.toComposeColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 92.dp),
                    style = TextStyle(fontFamily = current.toComposeFamily(), fontSize = 15.sp),
                )
                Text(" ▾", color = palette.textDim.toComposeColor(), fontSize = 11.sp)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FontMenuItems(current = current) {
                if (it != null) onPick(it)
                open = false
            }
        }
    }
}

@Composable
private fun SizeStepper(size: Double, onDelta: (Double) -> Unit) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", stringResource(R.string.smaller)) { onDelta(-1.0) } // minus
        Text(
            size.roundToInt().toString(),
            color = palette.text.toComposeColor(),
            fontSize = 15.sp,
            modifier = Modifier.width(26.dp),
        )
        StepButton("+", stringResource(R.string.larger)) { onDelta(1.0) }
    }
}

@Composable
private fun StepButton(glyph: String, label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Tooltip(label) {
        Box(
            modifier = Modifier.size(40.dp).clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = palette.text.toComposeColor(), fontSize = 20.sp)
        }
    }
}
