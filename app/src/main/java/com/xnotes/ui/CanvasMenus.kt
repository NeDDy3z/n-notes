package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * Floating action bar shown above a settled selection (spec-adjacent): delete,
 * cut, copy, bring-to-front, duplicate. Hidden while moving/resizing.
 */
@Composable
fun SelectionMenu(editor: Editor) {
    val rect = editor.selectionMenu ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current

    val barHeightPx = with(density) { 48.dp.toPx() }
    val barWidthPx = with(density) { ((if (editor.selectionIsImage) 6 else 5) * 46).dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val centerX = ((rect.left + rect.right) / 2.0).toFloat()
    val xPx = (centerX - barWidthPx / 2f).coerceAtLeast(with(density) { 8.dp.toPx() })
    val yPx = if (rect.top.toFloat() - barHeightPx - gap > 0f) {
        rect.top.toFloat() - barHeightPx - gap
    } else {
        rect.bottom.toFloat() + gap
    }
    val xDp = with(density) { xPx.toDp() }
    val yDp = with(density) { yPx.toDp() }

    Row(
        modifier = Modifier
            .offset(xDp, yDp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(10.dp)),
    ) {
        ActionIcon(XnotesIcons.trash, "Delete") { editor.deleteSelection() }
        ActionIcon(XnotesIcons.cut, "Cut") { editor.cutSelection() }
        ActionIcon(XnotesIcons.copy, "Copy") { editor.copySelection(); editor.dismissSelectionMenu() }
        ActionIcon(XnotesIcons.front, "Bring to front") { editor.bringToFront(); editor.dismissSelectionMenu() }
        ActionIcon(XnotesIcons.duplicate, "Duplicate") { editor.duplicateSelection() }
        if (editor.selectionIsImage) ActionIcon(XnotesIcons.rotate, "Rotate") { editor.rotateSelectedImage() }
    }
}

/**
 * The screenshot tool's floating action, shown above the frozen capture rectangle: a single
 * "Copy as image" button that renders the region and puts it on the system clipboard.
 */
@Composable
fun ScreenshotMenu(editor: Editor) {
    val rect = editor.screenshotMenu ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current

    val barHeightPx = with(density) { 44.dp.toPx() }
    val barWidthPx = with(density) { 170.dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val centerX = ((rect.left + rect.right) / 2.0).toFloat()
    val xPx = (centerX - barWidthPx / 2f).coerceAtLeast(with(density) { 8.dp.toPx() })
    val yPx = if (rect.top.toFloat() - barHeightPx - gap > 0f) {
        rect.top.toFloat() - barHeightPx - gap
    } else {
        rect.bottom.toFloat() + gap
    }
    Row(
        modifier = Modifier
            .offset(with(density) { xPx.toDp() }, with(density) { yPx.toDp() })
            .clip(RoundedCornerShape(10.dp))
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(10.dp))
            .clickable { editor.copyScreenshotAsImage() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            XnotesIcons.copy,
            contentDescription = "Copy as image",
            tint = palette.text.toComposeColor(),
            modifier = Modifier.size(20.dp),
        )
        Text(
            "Copy as image",
            color = palette.text.toComposeColor(),
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val tint = palette.text.toComposeColor().let { if (enabled) it else it.copy(alpha = 0.35f) }
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(46.dp)) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/**
 * Long-press paste menu on empty space: paste copied items or an image from the
 * system clipboard at the press point, or insert an image there.
 */
@Composable
fun LongPressMenu(editor: Editor, onInsertImageAt: (com.xnotes.core.geometry.Pt) -> Unit) {
    val target = editor.contextMenu ?: return
    val density = LocalDensity.current
    val xDp = with(density) { target.viewportX.toFloat().toDp() }
    val yDp = with(density) { target.viewportY.toFloat().toDp() }

    Box(modifier = Modifier.offset(xDp, yDp).size(1.dp)) {
        DropdownMenu(expanded = true, onDismissRequest = { editor.dismissContextMenu() }) {
            if (editor.hasClipboardItems) {
                DropdownMenuItem(text = { Text("Paste here") }, onClick = {
                    editor.pasteItemsAt(target.content); editor.dismissContextMenu()
                })
            }
            if (editor.clipboardHasImage) {
                DropdownMenuItem(text = { Text("Paste image") }, onClick = {
                    editor.pasteClipboardImageAt(target.content); editor.dismissContextMenu()
                })
            }
            DropdownMenuItem(text = { Text("Insert image…") }, onClick = {
                onInsertImageAt(target.content); editor.dismissContextMenu()
            })
        }
    }
}

/**
 * The flow-editing action bar (long press with the text tool, after the word
 * selection lands): a thin icon row like [SelectionMenu], anchored above the
 * selection so the drag handles stay visible. Not a popup: it never steals
 * focus (the keyboard stays up) and any canvas touch quietly retires it. The
 * paste icon expands the explicit paste modes (no auto-detection anywhere).
 */
@Composable
fun FlowEditMenu(editor: Editor) {
    val rect = editor.flowContextMenu ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val hasClip = editor.clipboardHasText()
    val hasSelection = editor.flowHasSelection
    var pasteOpen by remember { mutableStateOf(false) }

    val barHeightPx = with(density) { 48.dp.toPx() }
    val barWidthPx = with(density) { (4 * 46).dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    // When pushed below the selection, also clear the teardrop handles hanging there.
    val handleClearance = with(density) { (2 * com.xnotes.canvas.FlowTextController.HANDLE_RADIUS_DP).dp.toPx() }
    val centerX = ((rect.left + rect.right) / 2.0).toFloat()
    val xPx = (centerX - barWidthPx / 2f).coerceAtLeast(with(density) { 8.dp.toPx() })
    val yPx = if (rect.top.toFloat() - barHeightPx - gap > 0f) {
        rect.top.toFloat() - barHeightPx - gap
    } else {
        rect.bottom.toFloat() + handleClearance + gap
    }

    Row(
        modifier = Modifier
            .offset(with(density) { xPx.toDp() }, with(density) { yPx.toDp() })
            .clip(RoundedCornerShape(10.dp))
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(10.dp)),
    ) {
        ActionIcon(XnotesIcons.cut, "Cut", enabled = hasSelection) {
            editor.flowCut(); editor.dismissFlowContextMenu()
        }
        ActionIcon(XnotesIcons.copy, "Copy", enabled = hasSelection) {
            editor.flowCopy(); editor.dismissFlowContextMenu()
        }
        Box {
            ActionIcon(XnotesIcons.paste, "Paste", enabled = hasClip) { pasteOpen = true }
            DropdownMenu(
                expanded = pasteOpen,
                onDismissRequest = { pasteOpen = false },
                properties = PopupProperties(focusable = false),
            ) {
                DropdownMenuItem(text = { Text("Paste") }, onClick = {
                    pasteOpen = false; editor.pastePlainAtCaret(); editor.dismissFlowContextMenu()
                })
                DropdownMenuItem(text = { Text("Paste as Markdown") }, onClick = {
                    pasteOpen = false; editor.pasteMarkdownAtCaret(); editor.dismissFlowContextMenu()
                })
                DropdownMenuItem(text = { Text("Paste as Code") }, onClick = {
                    pasteOpen = false; editor.pasteAsCodeAtCaret(); editor.dismissFlowContextMenu()
                })
            }
        }
        ActionIcon(XnotesIcons.trash, "Delete", enabled = hasSelection) {
            editor.flowDeleteSelection(); editor.dismissFlowContextMenu()
        }
    }
}
