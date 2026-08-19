package com.xnotes.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.platform.ImageDecoder
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun Toolbar(
    editor: Editor,
    onToggleFullscreen: () -> Unit,
    onOpenBackstage: () -> Unit,
    onInsertImage: () -> Unit,
    onAddStickers: () -> Unit,
    onPresent: () -> Unit,
    modifier: Modifier = Modifier,
    onClosePane: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    // The five stroke tools use the designed vector drawables (res/drawable/ic_stroke_*),
    // tinted at the call site like every other icon; the rest use the built-in line set.
    val toolIcons: Map<Tool, ImageVector> = mapOf(
        Tool.PEN to ImageVector.vectorResource(R.drawable.ic_stroke_regular),
        Tool.DASHED to ImageVector.vectorResource(R.drawable.ic_stroke_dashed),
        Tool.CALLIGRAPHY to ImageVector.vectorResource(R.drawable.ic_stroke_calligraphy),
        Tool.SPEED to ImageVector.vectorResource(R.drawable.ic_stroke_speed),
        Tool.TAPER to ImageVector.vectorResource(R.drawable.ic_stroke_taper),
        Tool.HIGHLIGHTER to ImageVector.vectorResource(R.drawable.ic_stroke_highlighter),
        Tool.ERASER to XnotesIcons.eraser,
        Tool.PAN to XnotesIcons.pan,
        Tool.SELECT to XnotesIcons.select,
        Tool.LASSO to XnotesIcons.lasso,
        Tool.SCREENSHOT to XnotesIcons.scissors,
        Tool.SHAPE to XnotesIcons.shape,
        Tool.TEXT to XnotesIcons.text,
        Tool.TEXT_BOX to XnotesIcons.textBox,
    )
    var configForTool by remember { mutableStateOf<Tool?>(null) }
    var switcherIndex by remember { mutableStateOf<Int?>(null) }
    var renaming by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth().height(50.dp).background(palette.panel.toComposeColor()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The bar is driven by the user-customisable layout; separators sit between non-empty
            // sections, and each item dispatches to its renderer (see ToolbarItemView).
            editor.toolbarLayout.visibleSections.forEachIndexed { si, section ->
                if (si > 0) Separator()
                section.visibleEntries.forEach { entry ->
                    ToolbarItemView(
                        editor = editor,
                        item = entry.item,
                        toolIcons = toolIcons,
                        configForTool = configForTool,
                        setConfigForTool = { configForTool = it },
                        switcherIndex = switcherIndex,
                        setSwitcherIndex = { switcherIndex = it },
                        onRename = { renaming = true },
                        onOpenBackstage = onOpenBackstage,
                        onInsertImage = onInsertImage,
                        onAddStickers = onAddStickers,
                        onPresent = onPresent,
                        onToggleFullscreen = onToggleFullscreen,
                    )
                }
            }
        }
        // Pinned outside the scrolling row so closing a split pane is always one tap away.
        onClosePane?.let { ClosePaneButton(it) }
    }

    if (renaming) {
        RenameDialog(
            initial = editor.title,
            onConfirm = { name ->
                renaming = false
                if (!editor.renameCurrentDocument(name)) editor.message = "Couldn’t rename the note."
            },
            onDismiss = { renaming = false },
        )
    }
}

/** Renders one toolbar item by id, reusing the same controls/popups the bar has always used. */
@Composable
private fun ToolbarItemView(
    editor: Editor,
    item: ToolbarItem,
    toolIcons: Map<Tool, ImageVector>,
    configForTool: Tool?,
    setConfigForTool: (Tool?) -> Unit,
    switcherIndex: Int?,
    setSwitcherIndex: (Int?) -> Unit,
    onRename: () -> Unit,
    onOpenBackstage: () -> Unit,
    onInsertImage: () -> Unit,
    onAddStickers: () -> Unit,
    onPresent: () -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    when (item) {
        // Canvas-only items; a stored paged layout can never hold one, so nothing is drawn.
        ToolbarItem.WAYPOINTS, ToolbarItem.MINIMAP -> Unit

        ToolbarItem.HOME -> ToolbarIcon(XnotesIcons.prev, "Home") { onOpenBackstage() }
        ToolbarItem.TITLE -> Label(
            editor.title,
            modifier = Modifier
                .widthIn(max = 160.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable { onRename() },
        )
        ToolbarItem.SIDEBAR ->
            ToolbarIcon(XnotesIcons.sidebar, "Side panel", active = editor.sidebarVisible) { editor.toggleSidebar() }

        ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.CALLIGRAPHY, ToolbarItem.SPEED,
        ToolbarItem.TAPER, ToolbarItem.HIGHLIGHTER, ToolbarItem.ERASER, ToolbarItem.PAN,
        ToolbarItem.SELECT, ToolbarItem.LASSO, ToolbarItem.SCREENSHOT, ToolbarItem.SHAPE,
        ToolbarItem.TEXT, ToolbarItem.TEXT_BOX -> {
            val tool = Tool.fromId(item.id)
            if (tool != null) ToolButton(editor, tool, toolIcons[tool], configForTool, setConfigForTool)
        }

        ToolbarItem.WAND ->
            ToolbarIcon(XnotesIcons.magicWand, "Disappearing ink", active = editor.wandEnabled) { editor.toggleWand() }
        ToolbarItem.RULER ->
            ToolbarIcon(XnotesIcons.ruler, "Ruler", active = editor.rulerVisible) { editor.toggleRuler() }

        ToolbarItem.IMAGE -> ImageMenu(editor, onInsertImage, onAddStickers)

        ToolbarItem.UNDO -> ToolbarIcon(XnotesIcons.undo, "Undo", enabled = editor.canUndo) { editor.undo() }
        ToolbarItem.REDO -> ToolbarIcon(XnotesIcons.redo, "Redo", enabled = editor.canRedo) { editor.redo() }

        ToolbarItem.PAGE_NAV -> {
            ToolbarIcon(XnotesIcons.prev, "Previous page") { editor.prevPage() }
            var jumpOpen by remember { mutableStateOf(false) }
            Box {
                Label(
                    "${editor.pageIndex + 1} / ${editor.pageCount}",
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { jumpOpen = true },
                )
                if (jumpOpen) PageJumpPopup(editor) { jumpOpen = false }
            }
            ToolbarIcon(XnotesIcons.next, "Next page") { editor.nextPage() }
        }
        ToolbarItem.PAGE_MENU -> PageMenu(editor)
        ToolbarItem.STYLES -> StylesButton(editor)
        ToolbarItem.MARGINS -> MarginsButton(editor)
        ToolbarItem.VIEW -> ViewButton(editor)

        ToolbarItem.ZOOM -> {
            ToolbarIcon(XnotesIcons.zoomOut, "Zoom out", enabled = !editor.zoomLocked) { editor.zoomOut() }
            var zoomMenuOpen by remember { mutableStateOf(false) }
            Box {
                Label(
                    "${editor.zoomPercent}%",
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { zoomMenuOpen = true },
                )
                if (zoomMenuOpen) ZoomMenuPopup(editor) { zoomMenuOpen = false }
            }
            ToolbarIcon(XnotesIcons.zoomIn, "Zoom in", enabled = !editor.zoomLocked) { editor.zoomIn() }
        }
        ToolbarItem.FIT -> FitMenu(editor)
        ToolbarItem.ZOOM_LOCK -> ToolbarIcon(
            if (editor.zoomLocked) XnotesIcons.lock else XnotesIcons.unlock,
            "Zoom lock",
            active = editor.zoomLocked,
        ) { editor.toggleZoomLock() }

        ToolbarItem.FULLSCREEN -> ToolbarIcon(XnotesIcons.fullscreen, "Full screen") { onToggleFullscreen() }
        ToolbarItem.PRESENT ->
            ToolbarIcon(XnotesIcons.present, "Present", active = editor.presentationRunning) { onPresent() }

        ToolbarItem.COLORS -> editor.toolbarColors.take(editor.toolbarColorCount).forEachIndexed { i, color ->
            Box {
                Swatch(
                    color = color.toComposeColor(),
                    active = i == editor.activeColorIndex,
                    onClick = { if (i == editor.activeColorIndex) setSwitcherIndex(i) else editor.pickColor(i) },
                )
                if (switcherIndex == i) ColorSwitcherPopup(editor, i) { setSwitcherIndex(null) }
            }
        }
    }
}

/** A stroke/edit tool button: arms the tool, and re-clicking an armed config tool opens its popup. */
@Composable
private fun ToolButton(
    editor: Editor,
    tool: Tool,
    icon: ImageVector?,
    configForTool: Tool?,
    setConfigForTool: (Tool?) -> Unit,
) {
    if (icon == null) return
    Box {
        ToolbarIcon(icon, tool.name, active = editor.tool == tool) {
            if (editor.tool == tool && (tool.isStroke || tool == Tool.SHAPE || tool == Tool.ERASER || tool == Tool.SELECT || tool == Tool.TEXT)) {
                setConfigForTool(tool)
            } else {
                editor.selectTool(tool)
                setConfigForTool(null)
            }
        }
        if (configForTool == tool) {
            when {
                tool == Tool.SHAPE -> ShapeConfigPopup(editor) { setConfigForTool(null) }
                tool == Tool.ERASER -> EraserConfigPopup(editor) { setConfigForTool(null) }
                tool == Tool.SELECT -> SelectConfigPopup(editor) { setConfigForTool(null) }
                tool == Tool.TEXT -> TextToolConfigPopup(editor) { setConfigForTool(null) }
                else -> ToolConfigPopup(editor, tool) { setConfigForTool(null) }
            }
        }
    }
}

/** Renames the open note: a small prefilled text field; the ".xnote" suffix is implicit. */
@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename note") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isBlank()) onDismiss() else onConfirm(text) }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = LocalPalette.current.menuBg.toComposeColor(),
    )
}

/** Opens the page-styles popup (paper colour + ruling) for the document and the current page. */
@Composable
private fun StylesButton(editor: Editor) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolbarIcon(XnotesIcons.sliders, "Styles") { open = true }
        if (open) StylesPopup(editor) { open = false }
    }
}

/** Opens the page-margins popup (extra paper on any edge, for the document or the current page). */
@Composable
private fun MarginsButton(editor: Editor) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolbarIcon(XnotesIcons.margins, "Margins") { open = true }
        if (open) MarginsPopup(editor) { open = false }
    }
}

/** Opens the view menu (viewing mode, scroll direction, PDF filters, rotation, scrollbar). */
@Composable
private fun ViewButton(editor: Editor) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolbarIcon(XnotesIcons.view, "View") { open = true }
        if (open) ViewMenuPopup(editor) { open = false }
    }
}

@Composable
internal fun ToolbarIcon(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    // Material tones can sit too close to the idle grey for an accent tint to read as
    // "selected", so the material chrome marks active icons with a filled accent disc
    // (the icon flipped to the on-accent colour) instead of a tint swap.
    val filled = active && palette.isMaterial
    val tint = when {
        !enabled -> com.xnotes.ui.theme.Palette.DISABLED_ICON.toComposeColor()
        filled -> palette.bg.toComposeColor()
        active -> palette.accent.toComposeColor()
        else -> palette.textDim.toComposeColor()
    }
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(42.dp)) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (filled) palette.accent.toComposeColor() else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}

/** Closes this pane of a split, leaving the other one to fill the window. Shown on both toolbars. */
@Composable
internal fun ClosePaneButton(onClose: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .width(1.dp)
            .height(26.dp)
            .background(palette.border.toComposeColor()),
    )
    ToolbarIcon(XnotesIcons.close, "Close this pane", onClick = onClose)
}

@Composable
internal fun Swatch(color: androidx.compose.ui.graphics.Color, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .size(28.dp)
            // The selection ring takes the swatch's own colour, not the theme accent.
            .then(if (active) Modifier.border(2.dp, color, CircleShape) else Modifier)
            .padding(4.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
    )
}

@Composable
internal fun Label(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = LocalPalette.current.textDim.toComposeColor(),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

@Composable
internal fun Separator() {
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(26.dp)
            .background(LocalPalette.current.border.toComposeColor()),
    )
}

@Composable
private fun ImageMenu(editor: Editor, onInsertImage: () -> Unit, onAddStickers: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var stickersOpen by remember { mutableStateOf(false) }
    Box {
        ToolbarIcon(XnotesIcons.image, "Image") { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Paste image") }, onClick = { editor.pasteImage(); expanded = false })
            DropdownMenuItem(text = { Text("Insert image…") }, onClick = { onInsertImage(); expanded = false })
            DropdownMenuItem(text = { Text("Stickers") }, onClick = { expanded = false; stickersOpen = true })
        }
        if (stickersOpen) StickersMenu(editor, onAddStickers) { stickersOpen = false }
    }
}

/**
 * The sticker library popup: a grid of saved images that insert with one tap, so a
 * recurring image never needs the gallery round trip. Stickers live on disk (see
 * [Editor.stickers]); each tile decodes its own small preview off the main thread.
 */
@Composable
private fun StickersMenu(editor: Editor, onAddStickers: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Add stickers…") },
            leadingIcon = { Icon(XnotesIcons.plus, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = onAddStickers,
        )
        if (editor.stickers.isEmpty()) {
            Text(
                "No stickers yet.",
                color = palette.textDim.toComposeColor(),
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        } else {
            // Both dimensions must be fixed: the menu measures its content by intrinsics, which a
            // lazy grid cannot answer (it crashes) — a fixed size short-circuits the query.
            val rows = ((editor.stickers.size + 2) / 3).coerceAtMost(3)
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .width(72.dp * 3 + 6.dp * 2)
                    .height(72.dp * rows + 6.dp * (rows - 1)),
            ) {
                items(editor.stickers, key = { it.name }) { file ->
                    StickerTile(
                        file = file,
                        onInsert = { editor.insertSticker(file); onDismiss() },
                        onRemove = { editor.removeSticker(file) },
                    )
                }
            }
            Text(
                "Tap to insert, hold to remove",
                color = palette.textDim.toComposeColor(),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerTile(file: java.io.File, onInsert: () -> Unit, onRemove: () -> Unit) {
    val palette = LocalPalette.current
    val thumbPx = with(LocalDensity.current) { 72.dp.roundToPx() }
    val thumb by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            ImageDecoder.decodeSampledFile(file.path, thumbPx, thumbPx)?.asImageBitmap()
        }
    }
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(6.dp))
            .combinedClickable(onClick = onInsert, onLongClick = onRemove),
        contentAlignment = Alignment.Center,
    ) {
        thumb?.let {
            Image(
                bitmap = it,
                contentDescription = "Sticker",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(3.dp),
            )
        }
    }
}

@Composable
private fun PageMenu(editor: Editor) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolbarIcon(XnotesIcons.page, "Pages") { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Add page") }, onClick = { editor.addPage(); expanded = false })
            DropdownMenuItem(text = { Text("Delete current page") }, onClick = { editor.deleteCurrentPage(); expanded = false })
        }
    }
}

@Composable
private fun FitMenu(editor: Editor) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolbarIcon(XnotesIcons.fit, "Fit") { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Fit whole page") }, onClick = { editor.fitPage(); expanded = false })
            DropdownMenuItem(text = { Text("Fit page width") }, onClick = { editor.fitWidth(); expanded = false })
            DropdownMenuItem(text = { Text("Fit page height") }, onClick = { editor.fitHeight(); expanded = false })
        }
    }
}

