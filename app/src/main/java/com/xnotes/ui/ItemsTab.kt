package com.xnotes.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ConnectorItem
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.MarkupType
import com.xnotes.core.model.Page
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TableItem
import com.xnotes.core.model.TapeItem
import com.xnotes.core.model.TextItem
import com.xnotes.core.model.TextMarkup
import com.xnotes.core.tools.Tool
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** One row of the side panel's Items tab: something the user put on a page. */
internal class ItemRow(
    val page: Int,
    @StringRes val label: Int,
    val icon: ImageVector,
    /** A one-line hint at what the item is: its words, or its grid or label. */
    val detail: String?,
    /** The canvas item to go to, when this row is one. */
    val item: CanvasItem? = null,
    /** The PDF text markup to go to, when this row is one instead of an item. */
    val markup: TextMarkup? = null,
)

/**
 * Every item the user put on [pages], in page then z-order, with each page's PDF text markups after
 * its items. Items inside a sticky note belong to the note, so they are not listed apart from it.
 */
internal fun collectItemRows(pages: List<Page>): List<ItemRow> {
    val rows = ArrayList<ItemRow>()
    pages.forEachIndexed { index, page ->
        for (item in page.items) rows += itemRow(index, item)
        for (markup in page.markups) rows += markupRow(index, markup)
    }
    return rows
}

private fun itemRow(page: Int, item: CanvasItem): ItemRow = when (item) {
    is Stroke -> if (item.tool == Tool.HIGHLIGHTER) {
        ItemRow(page, R.string.item_highlight, XnotesIcons.highlight, null, item = item)
    } else {
        ItemRow(page, R.string.item_stroke, XnotesIcons.squiggly, null, item = item)
    }
    is TextItem -> ItemRow(
        page,
        if (item.math) R.string.item_formula else R.string.item_text_box,
        if (item.math) XnotesIcons.text else XnotesIcons.textBox,
        item.text.snippet(),
        item = item,
    )
    is TapeItem -> ItemRow(page, R.string.item_tape, XnotesIcons.tape, null, item = item)
    is ImageItem -> ItemRow(page, R.string.item_image, XnotesIcons.image, null, item = item)
    is ShapeItem -> ItemRow(page, R.string.item_shape, XnotesIcons.shape, item.label?.text?.snippet(), item = item)
    is TableItem -> ItemRow(page, R.string.item_table, XnotesIcons.table, "${item.cols} × ${item.rows}", item = item)
    is ConnectorItem -> ItemRow(page, R.string.item_connector, XnotesIcons.link, item.label?.text?.snippet(), item = item)
    else -> ItemRow(page, R.string.item_other, XnotesIcons.shape, null, item = item)
}

private fun markupRow(page: Int, markup: TextMarkup): ItemRow {
    val label = when (markup.type) {
        MarkupType.HIGHLIGHT -> R.string.item_markup_highlight
        MarkupType.UNDERLINE -> R.string.item_markup_underline
        MarkupType.STRIKEOUT -> R.string.item_markup_strikeout
        MarkupType.SQUIGGLY -> R.string.item_markup_squiggly
    }
    val icon = when (markup.type) {
        MarkupType.HIGHLIGHT -> XnotesIcons.highlight
        MarkupType.UNDERLINE -> XnotesIcons.underline
        MarkupType.STRIKEOUT -> XnotesIcons.strikethrough
        MarkupType.SQUIGGLY -> XnotesIcons.squiggly
    }
    val detail = markup.text.snippet() ?: markup.note?.snippet()
    return ItemRow(page, label, icon, detail, markup = markup)
}

/** The first line of a piece of text, trimmed, for a row's one-line hint. */
private fun String.snippet(): String? =
    lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(200)

/**
 * The side panel's Items tab: everything the user put on the note's pages, grouped by page. A row
 * brings its item to the view when tapped, and does nothing when its item has since gone.
 */
@Composable
internal fun ItemsTab(editor: Editor) {
    // The item list follows edits: one is rebuilt whenever the document's content version moves.
    val version = editor.contentVersion
    val rows = remember(version) { collectItemRows(editor.pagesSnapshot()) }
    if (rows.isEmpty()) {
        EmptyHint(stringResource(R.string.no_items))
        return
    }
    val listState = rememberLazyListState()
    // Open on the page being looked at, not on page 1, as the Pages tab does.
    LaunchedEffect(Unit) {
        val at = rows.indexOfFirst { it.page >= editor.pageIndex }
        if (at >= 0) listState.scrollToItem(at)
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        items(rows.size) { index ->
            val row = rows[index]
            if (index == 0 || rows[index - 1].page != row.page) PageHeading(row.page)
            ItemLine(editor, row)
        }
    }
}

@Composable
private fun PageHeading(page: Int) {
    val palette = LocalPalette.current
    Text(
        stringResource(R.string.page_n, page + 1),
        color = palette.textDim.toComposeColor(),
        fontSize = 11.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun ItemLine(editor: Editor, row: ItemRow) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                val markup = row.markup
                if (markup != null) editor.goToMarkup(row.page, markup) else row.item?.let { editor.goToItem(row.page, it) }
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(row.icon, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(row.label),
                color = palette.text.toComposeColor(),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.detail?.let {
                Text(
                    it,
                    color = palette.textDim.toComposeColor(),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
