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
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * The tools the infinite canvas offers, grouped as the paged bar groups them.
 *
 * The order is [com.xnotes.core.tools.ToolbarLayout.DEFAULT]'s with everything pageless removed:
 * ink and eraser, then navigation and selection, then the shape tool. Text, the screenshot tool and
 * the page tools mean nothing here, so their groups simply do not appear.
 */
private val CANVAS_TOOL_GROUPS = listOf(
    listOf(
        Tool.PEN, Tool.DASHED, Tool.CALLIGRAPHY, Tool.SPEED, Tool.TAPER, Tool.HIGHLIGHTER,
        Tool.ERASER,
    ),
    listOf(Tool.PAN, Tool.SELECT, Tool.LASSO),
    listOf(Tool.SHAPE),
)

/**
 * The infinite canvas's chrome. A separate bar from the paged [Toolbar] because most of that one
 * addresses pages, viewing modes, pagination and text, none of which exist here; but it is built
 * from the same pieces, so the two look like one app rather than two.
 */
@Composable
fun InfiniteToolbar(
    editor: InfiniteEditor,
    onOpenBackstage: () -> Unit,
    onInsertImage: () -> Unit = {},
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
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(palette.panel.toComposeColor())
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarIcon(XnotesIcons.prev, "Home") { onOpenBackstage() }
        Label(editor.title, Modifier.padding(end = 4.dp))

        for (group in CANVAS_TOOL_GROUPS) {
            Separator()
            for (tool in group) {
                val icon = toolIcons[tool] ?: continue
                Box {
                    // Tapping the armed eraser again opens its settings, exactly as the paged bar does.
                    ToolbarIcon(icon, tool.name, active = editor.tool == tool) {
                        when {
                            tool == Tool.ERASER && editor.tool == tool -> eraserOpen = true
                            tool == Tool.SHAPE && editor.tool == tool -> shapeOpen = true
                            tool == Tool.SELECT && editor.tool == tool -> selectOpen = true
                            tool.isStroke && editor.tool == tool -> penOpen = tool
                            else -> editor.armTool(tool)
                        }
                    }
                    // The very popups the paged toolbar opens, not lookalikes: same controls, same
                    // wording, same ranges, and they cannot drift apart.
                    if (tool == Tool.ERASER && eraserOpen) EraserConfigPopup(editor) { eraserOpen = false }
                    if (tool == Tool.SHAPE && shapeOpen) ShapeConfigPopup(editor) { shapeOpen = false }
                    if (tool == Tool.SELECT && selectOpen) SelectConfigPopup(editor) { selectOpen = false }
                    if (penOpen == tool) ToolConfigPopup(editor, tool) { penOpen = null }
                }
            }
        }
        Separator()

        ToolbarIcon(XnotesIcons.image, "Insert image") { onInsertImage() }
        Separator()

        for (i in editor.toolbarColors.indices) {
            val color = editor.toolbarColors[i]
            Swatch(color.toComposeColor(), active = editor.activeColorIndex == i) {
                editor.pickColor(i)
            }
        }
        Separator()

        ToolbarIcon(XnotesIcons.undo, "Undo", enabled = editor.canUndo) { editor.undo() }
        ToolbarIcon(XnotesIcons.redo, "Redo", enabled = editor.canRedo) { editor.redo() }
        Separator()

        // Where the paged bar keeps its page, styles and view menus. Waypoints take the place of
        // pagination: on an unbounded canvas, a saved view is what a page number was.
        Box {
            ToolbarIcon(XnotesIcons.sliders, "Styles", active = stylesOpen) { stylesOpen = true }
            if (stylesOpen) CanvasStylesPopup(editor) { stylesOpen = false }
        }
        Box {
            ToolbarIcon(XnotesIcons.bookmark, "Waypoints", active = waypointsOpen) { waypointsOpen = true }
            if (waypointsOpen) CanvasWaypointsPopup(editor) { waypointsOpen = false }
        }
        Separator()

        ToolbarIcon(XnotesIcons.zoomOut, "Zoom out") { editor.zoomBy(1.0 / InfiniteEditor.ZOOM_STEP) }
        Label("${editor.zoomPercent}%")
        ToolbarIcon(XnotesIcons.zoomIn, "Zoom in") { editor.zoomBy(InfiniteEditor.ZOOM_STEP) }
        ToolbarIcon(XnotesIcons.fit, "Fit all") { editor.zoomToFit() }

        editor.renderFailure?.let {
            Separator()
            Label("GL unavailable")
        }
        Spacer(Modifier.padding(horizontal = 2.dp))
    }
}
