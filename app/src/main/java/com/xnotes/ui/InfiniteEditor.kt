package com.xnotes.ui

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.History
import com.xnotes.core.infinite.AddCanvasItem
import com.xnotes.core.infinite.AddCanvasItems
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.infinite.CanvasSelection
import com.xnotes.core.infinite.CanvasViewport
import com.xnotes.core.infinite.EraseCanvasItems
import com.xnotes.core.infinite.MeshPart
import com.xnotes.core.infinite.Minimap
import com.xnotes.core.infinite.OverlayTessellator
import com.xnotes.core.infinite.ReplaceCanvasItems
import com.xnotes.core.infinite.StrokeTessellator
import com.xnotes.core.infinite.EraseSession
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.deepCopy
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
class InfiniteEditor(context: Context) : ToolPopupHost, SelectionMenuHost, LongPressMenuHost {

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
        onLiftSelection = { items, at -> scene.setLift(items, at) },
        devicePxPerDp = { devicePxPerDp },
        onMinimapPress = { vx, vy -> minimapTap(vx, vy) },
        onContextMenu = { vp, content -> contextMenu = ContextMenuTarget(vp.x, vp.y, content) },
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

    /** Whether the minimap is shown. */
    var minimapVisible by mutableStateOf(true)
        private set

    fun toggleMinimap() {
        minimapVisible = !minimapVisible
        view.minimapVisible = minimapVisible
    }

    /** Saved views, mirrored into Compose so the chrome can list them. */
    var waypoints by mutableStateOf<List<Waypoint>>(emptyList())
        private set

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
        view.minimapVisible = minimapVisible
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

    // --- selection actions ---

    /**
     * The selection's own clipboard, and the actions the floating menu offers.
     *
     * These are the canvas's siblings of the paged controller's, not a reuse of them: the paged ones
     * are written against pages, and every one of them would need a page index that does not exist
     * here. The behaviour they present is the same, which is what the shared [SelectionMenu] holds
     * them to.
     */
    private val clipboard = ArrayList<CanvasItem>()

    /** Only text needs a measurer to clone, and the canvas has none; this satisfies the signature. */
    private val textMeasurer = com.xnotes.platform.AndroidTextMeasurer()

    /** Where the floating menu sits, or null while a gesture is running or nothing is selected. */
    override var selectionMenuRect: Rect? by mutableStateOf(null)
        private set

    override val selectionMenuIsImage: Boolean
        get() = selection.items.singleOrNull() is ImageItem

    /** Re-anchor the floating menu over the settled selection, or take it away. */
    private fun refreshSelectionMenu() {
        val box = selection.box
        if (box == null || interaction.mode != CanvasPointerMode.IDLE) {
            selectionMenuRect = null
            return
        }
        val bounds = Rect.bounding(box.corners().map { viewport.contentToViewport(it) })
        // Lift the anchor's top clear of the rotate grip, which is drawn its arm plus its own
        // radius above the box. Without this the bar lands on the grip and buries it. The bottom
        // stays put, so the fallback placement below the selection is unchanged.
        val clearance = OverlayTessellator.GRIP_ARM_PX + OverlayTessellator.GRIP_PX / 2.0
        selectionMenuRect = Rect(bounds.x, bounds.y - clearance, bounds.w, bounds.h + clearance)
    }

    override fun dismissSelectionMenu() {
        selectionMenuRect = null
    }

    /** Delete whatever is selected, as one undoable edit. */
    override fun deleteSelection() {
        val items = selection.items
        if (items.isEmpty()) return
        val command = EraseCanvasItems.capture(document, items)
        document.removeAll(items)
        history.push(command)
        interaction.clearSelection()
        markDirty()
        refresh()
    }

    override fun copySelection() {
        if (selection.isEmpty) return
        clipboard.clear()
        selection.items.mapTo(clipboard) { it.deepCopy(textMeasurer) }
    }

    override fun cutSelection() {
        if (selection.isEmpty) return
        copySelection()
        deleteSelection()
    }

    /** Clone the selection a nudge down and right, and leave the copies selected. */
    override fun duplicateSelection() {
        if (selection.isEmpty) return
        val clones = selection.items.map { it.deepCopy(textMeasurer) }
        for (clone in clones) clone.translate(DUPLICATE_NUDGE, DUPLICATE_NUDGE)
        document.addAll(clones)
        history.push(AddCanvasItems(document, clones))
        selection.select(clones)
        markDirty()
        refresh()
        publishOverlay()
    }

    /** Paste the clipboard at [atContent], or a nudge from where it was copied. */
    fun pasteClipboard(atContent: Pt? = null) {
        if (clipboard.isEmpty()) return
        val clones = clipboard.map { it.deepCopy(textMeasurer) }
        var box: Rect? = null
        for (clone in clones) box = box?.union(clone.bounds()) ?: clone.bounds()
        val bounds = box ?: return
        val dx: Double
        val dy: Double
        if (atContent == null) {
            dx = DUPLICATE_NUDGE
            dy = DUPLICATE_NUDGE
        } else {
            dx = atContent.x - bounds.left
            dy = atContent.y - bounds.top
        }
        for (clone in clones) clone.translate(dx, dy)
        document.addAll(clones)
        history.push(AddCanvasItems(document, clones))
        selection.select(clones)
        armTool(Tool.SELECT)
        markDirty()
        refresh()
        publishOverlay()
    }

    override val hasClipboardItems: Boolean get() = clipboard.isNotEmpty()

    // --- long-press paste menu ---

    /** Where a held finger opened the paste menu, or null when no menu is open. */
    override var contextMenu: ContextMenuTarget? by mutableStateOf(null)

    override val clipboardHasImage: Boolean
        get() = com.xnotes.platform.SystemClipboard.hasImage(appContext)

    override fun dismissContextMenu() {
        contextMenu = null
    }

    override fun pasteItemsAt(content: Pt) {
        pasteClipboard(content)
    }

    override fun pasteClipboardImageAt(content: Pt) {
        val bytes = com.xnotes.platform.SystemClipboard.imageBytes(appContext) ?: return
        insertImage(bytes, content)
    }

    /** Put the selection on top. On a flat canvas that is purely a reorder of the item list. */
    override fun bringToFront() {
        if (selection.isEmpty) return
        val before = document.items.toList()
        val after = com.xnotes.core.infinite.bringToFrontOrder(before, selection.items)
        if (com.xnotes.core.infinite.sameOrder(before, after)) return
        document.replaceAll(after)
        history.push(ReplaceCanvasItems(document, before, after))
        markDirty()
        refresh()
        publishOverlay()
    }

    /** Turn the one selected image a quarter turn; the stored bytes are untouched, so it is lossless. */
    override fun rotateSelectedImage() {
        val item = selection.items.singleOrNull() as? ImageItem ?: return
        val oldRect = item.rect
        val oldOrientation = item.orientation
        val centre = oldRect.center
        val newRect = Rect(
            centre.x - oldRect.h / 2.0, centre.y - oldRect.w / 2.0, oldRect.h, oldRect.w,
        )
        val newOrientation = (oldOrientation + 90) % 360
        item.rect = newRect
        item.orientation = newOrientation
        document.itemsChanged(listOf(item))
        history.push(
            com.xnotes.core.infinite.OnCanvas(
                document,
                com.xnotes.core.history.RotateImage(
                    item, oldRect, oldOrientation, newRect, newOrientation,
                ),
                listOf(item),
            ),
        )
        selection.refreshBox()
        markDirty()
        refresh()
        publishOverlay()
    }

    fun armInkColor(color: Rgba) {
        inkColor = color
    }

    /** Arm swatch [index], the same way the paged toolbar picks its ink. */
    fun pickColor(index: Int) {
        if (index !in toolbarColors.indices) return
        activeColorIndex = index
        inkColor = toolbarColors[index]
        onToolStyleChanged?.invoke()
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

    /**
     * The stored style for [tool], without the ink colour folded in. The popups edit this, and it
     * is what gets persisted, so a pen tuned here is the same pen on a note.
     */
    override fun toolConfig(tool: Tool): ToolConfig =
        toolConfigs.getOrPut(tool) { ToolDefaults.configFor(tool) }

    override fun updateToolConfig(tool: Tool, config: ToolConfig) {
        toolConfigs[tool] = config
        onToolStyleChanged?.invoke()
    }

    override val hostShapeConfig: ShapeConfig get() = shapeConfig

    override fun updateShapeConfig(config: ShapeConfig) {
        shapeConfig = config
        onToolStyleChanged?.invoke()
    }

    override val hostToolbarColors: List<Rgba> get() = toolbarColors
    override val hostActiveColorIndex: Int get() = activeColorIndex
    override val hostRecentColors: List<Rgba> get() = recentColors

    /** The toolbar's swatches and recents, handed over by the host so both surfaces share them. */
    var toolbarColors by mutableStateOf(InkPalette.presets)
    var activeColorIndex by mutableStateOf(0)
    var recentColors by mutableStateOf<List<Rgba>>(emptyList())

    /** Fired when a tool's style changed here, so the host can persist it. */
    var onToolStyleChanged: (() -> Unit)? = null

    /** Latch a stylus side button that arrived as a key event, so the pen behaves as on a note. */
    fun onStylusButtonKey(keyCode: Int, down: Boolean): Boolean =
        interaction.onStylusButtonKey(keyCode, down)

    // --- drawing ---

    private fun publishWetStroke(stroke: Stroke?) {
        if (stroke == null) {
            scene.setWetParts(emptyList(), Rect(0.0, 0.0, 0.0, 0.0))
            return
        }
        val meshed = ItemMesher.mesh(stroke) ?: return
        // Every run, not just the first: a neon stroke is a halo, a lit body and a white core, and
        // keeping only one of them left the wet stroke invisible until the pen lifted.
        scene.setWetParts(meshed.parts, meshed.bounds)
    }

    /**
     * Rebuild the chrome drawn over the content: the selection box and handles, or whichever
     * marquee a drag is sweeping out. It goes through the same transient buffer the wet stroke
     * uses, since inking and selecting can never happen at once.
     */
    private fun publishOverlay() {
        hasSelection = !selection.isEmpty
        refreshSelectionMenu()
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

    /** Adopt the configured zoom range, then pull the current zoom back into it. */
    fun applyZoomRange(minPercent: Int, maxPercent: Int) {
        viewport.minZoom = (minPercent / 100.0).coerceAtLeast(0.0001)
        viewport.maxZoom = (maxPercent / 100.0).coerceAtLeast(viewport.minZoom)
        viewport.clampZoom()
        onViewChanged()
        view.publish()
    }

    /** Keyboard shortcuts. Only the ones that mean something without pages. */
    fun handleKeyDown(e: android.view.KeyEvent): Boolean {
        val ctrl = e.isCtrlPressed
        val shift = e.isShiftPressed
        when {
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_Z && shift -> redo()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_Z -> undo()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_Y -> redo()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_A -> selectAll()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_C -> copySelection()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_X -> cutSelection()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_V -> pasteClipboard()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_D -> duplicateSelection()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_0 -> zoomToFit()
            ctrl && (e.keyCode == android.view.KeyEvent.KEYCODE_PLUS || e.keyCode == android.view.KeyEvent.KEYCODE_EQUALS) -> zoomBy(ZOOM_STEP)
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_MINUS -> zoomBy(1.0 / ZOOM_STEP)
            e.keyCode == android.view.KeyEvent.KEYCODE_DEL || e.keyCode == android.view.KeyEvent.KEYCODE_FORWARD_DEL -> deleteSelection()
            e.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE -> interaction.clearSelection()
            else -> return false
        }
        return true
    }

    /** Select everything on the canvas, which on an unbounded one means literally everything. */
    fun selectAll() {
        if (document.isEmpty) return
        selection.select(document.items.toList())
        armTool(Tool.SELECT)
        publishOverlay()
    }

    fun zoomBy(factor: Double) {
        viewport.zoomAroundCenter(viewport.zoom * factor)
        onViewChanged()
        view.publish()
    }

    private var palette: Palette? = null

    /** Adopt the chrome's palette, so the paper and the selection accent match the rest of the app. */
    fun applyPalette(palette: Palette) {
        this.palette = palette
        view.paperColor = document.background.paperColor ?: palette.paper
        view.accent = palette.accent
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
        waypoints = document.waypoints.toList()
        markDirty()
    }

    fun removeWaypoint(waypoint: Waypoint) {
        document.waypoints.removeAll { it.name == waypoint.name }
        waypoints = document.waypoints.toList()
        markDirty()
    }

    /** A tap on the minimap: centre the view on whatever was tapped. */
    fun minimapTap(vx: Double, vy: Double): Boolean {
        if (!minimapVisible) return false
        val panel = Minimap.panel(viewport.widthPx, viewport.heightPx)
        if (!panel.contains(Pt(vx, vy))) return false
        val extent = Minimap.mappedExtent(document.contentBounds(), viewport.visibleContentRect())
        val target = Minimap.toContent(Pt(vx, vy), extent, panel)
        viewport.centerOn(target.x, target.y)
        onViewChanged()
        view.publish()
        return true
    }

    fun setBackground(background: CanvasBackground) {
        document.background = background
        view.background = background
        view.paperColor = background.paperColor ?: view.paperColor
        markDirty()
    }

    // --- history ---

    // History moves geometry the selection box cannot follow: an undone rotation puts the ink back
    // upright and leaves the box turned over it. The paged editor drops the selection for the same
    // reason, so both surfaces behave alike.
    fun undo() {
        history.undo()
        interaction.clearSelection()
        markDirty()
        refresh()
        view.publish()
    }

    fun redo() {
        history.redo()
        interaction.clearSelection()
        markDirty()
        refresh()
        view.publish()
    }

    private fun onViewChanged() {
        document.lastView = viewport.toWaypoint()
        zoomPercent = Math.round(viewport.zoom * 100).toInt()
        // The menu is anchored in viewport pixels, so a pan or a zoom moves it.
        refreshSelectionMenu()
        // The minimap maps everything drawn, so its extent moves with the content, not the view.
        view.contentBounds = document.contentBounds()
    }

    private fun refresh() {
        title = document.title
        canUndo = history.canUndo
        canRedo = history.canRedo
        waypoints = document.waypoints.toList()
        view.contentBounds = document.contentBounds()
    }

    companion object {
        /** Zoom step for the keyboard, matching a comfortable notch of a pinch. */
        const val ZOOM_STEP = 1.25

        /** Content pixels a duplicate lands from its original, so the copy is visibly a copy. */
        const val DUPLICATE_NUDGE = 24.0
    }
}
