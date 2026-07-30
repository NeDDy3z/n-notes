package com.xnotes.core.infinite

import com.xnotes.core.history.Command
import com.xnotes.core.model.CanvasItem

/**
 * Undo commands for the flat, pageless canvas. The paged commands in `core.history` that only
 * touch item geometry (move, resize, transform, rotate) work here unchanged and are reused
 * through [OnCanvas]; only the ones that add to or remove from a [com.xnotes.core.model.Page]
 * need a sibling, because an infinite canvas has no page to hold the list.
 *
 * Every one of these mutates through [InfiniteDocument], never the backing list, so an undo also
 * re-files the spatial index and tells the renderer which GPU buffers to patch.
 */

/** Append an item (finishing a stroke, pasting, inserting an image). */
class AddCanvasItem(
    private val doc: InfiniteDocument,
    private val item: CanvasItem,
) : Command {
    override fun redo() {
        if (!doc.containsRef(item)) doc.add(item)
    }

    override fun undo() {
        doc.remove(item)
    }
}

/** Append several items as one edit (paste, duplicate, an eraser split's survivors). */
class AddCanvasItems(
    private val doc: InfiniteDocument,
    private val items: List<CanvasItem>,
) : Command {
    override fun redo() {
        for (item in items) if (!doc.containsRef(item)) doc.add(item)
    }

    override fun undo() {
        doc.removeAll(items)
    }
}

/**
 * Remove items, restoring each to its own z position on undo. The slots are captured at
 * construction time (before the removal is applied) and re-inserted low index first, so a
 * multi-item delete comes back in exactly the order it left.
 */
class EraseCanvasItems private constructor(
    private val doc: InfiniteDocument,
    private val slots: List<Pair<Int, CanvasItem>>,
) : Command {
    override fun redo() {
        doc.removeAll(slots.map { it.second })
    }

    override fun undo() {
        for ((at, item) in slots) if (!doc.containsRef(item)) doc.add(at, item)
    }

    companion object {
        /** Capture [items]' z slots. Call **before** removing them from [doc]. */
        fun capture(doc: InfiniteDocument, items: List<CanvasItem>): EraseCanvasItems {
            val slots = items
                .map { doc.indexOfRef(it) to it }
                .filter { it.first >= 0 }
                .sortedBy { it.first }
            return EraseCanvasItems(doc, slots)
        }
    }
}

/**
 * Swap the whole item list between before and after snapshots. The area eraser splits strokes
 * into fragments in place, possibly re-splitting a fragment later in the same drag, so only the
 * net list can be undone reliably. Bring-to-front uses it too, since that is purely a reorder.
 */
class ReplaceCanvasItems(
    private val doc: InfiniteDocument,
    private val before: List<CanvasItem>,
    private val after: List<CanvasItem>,
) : Command {
    override fun redo() = doc.replaceAll(after)
    override fun undo() = doc.replaceAll(before)
}

/**
 * Run a paged geometry command against the canvas, telling the document which items moved so the
 * index re-files them and the renderer re-uploads their vertices. Wraps `MoveItems`,
 * `ResizeItem`, `TransformItems` and `RotateImage` without forking any of them.
 */
class OnCanvas(
    private val doc: InfiniteDocument,
    private val inner: Command,
    private val touched: List<CanvasItem>,
) : Command {
    override fun redo() {
        inner.redo()
        doc.itemsChanged(touched)
    }

    override fun undo() {
        inner.undo()
        doc.itemsChanged(touched)
    }
}
