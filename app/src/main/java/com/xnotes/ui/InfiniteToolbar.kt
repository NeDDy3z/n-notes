package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** Which tool a toolbar item arms, for the items that are simply a tool button. */
private val CANVAS_TOOL_OF: Map<ToolbarItem, Tool> = mapOf(
    ToolbarItem.PEN to Tool.PEN,
    ToolbarItem.DASHED to Tool.DASHED,
    ToolbarItem.CALLIGRAPHY to Tool.CALLIGRAPHY,
    ToolbarItem.SPEED to Tool.SPEED,
    ToolbarItem.TAPER to Tool.TAPER,
    ToolbarItem.HIGHLIGHTER to Tool.HIGHLIGHTER,
    ToolbarItem.ERASER to Tool.ERASER,
    ToolbarItem.PAN to Tool.PAN,
    ToolbarItem.SELECT to Tool.SELECT,
    ToolbarItem.LASSO to Tool.LASSO,
    ToolbarItem.SHAPE to Tool.SHAPE,
)

/**
 * The infinite canvas's chrome. A separate bar from the paged [Toolbar] because most of that one
 * addresses pages, viewing modes, pagination and text, none of which exist here; but it is built
 * from the same pieces, so the two look like one app rather than two.
 *
 * It draws from its own [com.xnotes.core.tools.ToolbarLayout], arranged in Preferences exactly as
 * the paged bar is. Its own, not the paged one: a page menu means nothing here and a waypoint means
 * nothing there, so the two layouts hold different items and are stored apart.
 */
@Composable
fun InfiniteToolbar(
    editor: InfiniteEditor,
    onOpenBackstage: () -> Unit,
    onInsertImage: () -> Unit = {},
    onClosePane: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    // The stroke tools use the same designed drawables the paged toolbar does, so a pen looks like
    // a pen on either canvas rather than like a generic glyph.
    val toolIcons: Map<Tool, ImageVector> = mapOf(
        Tool.PEN to ImageVector.vectorResource(R.drawable.ic_stroke_regular),
        Tool.DASHED to ImageVector.vectorResource(R.drawable.ic_stroke_dashed),
        Tool.CALLIGRAPHY to ImageVector.vectorResource(R.drawable.ic_stroke_calligraphy),
        Tool.SPEED to ImageVector.vectorResource(R.drawable.ic_stroke_speed),
        Tool.TAPER to ImageVector.vectorResource(R.drawable.ic_stroke_taper),
        Tool.HIGHLIGHTER to ImageVector.vectorResource(R.drawable.ic_stroke_highlighter),
        Tool.PAN to XnotesIcons.pan,
        Tool.ERASER to XnotesIcons.eraser,
        Tool.SHAPE to XnotesIcons.shape,
        Tool.SELECT to XnotesIcons.select,
        Tool.LASSO to XnotesIcons.lasso,
    )
    var stylesOpen by remember { mutableStateOf(false) }
    var eraserOpen by remember { mutableStateOf(false) }
    var shapeOpen by remember { mutableStateOf(false) }
    var waypointsOpen by remember { mutableStateOf(false) }
    var penOpen by remember { mutableStateOf<Tool?>(null) }
    var selectOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).background(palette.panel.toComposeColor()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val sections = editor.toolbarLayout.visibleSections
            sections.forEachIndexed { index, section ->
                if (index > 0) Separator()
                for (entry in section.visibleEntries) {
                    when (val item = entry.item) {
                        ToolbarItem.HOME -> ToolbarIcon(XnotesIcons.prev, "Home") { onOpenBackstage() }
                        ToolbarItem.TITLE -> Label(editor.title, Modifier.padding(end = 4.dp))

                        in CANVAS_TOOL_OF -> {
                            val tool = CANVAS_TOOL_OF.getValue(item)
                            val icon = toolIcons[tool]
                            if (icon != null) {
                                Box {
                                    // Tapping the armed tool again opens its settings, as the paged bar does.
                                    ToolbarIcon(icon, tool.name, active = editor.tool == tool) {
                                        when {
                                            tool == Tool.ERASER && editor.tool == tool -> eraserOpen = true
                                            tool == Tool.SHAPE && editor.tool == tool -> shapeOpen = true
                                            tool == Tool.SELECT && editor.tool == tool -> selectOpen = true
                                            tool.isStroke && editor.tool == tool -> penOpen = tool
                                            else -> editor.armTool(tool)
                                        }
                                    }
                                    // The very popups the paged toolbar opens, not lookalikes: same
                                    // controls, same wording, same ranges, and they cannot drift apart.
                                    if (tool == Tool.ERASER && eraserOpen) EraserConfigPopup(editor) { eraserOpen = false }
                                    if (tool == Tool.SHAPE && shapeOpen) ShapeConfigPopup(editor) { shapeOpen = false }
                                    if (tool == Tool.SELECT && selectOpen) SelectConfigPopup(editor) { selectOpen = false }
                                    if (penOpen == tool) ToolConfigPopup(editor, tool) { penOpen = null }
                                }
                            }
                        }

                        ToolbarItem.IMAGE -> ToolbarIcon(XnotesIcons.image, "Insert image") { onInsertImage() }

                        ToolbarItem.COLORS ->
                            editor.toolbarColors.take(editor.toolbarColorCount).forEachIndexed { i, color ->
                                Swatch(color.toComposeColor(), active = editor.activeColorIndex == i) {
                                    editor.pickColor(i)
                                }
                            }

                        ToolbarItem.UNDO ->
                            ToolbarIcon(XnotesIcons.undo, "Undo", enabled = editor.canUndo) { editor.undo() }
                        ToolbarItem.REDO ->
                            ToolbarIcon(XnotesIcons.redo, "Redo", enabled = editor.canRedo) { editor.redo() }

                        // Where the paged bar keeps its page, styles and view menus. Waypoints take the
                        // place of pagination: on an unbounded canvas, a saved view is what a page
                        // number was.
                        ToolbarItem.STYLES -> Box {
                            ToolbarIcon(XnotesIcons.sliders, "Styles", active = stylesOpen) { stylesOpen = true }
                            if (stylesOpen) CanvasStylesPopup(editor) { stylesOpen = false }
                        }
                        ToolbarItem.WAYPOINTS -> Box {
                            ToolbarIcon(XnotesIcons.bookmark, "Waypoints", active = waypointsOpen) { waypointsOpen = true }
                            if (waypointsOpen) CanvasWaypointsPopup(editor) { waypointsOpen = false }
                        }
                        ToolbarItem.MINIMAP ->
                            ToolbarIcon(XnotesIcons.map, "Minimap", active = editor.minimapVisible) {
                                editor.toggleMinimap()
                            }

                        ToolbarItem.ZOOM -> {
                            ToolbarIcon(XnotesIcons.zoomOut, "Zoom out") { editor.zoomBy(1.0 / InfiniteEditor.ZOOM_STEP) }
                            Label("${editor.zoomPercent}%")
                            ToolbarIcon(XnotesIcons.zoomIn, "Zoom in") { editor.zoomBy(InfiniteEditor.ZOOM_STEP) }
                        }
                        ToolbarItem.FIT -> ToolbarIcon(XnotesIcons.fit, "Fit all") { editor.zoomToFit() }

                        // Everything else belongs to the paged bar; a canvas layout never holds one.
                        else -> Unit
                    }
                }
            }

            editor.renderFailure?.let {
                Separator()
                Label("GL unavailable")
            }
            Spacer(Modifier.padding(horizontal = 2.dp))
        }
        // Pinned outside the scrolling row so closing a split pane is always one tap away.
        onClosePane?.let { ClosePaneButton(it) }
    }
}
