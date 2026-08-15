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
 * What the selection menu needs from whichever editor is open.
 *
 * The bar is the same bar on either canvas, so it is the same composable rather than a second one
 * that resembles it. Taking the few members it reads through an interface is what makes "identical"
 * a property of the code rather than something to keep checking, exactly as [ToolPopupHost] does
 * for the tool popups.
 */
interface SelectionMenuHost {
    /** Where the settled selection sits in viewport pixels, or null to hide the bar. */
    val selectionMenuRect: com.xnotes.core.geometry.Rect?

    fun deleteSelection()
    fun cutSelection()
    fun copySelection()
    fun bringToFront()
    fun duplicateSelection()
    fun dismissSelectionMenu()
}

/**
 * Floating action bar shown above a settled selection (spec-adjacent): delete,
 * cut, copy, bring-to-front, duplicate. Hidden while moving/resizing. Rotation
 * is not here: everything the bar can be shown over turns by its own grip.
 */
@Composable
fun SelectionMenu(host: SelectionMenuHost) {
    val rect = host.selectionMenuRect ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current

    val barHeightPx = with(density) { 48.dp.toPx() }
    val barWidthPx = with(density) { (5 * 46).dp.toPx() }
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
        ActionIcon(XnotesIcons.trash, "Delete") { host.deleteSelection() }
        ActionIcon(XnotesIcons.cut, "Cut") { host.cutSelection() }
        ActionIcon(XnotesIcons.copy, "Copy") { host.copySelection(); host.dismissSelectionMenu() }
        ActionIcon(XnotesIcons.front, "Bring to front") { host.bringToFront(); host.dismissSelectionMenu() }
        ActionIcon(XnotesIcons.duplicate, "Duplicate") { host.duplicateSelection() }
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
 * What the long-press menu needs from whichever editor is open. Same reasoning as
 * [SelectionMenuHost]: one menu, taken through an interface, rather than two that resemble each
 * other and drift.
 */
interface LongPressMenuHost {
    /** Where the press landed, or null when no menu is open. */
    val contextMenu: ContextMenuTarget?

    val hasClipboardItems: Boolean
    val clipboardHasImage: Boolean

    fun pasteItemsAt(content: com.xnotes.core.geometry.Pt)
    fun pasteClipboardImageAt(content: com.xnotes.core.geometry.Pt)
    fun dismissContextMenu()
}

/**
 * Long-press paste menu on empty space: paste copied items or an image from the
 * system clipboard at the press point, or insert an image there.
 */
@Composable
fun LongPressMenu(host: LongPressMenuHost, onInsertImageAt: (com.xnotes.core.geometry.Pt) -> Unit) {
    val target = host.contextMenu ?: return
    val density = LocalDensity.current
    val xDp = with(density) { target.viewportX.toFloat().toDp() }
    val yDp = with(density) { target.viewportY.toFloat().toDp() }

    Box(modifier = Modifier.offset(xDp, yDp).size(1.dp)) {
        DropdownMenu(expanded = true, onDismissRequest = { host.dismissContextMenu() }) {
            if (host.hasClipboardItems) {
                DropdownMenuItem(text = { Text("Paste here") }, onClick = {
                    host.pasteItemsAt(target.content); host.dismissContextMenu()
                })
            }
            if (host.clipboardHasImage) {
                DropdownMenuItem(text = { Text("Paste image") }, onClick = {
                    host.pasteClipboardImageAt(target.content); host.dismissContextMenu()
                })
            }
            DropdownMenuItem(text = { Text("Insert image…") }, onClick = {
                onInsertImageAt(target.content); host.dismissContextMenu()
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
