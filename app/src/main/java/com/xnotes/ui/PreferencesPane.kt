package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.util.NameTemplate
import com.xnotes.settings.Preferences
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.ColorMath
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.delay

private val accentPresets = listOf(
    Rgba(0, 230, 118), Rgba(255, 138, 30), Rgba(255, 77, 77), Rgba(255, 210, 30),
)
/** The classic Material accents (500 series), offered as seeds for the material palette. */
private val materialSeedPresets = listOf(
    Rgba(244, 67, 54),   // red
    Rgba(233, 30, 99),   // pink
    Rgba(156, 39, 176),  // purple
    Rgba(63, 81, 181),   // indigo
    Rgba(33, 150, 243),  // blue
    Rgba(0, 150, 136),   // teal
    Rgba(76, 175, 80),   // green
    Rgba(255, 193, 7),   // amber
)
internal val pageColorPresets = listOf(
    Rgba(22, 22, 22), Rgba(13, 13, 13), Rgba(255, 255, 255), Rgba(247, 243, 233), Rgba(232, 232, 232),
)
/** One-tap filename templates offered beside the free-text field. */
private val NAME_TEMPLATE_PRESETS = listOf(
    NameTemplate.DEFAULT,
    "note_YYYY-MM-DD",
    "note_YYYY-MM-DD_HH-mm",
)
private val penButtonOptions = listOf("eraser" to "Eraser", "pan" to "Pan", "select" to "Select", "none" to "None")
private val tapGestureOptions = listOf(
    "none" to "None",
    "undo" to "Undo",
    "redo" to "Redo",
    "toggle_pan" to "Toggle pan",
    "toggle_eraser" to "Toggle eraser",
    "toggle_previous" to "Toggle previous tool",
)

/**
 * Preferences as a backstage pane (spec 10 §8). Edits apply live — each change is
 * pushed straight to the [Editor] (and persisted), so theme/page tweaks are seen
 * immediately, including in the surrounding backstage.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreferencesPane(
    editor: Editor,
    compact: Boolean,
    sidebarOpen: Boolean,
    onShowSidebar: () -> Unit,
    onBackToHome: () -> Unit,
    onImportCodeTheme: () -> Unit = {},
    onImportFont: () -> Unit = {},
) {
    val palette = LocalPalette.current
    val focusManager = LocalFocusManager.current
    var prefs by remember { mutableStateOf(editor.preferences) }
    fun update(p: Preferences) {
        prefs = p
        editor.applyPreferences(p)
    }
    // Follow out-of-pane preference changes too (the .scm import round-trips a picker).
    LaunchedEffect(editor.prefsVersion) { prefs = editor.preferences }

    val scrollState = rememberScrollState()
    // Which bar the toolbar section is arranging. The two are separate layouts, so the drag state
    // below always belongs to whichever is on screen.
    var canvasTab by remember { mutableStateOf(false) }
    val layout = if (canvasTab) editor.canvasToolbarLayout else editor.toolbarLayout
    // Toolbar drag state, hoisted here so the floating "ghost" chip can be drawn at the pane
    // root (above and outside the scroll, so it is never clipped). Bounds are plain maps read
    // only at drag time; observable state is just the dragged item, finger, and drop slot.
    var dragItem by remember { mutableStateOf<ToolbarItem?>(null) }
    var dragFinger by remember { mutableStateOf(Offset.Zero) }
    var dragGrab by remember { mutableStateOf(Offset.Zero) }
    var dropTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val chipBounds = remember { mutableMapOf<ToolbarItem, Rect>() }
    val sectionBounds = remember { mutableMapOf<Int, Rect>() }
    val sectionGrabBounds = remember { mutableMapOf<Int, Rect>() }
    // Section-drag state (the whole card, by its grip handle); shares the finger/grab with the
    // chip drag since only one gesture runs at a time. The drop slot is an insertion index.
    var dragSection by remember { mutableStateOf<Int?>(null) }
    var sectionDropTarget by remember { mutableStateOf<Int?>(null) }
    var paneTopLeft by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    fun editedLayout(): ToolbarLayout = if (canvasTab) editor.canvasToolbarLayout else editor.toolbarLayout
    fun applyEdited(next: ToolbarLayout) {
        if (canvasTab) editor.applyCanvasToolbarLayout(next) else editor.applyToolbarLayout(next)
    }
    fun retarget() {
        dropTarget = toolbarDropTarget(editedLayout(), dragFinger, chipBounds, sectionBounds)
    }
    fun retargetSection() {
        sectionDropTarget = toolbarSectionDropTarget(editedLayout(), dragFinger, sectionBounds)
    }
    fun showTab(canvas: Boolean) {
        if (canvasTab == canvas) return
        canvasTab = canvas
        // The bounds maps are keyed by item and the two bars share most items, so stale entries
        // from the other tab would aim a drop at a chip that is no longer there.
        chipBounds.clear()
        sectionBounds.clear()
        sectionGrabBounds.clear()
        dragItem = null
        dragSection = null
        dropTarget = null
        sectionDropTarget = null
    }

    // A tap on empty space dismisses the template field's focus; children consume their own taps.
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
            .onGloballyPositioned { paneTopLeft = it.boundsInRoot().topLeft },
    ) {
    Column(Modifier.fillMaxSize()) {
        // Leading button inline with the title, at a constant row height so toggling the sidebar never
        // shifts the settings below: a back arrow to Home on compact, else a hamburger when hidden.
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (compact) {
                IconButton(onClick = onBackToHome) {
                    Icon(XnotesIcons.prev, "Back to home", tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(4.dp))
            } else if (!sidebarOpen) {
                IconButton(onClick = onShowSidebar) {
                    Icon(XnotesIcons.menu, "Show sidebar", tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(4.dp))
            }
            Text("Preferences", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { update(Preferences()) }) { Text("Reset to defaults", fontSize = 13.sp) }
        }
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.fillMaxSize().verticalScroll(scrollState)
                .onGloballyPositioned { viewport = it.boundsInRoot() },
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionTitle("General")
            FieldLabel("UI theme")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("System", prefs.uiAppearance == "system") { update(prefs.copy(uiAppearance = "system")) }
                Chip("Dark", prefs.uiAppearance == "dark") { update(prefs.copy(uiAppearance = "dark")) }
                Chip("Light", prefs.uiAppearance == "light") { update(prefs.copy(uiAppearance = "light")) }
                Chip("OLED", prefs.uiAppearance == "oled") { update(prefs.copy(uiAppearance = "oled")) }
            }
            FieldLabel("Colour palette")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Classic accent", prefs.paletteStyle == "classic") { update(prefs.withPaletteStyle("classic")) }
                Chip("Material You", prefs.paletteStyle == "material") { update(prefs.withPaletteStyle("material")) }
            }
            FieldLabel("Accent colour")
            if (prefs.paletteStyle == "material") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DynamicSeedDot(prefs.materialSeed == null) { update(prefs.copy(materialSeed = null)) }
                    materialSeedPresets.forEach { c ->
                        ColorDot(c.toComposeColor(), prefs.materialSeed == c) { update(prefs.copy(materialSeed = c)) }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    accentPresets.forEach { c ->
                        ColorDot(c.toComposeColor(), prefs.accentColor == c) { update(prefs.copy(accentColor = c)) }
                    }
                    ColorPickerDot(
                        prefs.accentColor,
                        custom = prefs.accentColor !in accentPresets,
                        onPick = { update(prefs.copy(accentColor = it)) },
                    ) { onDismiss, onPick -> AccentColorGridPopup(onDismiss, onPick) }
                }
            }
            CheckRow("Start in fullscreen", editor.fullscreen) { editor.setFullscreenPref(it) }

            HorizontalDivider(color = palette.border.toComposeColor())
            SectionTitle("Input")
            CheckRow("Draw with finger (off = finger pans)", prefs.fingerDraws) { update(prefs.copy(fingerDraws = it)) }
            FieldLabel("Panning while zoom is locked")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip("Single-finger pan", prefs.zoomLockPan == "single") { update(prefs.copy(zoomLockPan = "single")) }
                Chip("Two-finger pan", prefs.zoomLockPan == "double") { update(prefs.copy(zoomLockPan = "double")) }
                Chip("No pan", prefs.zoomLockPan == "none") { update(prefs.copy(zoomLockPan = "none")) }
            }
            CheckRow("Snap held strokes to shapes (hold the pen still)", prefs.detectShapes) { update(prefs.copy(detectShapes = it)) }
            FieldLabel("Stylus/Pen side button (hold)")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                penButtonOptions.forEach { (id, label) ->
                    Chip(label, prefs.penButtonTool == id) { update(prefs.copy(penButtonTool = id)) }
                }
            }
            if (prefs.penButtonTool == "eraser" || prefs.penButtonTool == "pan") {
                CheckRow("Activate during hover (no need to touch the screen)", prefs.penButtonHover) {
                    update(prefs.copy(penButtonHover = it))
                }
            }
            FieldLabel("Two-finger tap")
            OptionDropdown(tapGestureOptions, prefs.twoFingerTap) { update(prefs.copy(twoFingerTap = it)) }
            FieldLabel("Three-finger tap")
            OptionDropdown(tapGestureOptions, prefs.threeFingerTap) { update(prefs.copy(threeFingerTap = it)) }
            FieldLabel("Stylus double-tap")
            OptionDropdown(tapGestureOptions, prefs.stylusDoubleTap) { update(prefs.copy(stylusDoubleTap = it)) }
            FieldLabel("Stylus side button (tap)")
            OptionDropdown(tapGestureOptions, prefs.stylusButtonTap) { update(prefs.copy(stylusButtonTap = it)) }
            FieldLabel("Redmi Smart Pen side buttons (tap)")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Button 1", color = palette.textDim.toComposeColor(), fontSize = 12.sp)
                    OptionDropdown(tapGestureOptions, prefs.stylusButton1Tap) { update(prefs.copy(stylusButton1Tap = it)) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Button 2", color = palette.textDim.toComposeColor(), fontSize = 12.sp)
                    OptionDropdown(tapGestureOptions, prefs.stylusButton2Tap) { update(prefs.copy(stylusButton2Tap = it)) }
                }
            }

            HorizontalDivider(color = palette.border.toComposeColor())
            SectionTitle("New notes")
            FieldLabel("Filename template")
            Text(
                "YYYY YY MM DD HH mm ss expand to the creation date and time. # becomes the number " +
                    "that keeps the name free in its folder.",
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
            )
            OutlinedTextField(
                value = prefs.newNoteNameTemplate,
                onValueChange = { update(prefs.copy(newNoteNameTemplate = it)) },
                singleLine = true,
                placeholder = { Text(NameTemplate.DEFAULT) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.width(280.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NAME_TEMPLATE_PRESETS.forEach { t ->
                    Chip(t, prefs.newNoteNameTemplate == t) { update(prefs.copy(newNoteNameTemplate = t)) }
                }
            }
            Text(
                "The next note would be named ${editor.newNoteStem(emptySet())}.xnote",
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
            )

            HorizontalDivider(color = palette.border.toComposeColor())
            SectionTitle("Page")
            FieldLabel("Default page size")
            SizeDropdown(prefs.defaultPageSize) { update(prefs.copy(defaultPageSize = it)) }
            if (prefs.defaultPageSize == PageSize.CUSTOM) {
                // A custom page is taken as typed, so the orientation chips have nothing to say
                // about it and are left out rather than shown doing nothing.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MillimetreField("Width (mm)", prefs.customPageWidthMm) {
                        update(prefs.copy(customPageWidthMm = it))
                    }
                    MillimetreField("Height (mm)", prefs.customPageHeightMm) {
                        update(prefs.copy(customPageHeightMm = it))
                    }
                }
            } else {
                FieldLabel("Orientation")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Portrait", prefs.defaultPageOrientation == Orientation.PORTRAIT) {
                        update(prefs.copy(defaultPageOrientation = Orientation.PORTRAIT))
                    }
                    Chip("Landscape", prefs.defaultPageOrientation == Orientation.LANDSCAPE) {
                        update(prefs.copy(defaultPageOrientation = Orientation.LANDSCAPE))
                    }
                }
            }
            CheckRow("Hide page borders", prefs.hidePageBorders) {
                update(prefs.copy(hidePageBorders = it))
            }
            FieldLabel("Side margin  ${prefs.sideMargin.toInt()} px")
            Slider(
                value = prefs.sideMargin.toFloat(),
                onValueChange = { update(prefs.copy(sideMargin = it.toDouble())) },
                valueRange = 0f..64f,
                modifier = Modifier.width(280.dp),
            )
            FieldLabel("Page colour")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pageColorPresets.forEach { c ->
                    ColorDot(c.toComposeColor(), prefs.pageColor == c) { update(prefs.copy(pageColor = c)) }
                }
                ColorPickerDot(
                    prefs.pageColor,
                    custom = prefs.pageColor != null && prefs.pageColor !in pageColorPresets,
                    onPick = { update(prefs.copy(pageColor = it)) },
                    dismissOnPick = false,
                ) { onDismiss, onPick -> PageColorGridPopup(prefs.pageColor, onDismiss, onPick) }
            }
            CheckRow("Page colour follows the theme", prefs.pageColor == null) {
                update(prefs.copy(pageColor = if (it) null else pageColorPresets.first()))
            }

            HorizontalDivider(color = palette.border.toComposeColor())
            SectionTitle("Performance")
            FieldLabel("Max cache resolution  ${prefs.maxCacheResolution} px")
            Slider(
                value = prefs.maxCacheResolution.toFloat(),
                onValueChange = { update(prefs.copy(maxCacheResolution = Math.round(it))) },
                valueRange = 1024f..4096f,
                steps = 5,
                modifier = Modifier.width(280.dp),
            )
            Text(
                "Higher feels smoother when you pan and zoom but uses more memory. Your ink and PDFs look just as crisp either way.",
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
            )

            if (editor.treeSitterAvailable) {
                HorizontalDivider(color = palette.border.toComposeColor())
                SectionTitle("Code highlighting")

                val langOptions = editor.scmLanguages().map { it to it }
                FieldLabel("Default code language")
                Text(
                    "\"Paste as Code\" highlights the pasted block in this language.",
                    color = palette.textDim.toComposeColor(),
                    fontSize = 12.sp,
                )
                OptionDropdown(listOf("plain" to "plain (no highlighting)") + langOptions, prefs.defaultCodeLanguage) {
                    update(prefs.copy(defaultCodeLanguage = it))
                }

                FieldLabel("Code theme")
                Text(
                    "Import a Helix editor theme (.toml) to recolour code.",
                    color = palette.textDim.toComposeColor(),
                    fontSize = 12.sp,
                )
                if (editor.hasCustomCodeTheme) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            prefs.codeThemeName ?: "Custom theme",
                            color = palette.text.toComposeColor(),
                            fontSize = 13.sp,
                        )
                        TextButton(onClick = { editor.resetCodeTheme() }) { Text("Reset", fontSize = 13.sp) }
                    }
                }
                TextButton(onClick = { onImportCodeTheme() }) { Text("Import theme…", fontSize = 13.sp) }
            }

            HorizontalDivider(color = palette.border.toComposeColor())
            SectionTitle("Fonts")
            Text(
                "Import a .ttf or .otf to offer it in the text font pickers.",
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
            )
            TextButton(onClick = { onImportFont() }) { Text("Import font…", fontSize = 13.sp) }
            for (font in editor.customFonts) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        font.label,
                        color = palette.text.toComposeColor(),
                        fontSize = 13.sp,
                        style = TextStyle(fontFamily = font.face.toComposeFamily()),
                    )
                    if (font.mono) {
                        Text("  mono", color = palette.accent.toComposeColor(), fontSize = 11.sp)
                    }
                    TextButton(onClick = { editor.removeCustomFont(font.face) }) { Text("Remove", fontSize = 12.sp) }
                }
            }

            HorizontalDivider(color = palette.border.toComposeColor())
            SectionTitle("Toolbar")
            // Above the tabs because it governs both bars: the swatch count is one setting.
            FieldLabel("Colours on the toolbar  ${editor.toolbarColorCount}")
            Slider(
                value = editor.toolbarColorCount.toFloat(),
                onValueChange = { editor.applyToolbarColorCount(Math.round(it)) },
                valueRange = 1f..7f,
                steps = 5,
                modifier = Modifier.width(280.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Chip("xnote", selected = !canvasTab) { showTab(false) }
                Chip("xcanvas", selected = canvasTab) { showTab(true) }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        applyEdited(if (canvasTab) ToolbarLayout.CANVAS_DEFAULT else ToolbarLayout.DEFAULT)
                    },
                ) { Text("Reset", fontSize = 13.sp) }
            }
            Text(
                "Tap a tool to show or hide it. Long-press to drag it within or across sections. Long-press a section's top to move the whole section.",
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
            )
            ToolbarCustomizerBody(
                layout = layout,
                dragItem = dragItem,
                dropTarget = dropTarget,
                dragSection = dragSection,
                sectionDropTarget = sectionDropTarget,
                chipBounds = chipBounds,
                sectionBounds = sectionBounds,
                sectionGrabBounds = sectionGrabBounds,
                onToggle = { s, i -> applyEdited(editedLayout().toggleVisible(s, i)) },
                onDelete = { s -> applyEdited(editedLayout().removeSection(s)) },
                onAdd = { applyEdited(editedLayout().addSection()) },
                onDragStart = { item, local ->
                    dragItem = item
                    dragGrab = local
                    dragFinger = (chipBounds[item]?.topLeft ?: Offset.Zero) + local
                    retarget()
                },
                onDrag = { delta -> dragFinger += delta; retarget() },
                onDrop = {
                    val target = dropTarget
                    val moving = dragItem
                    if (target != null && moving != null) {
                        val cur = editedLayout()
                        val fSec = cur.sections.indexOfFirst { s -> s.entries.any { it.item == moving } }
                        val fIdx = if (fSec >= 0) cur.sections[fSec].entries.indexOfFirst { it.item == moving } else -1
                        if (fSec >= 0 && fIdx >= 0) {
                            applyEdited(cur.moveItem(fSec, fIdx, target.first, target.second))
                        }
                    }
                    dragItem = null
                    dropTarget = null
                },
                onCancel = {
                    dragItem = null
                    dropTarget = null
                },
                onSectionDragStart = { sec, local ->
                    dragSection = sec
                    val grab = sectionGrabBounds[sec]?.topLeft ?: sectionBounds[sec]?.topLeft ?: Offset.Zero
                    val card = sectionBounds[sec]?.topLeft ?: Offset.Zero
                    dragFinger = grab + local
                    dragGrab = dragFinger - card
                    retargetSection()
                },
                onSectionDrag = { delta -> dragFinger += delta; retargetSection() },
                onSectionDrop = {
                    val from = dragSection
                    val insertAt = sectionDropTarget
                    if (from != null && insertAt != null) {
                        val cur = editedLayout()
                        val to = (if (insertAt > from) insertAt - 1 else insertAt).coerceIn(0, cur.sections.lastIndex)
                        if (to != from) applyEdited(cur.moveSection(from, to))
                    }
                    dragSection = null
                    sectionDropTarget = null
                },
                onSectionCancel = {
                    dragSection = null
                    sectionDropTarget = null
                },
            )
            Spacer(Modifier.size(8.dp))
        }
    }
        // The dragged chip/section's floating copy: a pane-root sibling so the scroll never clips it.
        val gx = (dragFinger.x - dragGrab.x - paneTopLeft.x).toInt()
        val gy = (dragFinger.y - dragGrab.y - paneTopLeft.y).toInt()
        val ghostChip = dragItem
        val ghostSecIdx = dragSection
        if (ghostChip != null) {
            Box(Modifier.offset { IntOffset(gx, gy) }) { ToolbarDragGhost(ghostChip) }
        } else if (ghostSecIdx != null) {
            val sectionG = (if (canvasTab) editor.canvasToolbarLayout else editor.toolbarLayout)
                .sections.getOrNull(ghostSecIdx)
            val widthG = sectionBounds[ghostSecIdx]?.width
            if (sectionG != null && widthG != null) {
                Box(Modifier.offset { IntOffset(gx, gy) }) { SectionCardGhost(sectionG, widthG) }
            }
        }
    }

    // While dragging near a vertical edge of the scroll viewport, ease the list along so items
    // off-screen can be reached without lifting the finger.
    LaunchedEffect(dragItem != null || dragSection != null) {
        while (dragItem != null || dragSection != null) {
            val band = 90f
            val y = dragFinger.y
            val delta = when {
                y < viewport.top + band -> -((viewport.top + band - y) / band) * 14f
                y > viewport.bottom - band -> ((y - (viewport.bottom - band)) / band) * 14f
                else -> 0f
            }
            if (delta != 0f) {
                scrollState.scrollBy(delta)
                if (dragItem != null) retarget() else retargetSection()
            }
            delay(16L)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = LocalPalette.current.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 15.sp)
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = LocalPalette.current.accent.toComposeColor(), fontSize = 13.sp)
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) palette.accentAlpha(48).toComposeColor() else palette.surface.toComposeColor())
            .border(1.dp, if (selected) palette.accent.toComposeColor() else palette.border.toComposeColor(), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (selected) palette.accent.toComposeColor() else palette.text.toComposeColor(),
            fontSize = 14.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
internal fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .size(30.dp)
            .then(if (selected) Modifier.border(2.dp, palette.accent.toComposeColor(), CircleShape) else Modifier)
            .padding(4.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, palette.border.toComposeColor(), CircleShape)
            .clickable(onClick = onClick),
    )
}

/** A soft material sweep stands in for whatever colours the system derives from the wallpaper. */
private val materialSweepBrush = Brush.sweepGradient(
    listOf(
        Color(0xFF2196F3), Color(0xFF9C27B0), Color(0xFFF44336),
        Color(0xFFFFC107), Color(0xFF4CAF50), Color(0xFF2196F3),
    ),
)

/** The dynamic (wallpaper-following) material seed choice, first in the seed row. */
@Composable
private fun DynamicSeedDot(selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .size(30.dp)
            .then(if (selected) Modifier.border(2.dp, palette.accent.toComposeColor(), CircleShape) else Modifier)
            .padding(4.dp)
            .clip(CircleShape)
            .background(materialSweepBrush)
            .border(1.dp, palette.border.toComposeColor(), CircleShape)
            .clickable(onClick = onClick),
    )
}

/** A spectrum wheel signals that this dot opens the full picker. */
private val spectrumBrush = Brush.sweepGradient(
    listOf(
        Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
        Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
    ),
)

/**
 * A spectrum dot that opens [grid], a popup of colour swatches — shared by the accent and
 * page-colour rows. Until a colour outside the row's presets is chosen the dot shows the
 * spectrum wheel; once one is, it fills with that colour and reads as selected.
 */
@Composable
internal fun ColorPickerDot(
    current: Rgba?,
    custom: Boolean,
    onPick: (Rgba) -> Unit,
    dismissOnPick: Boolean = true,
    grid: @Composable (onDismiss: () -> Unit, onPick: (Rgba) -> Unit) -> Unit,
) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .size(30.dp)
                .then(if (custom) Modifier.border(2.dp, palette.accent.toComposeColor(), CircleShape) else Modifier)
                .padding(4.dp)
                .clip(CircleShape)
                .then(if (custom && current != null) Modifier.background(current.toComposeColor()) else Modifier.background(spectrumBrush))
                .border(1.dp, palette.border.toComposeColor(), CircleShape)
                .clickable { open = true },
        )
        // A live picker (e.g. the page/ink popup) edits across several taps, so it stays open until a
        // tap outside; a one-shot grid (the accent swatches) closes the moment a colour is chosen.
        if (open) grid({ open = false }, { onPick(it); if (dismissOnPick) open = false })
    }
}

/** One tappable colour cell in a picker grid. */
@Composable
private fun Swatch(c: Rgba, onPick: (Rgba) -> Unit) {
    Box(
        Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(c.toComposeColor())
            .border(0.5.dp, LocalPalette.current.border.toComposeColor(), RoundedCornerShape(2.dp))
            .clickable { onPick(c) },
    )
}

/** Picker grid restricted to bright, saturated hues — no greys, no washed-out tints. */
@Composable
private fun AccentColorGridPopup(onDismiss: () -> Unit, onPick: (Rgba) -> Unit) {
    val hues = (0 until 12).map { it * 360.0 / 12.0 }
    val shades = listOf(1.0 to 1.0, 1.0 to 0.82, 0.78 to 1.0)
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            shades.forEach { (s, v) ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    hues.forEach { h -> Swatch(ColorMath.hsvToRgb(h, s, v), onPick) }
                }
            }
        }
    }
}

/**
 * The colour-code picker shown inside a note/folder's overflow menu: a None row to clear the colour,
 * then the picker's full matrix (pale tints through near-black plus the greyscale row). [onPick] is
 * called with null for None. Rendered directly inside the menu's own dropdown column.
 */
@Composable
internal fun ColorCodeMenuContent(onPick: (Rgba?) -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(4.dp)).clickable { onPick(null) }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(20.dp).clip(RoundedCornerShape(2.dp))
                    .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(2.dp)),
            )
            Text("None", color = palette.text.toComposeColor(), fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
        }
        FullSwatchGrid { onPick(it) }
    }
}

/**
 * Page/pattern colour picker: the shared [ColorPickerPopup] (Swatches + Spectrum tabs, full
 * 13×13 range from pale tint to near-black plus a greyscale row, and HEX/RGB fields), so paper-like
 * and muted page backgrounds are reachable, not just the saturated ones. It keeps no recents list.
 */
@Composable
internal fun PageColorGridPopup(initial: Rgba?, onDismiss: () -> Unit, onPick: (Rgba) -> Unit) {
    ColorPickerPopup(initial = initial, recents = emptyList(), onDismiss = onDismiss, onPick = onPick)
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onChange(!checked) }) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(2.dp))
        Text(label, color = LocalPalette.current.text.toComposeColor(), fontSize = 14.sp)
    }
}

/**
 * One side of a custom page, in millimetres. The field keeps whatever is typed so a number can be
 * cleared and retyped; only a value that parses inside the settable range reaches the preference.
 */
@Composable
private fun MillimetreField(label: String, value: Double, onChange: (Double) -> Unit) {
    var text by remember { mutableStateOf(formatMm(value)) }
    // Adopt an outside change (Reset to defaults) without ever rewriting what is being typed.
    LaunchedEffect(value) { if (text.trim().toDoubleOrNull() != value) text = formatMm(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw
            raw.trim().toDoubleOrNull()?.let {
                if (it in Preferences.CUSTOM_PAGE_MIN_MM..Preferences.CUSTOM_PAGE_MAX_MM) onChange(it)
            }
        },
        singleLine = true,
        label = { Text(label, fontSize = 12.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        modifier = Modifier.width(136.dp),
    )
}

/** Drop a whole number's ".0" so the field reads "210", not "210.0". */
private fun formatMm(v: Double): String =
    if (v == Math.floor(v) && !v.isInfinite()) v.toInt().toString() else v.toString()

@Composable
private fun SizeDropdown(size: PageSize, onSelect: (PageSize) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    Box {
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(6.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(size.displayName, color = palette.text.toComposeColor(), fontSize = 14.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PageSize.entries.forEach { s ->
                DropdownMenuItem(text = { Text(s.displayName) }, onClick = { onSelect(s); expanded = false })
            }
        }
    }
}

@Composable
private fun OptionDropdown(options: List<Pair<String, String>>, selectedId: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    val label = options.firstOrNull { it.first == selectedId }?.second ?: options.first().second
    Box {
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(6.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(label, color = palette.text.toComposeColor(), fontSize = 14.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, lbl) ->
                DropdownMenuItem(text = { Text(lbl) }, onClick = { onSelect(id); expanded = false })
            }
        }
    }
}
