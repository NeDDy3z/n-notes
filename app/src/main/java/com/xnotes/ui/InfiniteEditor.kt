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
import com.xnotes.core.infinite.CanvasSelection
import com.xnotes.core.infinite.CanvasViewport
import com.xnotes.core.infinite.EraseCanvasItems
import com.xnotes.core.infinite.MeshPart
import com.xnotes.core.infinite.OverlayTessellator
import com.xnotes.core.infinite.StrokeTessellator
import com.xnotes.core.infinite.EraseSession
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.ShapeConfig
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
        setInteractive = { active, linger -> view.setInteractive(active, linger) },
        configFor = { configFor(it) },
        onWetStroke = { publishWetStroke(it) },
        onCommitStroke = { commitStroke(it) },
        onEraseBegin = { EraseSession(document) },
        onEraseEnd = { commitErase(it) },
        onEraserCursor = { at, radius -> view.setEraserCursor(at, radius) },
        onPendingShape = { publishPendingShape(it) },
        onCommitShape = { commitItem(it) },
        shapeConfig = { shapeConfig },
        inkColor = { inkColor },
        detectShapes = { detectShapes },
        selection = { selection },
        itemsIn = { rect -> document.itemsIn(rect) },
        onSelectionChanged = { publishOverlay() },
        onCommitSelection = { commitSelection(it) },
        devicePxPerDp = { devicePxPerDp },
    )

    private val devicePxPerDp = appContext.resources.displayMetrics.density.toDouble()
    private val imageCodec = com.xnotes.platform.AndroidImageCodec()

    /** Per-tool style, with the toolbar's active ink colour folded in at draw time. */
    private val toolConfigs = HashMap<Tool, ToolConfig>()

    /** The armed tool, mirrored into Compose so the toolbar can show which one it is. */
    var tool by mutableStateOf(Tool.PEN)
        private set

    /** The active ink colour, used by any tool without a colour override of its own. */
    var inkColor by mutableStateOf(InkPalette.DEFAULT)
        private set

    /** The shape tool's style. */
    var shapeConfig by mutableStateOf(ShapeConfig())
        private set

    /** Whether a held freehand stroke may snap to the shape it looks like. */
    var detectShapes by mutableStateOf(true)
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

    /** What is selected, and the arithmetic of moving, scaling and rotating it. */
    var selection = CanvasSelection(document)
        private set

    /** True while anything is selected, so the chrome can offer the actions that need one. */
    var hasSelection by mutableStateOf(false)
        private set

    /** The GL-side mirror of the document. Fed by [modelListener]; never reads the model itself. */
    private val scene = CanvasScene()

    /** One low-priority thread decoding images, so a big photo never stalls a frame. */
    private val decodeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "xnotes-canvas-decode").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    /** Where inserted images are written; supplied by the host so both editors share one dir. */
    var imageDir: java.io.File? = null

    /**
     * Keeps the renderer in step with the model. Every mutation goes through [InfiniteDocument],
     * including the ones history performs, so undo and redo repaint through exactly this path with
     * nothing extra to remember.
     */
    private val modelListener = object : InfiniteDocument.Listener {
        override fun onItemAdded(item: CanvasItem) {
            pushItem(item)
        }

        override fun onItemRemoved(item: CanvasItem) {
            scene.remove(item)
        }

        override fun onOrderChanged() {
            // Once per structural edit rather than once per item, so an eraser drag that cuts a
            // dozen strokes publishes one ordering instead of a dozen.
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
        // Decoding reads a file and can take tens of milliseconds, so it never runs on the render
        // thread; the finished bitmap is picked up and uploaded at the start of the next frame.
        scene.decodeOn = { work -> decodeExecutor.execute(work) }
        document.listener = modelListener
    }

    /**
     * Set only while the stroke under the pen is being committed, so the message that adds it also
     * releases the wet buffer. Two messages would let a frame fall between them and blink.
     */
    private var committingWetStroke = false

    /** Tessellate [item] and hand the triangles to the renderer, or drop it if it draws nothing. */
    private fun pushItem(item: CanvasItem) {
        if (item is ImageItem) {
            scene.upsertImage(item, item.paintBounds())
            return
        }
        val started = System.nanoTime()
        val meshed = ItemMesher.mesh(item)
        scene.lastTessellateMs = (System.nanoTime() - started) / 1_000_000.0
        if (meshed == null || meshed.isEmpty) {
            scene.remove(item)
            return
        }
        scene.upsert(item, meshed.parts, meshed.bounds, committingWetStroke)
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
        // Leaving the selection tools drops the selection, so its chrome cannot linger over ink.
        if (tool != next && (tool == Tool.SELECT || tool == Tool.LASSO)) interaction.clearSelection()
        tool = next
        interaction.tool = next
    }

    /** Delete whatever is selected, as one undoable edit. */
    fun deleteSelection() {
        val items = selection.items
        if (items.isEmpty()) return
        val command = EraseCanvasItems.capture(document, items)
        document.removeAll(items)
        history.push(command)
        interaction.clearSelection()
        markDirty()
        refresh()
    }

    fun armInkColor(color: Rgba) {
        inkColor = color
    }

    fun setToolConfig(forTool: Tool, config: ToolConfig) {
        toolConfigs[forTool] = config
    }

    fun armShapeConfig(config: ShapeConfig) {
        shapeConfig = config
    }

    fun armDetectShapes(on: Boolean) {
        detectShapes = on
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
        val part = meshed.parts.firstOrNull() ?: return
        scene.setWet(part.mesh, part.color, part.pass, meshed.bounds)
    }

    /**
     * Rebuild the chrome drawn over the content: the selection box and handles, or whichever
     * marquee a drag is sweeping out. It goes through the same transient buffer the wet stroke
     * uses, since inking and selecting can never happen at once.
     */
    private fun publishOverlay() {
        hasSelection = !selection.isEmpty
        val accent = palette?.accent ?: InkPalette.DEFAULT
        val zoom = viewport.zoom
        val parts = ArrayList<MeshPart>(3)
        var bounds: Rect? = null
        interaction.bandRect?.let {
            parts += OverlayTessellator.band(it, zoom, accent, StrokeTessellator.DEFAULT_TOLERANCE)
            bounds = it.outset(4.0 / zoom)
        }
        if (interaction.lassoPoints.size >= 2) {
            val points = interaction.lassoPoints.toList()
            parts += OverlayTessellator.lasso(points, zoom, accent, StrokeTessellator.DEFAULT_TOLERANCE)
            bounds = Rect.bounding(points).outset(4.0 / zoom)
        }
        selection.box?.let { box ->
            parts += OverlayTessellator.selection(box, zoom, accent, StrokeTessellator.DEFAULT_TOLERANCE)
            val b = OverlayTessellator.selectionBounds(box, zoom)
            bounds = bounds?.union(b) ?: b
        }
        if (parts.isEmpty()) {
            scene.setWet(null, InkPalette.DEFAULT, InkPass.OPAQUE, Rect(0.0, 0.0, 0.0, 0.0))
        } else {
            scene.setWetParts(parts, bounds ?: Rect(0.0, 0.0, 0.0, 0.0))
        }
        view.publish()
    }

    /** A finished selection drag: record it, if it changed anything. */
    private fun commitSelection(command: com.xnotes.core.history.Command?) {
        if (command == null) return
        history.push(command)
        markDirty()
        refresh()
    }

    /** The shape being dragged out, drawn live over the committed geometry like a wet stroke. */
    private fun publishPendingShape(shape: ShapeItem?) {
        if (shape == null) {
            scene.setWet(null, InkPalette.DEFAULT, InkPass.OPAQUE, Rect(0.0, 0.0, 0.0, 0.0))
            return
        }
        val meshed = ItemMesher.mesh(shape) ?: return
        scene.setWetParts(meshed.parts, meshed.bounds)
    }

    /** Add a finished item and record the edit, the common tail of every creating tool. */
    private fun commitItem(item: CanvasItem) {
        committingWetStroke = true
        try {
            document.add(item)
        } finally {
            committingWetStroke = false
        }
        history.push(AddCanvasItem(document, item))
        markDirty()
        refresh()
    }

    /**
     * Insert an encoded image, centred on [atContent] or on the middle of the view. The bytes are
     * written to a file and the item keeps only that path, so a canvas full of photographs never
     * holds their pixels: each is decoded when drawn, at the size the screen can show.
     */
    fun insertImage(bytes: ByteArray, atContent: com.xnotes.core.geometry.Pt? = null): Boolean {
        val dir = imageDir ?: return false
        val file = runCatching {
            java.io.File.createTempFile("img", null, dir).apply { writeBytes(bytes) }
        }.getOrNull()
        val size = file?.let { imageCodec.probeFile(it.path) }
        if (file == null || size == null || size.width <= 0 || size.height <= 0) {
            file?.delete()
            return false
        }
        // Land it at a comfortable size for the current view rather than at its pixel size, which
        // on an unbounded canvas would be arbitrary.
        val visible = viewport.visibleContentRect()
        val maxW = visible.w * 0.6
        val maxH = visible.h * 0.6
        val scale = minOf(1.0, maxW / size.width, maxH / size.height)
        val w = size.width * scale
        val h = size.height * scale
        val centre = atContent ?: viewport.centerContent
        val rect = Rect(centre.x - w / 2.0, centre.y - h / 2.0, w, h)
        commitItem(ImageItem(ImageData(file, size.width, size.height), rect))
        return true
    }

    /** Pen up on an eraser drag: the whole drag is one undoable edit, however much it cut. */
    private fun commitErase(session: EraseSession) {
        val command = session.buildCommand() ?: return
        history.push(command)
        markDirty()
        refresh()
        view.publish()
    }

    /** Pen up: the finished stroke joins the document, and the edit joins the undo stack. */
    private fun commitStroke(stroke: Stroke) = commitItem(stroke)

    private fun markDirty() {
        document.dirty = true
        onContentChanged?.invoke()
    }

    /** Adopt the app's pen preferences, so the canvas and the paged note behave the same. */
    fun applyInputPrefs(fingerDraws: Boolean, penButtonTool: Tool?) {
        interaction.fingerDraws = fingerDraws
        interaction.penButtonTool = penButtonTool
    }

    private var palette: Palette? = null

    /** Adopt the chrome's palette, so the paper and the selection accent match the rest of the app. */
    fun applyPalette(palette: Palette) {
        this.palette = palette
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
        selection = CanvasSelection(next)
        hasSelection = false
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
