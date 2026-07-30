package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.Tool
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** The tools the infinite canvas offers; text, screenshot and the page tools have no meaning here. */
private val CANVAS_TOOLS = listOf(
    Tool.PAN, Tool.PEN, Tool.DASHED, Tool.CALLIGRAPHY, Tool.SPEED, Tool.TAPER, Tool.HIGHLIGHTER,
)

private fun iconFor(tool: Tool) = when (tool) {
    Tool.PAN -> XnotesIcons.pan
    Tool.HIGHLIGHTER -> XnotesIcons.shapeRect
    Tool.ERASER -> XnotesIcons.eraser
    else -> XnotesIcons.edit
}

/**
 * The infinite canvas's chrome. Deliberately a separate bar from the paged [Toolbar]: most of that
 * one addresses pages, viewing modes, pagination and text, none of which mean anything here, and
 * bolting a document-type branch onto every one of its rows would be worse than a second bar.
 */
@Composable
fun InfiniteToolbar(
    editor: InfiniteEditor,
    onOpenBackstage: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(palette.panel.toComposeColor())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenBackstage) {
            Icon(
                XnotesIcons.home,
                contentDescription = "Home",
                modifier = Modifier.size(22.dp),
                tint = palette.text.toComposeColor(),
            )
        }
        for (t in CANVAS_TOOLS) {
            val active = editor.tool == t
            IconButton(onClick = { editor.armTool(t) }) {
                Icon(
                    iconFor(t),
                    contentDescription = t.id,
                    modifier = Modifier.size(22.dp),
                    tint = (if (active) palette.accent else palette.text).toComposeColor(),
                )
            }
        }
        for (c in InkPalette.presets) {
            val active = editor.inkColor == c
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (active) 20.dp else 16.dp)
                    .background(c.toComposeColor())
                    .clickable { editor.armInkColor(c) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            editor.renderFailure?.let {
                Text(
                    "GL unavailable",
                    color = palette.accent.toComposeColor(),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            Text(
                "${editor.zoomPercent}%",
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(onClick = { editor.zoomToFit() }) {
                Icon(
                    XnotesIcons.fit,
                    contentDescription = "Fit all",
                    modifier = Modifier.size(22.dp),
                    tint = palette.text.toComposeColor(),
                )
            }
            IconButton(onClick = { editor.undo() }, enabled = editor.canUndo) {
                Icon(
                    XnotesIcons.undo,
                    contentDescription = "Undo",
                    modifier = Modifier.size(22.dp),
                    tint = (if (editor.canUndo) palette.text else palette.textDim).toComposeColor(),
                )
            }
            IconButton(onClick = { editor.redo() }, enabled = editor.canRedo) {
                Icon(
                    XnotesIcons.redo,
                    contentDescription = "Redo",
                    modifier = Modifier.size(22.dp),
                    tint = (if (editor.canRedo) palette.text else palette.textDim).toComposeColor(),
                )
            }
        }
    }
}
