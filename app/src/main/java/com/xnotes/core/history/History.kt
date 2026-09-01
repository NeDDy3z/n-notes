package com.xnotes.core.history

/**
 * A linear undo/redo stack (spec 07 §1). A new edit clears the redo branch.
 * Commands operate on the in-memory model by identity, so item/page references
 * must remain stable across an undo/redo cycle.
 */
class History {
    private val undoStack = ArrayDeque<Command>()
    private val redoStack = ArrayDeque<Command>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** The command the next [undo]/[redo] would apply, so a caller can read what it is about to
     *  disturb (see [Command.touched]) before and after the swap. */
    val nextUndo: Command? get() = undoStack.lastOrNull()
    val nextRedo: Command? get() = redoStack.lastOrNull()

    /** Record an already-applied edit and invalidate any redo branch. */
    fun push(command: Command) {
        undoStack.addLast(command)
        redoStack.clear()
    }

    fun undo() {
        val command = undoStack.removeLastOrNull() ?: return
        command.undo()
        redoStack.addLast(command)
    }

    fun redo() {
        val command = redoStack.removeLastOrNull() ?: return
        command.redo()
        undoStack.addLast(command)
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
