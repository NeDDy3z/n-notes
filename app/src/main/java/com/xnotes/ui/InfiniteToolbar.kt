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
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.Tool
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** The tools the infinite canvas offers. Text, screenshot and the page tools mean nothing here. */
private val CANVAS_TOOLS = listOf(
    Tool.PAN, Tool.PEN, Tool.DASHED, Tool.CALLIGRAPHY, Tool.SPEED, Tool.TAPER, Tool.HIGHLIGHTER,
    Tool.ERASER, Tool.SHAPE, Tool.SELECT, Tool.LASSO,
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
        Separator()

        for (tool in CANVAS_TOOLS) {
            val icon = toolIcons[tool] ?: continue
            Box {
                // Tapping the armed eraser again opens its settings, exactly as the paged bar does.
                ToolbarIcon(icon, tool.name, active = editor.tool == tool) {
                    when {
                        tool == Tool.ERASER && editor.tool == tool -> eraserOpen = true
                        tool == Tool.SHAPE && editor.tool == tool -> shapeOpen = true
                        else -> editor.armTool(tool)
                    }
                }
                if (tool == Tool.ERASER && eraserOpen) CanvasEraserPopup(editor) { eraserOpen = false }
                if (tool == Tool.SHAPE && shapeOpen) CanvasShapePopup(editor) { shapeOpen = false }
            }
        }
        Separator()

        for (color in InkPalette.presets) {
            Swatch(color.toComposeColor(), active = editor.inkColor == color) { editor.armInkColor(color) }
        }
        Separator()

        if (editor.hasSelection) {
            ToolbarIcon(XnotesIcons.trash, "Delete selection") { editor.deleteSelection() }
        }
        ToolbarIcon(XnotesIcons.image, "Insert image") { onInsertImage() }
        Box {
            ToolbarIcon(XnotesIcons.sliders, "Styles", active = stylesOpen) { stylesOpen = true }
            if (stylesOpen) CanvasStylesPopup(editor) { stylesOpen = false }
        }
        ToolbarIcon(XnotesIcons.fit, "Fit all") { editor.zoomToFit() }
        Label("${editor.zoomPercent}%")
        ToolbarIcon(XnotesIcons.undo, "Undo", enabled = editor.canUndo) { editor.undo() }
        ToolbarIcon(XnotesIcons.redo, "Redo", enabled = editor.canRedo) { editor.redo() }

        editor.renderFailure?.let {
            Separator()
            Label("GL unavailable")
        }
        Spacer(Modifier.padding(horizontal = 2.dp))
    }
}
