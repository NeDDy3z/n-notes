package com.xnotes.ui

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.core.history.History
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.infinite.CanvasViewport
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.Waypoint
import com.xnotes.gl.InfiniteCanvasView
import com.xnotes.ui.theme.Palette

/**
 * The infinite canvas's orchestrator, mirroring [Editor]'s role for the paged notebook: it owns the
 * document, the view, the history stack and the gesture layer, and exposes the Compose-observable
 * state the chrome reads.
 *
 * It is a sibling of [Editor], not a mode inside it. [Editor] is already the largest file in the
 * project and every file operation would have grown a document-type branch. It also deliberately
 * creates no temp directories of its own at construction: [Editor]'s constructor purges the shared
 * ones, so a second object doing the same would delete the open note's live files.
 */
@Stable
class InfiniteEditor(context: Context) {

    private val appContext = context.applicationContext

    var document: InfiniteDocument = InfiniteDocument()
        private set

    val history = History()

    val view = InfiniteCanvasView(appContext)

    val viewport: CanvasViewport get() = view.viewport

    val interaction = InfiniteInteraction(
        viewport = view.viewport,
        requestRender = { view.publish() },
        onViewChanged = { onViewChanged() },
    )

    /** Live zoom, mirrored into Compose so a readout can follow a pinch frame by frame. */
    var zoomPercent by mutableStateOf(100)
        private set

    var title by mutableStateOf("Untitled")
        private set

    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    /** Set when the GL surface refuses to come up, so the host can say so instead of showing black. */
    var renderFailure by mutableStateOf<String?>(null)
        private set

    init {
        view.input = { interaction.onTouch(it) }
        view.afterLayout = { applyInitialView() }
        view.onContextReady = { renderFailure = view.failure }
    }

    /** Repaint the canvas with whatever the model currently says. */
    fun requestRender() = view.publish()

    /** Adopt the chrome's palette, so the paper matches the rest of the app. */
    fun applyPalette(palette: Palette) {
        view.paperColor = document.background.paperColor ?: palette.paper
    }

    // --- documents ---

    fun newCanvas() {
        replaceDocument(InfiniteDocument())
    }

    fun replaceDocument(next: InfiniteDocument) {
        document = next
        history.clear()
        interaction.resetGestureState()
        view.background = next.background
        view.paperColor = next.background.paperColor ?: view.paperColor
        appliedInitialView = false
        applyInitialView()
        refresh()
    }

    // --- view ---

    private var appliedInitialView = false

    /**
     * Put the canvas where it was left, or on its content, or at the origin. Runs once the viewport
     * has a size, since both fitting and centring need one.
     */
    private fun applyInitialView() {
        if (appliedInitialView) return
        if (viewport.widthPx <= 0 || viewport.heightPx <= 0) return
        appliedInitialView = true
        val saved = document.lastView
        when {
            saved != null -> viewport.apply(saved)
            else -> document.contentBounds()?.let { viewport.fit(it) } ?: viewport.centerOn(0.0, 0.0)
        }
        onViewChanged()
        view.publish()
    }

    /** Frame every item on the canvas. */
    fun zoomToFit() {
        val bounds = document.contentBounds()
        if (bounds == null) {
            viewport.zoom = 1.0
            viewport.centerOn(0.0, 0.0)
        } else {
            viewport.fit(bounds)
        }
        onViewChanged()
        view.publish()
    }

    fun jumpTo(waypoint: Waypoint) {
        viewport.apply(waypoint)
        onViewChanged()
        view.publish()
    }

    /** Save the current view under [name], replacing any waypoint that already has it. */
    fun saveWaypoint(name: String) {
        val clean = Waypoint.sanitizeName(name)
        if (clean.isEmpty()) return
        document.waypoints.removeAll { it.name.equals(clean, ignoreCase = true) }
        document.waypoints.add(viewport.toWaypoint(clean))
        document.dirty = true
    }

    fun setBackground(background: CanvasBackground) {
        document.background = background
        document.dirty = true
        view.background = background
        view.paperColor = background.paperColor ?: view.paperColor
    }

    // --- history ---

    fun undo() {
        history.undo()
        refresh()
        view.publish()
    }

    fun redo() {
        history.redo()
        refresh()
        view.publish()
    }

    private fun onViewChanged() {
        document.lastView = viewport.toWaypoint()
        zoomPercent = Math.round(viewport.zoom * 100).toInt()
    }

    private fun refresh() {
        title = document.title
        canUndo = history.canUndo
        canRedo = history.canRedo
    }
}
