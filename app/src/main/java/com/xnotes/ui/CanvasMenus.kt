package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.xnotes.core.model.DrawStyle
import com.xnotes.core.model.Rgba
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

    /** The colour and width of every selected stroke/shape, for the restyle popup to open on. */
    fun selectionStyles(): List<DrawStyle>

    /**
     * Recolour and/or re-thicken the selection; a null [color] or [width] leaves that half alone.
     * A [preview] call skips history, and the next call without it records everything since as one
     * undo step, so dragging the thickness slider is a single edit rather than one per sample.
     */
    fun restyleSelection(color: Rgba?, width: Double?, preview: Boolean = false)

    /** The toolbar's ink swatches and recently picked colours, offered by the restyle popup. */
    val hostToolbarColors: List<Rgba>
    val hostRecentColors: List<Rgba>
}

/**
 * Floating action bar shown above a settled selection (spec-adjacent): delete,
 * cut, copy, bring-to-front, duplicate, and an overflow menu for the rest.
 * Hidden while moving/resizing. Rotation is not here: everything the bar can be
 * shown over turns by its own grip.
 */
@Composable
fun SelectionMenu(host: SelectionMenuHost) {
    val rect = host.selectionMenuRect ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    var overflowOpen by remember { mutableStateOf(false) }
    var styleOpen by remember { mutableStateOf(false) }

    val barHeightPx = with(density) { 48.dp.toPx() }
    val barWidthPx = with(density) { (6 * 46).dp.toPx() }
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
        Box {
            ActionIcon(XnotesIcons.more, "More") { overflowOpen = true }
            DropdownMenu(
                expanded = overflowOpen,
                onDismissRequest = { overflowOpen = false },
                properties = PopupProperties(focusable = false),
            ) {
                // Empty when nothing selected carries ink: an image or a text box has no
                // colour-and-width pair to restyle.
                val styles = host.selectionStyles()
                DropdownMenuItem(
                    text = { Text("Change style") },
                    enabled = styles.isNotEmpty(),
                    onClick = { overflowOpen = false; styleOpen = true },
                )
            }
            if (styleOpen) {
                // Closing settles any preview the slider left open.
                SelectionStylePopup(host) { host.restyleSelection(null, null); styleOpen = false }
            }
        }
    }
}

/**
 * "Change style" on a settled selection: the toolbar's swatches plus the full colour picker, and a
 * thickness slider. Both apply live, so the result can be judged on the page rather than guessed
 * at. The controls open on the styles the selection had when the popup did — a shared colour when
 * every item agrees, the first item's otherwise.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionStylePopup(host: SelectionMenuHost, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val opened = remember { host.selectionStyles() }
    val first = opened.firstOrNull()
    val shared = opened.firstOrNull()?.color?.takeIf { c -> opened.all { it.color == c } }
    var color by remember { mutableStateOf(shared ?: first?.color ?: Rgba(0, 0, 0)) }
    var width by remember { mutableStateOf((first?.width ?: 1.0).toFloat()) }
    if (first == null) return

    DropdownMenu(expanded = true, onDismissRequest = onDismiss, properties = PopupProperties(focusable = false)) {
        Column(Modifier.width(250.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle("CHANGE STYLE")
            StyleCaption("COLOUR")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                host.hostToolbarColors.forEach { c ->
                    ColorDot(c.toComposeColor(), selected = color == c) {
                        color = c
                        host.restyleSelection(c, null)
                    }
                }
                ColorPickerDot(
                    color,
                    custom = color !in host.hostToolbarColors,
                    onPick = { color = it; host.restyleSelection(it, null) },
                    dismissOnPick = false,
                ) { dismiss, pick ->
                    ColorPickerPopup(
                        initial = color,
                        recents = host.hostRecentColors,
                        onDismiss = dismiss,
                        onPick = pick,
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            // The drag previews live and commits on release, so it is one undo step, not fifty.
            SliderRow(
                "THICKNESS",
                width,
                DrawStyle.MIN_WIDTH.toFloat()..DrawStyle.MAX_WIDTH.toFloat(),
                onChangeFinished = { host.restyleSelection(null, null) },
            ) { w ->
                width = w
                host.restyleSelection(null, w.toDouble(), preview = true)
            }
            Text(
                "Applies to the selected strokes and shapes.",
                color = palette.textDim.toComposeColor(),
                fontSize = 11.sp,
            )
        }
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
