package com.xnotes.ui

import com.xnotes.settings.ExplorerSortKey

/**
 * Orders explorer entries: folders first when [foldersFirst] (rendered on their own), then files,
 * with the chosen [key]/[descending] sort applied within each group. [createdOf] supplies the
 * creation time (see [com.xnotes.platform.CreationTimeStore]); it breaks ties on the modified/size
 * keys, and name is the final ascending tiebreaker so the order is always stable.
 *
 * Pure (no Android), so it's unit-tested directly on [BrowseEntry] lists.
 */
internal fun explorerComparator(
    key: ExplorerSortKey,
    descending: Boolean,
    foldersFirst: Boolean = true,
    createdOf: (BrowseEntry) -> Long,
): Comparator<BrowseEntry> {
    val base: Comparator<BrowseEntry> = when (key) {
        ExplorerSortKey.NAME -> compareBy { it.name.lowercase() }
        ExplorerSortKey.MODIFIED -> compareBy({ it.modified }, { createdOf(it) })
        ExplorerSortKey.CREATED -> compareBy({ createdOf(it) }, { it.modified })
        ExplorerSortKey.SIZE -> compareBy({ it.size }, { createdOf(it) })
    }
    val within = (if (descending) base.reversed() else base).thenBy { it.name.lowercase() }
    if (!foldersFirst) return within
    return Comparator { a, b ->
        when {
            a.isDir && !b.isDir -> -1
            !a.isDir && b.isDir -> 1
            else -> within.compare(a, b)
        }
    }
}
