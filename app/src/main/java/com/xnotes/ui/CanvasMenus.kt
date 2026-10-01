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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridOff
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.xnotes.R
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

    /** Pin the selection where it is and put it away; a held finger over it offers to release it. */
    fun lockSelection()

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
            .clip(MaterialTheme.shapes.medium)
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium),
    ) {
        ActionIcon(XnotesIcons.trash, stringResource(R.string.delete)) { host.deleteSelection() }
        ActionIcon(XnotesIcons.cut, stringResource(R.string.cut)) { host.cutSelection() }
        ActionIcon(XnotesIcons.copy, stringResource(R.string.copy)) { host.copySelection(); host.dismissSelectionMenu() }
        ActionIcon(XnotesIcons.front, stringResource(R.string.bring_to_front)) { host.bringToFront(); host.dismissSelectionMenu() }
        ActionIcon(XnotesIcons.duplicate, stringResource(R.string.duplicate)) { host.duplicateSelection() }
        Box {
            ActionIcon(XnotesIcons.more, stringResource(R.string.more)) { overflowOpen = true }
            DropdownMenu(
                expanded = overflowOpen,
                onDismissRequest = { overflowOpen = false },
                properties = PopupProperties(focusable = false),
            ) {
                // Empty when nothing selected carries ink: an image or a text box has no
                // colour-and-width pair to restyle.
                val styles = host.selectionStyles()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.change_style)) },
                    enabled = styles.isNotEmpty(),
                    onClick = { overflowOpen = false; styleOpen = true },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.lock)) },
                    onClick = { overflowOpen = false; host.lockSelection() },
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
            PopupTitle(stringResource(R.string.title_change_style))
            StyleCaption(stringResource(R.string.caption_colour))
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
                stringResource(R.string.caption_thickness),
                width,
                DrawStyle.MIN_WIDTH.toFloat()..DrawStyle.MAX_WIDTH.toFloat(),
                onChangeFinished = { host.restyleSelection(null, null) },
            ) { w ->
                width = w
                host.restyleSelection(null, w.toDouble(), preview = true)
            }
            Text(
                stringResource(R.string.change_style_hint),
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
            .clip(MaterialTheme.shapes.medium)
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium)
            .clickable { editor.copyScreenshotAsImage() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            XnotesIcons.copy,
            contentDescription = stringResource(R.string.copy_as_image),
            tint = palette.text.toComposeColor(),
            modifier = Modifier.size(20.dp),
        )
        Text(
            stringResource(R.string.copy_as_image),
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

    /** Release [item], so it can be selected again. */
    fun unlockItem(item: com.xnotes.core.model.CanvasItem)
}

/**
 * Long-press menu: paste copied items or an image from the system clipboard at the press point, or
 * insert an image there. A press that landed on a locked item offers only to release it, since
 * nothing else can be done with one and there is no other way back.
 */
@Composable
fun LongPressMenu(host: LongPressMenuHost, onInsertImageAt: (com.xnotes.core.geometry.Pt) -> Unit) {
    val target = host.contextMenu ?: return
    val density = LocalDensity.current
    val xDp = with(density) { target.viewportX.toFloat().toDp() }
    val yDp = with(density) { target.viewportY.toFloat().toDp() }

    Box(modifier = Modifier.offset(xDp, yDp).size(1.dp)) {
        DropdownMenu(expanded = true, onDismissRequest = { host.dismissContextMenu() }) {
            val locked = target.locked
            if (locked != null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.unlock)) }, onClick = {
                    host.unlockItem(locked); host.dismissContextMenu()
                })
                return@DropdownMenu
            }
            if (host.hasClipboardItems) {
                DropdownMenuItem(text = { Text(stringResource(R.string.paste_here)) }, onClick = {
                    host.pasteItemsAt(target.content); host.dismissContextMenu()
                })
            }
            if (host.clipboardHasImage) {
                DropdownMenuItem(text = { Text(stringResource(R.string.paste_image)) }, onClick = {
                    host.pasteClipboardImageAt(target.content); host.dismissContextMenu()
                })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.insert_image_ellipsis)) }, onClick = {
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
    val handleClearance = with(density) { (2 * com.xnotes.canvas.TextHandles.RADIUS_DP).dp.toPx() }
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
            .clip(MaterialTheme.shapes.medium)
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium),
    ) {
        ActionIcon(XnotesIcons.cut, stringResource(R.string.cut), enabled = hasSelection) {
            editor.flowCut(); editor.dismissFlowContextMenu()
        }
        ActionIcon(XnotesIcons.copy, stringResource(R.string.copy), enabled = hasSelection) {
            editor.flowCopy(); editor.dismissFlowContextMenu()
        }
        Box {
            ActionIcon(XnotesIcons.paste, stringResource(R.string.paste), enabled = hasClip) { pasteOpen = true }
            DropdownMenu(
                expanded = pasteOpen,
                onDismissRequest = { pasteOpen = false },
                properties = PopupProperties(focusable = false),
            ) {
                DropdownMenuItem(text = { Text(stringResource(R.string.paste)) }, onClick = {
                    pasteOpen = false; editor.pastePlainAtCaret(); editor.dismissFlowContextMenu()
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.paste_markdown)) }, onClick = {
                    pasteOpen = false; editor.pasteMarkdownAtCaret(); editor.dismissFlowContextMenu()
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.paste_code)) }, onClick = {
                    pasteOpen = false; editor.pasteAsCodeAtCaret(); editor.dismissFlowContextMenu()
                })
            }
        }
        ActionIcon(XnotesIcons.trash, stringResource(R.string.delete), enabled = hasSelection) {
            editor.flowDeleteSelection(); editor.dismissFlowContextMenu()
        }
    }
}

/**
 * The PDF text selection's bar: Copy, the four marks (made in the active ink colour), and behind
 * the overflow the apps that act on selected text ("Translate", "Search" and the like). Never takes
 * focus; a canvas touch hides it until the selection, or the view under it, settles again.
 */
@Composable
fun PdfSelectionMenu(editor: Editor) {
    val rect = editor.pdfTextMenu ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val actions = remember { editor.pdfTextActions() }
    var overflowOpen by remember { mutableStateOf(false) }

    val barHeightPx = with(density) { 48.dp.toPx() }
    val barWidthPx = with(density) { ((1 + PDF_MARKS.size + if (actions.isEmpty()) 0 else 1) * 46).dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val margin = with(density) { 8.dp.toPx() }
    // When pushed below the selection, also clear the teardrop handles hanging there.
    val handleClearance = with(density) { (2 * com.xnotes.canvas.TextHandles.RADIUS_DP).dp.toPx() }
    val centerX = ((rect.left + rect.right) / 2.0).toFloat()
    val xPx = (centerX - barWidthPx / 2f).coerceAtLeast(margin)
    val above = rect.top.toFloat() - barHeightPx - gap
    val below = rect.bottom.toFloat() + handleClearance + gap
    // A selection taller than the view leaves no room either side: keep the bar on screen.
    val maxY = (editor.state.viewportH - barHeightPx - margin).coerceAtLeast(margin)
    val yPx = (if (above > 0f) above else below).coerceIn(margin, maxY)

    Row(
        modifier = Modifier
            .offset(with(density) { xPx.toDp() }, with(density) { yPx.toDp() })
            .clip(MaterialTheme.shapes.medium)
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium),
    ) {
        ActionIcon(XnotesIcons.copy, stringResource(R.string.copy)) { editor.copyPdfText() }
        for ((type, icon, label) in PDF_MARKS) {
            ActionIcon(icon, stringResource(label)) { editor.markPdfSelection(type) }
        }
        if (actions.isNotEmpty()) {
            Box {
                ActionIcon(XnotesIcons.more, stringResource(R.string.more)) { overflowOpen = true }
                DropdownMenu(
                    expanded = overflowOpen,
                    onDismissRequest = { overflowOpen = false },
                    properties = PopupProperties(focusable = false),
                ) {
                    for ((label, app) in actions) {
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            overflowOpen = false
                            editor.processPdfText(app)
                        })
                    }
                }
            }
        }
    }
}

/**
 * A markup's tap menu: its colour and kind (each a small list), its note, Copy (the text it marks),
 * Delete, and Open link when the tap was on a link. Placed like the selection bar, by the markup.
 */
@Composable
fun MarkupMenu(editor: Editor) {
    val menu = editor.markupMenu ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    var colorsOpen by remember { mutableStateOf(false) }
    var kindsOpen by remember { mutableStateOf(false) }
    val icons = 5 + if (menu.openLink != null) 1 else 0
    val barHeightPx = with(density) { 48.dp.toPx() }
    val barWidthPx = with(density) { (icons * 46).dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val margin = with(density) { 8.dp.toPx() }
    val rect = menu.anchor
    val xPx = (((rect.left + rect.right) / 2.0).toFloat() - barWidthPx / 2f).coerceAtLeast(margin)
    val above = rect.top.toFloat() - barHeightPx - gap
    val maxY = (editor.state.viewportH - barHeightPx - margin).coerceAtLeast(margin)
    val yPx = (if (above > 0f) above else rect.bottom.toFloat() + gap).coerceIn(margin, maxY)
    val m = menu.markup

    Row(
        modifier = Modifier
            .offset(with(density) { xPx.toDp() }, with(density) { yPx.toDp() })
            .clip(MaterialTheme.shapes.medium)
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            val colourLabel = stringResource(R.string.markup_colour)
            IconButton(onClick = { colorsOpen = true }, modifier = Modifier.size(46.dp).semantics { contentDescription = colourLabel }) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(m.color.toComposeColor()))
            }
            DropdownMenu(expanded = colorsOpen, onDismissRequest = { colorsOpen = false }, properties = PopupProperties(focusable = false)) {
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    editor.toolbarColors.take(editor.toolbarColorCount).forEach { c ->
                        MarkupDot(c.toComposeColor(), active = c.r == m.color.r && c.g == m.color.g && c.b == m.color.b) {
                            colorsOpen = false
                            editor.recolorMarkup(c)
                        }
                    }
                }
            }
        }
        Box {
            ActionIcon(markIcon(m.type), stringResource(R.string.markup_kind)) { kindsOpen = true }
            DropdownMenu(expanded = kindsOpen, onDismissRequest = { kindsOpen = false }, properties = PopupProperties(focusable = false)) {
                for ((type, icon, label) in PDF_MARKS) {
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = {
                            kindsOpen = false
                            if (type != m.type) editor.retypeMarkup(type)
                        },
                    )
                }
            }
        }
        ActionIcon(XnotesIcons.note, stringResource(R.string.markup_note)) { editor.editMarkupNote() }
        ActionIcon(XnotesIcons.copy, stringResource(R.string.copy)) { editor.copyMarkupText() }
        ActionIcon(XnotesIcons.trash, stringResource(R.string.delete)) { editor.deleteMarkup() }
        if (menu.openLink != null) ActionIcon(XnotesIcons.link, stringResource(R.string.open_link)) { editor.openMarkupLink() }
    }
}

/** A markup's note to read or write: plain text, kept on Save, dropped when emptied or deleted. */
@Composable
fun MarkupNoteDialog(editor: Editor) {
    val target = editor.markupNote ?: return
    var text by remember(target) { mutableStateOf(target.markup.note.orEmpty()) }
    AlertDialog(
        onDismissRequest = { editor.dismissMarkupNote() },
        title = { Text(stringResource(R.string.markup_note)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.markup_note_hint)) },
                minLines = 3,
                maxLines = 10,
            )
        },
        confirmButton = { TextButton(onClick = { editor.saveMarkupNote(text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                if (target.markup.note != null) {
                    TextButton(onClick = { editor.saveMarkupNote("") }) { Text(stringResource(R.string.delete)) }
                }
                TextButton(onClick = { editor.dismissMarkupNote() }) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun MarkupDot(color: Color, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .then(if (active) Modifier.border(2.dp, color, CircleShape) else Modifier)
            .padding(5.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
    )
}

private fun markIcon(type: com.xnotes.core.model.MarkupType): ImageVector = PDF_MARKS.first { it.first == type }.second

/** The selection bar's marks, in the order the markup tool's popup lists them. */
private val PDF_MARKS = listOf(
    Triple(com.xnotes.core.model.MarkupType.HIGHLIGHT, XnotesIcons.highlight, R.string.markup_highlight),
    Triple(com.xnotes.core.model.MarkupType.UNDERLINE, XnotesIcons.underline, R.string.underline),
    Triple(com.xnotes.core.model.MarkupType.STRIKEOUT, XnotesIcons.strikethrough, R.string.strikethrough),
    Triple(com.xnotes.core.model.MarkupType.SQUIGGLY, XnotesIcons.squiggly, R.string.markup_squiggly),
)

private const val TABLE_BAR_ICONS = 4

/**
 * Where the table bar sits in viewport px: centred over the top of the table's
 * first slice and kept on screen (it overlaps the table rather than leave the
 * top of the view). Null when the table is not laid out.
 */
private fun tableBarRect(editor: Editor, table: com.xnotes.core.text.FlowTable, density: androidx.compose.ui.unit.Density): androidx.compose.ui.geometry.Rect? {
    editor.tableChromeTick
    val (pi, frag) = editor.tableFrags(table).firstOrNull() ?: return null
    val t = editor.pageRectToViewport(pi, frag.rect) ?: return null
    val w = with(density) { (TABLE_BAR_ICONS * 46).dp.toPx() }
    val h = with(density) { 48.dp.toPx() }
    val margin = with(density) { 8.dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val x = (((t.left + t.right) / 2.0).toFloat() - w / 2f).coerceAtLeast(margin)
    val y = (t.top.toFloat() - h - gap).coerceAtLeast(margin)
    return androidx.compose.ui.geometry.Rect(x, y, x + w, y + h)
}

/**
 * The table's action bar: edit its structure, fit its columns (the magic wand),
 * restyle it, delete it. Opens the moment a long press holds the table itself
 * (a rule, padding, empty cell space), above the table whatever part was
 * pressed. Like the text bar it never takes focus; the next canvas touch
 * retires it.
 */
@Composable
fun FlowTableMenu(editor: Editor) {
    val table = editor.tableMenu ?: return
    if (editor.editingTable != null) return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val bar = tableBarRect(editor, table, density) ?: return
    Row(
        modifier = Modifier
            .offset(with(density) { bar.left.toDp() }, with(density) { bar.top.toDp() })
            .clip(MaterialTheme.shapes.medium)
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium),
    ) {
        ActionIcon(XnotesIcons.tableEdit, stringResource(R.string.edit_table)) { editor.startTableEdit(table) }
        ActionIcon(XnotesIcons.magicWand, stringResource(R.string.fit_columns)) { editor.tableAutoFit(table) }
        ActionIcon(Icons.Outlined.TableChart, stringResource(R.string.table_style)) { editor.openTableStyle(table) }
        ActionIcon(Icons.Outlined.GridOff, stringResource(R.string.delete_table)) { editor.tableDelete(table) }
    }
}
