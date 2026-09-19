package com.xnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Picks a folder in the tree under [root] to move things into, starting at [start] (a path of (document id,
 * name) below the top folder). [blocked] folders, the ones being moved, can't be opened or picked.
 */
@Composable
internal fun FolderPickerDialog(
    editor: Editor,
    root: String,
    rootName: String,
    title: String,
    confirmLabel: String,
    start: List<Pair<String, String>>,
    blocked: (docId: String) -> Boolean,
    onPick: (docId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val rootId = remember(root) { editor.browseRootDocId(root) }
    val path = remember { mutableStateListOf<Pair<String, String>>().apply { addAll(start) } }
    val here = path.lastOrNull()?.first ?: rootId
    val folders by produceState(editor.cachedChildren(root, here)?.filter { it.isDir }, root, here) {
        value = withContext(Dispatchers.IO) { editor.browseChildren(root, here).filter { it.isDir } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().height(380.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    Crumb("${rootName}/", current = path.isEmpty()) { path.clear() }
                    path.forEachIndexed { i, (_, name) ->
                        Crumb("$name/", current = i == path.lastIndex) { while (path.size > i + 1) path.removeAt(path.lastIndex) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                val list = folders
                when {
                    list == null -> EmptyNote(stringResource(R.string.loading))
                    list.isEmpty() -> EmptyNote(stringResource(R.string.no_folders_here))
                    else -> LazyColumn(Modifier.fillMaxWidth()) {
                        items(list, key = { it.documentUri }) { f ->
                            val id = editor.browseDocId(f.documentUri)
                            val off = blocked(id)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = !off) { path.add(id to f.name) }
                                    .alpha(if (off) 0.4f else 1f)
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                val code = f.color?.let { codeTint(it, palette) }
                                Icon(XnotesIcons.folder, null, tint = code ?: palette.textDim.toComposeColor(), modifier = Modifier.size(20.dp))
                                Text(f.name, color = palette.text.toComposeColor(), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Icon(XnotesIcons.next, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(here) }, enabled = !blocked(here)) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = palette.menuBg.toComposeColor(),
    )
}
