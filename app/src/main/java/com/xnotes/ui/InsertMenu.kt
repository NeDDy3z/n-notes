package com.xnotes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** One row of the Insert menu's Image group; [more] marks a row that opens a further menu. */
class InsertRow(val icon: ImageVector, val label: String, val more: Boolean = false, val onPick: () -> Unit)

/**
 * The Insert button, the same on both bars: one menu, pictures in one group and sound in the other.
 * [imageRows] are the bar's own picture rows, since a note offers more of them than a canvas does;
 * [popups] are menus those rows open, anchored to the button like the menu itself.
 */
@Composable
internal fun InsertButton(
    imageRows: List<InsertRow>,
    onAudioFromFile: () -> Unit,
    popups: @Composable () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolbarIcon(XnotesIcons.paperclip, stringResource(R.string.insert), active = expanded) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            InsertGroup(stringResource(R.string.insert_group_image))
            for (row in imageRows) InsertMenuRow(row.icon, row.label, row.more) { expanded = false; row.onPick() }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = LocalPalette.current.border.toComposeColor())
            InsertGroup(stringResource(R.string.insert_group_audio))
            InsertMenuRow(XnotesIcons.music, stringResource(R.string.audio_from_file)) { expanded = false; onAudioFromFile() }
        }
        popups()
    }
}

@Composable
private fun InsertGroup(label: String) {
    Text(
        label,
        color = LocalPalette.current.textDim.toComposeColor(),
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 16.dp, end = 18.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
internal fun InsertMenuRow(icon: ImageVector, label: String, more: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
        trailingIcon = if (more) ({ Icon(XnotesIcons.next, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
        enabled = enabled,
        onClick = onClick,
        contentPadding = PaddingValues(start = 14.dp, end = 18.dp),
    )
}
