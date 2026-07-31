package com.xnotes.ui

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.History
import com.xnotes.core.infinite.AddCanvasItem
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.infinite.CanvasViewport
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.gl.CanvasScene
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
        configFor = { configFor(it) },
        onWetStroke = { publishWetStroke(it) },
        onCommitStroke = { commitStroke(it) },
        devicePxPerDp = { devicePxPerDp },
    )

    private val devicePxPerDp = appContext.resources.displayMetrics.density.toDouble()

    /** Per-tool style, with the toolbar's active ink colour folded in at draw time. */
    private val toolConfigs = HashMap<Tool, ToolConfig>()

    /** The armed tool, mirrored into Compose so the toolbar can show which one it is. */
    var tool by mutableStateOf(Tool.PEN)
        private set

    /** The active ink colour, used by any tool without a colour override of its own. */
    var inkColor by mutableStateOf(InkPalette.DEFAULT)
        private set

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

    /** Fired after any edit that makes the document dirty, so the host can schedule an autosave. */
    var onContentChanged: (() -> Unit)? = null

    /** Whether the debug HUD is up; toggled by a four-finger tap, as on the paged canvas. */
    var debugVisible by mutableStateOf(false)
        private set

    fun toggleDebug() {
        debugVisible = !debugVisible
        view.publish()
    }

    /** The GL-side mirror of the document. Fed by [modelListener]; never reads the model itself. */
    private val scene = CanvasScene()

    /**
     * Keeps the renderer in step with the model. Every mutation goes through [InfiniteDocument],
     * including the ones history performs, so undo and redo repaint through exactly this path with
     * nothing extra to remember.
     */
    private val modelListener = object : InfiniteDocument.Listener {
        override fun onItemAdded(item: CanvasItem) {
            pushItem(item)
            scene.setOrder(document.items)
            view.publish()
        }

        override fun onItemRemoved(item: CanvasItem) {
            scene.remove(item)
            scene.setOrder(document.items)
            view.publish()
        }

        override fun onItemChanged(item: CanvasItem) {
            pushItem(item)
            view.publish()
        }

        override fun onReset() {
            rebuildScene()
            view.publish()
        }
    }

    init {
        view.input = { interaction.onTouch(it) }
        view.genericMotion = { interaction.onGenericMotion(it) }
        view.afterLayout = { applyInitialView() }
        view.onContextReady = { renderFailure = view.failure }
        view.onFourFingerTap = { toggleDebug() }
        view.scene = scene
        document.listener = modelListener
    }

    /**
     * Set only while the stroke under the pen is being committed, so the message that adds it also
     * releases the wet buffer. Two messages would let a frame fall between them and blink.
     */
    private var committingWetStroke = false

    /** Tessellate [item] and hand the triangles to the renderer, or drop it if it draws nothing. */
    private fun pushItem(item: CanvasItem) {
        val started = System.nanoTime()
        val meshed = ItemMesher.mesh(item)
        scene.lastTessellateMs = (System.nanoTime() - started) / 1_000_000.0
        if (meshed == null) {
            scene.remove(item)
            return
        }
        scene.upsert(item, meshed.mesh, meshed.color, meshed.pass, meshed.bounds, committingWetStroke)
    }

    /** Re-tessellate the whole document, after a load or a wholesale list replacement. */
    private fun rebuildScene() {
        scene.reset()
        for (item in document.items) pushItem(item)
        scene.setOrder(document.items)
    }

    /** Repaint the canvas with whatever the model currently says. */
    fun requestRender() = view.publish()

    // --- tools ---

    fun armTool(next: Tool) {
        tool = next
        interaction.tool = next
    }

    fun armInkColor(color: Rgba) {
        inkColor = color
    }

    fun setToolConfig(forTool: Tool, config: ToolConfig) {
        toolConfigs[forTool] = config
    }

    fun configFor(forTool: Tool): ToolConfig {
        val base = toolConfigs.getOrPut(forTool) { ToolDefaults.configFor(forTool) }
        // A tool with a colour override always draws in its own colour; the rest follow the
        // toolbar's active ink.
        return base.copy(rgba = base.colorOverride ?: inkColor)
    }

    /** Latch a stylus side button that arrived as a key event, so the pen behaves as on a note. */
    fun onStylusButtonKey(keyCode: Int, down: Boolean): Boolean =
        interaction.onStylusButtonKey(keyCode, down)

    // --- drawing ---

    private fun publishWetStroke(stroke: Stroke?) {
        if (stroke == null) {
            scene.setWet(null, InkPalette.DEFAULT, InkPass.OPAQUE, Rect(0.0, 0.0, 0.0, 0.0))
            return
        }
        val meshed = ItemMesher.mesh(stroke) ?: return
        scene.setWet(meshed.mesh, meshed.color, meshed.pass, meshed.bounds)
    }

    /** Pen up: the finished stroke joins the document, and the edit joins the undo stack. */
    private fun commitStroke(stroke: Stroke) {
        committingWetStroke = true
        try {
            document.add(stroke)
        } finally {
            committingWetStroke = false
        }
        history.push(AddCanvasItem(document, stroke))
        markDirty()
        refresh()
    }

    private fun markDirty() {
        document.dirty = true
        onContentChanged?.invoke()
    }

    /** Adopt the app's pen preferences, so the canvas and the paged note behave the same. */
    fun applyInputPrefs(fingerDraws: Boolean, penButtonTool: Tool?) {
        interaction.fingerDraws = fingerDraws
        interaction.penButtonTool = penButtonTool
    }

    /** Adopt the chrome's palette, so the paper matches the rest of the app. */
    fun applyPalette(palette: Palette) {
        view.paperColor = document.background.paperColor ?: palette.paper
    }

    // --- documents ---

    fun newCanvas() {
        replaceDocument(InfiniteDocument())
    }

    fun replaceDocument(next: InfiniteDocument) {
        document.listener = null
        document = next
        next.listener = modelListener
        history.clear()
        interaction.resetGestureState()
        view.background = next.background
        view.paperColor = next.background.paperColor ?: view.paperColor
        rebuildScene()
        appliedInitialView = false
        applyInitialView()
        refresh()
        view.publish()
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
        markDirty()
    }

    fun setBackground(background: CanvasBackground) {
        document.background = background
        view.background = background
        view.paperColor = background.paperColor ?: view.paperColor
        markDirty()
    }

    // --- history ---

    fun undo() {
        history.undo()
        markDirty()
        refresh()
        view.publish()
    }

    fun redo() {
        history.redo()
        markDirty()
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
