package com.xnotes.canvas

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Obb
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.AddItem
import com.xnotes.core.history.AddItems
import com.xnotes.core.history.CompositeCommand
import com.xnotes.core.history.EraseItems
import com.xnotes.core.history.History
import com.xnotes.core.history.EditText
import com.xnotes.core.history.MoveItems
import com.xnotes.core.history.ReorderItems
import com.xnotes.core.history.ReplacePageItems
import com.xnotes.core.history.ResizeItem
import com.xnotes.core.history.RestyleText
import com.xnotes.core.history.RotateImage
import com.xnotes.core.history.TransformItems
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.deepCopy
import com.xnotes.core.model.GeoHandle
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.RectHandle
import com.xnotes.core.model.Resizable
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeHandle
import com.xnotes.core.model.GeometrySnapshot
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextHandle
import com.xnotes.core.model.TextItem
import com.xnotes.core.model.TextStyle
import com.xnotes.core.pal.BlendMode
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.stroke.RecognizedShape
import com.xnotes.core.stroke.Sample
import com.xnotes.core.stroke.ShapeRecognizer
import com.xnotes.core.stroke.StrokeSimplify
import com.xnotes.core.tools.EraseMode
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.tools.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.ui.theme.Palette
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/** The pointer state machine modes (spec 06 §1). */
enum class PointerMode {
    IDLE, DRAW, ERASE, BAND, LASSO_DRAW, SHOT, SHAPE, MOVE, RESIZE, TRANSFORM, PAN, PINCH, FLOW_TEXT,
    TEXT_DRAG, RULER_MOVE, RULER_TRANSFORM, RULER_ROTATE,
}

/**
 * Geometry of the live text editor field: [x]/[y] place its top-left in viewport pixels, while
 * [width]/[height]/[fontPx] are **content-space** (page pixels, pre-zoom) and [zoom] maps them
 * to the screen. The overlay lays text out at content scale and draws scaled by [zoom] — the
 * same mechanism as the baked painter — so its line wrapping matches the canvas exactly.
 */
data class EditingField(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val fontPx: Double,
    val zoom: Double,
    val face: FontFace,
    val rgba: Rgba,
    val text: String,
    /** The view's page rotation (deg cw); the overlay spins by this around (x, y). */
    val rotation: Int = 0,
)

/** The floating text style bar's target: the active box's viewport rect + its style. */
data class TextBar(
    val rect: Rect,
    val face: FontFace,
    val pointSize: Double,
    /** True while the keyboard field is up (vs the box merely being selected). */
    val editing: Boolean,
)

/**
 * Drives editing from pointer input (spec 06): drawing, the object eraser,
 * rubber-band and lasso selection, moving a selection, plus pan/zoom. Resize,
 * shapes, long-press and text are layered on in later commits.
 */
class InteractionController(
    private val state: CanvasState,
    val history: History,
    private val textMeasurer: TextMeasurer,
    private val requestRender: () -> Unit,
    private val onContentChanged: () -> Unit = {},
    private val onViewChanged: () -> Unit = {},
    /** A pinch just snapped the view to fit-to-width (newly): surface the lock hint. */
    private val onFitWidthSnapped: () -> Unit = {},
    /** A pinch broke past the fit-to-width magnet: dismiss the lock hint. */
    private val onFitWidthReleased: () -> Unit = {},
    private val onSelectionChanged: (Boolean) -> Unit = {},
    private val onToolChanged: (Tool) -> Unit = {},
    private val onTextEditStart: (EditingField?) -> Unit = {},
    private val onTextEditEnd: () -> Unit = {},
    /** Selection menu: a viewport rect to show it anchored to, or null to hide. */
    private val onSelectionMenu: (Rect?) -> Unit = {},
    /** Screenshot menu: a viewport rect to anchor the "copy as image" bar to, or null to hide. */
    private val onScreenshotMenu: (Rect?) -> Unit = {},
    /** Long-press on empty space: open a context menu at (viewport, content). */
    private val onContextMenu: (Pt, Pt) -> Unit = { _, _ -> },
    /** Pulled past the document's bottom end far enough and released: append a new page. */
    private val onAddPageAtEnd: () -> Unit = {},
    /** A short haptic tick (e.g. the overscroll pull crossed the add-page threshold). */
    private val onHaptic: () -> Unit = {},
) {
    /** Whether the system clipboard currently holds an image (provided by the host). */
    var clipboardHasImage: () -> Boolean = { false }

    /** The inline-flow caret controller; TEXT-tool gestures route here (installed by the Editor). */
    var flowText: FlowTextController? = null

    /** Host hook for tap-to-open PDF links. A finger tap landed on page [pageIndex] at [pageLocal]
     *  (page-local content px). Returns true if it hit a known link and was handled, so the tap is
     *  consumed (skipping the selection-dismiss / fling). The host parses link rects lazily off the
     *  main thread, so a tap on a not-yet-parsed page returns false and opens the link a moment later
     *  once it is ready. */
    var onLinkTap: ((pageIndex: Int, pageLocal: Pt) -> Boolean)? = null
    val document: Document get() = state.document

    var tool: Tool = Tool.DEFAULT
        private set

    /** The single immediately-prior Tool (not a stack); null until the first switch. Used by the
     *  tap-gesture toggles. Ruler/wand are never Tools, so they can't land here. Not persisted. */
    var previousTool: Tool? = null
        private set
    var inkColor: Rgba = InkPalette.DEFAULT

    /** Whether a finger draws (true) or pans (false). The stylus always uses the armed tool. */
    var fingerDraws: Boolean = false

    /** Panning allowed while zoom is locked: "single" (default) | "double" | "none". */
    var zoomLockPan: String = "single"

    /** Whether holding a freehand ink stroke still snaps it to a recognized shape (spec: "hold to snap"). */
    var detectShapes: Boolean = false

    /** Tool the stylus side button activates while held, or null to ignore the button. */
    var penButtonTool: Tool? = Tool.ERASER

    /** Side button as last seen on the hover/generic-motion stream or a stylus-button KeyEvent
     *  (Feeder C, for Bluetooth pens that report it only there); read only at touch-down, so a
     *  press after the pen is already down does not activate the mapped tool. */
    private var stylusButtonHeld = false

    /** When true, the side-button tool also runs off the hover stream (no contact needed); eraser/pan only. */
    var penButtonHover: Boolean = false

    /** The side-button tool currently running off the hover stream, or null when no hover gesture is live. */
    private var hoverActionTool: Tool? = null

    private val toolConfigs: MutableMap<Tool, ToolConfig> =
        Tool.entries.associateWith { ToolDefaults.configFor(it) }.toMutableMap()

    private var mode = PointerMode.IDLE

    // DRAW
    private var liveStroke: Stroke? = null
    private var strokePageIndex: Int? = null

    /** Event time of the live stroke's first sample; later samples store `eventTime − this` (the speed pen reads it). */
    private var strokeStartTimeMs = 0L
    private var drawingPointerId = -1
    private var drawingIsStylus = false

    // SHAPE SNAP (hold a freehand stroke still → it becomes a real shape)
    /** Pending "pen held still" timer; non-null only while a stroke is eligible and armed. */
    private var dwellRunnable: Runnable? = null
    /** Viewport px of the last point that re-armed the dwell timer (sub-slop jitter doesn't reset it). */
    private var dwellAnchor = Pt.ZERO
    /** True for the current stroke when snapping is allowed (pref on, an ink pen, not a straight line). */
    private var dwellEligible = false
    /** A mid-stroke snap auto-selected a shape; show its menu once the pen lifts (endDraw). */
    private var snappedSelectionPendingMenu = false
    /** This stroke began by dismissing an active selection; a bare tap then leaves no dot. */
    private var strokeDismissedSelection = false
    /** Viewport-px down point of the current stroke, for the tap-vs-drag dismiss test. */
    private var drawDownViewport = Pt.ZERO

    // PAN + inertial fling
    private var lastPan = Pt.ZERO
    private var lastMoveMs = 0L
    private var panVel = Pt.ZERO // smoothed finger velocity, viewport px/s
    private var panDownViewport = Pt.ZERO // where the current pan began (viewport px), for tap-to-dismiss
    private var downStoppedFling = false // this touch landed on a moving glide, so its lift isn't a dismiss tap
    private var panMayCommitText = false // pan begun off an open text box: a tap commits it, a drag scrolls
    // Framework singletons, created lazily on first use (always a gesture on the main thread) so
    // the controller's selection/edit logic stays constructible — and unit-testable — off-device.
    private val choreographer by lazy { Choreographer.getInstance() }
    private var flinging = false
    private var flingVel = Pt.ZERO // scroll-space velocity, viewport px/s
    private var lastFlingMs = 0L
    private val flingFrame = Choreographer.FrameCallback { frameTimeNanos -> stepFling(frameTimeNanos) }

    // ELASTIC OVERSCROLL (pull past the bottom end to add a page)
    /** True once the live stretch has crossed the add-page threshold, so the haptic fires once. */
    private var overscrollArmed = false
    private var overscrollSettling = false
    private var lastOverscrollMs = 0L
    private val overscrollFrame = Choreographer.FrameCallback { frameTimeNanos -> stepOverscrollSettle(frameTimeNanos) }

    // PINCH
    private var pinchInitDist = 1.0
    private var pinchInitZoom = 1.0
    private var pinchAnchorContent = Pt.ZERO

    // RULER (transient screen-space straightedge; no model/undo state)
    val ruler = Ruler()
    private var rulerGrabOffset = Pt.ZERO            // ruler.center − grab point, for 1-finger move
    private var rulerXformStartCentroid = Pt.ZERO    // two-finger transform anchors
    private var rulerXformStartFingerAngle = 0.0
    private var rulerXformStartCenter = Pt.ZERO
    private var rulerXformStartRuler = 0.0
    private var rulerRotateSign = 1.0                // +1 if dragging the +direction handle, −1 the other
    // SNAP (per-sample magnet, live for the current stroke only)
    private var snapEngaged = false
    private var snapTopSide = false                  // which long edge the ink is riding
    private var snapRunStartEdge: Pt? = null         // start of the current engaged run, on the edge (viewport)
    private var snapCurrentEdge: Pt? = null          // current point on the edge (viewport)
    private var snapPenViewport: Pt? = null          // actual (unprojected) pen, for readout placement

    // MAGIC WAND (ephemeral "disappearing ink"; no model/undo/cache/save state)
    private var wandMode = false
    private val fadingStrokes = mutableListOf<FadingStroke>()
    private var fadeAlpha = 1.0                       // shared multiplier, 1 = solid, 0 = gone
    private var fading = false                        // true while the fade loop runs
    private var fadeStartMs = 0L
    private val fadeFrame = Choreographer.FrameCallback { t -> stepFade(t) }
    private var fadeTimerRunnable: Runnable? = null

    private class FadingStroke(val stroke: Stroke, val pageIndex: Int)

    // SELECTION
    private val selection = mutableListOf<Selected>()
    private val lassoPoints = mutableListOf<Pt>()
    private var bandRect: Rect? = null
    private var moveOrigin = Pt.ZERO
    private var moveOffset = Pt.ZERO

    // SCREENSHOT (drag a rectangle; on release it stays frozen and offers "copy as image")
    /** The capture rectangle in content space: live while dragging (mode == SHOT), then frozen
     *  with its menu showing until copied, dismissed, or the tool changes. */
    var screenshotRect: Rect? = null
        private set
    private var screenshotOrigin = Pt.ZERO

    // ERASE
    private val eraseRemovals = mutableListOf<Pair<Page, CanvasItem>>()
    /** AREA mode: each touched page's item list snapshotted on first contact this gesture, so the
     *  whole split-and-trim drag undoes/redoes as one [ReplacePageItems] step. */
    private val eraseSnapshots = linkedMapOf<Page, List<CanvasItem>>()
    private var eraserCursor: Pt? = null // viewport pixels
    /** Tool armed just before the eraser was selected, for the "switch back after erasing" option. */
    private var toolBeforeEraser: Tool? = null
    /** Tool armed just before the select tool, for the "switch back after a selection action" option. */
    private var toolBeforeSelect: Tool? = null
    /** Tool armed just before the screenshot tool, to return to after a capture is copied. */
    private var toolBeforeScreenshot: Tool? = null
    /** Tool armed just before the text tool, to return to once an edit is committed. */
    private var toolBeforeText: Tool? = null
    /** Whether the live erase is finger-driven (vs the stylus eraser tip / side button): a finger
     *  erase yields to a two-finger pinch, a stylus erase ignores incidental finger/palm contact. */
    private var erasingWithFinger = false

    // ITEM CLIPBOARD (in-app, for copy/cut/paste/duplicate)
    private val itemClipboard = mutableListOf<CanvasItem>()
    fun hasClipboardItems(): Boolean = itemClipboard.isNotEmpty()

    // SHAPE
    var shapeConfig: ShapeConfig = ShapeConfig()
    private var pendingShape: ShapeItem? = null
    private var shapePageIndex: Int? = null

    // RESIZE
    private var resizeItem: CanvasItem? = null
    private var resizeHandle: HandleId? = null
    private var resizeOldGeom: GeoHandle? = null
    private var resizePageIndex: Int = -1

    // GENERIC TRANSFORM (resize + rotate for any single non-line / multi / mixed selection)
    private var selObb: Obb? = null // the tilting selection box; null when nothing is selected
    private var txItems: List<Selected> = emptyList()
    private var txSnaps: List<GeometrySnapshot> = emptyList()
    private var txStartObb: Obb? = null
    private var txHandle: HandleId? = null // non-null = scale via this box handle; null = rotate
    private var txCenter = Pt.ZERO
    private var txGrabAngle = 0.0
    private var txStartAngle = 0.0

    // LONG-PRESS GRAB
    private val handler by lazy { Handler(Looper.getMainLooper()) } // lazy: see [choreographer]
    private var longPressRunnable: Runnable? = null
    private var longPressStart = Pt.ZERO
    private var longPressContent = Pt.ZERO
    private var longPressCandidate: Selected? = null
    private var longPressPrevTool: Tool? = null

    // TEXT EDITING
    private var editingText: TextItem? = null
    private var editingIsNew = false
    private var editingOldText = ""
    private var editingPageIndex = -1
    val editingItem: TextItem? get() = editingText
    val editingPage: Int get() = editingPageIndex

    /** The style new text boxes are created with; mirrors the active box while one is open. */
    var textFace: FontFace = TextItem.DEFAULT_FACE
        private set
    var textPointSize: Double = TextItem.DEFAULT_POINT_SIZE
        private set

    // TEXT DRAG-CREATE (drag a rectangle to size a new box; a tap makes a default one)
    private var textDragStart = Pt.ZERO // content space
    private var textDragRect: Rect? = null // content space, for the live preview
    private var textDragPageIndex = -1

    init {
        state.isLiftedItem = { item -> item === editingText || selection.any { it.item === item } }
    }

    val hasSelection: Boolean get() = selection.isNotEmpty()

    /** True when exactly one image is selected (drives the rotate affordance). */
    val selectionIsSingleImage: Boolean get() = selection.size == 1 && selection[0].item is ImageItem

    /** The in-progress stroke and the page it is on (for the presentation frame). */
    val activeLiveStroke: Stroke? get() = liveStroke
    val activeLiveStrokePage: Int? get() = strokePageIndex

    fun configFor(t: Tool): ToolConfig = toolConfigs[t] ?: ToolDefaults.configFor(t)

    fun setToolConfig(t: Tool, config: ToolConfig) {
        toolConfigs[t] = config
    }

    fun setTool(t: Tool) {
        if (t == tool) {
            return
        }
        // Remember what to re-arm if the eraser/select/screenshot/text later switches back (the tool it replaced).
        if (t == Tool.ERASER) toolBeforeEraser = tool
        if (t == Tool.SELECT) toolBeforeSelect = tool
        if (t == Tool.SCREENSHOT) toolBeforeScreenshot = tool
        if (t == Tool.TEXT_BOX) toolBeforeText = tool
        commitTextEdit()
        if (t != Tool.TEXT) flowText?.endSession()
        abortGesture()
        clearSelection()
        clearScreenshot()
        eraserCursor = null
        previousTool = tool
        tool = t
        onToolChanged(t)
        requestRender()
    }

    fun rulerVisible(): Boolean = ruler.visible

    /** Toggle the on-screen ruler; on first show it places itself in the current viewport. */
    fun toggleRuler() {
        ruler.visible = !ruler.visible
        if (ruler.visible && !ruler.initialized) {
            ruler.placeDefault(state.viewportW.toDouble(), state.viewportH.toDouble(), state.devicePxPerDp)
        }
        requestRender()
    }

    fun wandEnabled(): Boolean = wandMode

    /** Toggle disappearing-ink mode; turning it off vanishes anything currently held or fading. */
    fun toggleWand() {
        wandMode = !wandMode
        if (!wandMode) clearFading()
        requestRender()
    }

    // --- touch entry point ---

    fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(e)
            MotionEvent.ACTION_MOVE -> handleMove(e)
            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(e)
            MotionEvent.ACTION_UP -> handleUp(e)
            MotionEvent.ACTION_CANCEL -> {
                abortGesture()
                requestRender()
            }
        }
        return true
    }

    fun onHover(e: MotionEvent): Boolean {
        if (handleHoverAction(e)) return true
        val isEraserPointer = e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
        if (tool != Tool.ERASER && !isEraserPointer) return false
        eraserCursor = if (e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            null
        } else {
            Pt(e.x.toDouble(), e.y.toDouble())
        }
        requestRender()
        return true
    }

    /** Drive the side-button tool (eraser/pan) off the hover stream while the button is held and
     *  "activate during hover" is on, so the pen erases or pans without touching the screen. */
    private fun handleHoverAction(e: MotionEvent): Boolean {
        val buttonNow = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS &&
            e.actionMasked != MotionEvent.ACTION_HOVER_EXIT &&
            ((e.buttonState and STYLUS_BUTTON_MASK) != 0 || stylusButtonHeld)
        val want = penButtonHover && buttonNow &&
            (penButtonTool == Tool.ERASER || penButtonTool == Tool.PAN)
        val vx = e.x.toDouble()
        val vy = e.y.toDouble()
        return when {
            want && hoverActionTool == null -> { beginHoverAction(vx, vy); true }
            want && hoverActionTool != null -> { extendHoverAction(vx, vy); true }
            hoverActionTool != null -> { endHoverAction(); true }
            else -> false
        }
    }

    private fun beginHoverAction(vx: Double, vy: Double) {
        hoverActionTool = penButtonTool
        when (penButtonTool) {
            Tool.ERASER -> { clearSelection(); beginErase(vx, vy) }
            Tool.PAN -> beginPan(vx, vy)
            else -> hoverActionTool = null
        }
        requestRender()
    }

    private fun extendHoverAction(vx: Double, vy: Double) {
        when (hoverActionTool) {
            Tool.ERASER -> eraseAt(vx, vy)
            Tool.PAN -> extendPan(vx, vy)
            else -> Unit
        }
    }

    /** End a live hover gesture: commit the erase, or stop the pan (with a flick if it was moving). */
    private fun endHoverAction() {
        when (hoverActionTool) {
            Tool.ERASER -> endErase()
            Tool.PAN -> { mode = PointerMode.IDLE; startFling(panVel) }
            else -> Unit
        }
        hoverActionTool = null
        requestRender()
    }

    /** Some pens report the side button only on the hovering generic-motion stream
     *  (ACTION_BUTTON_PRESS/RELEASE), never in the touch buttonState. Latch it here; a release
     *  here also ends an in-progress hover gesture even if the pen has not moved. */
    fun onGenericMotion(e: MotionEvent) {
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            stylusButtonHeld = (e.buttonState and STYLUS_BUTTON_MASK) != 0
            if (!stylusButtonHeld && hoverActionTool != null) endHoverAction()
        }
    }

    /** Feeder C: Bluetooth/USI pens often deliver the side button only as a KeyEvent, never in any
     *  MotionEvent buttonState. Latch those into the same held flag ([down] true on key-down); a
     *  key-up also ends a live hover gesture. Returns true if the key was a stylus side button, so
     *  the host consumes it. */
    fun onStylusButtonKey(keyCode: Int, down: Boolean): Boolean {
        if (keyCode != KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY &&
            keyCode != KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY &&
            keyCode != KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY &&
            keyCode != KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL &&
            keyCode != VENDOR_HELD_BUTTON_KEYCODE
        ) {
            return false
        }
        stylusButtonHeld = down
        if (!down && hoverActionTool != null) endHoverAction()
        return true
    }

    private fun handleDown(e: MotionEvent) {
        if (hoverActionTool != null) endHoverAction() // a hovering side-button gesture yields to contact
        downStoppedFling = flinging // captured before stopping: a tap that only halts a glide must not dismiss
        stopFling() // a new touch halts any in-progress glide
        stopOverscrollSettle() // ...and lets a re-grab take over the elastic mid-spring
        panMayCommitText = false // a fresh gesture; the editing branch below re-arms it if it applies
        val toolType = e.getToolType(0)
        val vx = e.getX(0).toDouble()
        val vy = e.getY(0).toDouble()
        val content = state.viewportToContent(Pt(vx, vy))
        drawingPointerId = e.getPointerId(0)
        drawingIsStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS

        // Resolve which tool this pointer drives:
        //  - the stylus eraser end, or the held side button, force the eraser/side-button tool;
        //  - a finger pans unless finger-draw is enabled;
        //  - otherwise the armed tool.
        val buttonHeld = drawingIsStylus &&
            ((e.buttonState and STYLUS_BUTTON_MASK) != 0 || stylusButtonHeld)
        val effectiveTool: Tool = when {
            toolType == MotionEvent.TOOL_TYPE_ERASER -> Tool.ERASER
            buttonHeld && penButtonTool != null -> penButtonTool!!
            // While something is selected, the stylus grabs that selection (resize on a handle,
            // move on the body) instead of inking through it, matching the finger. Off the
            // selection it falls through to draw and dismisses the selection (see beginDraw).
            hasSelection && drawingIsStylus && (tool.isStroke || tool == Tool.SHAPE) &&
                fingerHitsSelection(content) -> Tool.SELECT
            // A finger may grab/resize the ACTIVE selection even when finger-draw is off;
            // off the selection it still pans.
            toolType == MotionEvent.TOOL_TYPE_FINGER && !fingerDraws && tool.fingerPansWhenOff &&
                fingerHitsSelection(content) -> Tool.SELECT
            // A finger otherwise pans instead of drawing/selecting/shaping/erasing (text stays usable by finger).
            toolType == MotionEvent.TOOL_TYPE_FINGER && !fingerDraws && tool.fingerPansWhenOff -> Tool.PAN
            else -> tool
        }

        // A press off the box while editing. With a finger we defer: a tap commits (re-arming the
        // prior tool), a drag scrolls the page with the edit kept live (decided in handleUp's PAN
        // branch). A stylus / side-button / eraser press commits now and proceeds as usual. A Text
        // press never spawns a new box on the same gesture (that double-create was a bug).
        if (editingText != null) {
            if (toolType == MotionEvent.TOOL_TYPE_FINGER && effectiveTool == Tool.TEXT_BOX) {
                panMayCommitText = true
                beginPan(vx, vy)
                return
            }
            commitTextEdit(restoreTool = true)
            if (effectiveTool == Tool.TEXT_BOX) {
                mode = PointerMode.IDLE
                return
            }
        }

        // Touching the ruler grabs it before the normal tool dispatch — for the stylus too, so a
        // pen-down ON the body moves it. A pen-down OFF the body falls through to drawing, where the
        // magnet snaps the in-progress stroke to the edge. Its buttons toggle; its body moves it.
        if (ruler.visible) {
            val v = Pt(vx, vy)
            val hi = ruler.hitHandle(v, rulerHandleDist(), (RULER_HANDLE_HIT * state.devicePxPerDp).coerceAtLeast(ruler.handleRadiusPx()))
            if (hi != null && !ruler.lockAngle) {
                rulerRotateSign = if (hi == 0) 1.0 else -1.0
                mode = PointerMode.RULER_ROTATE
                cancelLongPress()
                requestRender()
                return
            }
            val btn = ruler.hitButton(v, (RULER_BTN_HIT * state.devicePxPerDp).coerceAtLeast(ruler.buttonRadiusPx()))
            if (btn != null) {
                when (btn) {
                    RulerButton.LOCK_POS -> ruler.lockPosition = !ruler.lockPosition
                    RulerButton.LOCK_ANGLE -> ruler.lockAngle = !ruler.lockAngle
                }
                mode = PointerMode.IDLE
                requestRender()
                return
            }
            // Move zone: the body, minus an inner margin (as wide as the magnet band) along each edge
            // for the STYLUS — so a pen drawing right along the edge never accidentally grabs the ruler.
            val band = RULER_SNAP_DP * state.devicePxPerDp
            val moveHalf =
                if (toolType == MotionEvent.TOOL_TYPE_STYLUS) (ruler.thicknessPx / 2.0 - band).coerceAtLeast(0.0)
                else ruler.thicknessPx / 2.0
            if (abs(ruler.signedAcross(v)) <= moveHalf) {
                if (ruler.lockPosition) {
                    mode = PointerMode.IDLE // locked: swallow so it neither pans nor draws under the ruler
                } else {
                    mode = PointerMode.RULER_MOVE
                    rulerGrabOffset = ruler.center - v
                }
                requestRender()
                return
            }
        }

        // Zoom-lock pan preference: swallow a single-finger pan when locked and set to "double"/"none".
        if (effectiveTool == Tool.PAN && !singleFingerPanAllowed()) {
            mode = PointerMode.IDLE
            return
        }

        when {
            effectiveTool == Tool.PAN -> beginPan(vx, vy)
            effectiveTool.isStroke -> beginDraw(content, resolvePressure(e, 0, toolType), effectiveTool, e.eventTime, Pt(vx, vy))
            effectiveTool == Tool.ERASER -> {
                clearSelection()
                erasingWithFinger = toolType == MotionEvent.TOOL_TYPE_FINGER
                beginErase(vx, vy)
            }
            effectiveTool == Tool.SELECT -> beginSelect(content)
            effectiveTool == Tool.LASSO -> beginLasso(content)
            effectiveTool == Tool.SCREENSHOT -> beginScreenshot(content)
            effectiveTool == Tool.SHAPE -> beginShape(content)
            effectiveTool == Tool.TEXT -> beginTextGesture(content, Pt(vx, vy))
            effectiveTool == Tool.TEXT_BOX -> beginTextBoxGesture(content)
            else -> Unit
        }
        // The flow caret owns its own long press (word selection), and mid text-drag it is
        // suppressed so a hold-then-drag still sizes a box.
        if (mode != PointerMode.FLOW_TEXT && mode != PointerMode.TEXT_DRAG) {
            armLongPress(Pt(vx, vy), content, toolType == MotionEvent.TOOL_TYPE_FINGER)
        }
    }

    private fun handlePointerDown(e: MotionEvent) {
        cancelLongPress()
        // A second finger on a ruler being moved twists/translates it instead of pinch-zooming.
        if (mode == PointerMode.RULER_MOVE && e.pointerCount >= 2) {
            beginRulerTransform(e)
            return
        }
        if (mode == PointerMode.RULER_ROTATE) return // handle-drag rotation ignores extra fingers
        if (mode == PointerMode.DRAW && drawingIsStylus) return
        // A finger erase (finger-draw on) yields to a two-finger pinch: commit what was erased so
        // far as one undo step, then start the zoom. A stylus-eraser erase keeps ignoring incidental
        // finger/palm contact, so a resting hand never starts a zoom mid-erase.
        if (mode == PointerMode.ERASE) {
            if (erasingWithFinger && e.pointerCount >= 2) { endErase(); beginPinch(e) }
            return
        }
        if (e.pointerCount >= 2) beginPinch(e)
    }

    private fun handleMove(e: MotionEvent) {
        val idx = e.findPointerIndex(drawingPointerId).coerceAtLeast(0)
        val vx = e.getX(idx).toDouble()
        val vy = e.getY(idx).toDouble()
        val content = state.viewportToContent(Pt(vx, vy))
        maybeCancelLongPress(Pt(vx, vy))
        when (mode) {
            PointerMode.DRAW -> extendDraw(e)
            PointerMode.PAN -> extendPan(vx, vy)
            PointerMode.PINCH -> updatePinch(e)
            PointerMode.RULER_MOVE -> { ruler.center = Pt(vx, vy) + rulerGrabOffset; requestRender() }
            PointerMode.RULER_TRANSFORM -> updateRulerTransform(e)
            PointerMode.RULER_ROTATE -> updateRulerRotate(e)
            PointerMode.ERASE -> eraseAt(vx, vy)
            PointerMode.BAND -> extendBand(content)
            PointerMode.LASSO_DRAW -> extendLasso(content)
            PointerMode.SHOT -> extendScreenshot(content)
            PointerMode.MOVE -> extendMove(content)
            PointerMode.RESIZE -> extendResize(content)
            PointerMode.TRANSFORM -> extendTransform(content)
            PointerMode.SHAPE -> extendShape(content)
            PointerMode.FLOW_TEXT ->
                // A plain drag with the text tool scrolls the document; only a long-pressed
                // drag extends the selection (dragTo returns true to request the pan handoff).
                if (flowText?.dragTo(content, Pt(vx, vy)) == true) beginPan(vx, vy)
            PointerMode.TEXT_DRAG -> extendTextDrag(content)
            else -> Unit
        }
    }

    private fun handlePointerUp(e: MotionEvent) {
        if (mode == PointerMode.RULER_TRANSFORM) {
            // One finger lifted: fall back to a single-finger move with whichever finger remains.
            val up = e.actionIndex
            val remaining = (0 until e.pointerCount).firstOrNull { it != up }
            if (remaining != null) {
                drawingPointerId = e.getPointerId(remaining)
                rulerGrabOffset = ruler.center - Pt(e.getX(remaining).toDouble(), e.getY(remaining).toDouble())
                mode = PointerMode.RULER_MOVE
            } else {
                mode = PointerMode.IDLE
            }
            requestRender()
            return
        }
        if (mode == PointerMode.PINCH && e.pointerCount <= 2) endPinch()
    }

    private fun handleUp(e: MotionEvent) {
        cancelLongPress()
        val idx = e.findPointerIndex(drawingPointerId).coerceAtLeast(0)
        val content = state.viewportToContent(Pt(e.getX(idx).toDouble(), e.getY(idx).toDouble()))
        when (mode) {
            PointerMode.DRAW -> endDraw(e)
            PointerMode.PAN -> {
                mode = PointerMode.IDLE
                val upViewport = Pt(e.getX(idx).toDouble(), e.getY(idx).toDouble())
                val tap = !downStoppedFling && upViewport.distanceTo(panDownViewport) <= TAP_SLOP
                if (panMayCommitText) {
                    // Press off the box while editing: only a tap commits (and re-arms the prior tool);
                    // a drag just scrolled with the edit kept live. Either way settle any elastic/glide.
                    panMayCommitText = false
                    when {
                        tap -> commitTextEdit(restoreTool = true)
                        state.overscrollY > 0.0 -> releaseOverscroll()
                        !state.verticalScroll -> endPanPaginated()
                        else -> startFling(panVel)
                    }
                } else {
                    // A finger tap (no drag) that didn't just halt a glide, landing off the current
                    // selection, dismisses it — the finger's counterpart to the stylus's empty-tap clear.
                    val linkHandled = tap && state.overscrollY <= 0.0 && tryLinkTap(content)
                    when {
                        linkHandled -> Unit
                        state.overscrollY > 0.0 -> releaseOverscroll()
                        tap && hasSelection && selectionBoundsContent()?.contains(content) != true -> clearSelection()
                        !state.verticalScroll -> endPanPaginated()
                        else -> startFling(panVel)
                    }
                }
            }
            PointerMode.PINCH -> endPinch()
            PointerMode.ERASE -> { endErase(); maybeSwitchBackAfterErase() }
            PointerMode.BAND -> endBand()
            PointerMode.LASSO_DRAW -> endLasso()
            PointerMode.SHOT -> endScreenshot()
            PointerMode.MOVE -> endMove(content)
            PointerMode.RESIZE -> endResize()
            PointerMode.TRANSFORM -> endTransform()
            PointerMode.SHAPE -> endShape()
            PointerMode.FLOW_TEXT -> {
                mode = PointerMode.IDLE
                flowText?.release(content, Pt(e.getX(idx).toDouble(), e.getY(idx).toDouble()), e.eventTime)
            }
            PointerMode.TEXT_DRAG -> endTextDrag(content)
            PointerMode.RULER_MOVE -> { mode = PointerMode.IDLE; requestRender() }
            PointerMode.RULER_TRANSFORM -> { mode = PointerMode.IDLE; requestRender() }
            PointerMode.RULER_ROTATE -> { mode = PointerMode.IDLE; requestRender() }
            else -> Unit
        }
    }

    /** Map a finger tap's content point to its page + page-space point and offer it to [onLinkTap]. */
    private fun tryLinkTap(content: Pt): Boolean {
        val cb = onLinkTap ?: return false
        val pageIndex = state.pageIndexAtContent(content) ?: return false
        return cb(pageIndex, state.toPageSpace(pageIndex, content))
    }

    // --- DRAW ---

    private fun beginDraw(content: Pt, pressure: Double, drawTool: Tool, timeMs: Long, downViewport: Pt) {
        val pageIndex = state.pageIndexAtContent(content) ?: return
        // Reaching here with a live selection means the press landed off it (on it the stylus would
        // have grabbed it): dismiss it. A bare tap that only dismissed is dropped in endDraw.
        strokeDismissedSelection = hasSelection
        drawDownViewport = downViewport
        if (hasSelection) clearSelection()
        // Capture content-px → dp scale now, so the speed pen judges gesture speed in
        // zoom- and density-independent dp regardless of how the stroke is later viewed.
        val speedScale = state.zoom / state.devicePxPerDp
        val cfg0 = configFor(drawTool)
        // SCALE off: normalise the stroke to its 100%-zoom size by dividing the spatial
        // dimensions by the draw-time zoom, so it draws at a constant on-screen thickness
        // whatever zoom you are at. Baked into the snapshot, so it is ordinary ink afterwards.
        // A pen with a colour override always draws in its own colour; otherwise it follows the
        // toolbar's active ink colour.
        val drawColor = cfg0.colorOverride ?: inkColor
        val cfg = if (cfg0.scale) {
            cfg0.copy(rgba = drawColor)
        } else {
            val z = state.zoom
            cfg0.copy(
                rgba = drawColor,
                baseWidth = cfg0.baseWidth / z,
                dashLength = cfg0.dashLength / z,
                dashGap = cfg0.dashGap / z,
                scale = true,
            )
        }
        val straight = drawTool == Tool.HIGHLIGHTER && cfg.straightLine
        val stroke = Stroke(
            drawTool, cfg, speedScale = speedScale, straight = straight,
            smoothScale = smoothScaleFor(state.zoom),
        )
        // Live until pen-up, so lift-time rules (the calligraphy dot swell) can't fire mid-draw.
        stroke.finished = false
        strokeStartTimeMs = timeMs
        // Pen back down: re-solidify the held batch, so a long stroke can't outlive the previous fade.
        if (wandMode && drawTool.isStroke) {
            stopFade()
            fadeAlpha = 1.0
            scheduleFade()
        }
        // Ruler magnet: reset per-stroke engagement, then snap the first sample if it lands in the zone.
        snapEngaged = false
        snapRunStartEdge = null
        snapCurrentEdge = null
        snapPenViewport = null
        val first = state.toPageSpace(pageIndex, state.viewportToContent(magnetize(downViewport)))
        stroke.addSample(Sample(first.x, first.y, pressure)) // first sample: t = 0
        liveStroke = stroke
        strokePageIndex = pageIndex
        mode = PointerMode.DRAW
        // Shape snap: only solid ink pens (not the highlighter or its straight-line mode) arm the
        // "hold still → shape" timer — and never while the ruler is up (you're drawing straight lines),
        // nor under the wand, whose strokes are ephemeral and must never commit a shape to the page.
        dwellEligible = detectShapes && drawTool.isStroke && drawTool != Tool.HIGHLIGHTER && !straight &&
            !ruler.visible && !wandMode
        if (dwellEligible) {
            dwellAnchor = downViewport
            armDwell()
        }
        requestRender()
    }

    private fun extendDraw(e: MotionEvent) {
        val idx = e.findPointerIndex(drawingPointerId)
        if (idx < 0) return
        for (h in 0 until e.historySize) {
            addStrokePoint(
                e.getHistoricalX(idx, h).toDouble(),
                e.getHistoricalY(idx, h).toDouble(),
                if (drawingIsStylus) e.getHistoricalPressure(idx, h).toDouble() else 1.0,
                e.getHistoricalEventTime(h),
                force = false,
            )
        }
        addStrokePoint(
            e.getX(idx).toDouble(), e.getY(idx).toDouble(),
            if (drawingIsStylus) e.getPressure(idx).toDouble() else 1.0, e.eventTime, force = false,
        )
        requestRender()
    }

    private fun addStrokePoint(vx: Double, vy: Double, pressure: Double, timeMs: Long, force: Boolean) {
        val stroke = liveStroke ?: return
        val pi = strokePageIndex ?: return
        if (state.pageRects.getOrNull(pi) == null) return
        val vp = magnetize(Pt(vx, vy))
        val content = state.viewportToContent(vp)
        val local = state.toPageSpace(pi, content)
        if (stroke.straight) {
            // Straight-line mode: the stroke is always pen-down → current point, so the moving
            // endpoint just tracks the pointer (decimation/spacing gates don't apply). Near an axis
            // it snaps flat like a dragged line, unless the ruler is already steering the angle.
            val start = stroke.samples.firstOrNull()
            val end = if (start != null && !ruler.visible) snapAxisEndpoint(Pt(start.x, start.y), local) else local
            stroke.setStraightEnd(Sample(end.x, end.y, pressure.coerceIn(0.0, 1.0), (timeMs - strokeStartTimeMs).toDouble()))
            return
        }
        val last = stroke.samples.lastOrNull()
        // Decimate by on-screen spacing, not content spacing: the gate is MIN_SAMPLE_DIST
        // viewport px (content px ÷ zoom), capped so it never coarsens past the old 1-content-px
        // floor when zoomed out. A fixed content-px gate discarded ever-finer detail the more you
        // zoomed in, so strokes drawn while zoomed faceted into ~zoom-px chords.
        val gate = (MIN_SAMPLE_DIST / state.zoom).coerceAtMost(MIN_SAMPLE_DIST)
        if (force || last == null || Pt(last.x, last.y).manhattanTo(local) >= gate) {
            stroke.addSample(Sample(local.x, local.y, pressure.coerceIn(0.0, 1.0), (timeMs - strokeStartTimeMs).toDouble()))
        }
        // Real movement restarts the hold-to-snap clock; staying within the slop lets it mature,
        // so the snap fires only once the pen has actually come to rest. (Sub-slop jitter is ignored
        // even when a sample is decimated out above, so a trembling-but-still pen still triggers.)
        if (dwellEligible && Pt(vx, vy).distanceTo(dwellAnchor) > SHAPE_DWELL_SLOP) {
            dwellAnchor = Pt(vx, vy)
            armDwell()
        }
    }

    private fun endDraw(e: MotionEvent) {
        // Drop the dwell timer before the final sample so the lift can't re-arm or fire a snap.
        cancelDwell()
        dwellEligible = false
        val idx = e.findPointerIndex(drawingPointerId).coerceAtLeast(0)
        addStrokePoint(
            e.getX(idx).toDouble(), e.getY(idx).toDouble(),
            if (drawingIsStylus) e.getPressure(idx).toDouble() else 1.0, e.eventTime, force = true,
        )
        // A mid-stroke snap already committed a shape and cleared liveStroke, so this block is
        // skipped and the freehand stroke is intentionally not also committed.
        val stroke = liveStroke
        val pi = strokePageIndex
        val up = Pt(e.getX(idx).toDouble(), e.getY(idx).toDouble())
        if (stroke != null && pi != null && !stroke.isEmpty) {
            // The pen is up: rebuild with lift-time rules on (a dot-sized calligraphy stroke
            // takes the nib's broad face) before the stroke is committed or held for fading.
            stroke.finished = true
            when {
                // A bare tap whose only job was to dismiss the selection: drop the dot it would leave.
                strokeDismissedSelection && up.distanceTo(drawDownViewport) <= TAP_SLOP -> Unit
                wandMode && stroke.tool.isStroke -> {
                    // Disappearing ink: held ephemerally, never committed to the model/undo/cache/save.
                    // A stroke drawn mid-fade cancels the fade and re-solidifies the whole held batch.
                    fadingStrokes.add(FadingStroke(stroke, pi))
                    stopFade()
                    fadeAlpha = 1.0
                    scheduleFade()
                }
                else -> {
                    simplifyForCommit(stroke)
                    val page = state.document.pages[pi]
                    page.items.add(stroke)
                    state.appendToCache(page, stroke)
                    history.push(AddItem(page, stroke))
                    state.document.dirty = true
                    onContentChanged()
                }
            }
        }
        strokeDismissedSelection = false
        liveStroke = null
        strokePageIndex = null
        snapEngaged = false
        snapRunStartEdge = null
        snapCurrentEdge = null
        snapPenViewport = null
        mode = PointerMode.IDLE
        // A mid-stroke snap left a shape selected; now that the gesture is idle, surface its menu.
        if (snappedSelectionPendingMenu) {
            snappedSelectionPendingMenu = false
            refreshSelectionMenu()
        }
        requestRender()
    }

    /** Pen-up sample reduction: like the capture gate, the tolerance is screen-space — viewport
     *  px at the draw zoom (÷ zoom → content px), capped so zoomed-out ink keeps content fidelity.
     *  The stroke's just-built geometry supplies the half-width channel, so pressure/speed width
     *  variation survives the reduction. */
    private fun simplifyForCommit(stroke: Stroke) {
        if (stroke.straight) return
        val eps = (SIMPLIFY_EPS / state.zoom).coerceAtMost(SIMPLIFY_EPS)
        val slim = StrokeSimplify.simplify(
            stroke.samples, stroke.geometry().halfWidths, eps,
            StrokeSimplify.dirArcFor(stroke.config.directionStrength, stroke.smoothScale),
        )
        if (slim.size == stroke.samples.size) return
        stroke.samples.clear()
        stroke.samples.addAll(slim)
        stroke.invalidate()
    }

    private fun armDwell() {
        cancelDwell()
        val r = Runnable { onDwellElapsed() }
        dwellRunnable = r
        handler.postDelayed(r, SHAPE_DWELL_MS)
    }

    private fun cancelDwell() {
        dwellRunnable?.let { handler.removeCallbacks(it) }
        dwellRunnable = null
    }

    /** Fired when the pen has held still: snap the live stroke to a shape if it's a confident match. */
    private fun onDwellElapsed() {
        dwellRunnable = null
        if (!dwellEligible) return
        val stroke = liveStroke ?: return
        val pi = strokePageIndex ?: return
        if (stroke.samples.size < SHAPE_MIN_SAMPLES) return // not enough yet; the next move re-arms
        val rec = ShapeRecognizer.recognize(stroke.samples) ?: return // not a shape; the next move re-arms
        commitRecognizedShape(stroke, pi, rec)
    }

    /** Replace the (uncommitted) live stroke with a recognized [ShapeItem], as one undoable add. */
    private fun commitRecognizedShape(stroke: Stroke, pageIndex: Int, rec: RecognizedShape) {
        val page = state.document.pages.getOrNull(pageIndex) ?: return
        val strokeWidth = stroke.config.baseWidth * SHAPE_PEN_PARITY
        val color = stroke.config.rgba // the as-drawn ink colour (not renderColor's alpha-scaled one)
        val dashed = stroke.tool == Tool.DASHED // a dashed pen snaps to a dashed shape
        val verts = rec.vertices
        val shape = if (verts != null) {
            // Polygon/polyline: keep the recognized corners (neon/dash carried like the other kinds).
            ShapeItem.poly(
                rec.kind, verts, color, strokeWidth, null, stroke.config.neon, stroke.config.neonStrength,
                dashed, stroke.config.dashLength, stroke.config.dashGap,
            )
        } else {
            ShapeItem(
                shape = rec.kind,
                start = rec.start,
                end = rec.end,
                strokeRgba = color,
                strokeWidth = strokeWidth,
                fillRgba = null,
                neon = stroke.config.neon, // a neon pen snaps to a neon shape (highlighter never snaps)
                neonStrength = stroke.config.neonStrength,
                dashed = dashed,
                dashLength = stroke.config.dashLength,
                dashGap = stroke.config.dashGap,
            )
        }
        page.items.add(shape)
        state.appendToCache(page, shape)
        history.push(AddItem(page, shape))
        state.document.dirty = true
        // The stroke was never added to the page, so clearing liveStroke makes the live ink preview
        // vanish the instant it snaps; the eventual pen-up in endDraw then commits nothing.
        liveStroke = null
        dwellEligible = false
        cancelDwell()
        // Auto-select the new shape so it can be resized right away. The menu only shows once the
        // gesture settles, so flag it for the pen lift (endDraw) rather than mid-stroke here.
        setSelection(listOf(Selected(pageIndex, shape)))
        snappedSelectionPendingMenu = true
        onContentChanged()
        onHaptic()
        requestRender()
    }

    // --- ERASE ---

    // SCALE off: hold the eraser at a constant on-screen size by shrinking its content-space
    // radius as you zoom in (both the hit-test and the cursor circle derive from this).
    private fun eraserRadius(): Double {
        val cfg = configFor(Tool.ERASER)
        return if (cfg.scale) cfg.baseWidth else cfg.baseWidth / state.zoom
    }

    private fun areaErase(): Boolean = configFor(Tool.ERASER).eraseMode == EraseMode.AREA

    private fun beginErase(vx: Double, vy: Double) {
        eraseRemovals.clear()
        eraseSnapshots.clear()
        mode = PointerMode.ERASE
        eraseAt(vx, vy)
    }

    private fun eraseAt(vx: Double, vy: Double) {
        eraserCursor = Pt(vx, vy)
        val content = state.viewportToContent(Pt(vx, vy))
        val radius = eraserRadius()
        val eraserBox = Rect(content.x - radius, content.y - radius, radius * 2, radius * 2)
        val area = areaErase()
        var changed = false
        val drawable = state.drawablePageRange()
        for (pi in state.document.pages.indices) {
            if (pi !in drawable) continue // a hidden paginated neighbour can't be erased
            val pr = state.pageRects.getOrNull(pi) ?: continue
            if (!pr.intersects(eraserBox)) continue // skip pages the eraser isn't over
            val page = state.document.pages[pi]
            val local = state.toPageSpace(pi, content)
            val cx = local.x
            val cy = local.y
            val dirty = if (area) eraseAreaFromPage(page, cx, cy, radius)
            else eraseStrokesFromPage(page, cx, cy, radius)
            if (dirty != null) {
                // Repaint only the erased area in place; fall back to a full
                // rebuild only when the page has no live cache yet.
                val rect = dirty.outset(REPAIR_PAD)
                if (!state.repairRegion(page, rect)) state.invalidatePage(page)
                changed = true
            }
        }
        if (changed) onContentChanged()
        requestRender()
    }

    /** STROKE mode: remove every stroke/shape the eraser circle touches. Images and text boxes are
     *  deliberately-placed and protected (delete those via select + delete). Returns the repaint
     *  region, or null if nothing changed. */
    private fun eraseStrokesFromPage(page: Page, cx: Double, cy: Double, radius: Double): Rect? {
        val toRemove = page.items.filter {
            it !is ImageItem && it !is TextItem && it.intersectsCircle(cx, cy, radius)
        }
        if (toRemove.isEmpty()) return null
        var dirty: Rect? = null
        for (item in toRemove) {
            page.items.remove(item)
            eraseRemovals.add(page to item)
            val b = item.paintBounds()
            dirty = dirty?.union(b) ?: b
        }
        return dirty
    }

    /** AREA mode: replace each touched stroke or shape with the fragments that survive the eraser
     *  circle, spliced in at the original's z-position. Text and images are left untouched. Returns
     *  the repaint region, or null if nothing changed. */
    private fun eraseAreaFromPage(page: Page, cx: Double, cy: Double, radius: Double): Rect? {
        var dirty: Rect? = null
        var i = 0
        while (i < page.items.size) {
            val item = page.items[i]
            val frags: List<CanvasItem>? = when (item) {
                is Stroke -> item.erasedBy(cx, cy, radius)
                is ShapeItem -> item.erasedBy(cx, cy, radius)
                else -> null
            }
            if (frags == null) {
                i++
                continue
            }
            // Snapshot the page's items on first contact this gesture, before mutating it.
            if (!eraseSnapshots.containsKey(page)) eraseSnapshots[page] = page.items.toList()
            val b = item.paintBounds()
            dirty = dirty?.union(b) ?: b
            page.items.removeAt(i)
            page.items.addAll(i, frags)
            i += frags.size // step past the freshly-inserted fragments
        }
        return dirty
    }

    private fun endErase() {
        if (areaErase()) {
            // One drag may split/trim many strokes across pages; commit each touched page's
            // net before/after as one undo step.
            val cmds = eraseSnapshots.mapNotNull { (page, before) ->
                val after = page.items.toList()
                if (after != before) ReplacePageItems(page, before, after) else null
            }
            if (cmds.isNotEmpty()) {
                history.push(if (cmds.size == 1) cmds[0] else CompositeCommand(cmds))
                state.document.dirty = true
                onContentChanged()
            }
        } else if (eraseRemovals.isNotEmpty()) {
            history.push(EraseItems(eraseRemovals.toList()))
            state.document.dirty = true
            onContentChanged()
        }
        eraseRemovals.clear()
        eraseSnapshots.clear()
        eraserCursor = null
        mode = PointerMode.IDLE
        requestRender()
    }

    /**
     * "Switch back after erasing": once a toolbar-eraser drag lifts, re-arm the pen/highlighter
     * that was active before the eraser. Only when the armed tool is the eraser (so a stylus-tip or
     * side-button erase, which never changed the armed tool, is left alone) and the remembered tool
     * is a stroke tool (a pen or the highlighter).
     */
    private fun maybeSwitchBackAfterErase() {
        if (tool != Tool.ERASER || !configFor(Tool.ERASER).switchBackAfterErase) return
        val back = toolBeforeEraser ?: return
        if (back.isStroke) setTool(back)
    }

    /**
     * "Switch back after a selection action": once a select-tool action (move, resize, or a menu
     * op like delete/cut/copy/duplicate) completes, re-arm the pen/highlighter that was active
     * before the select tool. Only when the armed tool is the select tool and the remembered tool
     * is a stroke tool; a long-press temporary grab is left to its own restore on deselect.
     */
    private fun maybeSwitchBackAfterSelect() {
        if (longPressPrevTool != null) return
        if (tool != Tool.SELECT || !configFor(Tool.SELECT).switchBackAfterSelect) return
        val back = toolBeforeSelect ?: return
        if (back.isStroke) setTool(back)
    }

    // --- SELECT / BAND ---

    /** The single selected line/arrow whose two endpoints are its own resize handles, or null.
     *  Every other selection (single non-line, multi, mixed) uses the generic box handles. */
    private fun singleEndpointShape(): Selected? {
        val sel = selection.singleOrNull() ?: return null
        val item = sel.item
        return if (item is ShapeItem && item.shape.isEndpointShape) sel else null
    }

    /** Resize handles for the current selection in content space: a single line/arrow's two
     *  endpoint handles, else the eight handles of the oriented selection box. */
    private fun selectionResizeHandles(): List<ResizeHandle> {
        val endpoint = singleEndpointShape()
        if (endpoint != null) return endpointHandles(endpoint)
        return selObb?.let { ResizeMath.obbHandles(it) } ?: emptyList()
    }

    /** A line/arrow's two endpoint handles, mapped from page space into content space. */
    private fun endpointHandles(sel: Selected): List<ResizeHandle> {
        val item = sel.item as? ShapeItem ?: return emptyList()
        if (state.pageRects.getOrNull(sel.pageIndex) == null) return emptyList()
        return listOf(
            ResizeHandle(HandleId.START, state.fromPageSpace(sel.pageIndex, item.start)),
            ResizeHandle(HandleId.END, state.fromPageSpace(sel.pageIndex, item.end)),
        )
    }

    /** Strokes and shapes rotate; images and text don't. A mixed selection rotates only when every
     *  member is rotatable. */
    private fun selectionIsRotatable(): Boolean =
        selection.isNotEmpty() && selection.all { it.item is Stroke || it.item is ShapeItem }

    /** Rotate-grip centre (content space, no move offset), out past the oriented box's top edge, or
     *  null when the selection can't rotate or is a single line/arrow (reoriented by an endpoint). */
    private fun selectionRotatePoint(): Pt? {
        if (!selectionIsRotatable() || singleEndpointShape() != null) return null
        val obb = selObb ?: return null
        return ResizeMath.obbRotateGrip(obb, ROTATE_ARM / state.zoom)
    }

    /** True when [content] lands on the active selection — a resize or rotate handle, or inside the
     *  oriented box. Lets a finger grab the selection even when finger-draw is off (off the
     *  selection the finger still pans). */
    private fun fingerHitsSelection(content: Pt): Boolean {
        if (selection.isEmpty()) return false
        val tol = HANDLE_HIT / state.zoom
        selectionRotatePoint()?.let { if (it.distanceTo(content) <= tol) return true }
        if (ResizeMath.hitHandle(selectionResizeHandles(), content, tol) != null) return true
        return selObb?.contains(content) == true
    }

    /** If [content] grabs a resize or rotate handle of the settled selection, begin that gesture
     *  and return true. Handles only; the caller handles an inside-the-bounds move. Shared by the
     *  select and lasso tools so both grab handles the same way. */
    private fun tryGrabSelectionHandle(content: Pt): Boolean {
        if (selection.isEmpty()) return false
        val tol = HANDLE_HIT / state.zoom
        selectionRotatePoint()?.let {
            if (it.distanceTo(content) <= tol) {
                beginTransform(null, content)
                return true
            }
        }
        val endpoint = singleEndpointShape()
        if (endpoint != null) {
            val id = ResizeMath.hitHandle(endpointHandles(endpoint), content, tol) ?: return false
            beginResize(endpoint, id)
            return true
        }
        val obb = selObb ?: return false
        val id = ResizeMath.hitHandle(ResizeMath.obbHandles(obb), content, tol) ?: return false
        beginTransform(id, content)
        return true
    }

    private fun beginSelect(content: Pt) {
        if (tryGrabSelectionHandle(content)) return
        val pageIndex = state.pageIndexAtContent(content)
        if (pageIndex != null) {
            val local = state.toPageSpace(pageIndex, content)
            val hit = state.document.pages[pageIndex].items.lastOrNull { it.contains(local) }
            if (hit != null) {
                if (selection.none { it.item === hit }) setSelection(listOf(Selected(pageIndex, hit)))
                beginMove(content)
                return
            }
        }
        if (selObb?.contains(content) == true) {
            beginMove(content)
            return
        }
        clearSelection()
        mode = PointerMode.BAND
        moveOrigin = content
        bandRect = Rect.fromPoints(content, content)
    }

    private fun extendBand(content: Pt) {
        bandRect = Rect.fromPoints(moveOrigin, content)
        requestRender()
    }

    private fun endBand() {
        bandRect?.let { band ->
            val drawable = state.drawablePageRange()
            setSelection(
                SelectionMath.bandMembers(state.document.pages, state.pageRects, band) { i, r ->
                    state.fromPageSpaceRect(i, r)
                }.filter { it.pageIndex in drawable }, // hidden paginated neighbours don't select
            )
        }
        bandRect = null
        mode = PointerMode.IDLE
        refreshSelectionMenu()
        requestRender()
    }

    // --- LASSO ---

    private fun beginLasso(content: Pt) {
        // A tap on the settled selection grabs it (a resize/rotate handle, or a move when inside the
        // oriented box) instead of starting a fresh lasso.
        if (tryGrabSelectionHandle(content)) return
        if (selObb?.contains(content) == true) {
            beginMove(content)
            return
        }
        clearSelection()
        lassoPoints.clear()
        lassoPoints.add(content)
        mode = PointerMode.LASSO_DRAW
    }

    private fun extendLasso(content: Pt) {
        lassoPoints.add(content)
        requestRender()
    }

    private fun endLasso() {
        if (lassoPoints.size >= 3) {
            val drawable = state.drawablePageRange()
            val members = SelectionMath.lassoMembers(state.document.pages, state.pageRects, lassoPoints) { i, p ->
                state.fromPageSpace(i, p)
            }.filter { it.pageIndex in drawable } // hidden paginated neighbours don't select
            if (members.isEmpty()) {
                clearSelection()
            } else {
                setSelection(members)
            }
        } else {
            clearSelection()
        }
        lassoPoints.clear()
        mode = PointerMode.IDLE
        refreshSelectionMenu()
        requestRender()
    }

    // --- SCREENSHOT ---

    private fun beginScreenshot(content: Pt) {
        clearScreenshot() // drop any previous frozen capture + its menu
        mode = PointerMode.SHOT
        screenshotOrigin = content
        screenshotRect = Rect.fromPoints(content, content)
    }

    private fun extendScreenshot(content: Pt) {
        screenshotRect = Rect.fromPoints(screenshotOrigin, content)
        requestRender()
    }

    private fun endScreenshot() {
        mode = PointerMode.IDLE
        val rect = screenshotRect
        // A tap or a sliver isn't a capture: drop it. Otherwise freeze the rect and show its menu.
        if (rect == null || rect.w < SHOT_MIN || rect.h < SHOT_MIN) clearScreenshot()
        else refreshScreenshotMenu()
        requestRender()
    }

    /** Drop the capture rectangle and hide its menu (after a copy, a tool change, or a re-drag). */
    fun clearScreenshot() {
        if (screenshotRect == null) return
        screenshotRect = null
        onScreenshotMenu(null)
        requestRender()
    }

    /** After a capture is copied, return to the previous pen, mirroring the eraser's switch-back. */
    fun switchBackAfterScreenshot() {
        if (tool != Tool.SCREENSHOT) return
        val back = toolBeforeScreenshot ?: return
        if (back.isStroke) setTool(back)
    }

    private fun refreshScreenshotMenu() {
        val rect = screenshotRect
        onScreenshotMenu(if (rect != null && mode == PointerMode.IDLE) screenshotRectViewport(rect) else null)
    }

    private fun screenshotRectViewport(rect: Rect): Rect {
        val tl = state.contentToViewport(rect.topLeft)
        val br = state.contentToViewport(Pt(rect.right, rect.bottom))
        return Rect.fromPoints(tl, br)
    }

    // --- MOVE ---

    private fun beginMove(content: Pt) {
        mode = PointerMode.MOVE
        moveOrigin = content
        moveOffset = Pt.ZERO
        onSelectionMenu(null) // hide while dragging
    }

    private fun extendMove(content: Pt) {
        moveOffset = content - moveOrigin
        requestRender()
    }

    private fun endMove(content: Pt) {
        moveOffset = content - moveOrigin
        val moved = abs(moveOffset.x) > MOVE_EPS || abs(moveOffset.y) > MOVE_EPS
        if (moved) {
            val items = selection.map { it.item }
            // Items hold page-space geometry: rotate the on-screen offset into page space.
            val local = state.vectorToPageSpace(moveOffset)
            for (item in items) item.translate(local.x, local.y)
            selObb = selObb?.translate(moveOffset.x, moveOffset.y)
            history.push(MoveItems(items, local.x, local.y))
            state.document.dirty = true
            // Moved items stay lifted (drawn live in the overlay), so the ink cache — which
            // already excludes them — needs no repair; it is repainted at their final spot
            // when the selection is later cleared.
            onContentChanged()
        }
        moveOffset = Pt.ZERO
        mode = PointerMode.IDLE
        refreshSelectionMenu()
        requestRender()
        if (moved) maybeSwitchBackAfterSelect()
    }

    // --- RESIZE ---

    private fun beginResize(sel: Selected, handle: HandleId) {
        resizeItem = sel.item
        resizeHandle = handle
        resizePageIndex = sel.pageIndex
        resizeOldGeom = (sel.item as Resizable).geometry()
        mode = PointerMode.RESIZE
        onSelectionMenu(null) // hide while resizing
    }

    private fun extendResize(content: Pt) {
        val item = resizeItem ?: return
        val handle = resizeHandle ?: return
        if (state.pageRects.getOrNull(resizePageIndex) == null) return
        val local = state.toPageSpace(resizePageIndex, content)
        when (item) {
            is ImageItem -> item.setGeometry(RectHandle(ResizeMath.resizeImage(item.rect, handle, local)))
            is TextItem -> {
                // Resize against the displayed bounds (grown-to-fit height) so handles track the box.
                val (pos, w, h) = ResizeMath.resizeText(item.pos, item.width, item.bounds().h, handle, local)
                item.setGeometry(TextHandle(pos, w, h))
            }
            is ShapeItem -> {
                val (s, en) = when {
                    item.shape.isEndpointShape -> ResizeMath.resizeOpenShape(item.start, item.end, handle, local)
                    item.shape == ShapeKind.CIRCLE -> ResizeMath.resizeSquareShape(item.start, item.end, handle, local)
                    else -> ResizeMath.resizeClosedShape(item.start, item.end, handle, local)
                }
                item.setGeometry(ShapeHandle(s, en))
            }
        }
        requestRender()
    }

    private fun endResize() {
        val item = resizeItem as? Resizable
        val old = resizeOldGeom
        var changed = false
        if (item != null && old != null) {
            val new = item.geometry()
            if (new != old) {
                changed = true
                history.push(ResizeItem(item, old, new))
                state.document.dirty = true
                // The resized item stays lifted (overlay-drawn); the ink cache it was already
                // lifted out of needs no repair — it is repainted at its new size on deselect.
                onContentChanged()
            }
        }
        resizeItem = null
        resizeHandle = null
        resizeOldGeom = null
        // The endpoint resize (a single line/arrow) reshapes the box; refit it upright.
        selObb = selectionBoundsContent()?.let { Obb.fromAabb(it) }
        mode = PointerMode.IDLE
        refreshSelectionMenu()
        requestRender()
        if (changed) maybeSwitchBackAfterSelect()
    }

    // --- TRANSFORM (generic resize + rotate) ---

    /** Begin a resize ([handle] non-null) or rotate ([handle] null) of the whole selection. Snapshots
     *  every member and the oriented box so each move can restore-then-rebake without drift. */
    private fun beginTransform(handle: HandleId?, content: Pt) {
        txItems = selection.toList()
        txSnaps = txItems.map { it.item.snapshotGeometry() }
        txStartObb = selObb
        txHandle = handle
        if (handle == null) {
            val c = selObb?.center ?: content
            txCenter = c
            txGrabAngle = atan2(content.y - c.y, content.x - c.x)
            txStartAngle = selObb?.angle ?: 0.0
        }
        mode = PointerMode.TRANSFORM
        onSelectionMenu(null) // hide while transforming
    }

    private fun extendTransform(content: Pt) {
        val obb0 = txStartObb ?: return
        // Restore every member to its gesture-start geometry, then bake the current transform so the
        // result is a pure function of the pointer (no per-frame compounding, e.g. of a rotation).
        txItems.forEachIndexed { i, sel -> sel.item.restoreGeometry(txSnaps[i]) }
        val world: Affine
        val handle = txHandle
        if (handle != null) {
            val res = ResizeMath.obbResize(obb0, handle, content)
            selObb = res.obb
            world = res.transform
        } else {
            val theta = atan2(content.y - txCenter.y, content.x - txCenter.x) - txGrabAngle
            selObb = obb0.copy(angle = txStartAngle + theta)
            world = Affine.rotateAbout(txCenter, theta)
        }
        // Items hold page-space geometry, so express the content-space transform per page
        // (a translation shift, plus the display rotation when the view is rotated).
        for (sel in txItems) {
            if (state.pageRects.getOrNull(sel.pageIndex) == null) continue
            sel.item.applyTransform(state.affineToPageSpace(sel.pageIndex, world))
        }
        requestRender()
    }

    private fun endTransform() {
        val items = txItems.map { it.item }
        var changed = false
        if (items.isNotEmpty()) {
            val after = items.map { it.snapshotGeometry() }
            if (after != txSnaps) {
                changed = true
                history.push(TransformItems(items, txSnaps, after))
                state.document.dirty = true
                // Members stay lifted (overlay-drawn); the ink cache they were lifted out of needs
                // no repair — it is repainted at their new geometry on deselect.
                onContentChanged()
            }
        }
        txItems = emptyList()
        txSnaps = emptyList()
        txStartObb = null
        txHandle = null
        mode = PointerMode.IDLE
        refreshSelectionMenu()
        requestRender()
        if (changed) maybeSwitchBackAfterSelect()
    }

    // --- SHAPE ---

    private fun beginShape(content: Pt) {
        val pageIndex = state.pageIndexAtContent(content) ?: return
        // Off-selection press with the shape tool: dismiss first (a tap makes no shape; endShape's
        // min-drag gate drops it), so dragging out a new shape also clears the old selection.
        clearSelection()
        val startLocal = state.toPageSpace(pageIndex, content)
        val kind = shapeConfig.shape
        val fill = if (shapeConfig.fill && kind.isClosed) inkColor.scaleAlpha(ShapeConfig.FILL_ALPHA) else null
        pendingShape = ShapeItem(
            kind, startLocal, startLocal, inkColor, shapeConfig.strokeWidth * SHAPE_PEN_PARITY, fill,
            shapeConfig.neon, shapeConfig.neonStrength,
            dashed = shapeConfig.dashed, dashLength = shapeConfig.dashLength, dashGap = shapeConfig.dashGap,
        )
        shapePageIndex = pageIndex
        mode = PointerMode.SHAPE
        requestRender()
    }

    private fun extendShape(content: Pt) {
        val shape = pendingShape ?: return
        val pi = shapePageIndex ?: return
        if (state.pageRects.getOrNull(pi) == null) return
        val raw = state.toPageSpace(pi, content)
        shape.end = when {
            // Line/arrow: pin the dragged end flat when it lands near an axis.
            shape.shape.isEndpointShape -> snapAxisEndpoint(shape.start, raw)
            // Circle: keep the box square so it stays a perfect circle.
            shape.shape == ShapeKind.CIRCLE -> squareCorner(shape.start, raw)
            else -> raw
        }
        requestRender()
    }

    /** Constrain a dragged corner [p] to a square box anchored at [anchor] (the perfect-circle shape). */
    private fun squareCorner(anchor: Pt, p: Pt): Pt {
        val side = max(abs(p.x - anchor.x), abs(p.y - anchor.y))
        val sx = if (p.x >= anchor.x) 1.0 else -1.0
        val sy = if (p.y >= anchor.y) 1.0 else -1.0
        return Pt(anchor.x + sx * side, anchor.y + sy * side)
    }

    /** Snap a line/arrow's dragged endpoint to an exactly horizontal or vertical run from [anchor]
     *  when it lands within [SHAPE_AXIS_SNAP_DEG] of one (mirrors the recognizer's axis snap). */
    private fun snapAxisEndpoint(anchor: Pt, p: Pt): Pt {
        val dx = p.x - anchor.x
        val dy = p.y - anchor.y
        if (dx == 0.0 && dy == 0.0) return p
        val snap = Math.toRadians(SHAPE_AXIS_SNAP_DEG)
        val fromHoriz = atan2(abs(dy), abs(dx)) // 0 = horizontal, PI/2 = vertical
        return when {
            fromHoriz <= snap -> Pt(p.x, anchor.y)
            fromHoriz >= Math.PI / 2.0 - snap -> Pt(anchor.x, p.y)
            else -> p
        }
    }

    private fun endShape() {
        val shape = pendingShape
        val pi = shapePageIndex
        if (shape != null && pi != null && shape.start.distanceTo(shape.end) > SHAPE_MIN_DRAG) {
            val page = state.document.pages[pi]
            page.items.add(shape)
            state.appendToCache(page, shape)
            history.push(AddItem(page, shape))
            state.document.dirty = true
            onContentChanged()
        }
        pendingShape = null
        shapePageIndex = null
        mode = PointerMode.IDLE
        requestRender()
    }

    // --- TEXT ---

    /**
     * A press with the Text tool (and no edit already open). Tapping an existing legacy
     * box is the text box tool's business; here everything routes to the inline-flow
     * caret (tap = place caret / toggle checkbox / fill empty lines, drag = select).
     */
    private fun beginTextGesture(content: Pt, viewport: Pt) {
        clearSelection()
        mode = PointerMode.FLOW_TEXT
        flowText?.pressAt(content, viewport)
        requestRender()
    }

    /**
     * A press with the Text box tool (and no edit already open). Tapping an existing box
     * edits it; otherwise this begins a tap-or-drag to create a new one ([endTextDrag]
     * decides which).
     */
    private fun beginTextBoxGesture(content: Pt) {
        val pi = state.pageIndexAtContent(content) ?: return
        val local = state.toPageSpace(pi, content)
        val page = state.document.pages[pi]
        val existing = page.items.lastOrNull { it is TextItem && it.contains(local) } as? TextItem
        if (existing != null) {
            startEditing(existing, pi, isNew = false)
            return
        }
        clearSelection()
        mode = PointerMode.TEXT_DRAG
        textDragPageIndex = pi
        textDragStart = content
        textDragRect = null
        requestRender()
    }

    private fun extendTextDrag(content: Pt) {
        textDragRect = Rect.fromPoints(textDragStart, content)
        requestRender()
    }

    /** Finish a tap-or-drag: a real drag (either axis) sizes the box; a tap makes a default one. */
    private fun endTextDrag(content: Pt) {
        val pi = textDragPageIndex
        textDragRect = null
        mode = PointerMode.IDLE
        if (state.pageRects.getOrNull(pi) == null) return
        val page = state.document.pages[pi]
        val startLocal = state.toPageSpace(pi, textDragStart)
        val rect = Rect.fromPoints(startLocal, state.toPageSpace(pi, content))
        val draggedX = rect.w * state.zoom >= TEXT_DRAG_SLOP
        val draggedY = rect.h * state.zoom >= TEXT_DRAG_SLOP
        val item = if (draggedX || draggedY) {
            // Use the drawn rectangle for whichever axis was actually dragged.
            val left = if (draggedX) rect.left else startLocal.x
            val maxW = (page.width - left - 8.0).coerceAtLeast(40.0)
            val w = if (draggedX) rect.w.coerceIn(40.0, maxW) else defaultTextWidth(page.width, left)
            val h = if (draggedY) rect.h else 0.0
            newTextItem(Pt(left, rect.top), w, h)
        } else {
            newTextItem(startLocal, defaultTextWidth(page.width, startLocal.x), 0.0)
        }
        startEditing(item, pi, isNew = true)
    }

    private fun defaultTextWidth(pageWidth: Double, left: Double): Double =
        (pageWidth - left - 14.0).coerceIn(80.0, 300.0)

    private fun newTextItem(pos: Pt, width: Double, height: Double): TextItem =
        TextItem(pos, width, height, "", inkColor, textPointSize, textFace, textMeasurer)

    /** Open the in-place editor on [item] (a new draft, or an existing box being re-edited). */
    private fun startEditing(item: TextItem, pi: Int, isNew: Boolean) {
        editingText = item
        editingIsNew = isNew
        editingOldText = item.text
        editingPageIndex = pi
        // The style bar / next new box follow the box being edited.
        textFace = item.face
        textPointSize = item.pointSize
        // An existing box is lifted out of the cache (isLiftedItem) while edited, so only the field
        // shows it. Repair just its region in place — a full invalidatePage flickered the ink layer.
        if (!isNew) repairTextRegion(state.document.pages[pi], item)
        onTextEditStart(editingField())
        requestRender()
    }

    /** Keep the model in sync with the live editor field (for auto-grow / commit). */
    fun updateEditingText(text: String) {
        editingText?.text = text
    }

    /**
     * Pan the document while a text edit stays open: a one-finger drag over the editor scrolls the
     * page (the overlay re-tracks the box through [editingField]) instead of scrolling the field's
     * own text. [dxFinger]/[dyFinger] are viewport-space finger deltas; the content follows the
     * finger, as in [extendPan]. A plain drag with no fling/overscroll.
     */
    fun panWhileEditing(dxFinger: Double, dyFinger: Double) {
        if (editingText == null) return
        state.scrollBy(-dxFinger, -dyFinger)
        onViewChanged()
        requestRender()
    }

    /** Current on-screen geometry of the editor field, or null when not editing. */
    fun editingField(): EditingField? {
        val item = editingText ?: return null
        if (state.pageRects.getOrNull(editingPageIndex) == null) return null
        // Anchor at the box's page-space top-left corner; a rotated view spins the overlay
        // around that anchor (EditingField.rotation) so it stays glued to the baked text.
        val topLeft = state.contentToViewport(state.fromPageSpace(editingPageIndex, item.pos))
        return EditingField(
            x = topLeft.x,
            y = topLeft.y,
            width = item.width,
            height = item.bounds().h,
            fontPx = item.pointSize * com.xnotes.platform.AndroidText.POINTS_TO_PX,
            zoom = state.zoom,
            face = item.face,
            rgba = item.rgba,
            text = item.text,
            rotation = state.rotationDeg,
        )
    }

    /**
     * Commit (tap outside / Escape / done / tool switch) using the model's current text.
     * An empty box is *deleted* (a new draft is simply dropped; an existing box is removed);
     * a box with content is kept and its change recorded. The single source of truth for
     * ending an edit — the field no longer commits itself, so there is no double-commit.
     */
    fun commitTextEdit(finalText: String? = null, restoreTool: Boolean = false) {
        val item = editingText ?: return
        finalText?.let { item.text = it }
        val pi = editingPageIndex
        val page = state.document.pages.getOrNull(pi)
        val empty = item.text.trim().isEmpty()
        if (editingIsNew) {
            if (!empty && page != null) {
                page.items.add(item)
                history.push(AddItem(page, item))
                state.document.dirty = true
                onContentChanged()
            }
        } else if (page != null) {
            if (empty) {
                page.items.remove(item)
                history.push(EraseItems(listOf(page to item)))
                state.document.dirty = true
                onContentChanged()
            } else if (item.text != editingOldText) {
                history.push(EditText(item, editingOldText, item.text))
                state.document.dirty = true
                onContentChanged()
            }
        }
        editingText = null
        editingIsNew = false
        editingPageIndex = -1
        editingOldText = ""
        // Repaint just the box's region in place (it is now unlifted, so it bakes back in) rather
        // than rebuilding the whole page — the same smart path selection uses, so no ink flicker.
        page?.let { repairTextRegion(it, item) }
        onTextEditEnd()
        requestRender()
        // Finishing an edit (tap outside / Escape / Back) re-arms whatever tool the text tool replaced,
        // so the pen/highlighter/pan is back without a manual switch. Skipped when the commit is itself
        // a tool switch (setTool passes restoreTool = false), which already arms the chosen tool.
        if (restoreTool) {
            val back = toolBeforeText
            toolBeforeText = null
            if (back != null && back != Tool.TEXT_BOX && tool == Tool.TEXT_BOX) setTool(back)
        }
    }

    /**
     * Repaint only the text box's region of the ink cache in place — the eraser/selection smart
     * path ([CanvasState.repairRegion]) — instead of a full-page rebuild, which flickered the whole
     * ink layer on every edit start/commit. The box is excluded while lifted (editing) and painted
     * back once unlifted, per isLiftedItem. Falls back to a rebuild only when there is no live cache.
     */
    private fun repairTextRegion(page: Page, box: TextItem) {
        if (!state.repairRegion(page, box.paintBounds().outset(REPAIR_PAD))) state.invalidatePage(page)
    }

    // --- text styling (driven by the floating style bar + the toolbar colour swatches) ---

    /** The text box currently being edited, or the lone selected one — what the style bar targets. */
    fun activeTextItem(): TextItem? = editingText ?: (selection.singleOrNull()?.item as? TextItem)

    /** Where to anchor the style bar and the box's current style, or null when no box is active. */
    fun computeTextBar(): TextBar? {
        val editing = editingText
        if (editing != null) {
            val f = editingField() ?: return null
            return TextBar(Rect(f.x, f.y, f.width * f.zoom, f.height * f.zoom), editing.face, editing.pointSize, editing = true)
        }
        if (mode != PointerMode.IDLE) return null
        val sel = selection.singleOrNull()?.item as? TextItem ?: return null
        val rect = selectionBoundsViewport() ?: return null
        return TextBar(rect, sel.face, sel.pointSize, editing = false)
    }

    fun setTextFace(face: FontFace) {
        textFace = face
        restyleActive { it.face = face }
    }

    fun setTextPointSize(size: Double) {
        val s = size.coerceIn(TEXT_MIN_PT, TEXT_MAX_PT)
        textPointSize = s
        restyleActive { it.pointSize = s }
    }

    /** Set the ink colour (for new strokes/boxes) and recolour the active text box, if any. */
    fun pickInk(c: Rgba) {
        inkColor = c
        restyleActive { it.rgba = c }
    }

    /**
     * Apply a style change to the active text box. A *new draft* mutates directly (its final
     * style is captured by the AddItem on commit); a committed/selected box records a [RestyleText]
     * so it is undoable. Both editing and selected boxes are lifted, so a render shows the change.
     */
    private inline fun restyleActive(mutate: (TextItem) -> Unit) {
        val item = activeTextItem() ?: return
        val isDraft = item === editingText && editingIsNew
        val old = TextStyle.of(item)
        mutate(item)
        if (!isDraft && TextStyle.of(item) != old) {
            history.push(RestyleText(item, old, TextStyle.of(item)))
            state.document.dirty = true
            onContentChanged()
        }
        if (item === editingText) {
            onTextEditStart(editingField()) // refresh the live field's metrics/colour
        } else {
            refreshSelectionMenu() // a size change moved the box; re-anchor its menu
        }
        requestRender()
    }

    // --- LONG-PRESS GRAB ---

    private fun armLongPress(viewport: Pt, content: Pt, isFinger: Boolean) {
        cancelLongPress()
        // Long-press (grab an item, or the paste menu on empty space) is a finger-only gesture:
        // the stylus always draws, so resting it never grabs or pops a menu.
        if (!isFinger) return
        val grabEligible = tool.isStroke || tool == Tool.PAN || tool == Tool.SELECT ||
            tool == Tool.LASSO || tool == Tool.SHAPE || tool == Tool.TEXT || tool == Tool.TEXT_BOX
        val pageIndex = state.pageIndexAtContent(content)
        val hit = if (pageIndex != null) {
            val local = state.toPageSpace(pageIndex, content)
            state.document.pages[pageIndex].items.lastOrNull { it.contains(local) }
        } else {
            null
        }
        longPressStart = viewport
        longPressContent = content
        longPressCandidate = if (grabEligible && hit != null) Selected(pageIndex!!, hit) else null
        // Arm to grab an item, or (on empty space) to open the paste menu when there is content to paste.
        val showEmptyMenu = hit == null && (hasClipboardItems() || clipboardHasImage())
        if (longPressCandidate == null && !showEmptyMenu) return
        val r = Runnable { triggerLongPress() }
        longPressRunnable = r
        handler.postDelayed(r, LONG_PRESS_MS)
    }

    private fun maybeCancelLongPress(viewport: Pt) {
        if (longPressRunnable != null && viewport.distanceTo(longPressStart) > LONG_PRESS_SLOP) cancelLongPress()
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
        longPressCandidate = null
    }

    private fun triggerLongPress() {
        longPressRunnable = null
        val candidate = longPressCandidate
        longPressCandidate = null
        // Abort the in-progress gesture (keep eraser removals); commit any text edit.
        commitTextEdit()
        liveStroke = null
        strokePageIndex = null
        pendingShape = null
        shapePageIndex = null
        bandRect = null
        lassoPoints.clear()
        if (candidate != null) {
            // Grab the item: switch to select, select it, and start a move.
            longPressPrevTool = tool
            tool = Tool.SELECT
            onToolChanged(tool)
            setSelection(listOf(candidate))
            beginMove(state.viewportToContent(longPressStart))
        } else {
            // Empty space: open the paste context menu at the press point.
            mode = PointerMode.IDLE
            onContextMenu(longPressStart, longPressContent)
        }
        requestRender()
    }

    // --- selection management ---

    private fun setSelection(items: List<Selected>) {
        // Items whose lifted state flips: those leaving the old selection (repainted back
        // into the cache) and those entering it (lifted out of it). Update the selection
        // first so the in-place repair below sees the new lifted set.
        val touched = selection + items
        selection.clear()
        selection.addAll(items)
        // A fresh selection starts upright: its box is the items' AABB, angle 0.
        selObb = selectionBoundsContent()?.let { Obb.fromAabb(it) }
        repairRegions(dirtyRegions(touched))
        onSelectionChanged(selection.isNotEmpty())
        requestRender()
    }

    fun clearSelection() {
        // Restore the tool a long-press grab temporarily switched away from.
        longPressPrevTool?.let {
            tool = it
            longPressPrevTool = null
            onToolChanged(tool)
        }
        onSelectionMenu(null)
        selObb = null
        if (selection.isEmpty()) return
        val regions = dirtyRegions(selection) // where the now-unlifted items sit (before clearing)
        selection.clear()
        repairRegions(regions) // repaint them back into the cache in place — no full rebuild
        onSelectionChanged(false)
        requestRender()
    }

    /**
     * Page-local dirty rects keyed by page index, unioning each item's paint extent (incl.
     * soft overflow such as neon glow) — the regions whose cached ink must be repaired when
     * these items' lifted state changes (lifting clears them out, unlifting repaints them).
     */
    private fun dirtyRegions(items: List<Selected>): Map<Int, Rect> {
        val regions = HashMap<Int, Rect>()
        for (s in items) {
            val b = s.item.paintBounds()
            regions[s.pageIndex] = regions[s.pageIndex]?.union(b) ?: b
        }
        return regions
    }

    /**
     * Repaint just [regions] of each page's ink cache in place — the eraser's smart path
     * ([CanvasState.repairRegion]), which keeps the cache entry and the (PDF/template)
     * background layer intact, so a selection edit no longer blanks the whole ink layer.
     * Falls back to a full page rebuild only where there is no live cache to repair.
     */
    private fun repairRegions(regions: Map<Int, Rect>) {
        for ((pageIndex, rect) in regions) {
            val page = state.document.pages.getOrNull(pageIndex) ?: continue
            if (!state.repairRegion(page, rect.outset(REPAIR_PAD))) state.invalidatePage(page)
        }
    }

    private fun selectionBoundsContent(): Rect? {
        if (selection.isEmpty()) return null
        var acc: Rect? = null
        for (sel in selection) {
            if (state.pageRects.getOrNull(sel.pageIndex) == null) continue
            val b = state.fromPageSpaceRect(sel.pageIndex, sel.item.bounds())
            acc = acc?.union(b) ?: b
        }
        return acc
    }

    // --- public edit operations (toolbar / context menu) ---

    fun deleteSelection() {
        if (selection.isEmpty()) return
        val removals = selection.map { state.document.pages[it.pageIndex] to it.item }
        for ((page, item) in removals) page.items.remove(item)
        history.push(EraseItems(removals))
        state.document.dirty = true
        // clearSelection repairs the vacated regions in place; the removed items were lifted
        // (already out of the ink cache) so they simply stop being drawn. The background/PDF
        // cache is left untouched — no full flush, no flicker.
        clearSelection()
        onContentChanged()
        maybeSwitchBackAfterSelect()
    }

    /**
     * Rotate the single selected image a quarter turn clockwise: the stored orientation advances and
     * the rect's width/height swap about its centre. The source bytes are untouched, so rotation is
     * lossless. The image is lifted (drawn live), so a render shows it at once and it re-bakes into the
     * cache on deselect, no manual cache repair here.
     */
    fun rotateSelectedImage() {
        val item = selection.singleOrNull()?.item as? ImageItem ?: return
        val oldRect = item.rect
        val oldOrientation = item.orientation
        val c = oldRect.center
        val newRect = Rect(c.x - oldRect.h / 2.0, c.y - oldRect.w / 2.0, oldRect.h, oldRect.w)
        val newOrientation = (item.orientation + 90) % 360
        item.rect = newRect
        item.orientation = newOrientation
        history.push(RotateImage(item, oldRect, oldOrientation, newRect, newOrientation))
        state.document.dirty = true
        refreshSelectionMenu()
        onContentChanged()
        requestRender()
    }

    fun selectAll() {
        val all = ArrayList<Selected>()
        state.document.pages.forEachIndexed { i, page -> page.items.forEach { all.add(Selected(i, it)) } }
        setSelection(all)
    }

    fun bringToFront() {
        if (selection.isEmpty()) return
        val byPage = selection.groupBy { it.pageIndex }
        for ((pageIndex, sels) in byPage) {
            val page = state.document.pages.getOrNull(pageIndex) ?: continue
            val selectedSet = sels.map { it.item }.toSet()
            val old = page.items.toList()
            val kept = old.filter { it !in selectedSet }
            val moved = old.filter { it in selectedSet }
            val new = kept + moved
            if (new != old) {
                history.push(ReorderItems(page, old, new))
                page.items.clear()
                page.items.addAll(new)
                // Selected items are lifted (excluded from the cache); the reorder moves only
                // those, leaving the cached non-lifted items' order unchanged. The new z-order
                // bakes into the cache when the selection is next cleared — no rebuild here.
            }
        }
        state.document.dirty = true
        onContentChanged()
        maybeSwitchBackAfterSelect()
    }

    fun escape() {
        val flow = flowText
        if (flow != null && flow.active) {
            flow.endSession()
            requestRender()
            return
        }
        commitTextEdit(restoreTool = true)
        clearSelection()
        requestRender()
    }

    // --- clipboard: copy / cut / duplicate / paste ---

    /** Copy the selection into the in-app clipboard. Pure: no tool/selection side effects, so
     *  cut/duplicate can reuse it without tripping the select tool's switch-back. */
    private fun copyToClipboard() {
        if (selection.isEmpty()) return
        itemClipboard.clear()
        selection.forEach { itemClipboard.add(cloneItem(it.item)) }
    }

    fun copySelection() {
        if (selection.isEmpty()) return
        copyToClipboard()
        maybeSwitchBackAfterSelect()
    }

    fun cutSelection() {
        if (selection.isEmpty()) return
        copyToClipboard()
        deleteSelection() // also runs the select tool's switch-back, once
    }

    fun duplicateSelection() {
        if (selection.isEmpty()) return
        copyToClipboard()
        val pageIndex = selection.first().pageIndex
        // Paste offset slightly from the originals (not repositioned to a point).
        pasteClonesOnPage(pageIndex, Pt.ZERO, offsetFromBoundsTopLeft = false, nudge = 24.0)
        maybeSwitchBackAfterSelect()
    }

    /** Paste the clipboard items so their top-left lands at the given content point. */
    fun pasteItemsAt(content: Pt) {
        val pageIndex = state.pageIndexAtContent(content) ?: state.currentPageIndex()
        pasteClonesOnPage(pageIndex, content, offsetFromBoundsTopLeft = true, nudge = 0.0)
    }

    private fun pasteClonesOnPage(pageIndex: Int, target: Pt, offsetFromBoundsTopLeft: Boolean, nudge: Double) {
        if (itemClipboard.isEmpty()) return
        val page = state.document.pages.getOrNull(pageIndex) ?: return
        if (state.pageRects.getOrNull(pageIndex) == null) return
        val clones = itemClipboard.map { cloneItem(it) }
        // Collective bounds (page-local) of the clones.
        var box: Rect? = null
        for (c in clones) box = box?.union(c.bounds()) ?: c.bounds()
        val b = box ?: return
        val targetLocal = state.toPageSpace(pageIndex, target)
        val dx = if (offsetFromBoundsTopLeft) targetLocal.x - b.left + nudge else nudge
        val dy = if (offsetFromBoundsTopLeft) targetLocal.y - b.top + nudge else nudge
        for (c in clones) c.translate(dx, dy)
        page.items.addAll(clones)
        history.push(AddItems(page, clones))
        state.document.dirty = true
        // The clones are immediately selected (lifted) below; setSelection repairs their
        // region in place, so no separate page rebuild is needed here.
        setSelection(clones.map { Selected(pageIndex, it) })
        refreshSelectionMenu()
        onContentChanged()
    }

    private fun cloneItem(item: CanvasItem): CanvasItem = item.deepCopy(textMeasurer)

    // --- selection menu ---

    /** Show the selection menu when a selection is settled (idle), else hide it. */
    private fun refreshSelectionMenu() {
        onSelectionMenu(if (selection.isNotEmpty() && mode == PointerMode.IDLE) selectionBoundsViewport() else null)
    }

    private fun selectionBoundsViewport(): Rect? {
        val content = selectionBoundsContent() ?: return null
        val tl = state.contentToViewport(content.topLeft)
        val br = state.contentToViewport(Pt(content.right, content.bottom))
        val rect = Rect.fromPoints(tl, br)
        // Raise the menu's anchor top above the rotate grip (drawn ROTATE_ARM + grip radius above the
        // box) so the floating action bar floats over the grip instead of covering it. Bottom is left
        // put, so the below-the-selection fallback placement is unchanged.
        if (selectionRotatePoint() == null) return rect
        val clearance = ROTATE_ARM + HANDLE_SIZE * 0.6
        return Rect(rect.left, rect.top - clearance, rect.w, rect.h + clearance)
    }

    // --- PAN ---

    // When zoom is locked, the zoomLockPan preference decides which pan gestures still move the
    // viewport: "single" keeps both, "double" drops the single finger, "none" freezes the page.
    private fun singleFingerPanAllowed(): Boolean = !(state.zoomLocked && zoomLockPan != "single")
    private fun pinchPanAllowed(): Boolean = !(state.zoomLocked && zoomLockPan == "none")

    private fun beginPan(vx: Double, vy: Double) {
        mode = PointerMode.PAN
        panDownViewport = Pt(vx, vy)
        startTrackingVelocity(vx, vy)
    }

    private fun extendPan(vx: Double, vy: Double) {
        trackVelocity(vx, vy)
        val dx = -(vx - lastPan.x)
        var dy = -(vy - lastPan.y)
        if (!state.verticalScroll) {
            extendPanPaginated(dx, dy)
            lastPan = Pt(vx, vy)
            onViewChanged()
            requestRender()
            return
        }
        // While the bottom elastic is stretched, finger motion works the elastic first (rubber-band)
        // rather than the scroll, so pulling back relaxes the stretch before the document scrolls.
        // Stretching it further needs the document end on screen: right after a pull adds a page
        // the end sits a full page below, so a quick swipe toward it must scroll, not re-arm.
        if (state.overscrollY > 0.0 && (dy < 0.0 || state.isDocumentEndVisible())) {
            val relaxed = (state.overscrollY + dy * OVERSCROLL_RESIST).coerceAtLeast(0.0)
            val consumed = (relaxed - state.overscrollY) / OVERSCROLL_RESIST
            state.overscrollY = relaxed.coerceAtMost(OVERSCROLL_MAX)
            dy -= consumed
            updateOverscrollArmed()
        }
        // Apply the remaining pan to the scroll; whatever the clamp rejects at the bottom feeds the elastic.
        val beforeY = state.scrollY
        state.scrollBy(dx, dy)
        val leftoverY = dy - (state.scrollY - beforeY)
        // Only feed the elastic when the document's end is actually on screen. Inferring "at the end"
        // from a rejected downward scroll alone is unsafe: a transient bad scroll/layout state right
        // after a document opens can make the clamp fire while there is still document below the fold,
        // spuriously arming add-page (seen on first open, even on long PDFs). isDocumentEndVisible()
        // checks the last page's bottom against the viewport through the same transform that draws the
        // frame, so the affordance can never appear while the user can still see more document below.
        if (leftoverY > 0.0 && state.isDocumentEndVisible()) {
            state.overscrollY = (state.overscrollY + leftoverY * OVERSCROLL_RESIST).coerceAtMost(OVERSCROLL_MAX)
            updateOverscrollArmed()
        }
        lastPan = Pt(vx, vy)
        onViewChanged()
        requestRender()
    }

    /** Fire the threshold haptic once as the live stretch crosses the add-page point. */
    private fun updateOverscrollArmed() {
        val past = state.overscrollY >= OVERSCROLL_TRIGGER
        if (past && !overscrollArmed) onHaptic()
        overscrollArmed = past
    }

    // --- paginated page flip ---

    /**
     * Paginated pan: pan freely inside the current row (the clamp pins the window to it);
     * whatever the clamp rejects horizontally works the edge-pull elastic instead, arming
     * the flip. Relaxing motion unwinds the pull before the scroll moves again, exactly
     * like the bottom overscroll. No add-page elastic in this mode.
     */
    private fun extendPanPaginated(dx0: Double, dy: Double) {
        var dx = dx0
        val pull = state.flipOffsetX
        if (pull > 0.0 && dx < 0.0 || pull < 0.0 && dx > 0.0) {
            val relaxed = pull + dx * FLIP_RESIST
            if (relaxed * pull <= 0.0) { // crossed zero: the leftover motion scrolls
                state.flipOffsetX = 0.0
                dx += pull / FLIP_RESIST
            } else {
                state.flipOffsetX = relaxed
                dx = 0.0
            }
        }
        val beforeX = state.scrollX
        state.scrollBy(dx, dy)
        val leftoverX = dx - (state.scrollX - beforeX)
        if (leftoverX != 0.0) {
            val cap = FLIP_MAX_FRACTION * state.viewportW
            val armedBefore = abs(state.flipOffsetX) >= FLIP_TRIGGER
            state.flipOffsetX = (state.flipOffsetX + leftoverX * FLIP_RESIST).coerceIn(-cap, cap)
            if (!armedBefore && abs(state.flipOffsetX) >= FLIP_TRIGGER) onHaptic()
        }
        // The pull reveals only empty background; the neighbouring row never joins the screen.
    }

    /** Decide what a lifted paginated pan does: flip instantly, drop the pull, or glide in-row. */
    private fun endPanPaginated() {
        val rows = state.rowRanges().size
        val pull = state.flipOffsetX
        when {
            pull >= FLIP_TRIGGER && state.currentRow < rows - 1 -> flipTo(state.currentRow + 1)
            pull <= -FLIP_TRIGGER && state.currentRow > 0 -> flipTo(state.currentRow - 1)
            panVel.x <= -FLIP_FLING_VEL && state.atRowEdge(next = true) && state.currentRow < rows - 1 ->
                flipTo(state.currentRow + 1)
            panVel.x >= FLIP_FLING_VEL && state.atRowEdge(next = false) && state.currentRow > 0 ->
                flipTo(state.currentRow - 1)
            pull != 0.0 -> { clearFlipPull(); onViewChanged(); requestRender() }
            else -> startFling(panVel)
        }
    }

    /**
     * Jump to [rowIndex] with no animation: the new row simply appears at its landing spot
     * ([CanvasState.rowTargetScroll] — zoom kept, top-aligned).
     */
    private fun flipTo(rowIndex: Int) {
        stopFling()
        state.goToPage(state.rowRanges()[rowIndex].first)
        onViewChanged()
        requestRender()
    }

    // --- inertial fling ---

    private fun startTrackingVelocity(vx: Double, vy: Double) {
        stopFling()
        lastPan = Pt(vx, vy)
        lastMoveMs = System.nanoTime() / 1_000_000L
        panVel = Pt.ZERO
    }

    private fun trackVelocity(vx: Double, vy: Double) {
        val now = System.nanoTime() / 1_000_000L
        val dt = ((now - lastMoveMs).coerceAtLeast(1L)) / 1000.0
        val inst = Pt((vx - lastPan.x) / dt, (vy - lastPan.y) / dt)
        panVel = Pt(panVel.x * VEL_SMOOTH + inst.x * (1 - VEL_SMOOTH), panVel.y * VEL_SMOOTH + inst.y * (1 - VEL_SMOOTH))
        lastMoveMs = now
    }

    private fun startFling(fingerVel: Pt) {
        if (fingerVel.length() < FLING_MIN_START) return
        flingVel = Pt(-fingerVel.x, -fingerVel.y) // scroll moves opposite the finger
        flinging = true
        lastFlingMs = System.nanoTime() / 1_000_000L
        choreographer.postFrameCallback(flingFrame)
    }

    private fun stopFling() {
        flinging = false
    }

    private fun stepFling(frameTimeNanos: Long) {
        if (!flinging) return
        val now = frameTimeNanos / 1_000_000L
        val dt = ((now - lastFlingMs).coerceIn(1L, 40L)) / 1000.0
        lastFlingMs = now
        val beforeX = state.scrollX
        val beforeY = state.scrollY
        state.scrollBy(flingVel.x * dt, flingVel.y * dt)
        val decay = exp(-FLING_FRICTION * dt)
        flingVel = Pt(flingVel.x * decay, flingVel.y * decay)
        onViewChanged()
        requestRender()
        val moved = state.scrollX != beforeX || state.scrollY != beforeY
        if (flingVel.length() < FLING_MIN_STOP || !moved) {
            flinging = false
        } else {
            choreographer.postFrameCallback(flingFrame)
        }
    }

    // --- disappearing ink (magic wand) ---

    /** Drop all held ephemeral strokes and stop any pending or running fade. */
    private fun clearFading() {
        cancelFadeTimer()
        stopFade()
        fadingStrokes.clear()
        fadeAlpha = 1.0
    }

    /** (Re)start the debounce so the held batch fades only once drawing has paused. */
    private fun scheduleFade() {
        cancelFadeTimer()
        val r = Runnable { startFade() }
        fadeTimerRunnable = r
        handler.postDelayed(r, WAND_HOLD_MS)
    }

    private fun cancelFadeTimer() {
        fadeTimerRunnable?.let { handler.removeCallbacks(it) }
        fadeTimerRunnable = null
    }

    private fun startFade() {
        fadeTimerRunnable = null
        if (fadingStrokes.isEmpty()) return
        // Still drawing: the batch only fades once the pen has been up for the whole hold.
        if (liveStroke != null) {
            scheduleFade()
            return
        }
        fading = true
        fadeAlpha = 1.0
        fadeStartMs = System.nanoTime() / 1_000_000L
        choreographer.postFrameCallback(fadeFrame)
    }

    private fun stopFade() {
        fading = false
    }

    private fun stepFade(frameTimeNanos: Long) {
        if (!fading) return
        val now = frameTimeNanos / 1_000_000L
        val t = ((now - fadeStartMs).toDouble() / WAND_FADE_MS).coerceIn(0.0, 1.0)
        fadeAlpha = (1.0 - t) * (1.0 - t) // ease-out so the batch melts away rather than blinking off
        requestRender()
        if (t >= 1.0) {
            fadingStrokes.clear()
            fadeAlpha = 1.0
            fading = false
        } else {
            choreographer.postFrameCallback(fadeFrame)
        }
    }

    // --- elastic overscroll release ---

    /** Finger lifted while the bottom elastic was stretched: add a page if pulled far enough, then spring back. */
    private fun releaseOverscroll() {
        stopFling()
        if (state.overscrollY >= OVERSCROLL_TRIGGER) {
            onAddPageAtEnd()
            // The stretch is spent: sink it below the trigger so a re-grab mid-spring
            // cannot release it as a second add.
            state.overscrollY = OVERSCROLL_TRIGGER - 1.0
        }
        overscrollArmed = false
        if (!overscrollSettling) {
            overscrollSettling = true
            lastOverscrollMs = System.nanoTime() / 1_000_000L
            choreographer.postFrameCallback(overscrollFrame)
        }
    }

    private fun stopOverscrollSettle() {
        overscrollSettling = false
    }

    /** Drop the elastic immediately (no spring) — used when a gesture is cancelled or supplanted. */
    private fun clearOverscroll() {
        overscrollSettling = false
        overscrollArmed = false
        state.overscrollY = 0.0
    }

    /** Fold any paginated edge-pull back into the (clamped) scroll, with no animation. */
    private fun clearFlipPull() {
        if (state.flipOffsetX != 0.0) {
            state.scrollX += state.flipOffsetX
            state.flipOffsetX = 0.0
            state.clampScroll()
        }
    }

    /**
     * Drop any in-flight scroll/zoom physics and partial gesture so the previous document's fling,
     * elastic stretch or half-finished pan can't bleed into a freshly opened one. The editor calls
     * this whenever it swaps the open document — otherwise a stale fling/overscroll could leave the
     * new document at (or believing it is at) its bottom, spuriously arming add-page on first scroll.
     */
    fun resetGestureState() {
        stopFling()
        state.flipOffsetX = 0.0
        clearOverscroll()
        cancelDwell()
        clearFading()
        dwellEligible = false
        mode = PointerMode.IDLE
        lastPan = Pt.ZERO
        panVel = Pt.ZERO
    }

    private fun stepOverscrollSettle(frameTimeNanos: Long) {
        if (!overscrollSettling) return
        val now = frameTimeNanos / 1_000_000L
        val dt = ((now - lastOverscrollMs).coerceIn(1L, 40L)) / 1000.0
        lastOverscrollMs = now
        state.overscrollY *= exp(-OVERSCROLL_SPRING * dt) // exponential ease toward rest
        if (state.overscrollY < 0.5) {
            state.overscrollY = 0.0
            overscrollSettling = false
        } else {
            choreographer.postFrameCallback(overscrollFrame)
        }
        onViewChanged()
        requestRender()
    }

    // --- PINCH ---

    private fun beginPinch(e: MotionEvent) {
        liveStroke = null
        strokePageIndex = null
        cancelDwell() // a second finger turns the gesture into a zoom; don't snap a shape mid-pinch
        dwellEligible = false
        bandRect = null
        lassoPoints.clear()
        if (mode == PointerMode.SHOT) clearScreenshot() // a second finger turns the drag into a zoom
        clearOverscroll() // a second finger ends any bottom-pull; the elastic snaps away
        clearFlipPull() // ...and any paginated edge-pull folds back into the scroll
        mode = PointerMode.PINCH
        val a = Pt(e.getX(0).toDouble(), e.getY(0).toDouble())
        val b = Pt(e.getX(1).toDouble(), e.getY(1).toDouble())
        val mid = (a + b) * 0.5
        pinchInitDist = a.distanceTo(b).coerceAtLeast(1.0)
        pinchInitZoom = state.zoom
        pinchAnchorContent = state.viewportToContent(mid)
        startTrackingVelocity(mid.x, mid.y)
        state.zoomingInProgress = true
    }

    private fun updatePinch(e: MotionEvent) {
        if (e.pointerCount < 2) return
        val a = Pt(e.getX(0).toDouble(), e.getY(0).toDouble())
        val b = Pt(e.getX(1).toDouble(), e.getY(1).toDouble())
        val dist = a.distanceTo(b)
        if (dist < 1e-3) return
        val mid = (a + b) * 0.5
        trackVelocity(mid.x, mid.y)
        // Zoom lock: pan only (keep the initial zoom).
        val raw = (pinchInitZoom * (dist / pinchInitDist)).coerceIn(state.minZoom, state.maxZoom)
        val wasFit = state.fitWidthActive || state.fitHeightActive
        // Magnetic fit: the live zoom sticks to fit-width — and, paginated, fit-height — while
        // within the band (pinch past it to break free). The lock hint surfaces the moment a
        // magnet grabs and is dismissed the moment it breaks free. A locked pinch is pan-only,
        // so it never snaps.
        val z = if (state.zoomLocked) pinchInitZoom else state.snapZoomToFit(raw)
        state.zoom = z
        if (!state.zoomLocked) {
            val nowFit = state.fitWidthActive || state.fitHeightActive
            if (!wasFit && nowFit) onFitWidthSnapped()
            else if (wasFit && !nowFit) onFitWidthReleased()
        }
        if (pinchPanAllowed()) {
            state.scrollX = pinchAnchorContent.x * z - mid.x
            state.scrollY = pinchAnchorContent.y * z - mid.y
            state.clampScroll()
        }
        lastPan = mid
        onViewChanged() // live zoom %: refresh the toolbar each pinch frame, not just at the end
        requestRender()
    }

    private fun endPinch() {
        mode = PointerMode.IDLE
        state.zoomingInProgress = false
        state.invalidateCachesForZoom() // keep stale surfaces to blit until the sharp rebuild lands
        onViewChanged()
        requestRender()
        if (pinchPanAllowed()) startFling(panVel)
    }

    private fun abortGesture() {
        cancelLongPress()
        cancelDwell()
        dwellEligible = false
        snappedSelectionPendingMenu = false
        strokeDismissedSelection = false
        panMayCommitText = false
        stopFling()
        clearFlipPull()
        clearOverscroll()
        liveStroke = null
        strokePageIndex = null
        snapEngaged = false
        snapRunStartEdge = null
        snapCurrentEdge = null
        snapPenViewport = null
        pendingShape = null
        shapePageIndex = null
        bandRect = null
        lassoPoints.clear()
        textDragRect = null
        // Cancel an in-progress capture drag; a frozen capture (mode IDLE) survives.
        if (mode == PointerMode.SHOT) { screenshotRect = null; onScreenshotMenu(null) }
        if (mode == PointerMode.PINCH) {
            state.zoomingInProgress = false
            state.invalidateCachesForZoom()
        }
        mode = PointerMode.IDLE
    }

    private fun resolvePressure(e: MotionEvent, pointerIndex: Int, toolType: Int): Double =
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS) e.getPressure(pointerIndex).toDouble().coerceIn(0.0, 1.0) else 1.0

    // --- overlay ---

    fun drawOverlay(r: Renderer) {
        val origin = state.origin()
        r.withSave {
            r.translate(origin.x, origin.y)
            r.scale(state.zoom, state.zoom)

            // Lifted (selected) items, drawn live at the move offset.
            for (sel in selection) {
                val pr = state.pageRects.getOrNull(sel.pageIndex) ?: continue
                r.withSave {
                    r.translate(pr.left + moveOffset.x, pr.top + moveOffset.y)
                    state.applyPageRotation(r, state.document.pages[sel.pageIndex])
                    sel.item.paint(r)
                }
            }

            // Live in-progress stroke / shape preview, clipped to its page.
            liveStroke?.let { stroke -> paintClippedToPage(r, strokePageIndex) { stroke.paint(r) } }
            pendingShape?.let { shape -> paintClippedToPage(r, shapePageIndex) { shape.paint(r) } }

            // Disappearing ink (magic wand): ephemeral strokes drawn live at the shared fade alpha,
            // without mutating their stored colour. Highlighters keep their MULTIPLY look while fading.
            for (fs in fadingStrokes) {
                paintClippedToPage(r, fs.pageIndex) {
                    val blend = if (fs.stroke.tool == Tool.HIGHLIGHTER) BlendMode.MULTIPLY else BlendMode.SRC_OVER
                    r.saveLayerBlended(fs.stroke.paintBounds(), fadeAlpha, blend)
                    fs.stroke.paint(r)
                    r.restore()
                }
            }

            // Screenshot capture region: the live drag rect, kept frozen until "copy as image" is used.
            screenshotRect?.let { r.strokeRect(it, Pen(state.palette.accent, 1.6, cosmetic = true, dashed = true)) }

            // Selection chrome.
            val accent = Pen(state.palette.accent, 1.3, cosmetic = true, dashed = true)
            // Flow caret + selection highlight, under the selection chrome.
            flowText?.drawOverlay(r)

            when {
                mode == PointerMode.BAND -> bandRect?.let { r.strokeRect(it, accent) }
                mode == PointerMode.TEXT_DRAG -> textDragRect?.let { r.strokeRect(it, accent) }
                mode == PointerMode.LASSO_DRAW && lassoPoints.size >= 2 ->
                    r.strokePolyline(lassoPoints, Pen(state.palette.accent, 1.3, cosmetic = true))
                selection.isNotEmpty() ->
                    selObb?.let { obb ->
                        r.strokePolygon(obb.corners().map { Pt(it.x + moveOffset.x, it.y + moveOffset.y) }, accent)
                    }
            }

            // Resize + rotate handles for the settled selection (single, multi, or mixed).
            if (selection.isNotEmpty() && mode != PointerMode.BAND && mode != PointerMode.LASSO_DRAW) {
                val side = HANDLE_SIZE / state.zoom
                // Rotate grip: a short stem out from the box's top edge up to a round handle.
                selectionRotatePoint()?.let { rp ->
                    selObb?.let { obb ->
                        val base = ResizeMath.obbTopMid(obb)
                        val stemTop = Pt(base.x + moveOffset.x, base.y + moveOffset.y)
                        val grip = Pt(rp.x + moveOffset.x, rp.y + moveOffset.y)
                        r.strokePolyline(listOf(grip, stemTop), Pen(state.palette.accent, 1.3, cosmetic = true))
                        r.fillCircle(grip, side * 0.6, state.palette.accent)
                    }
                }
                for (h in selectionResizeHandles()) {
                    val c = Pt(h.content.x + moveOffset.x, h.content.y + moveOffset.y)
                    r.fillRect(Rect(c.x - side / 2, c.y - side / 2, side, side), state.palette.accent)
                }
            }
        }

        // Eraser cursor (viewport space, after the transform is restored).
        eraserCursor?.let {
            val radius = eraserRadius() * state.zoom
            r.strokeEllipse(it, radius, radius, Pen(state.palette.textDim, 1.3, cosmetic = true))
        }

        // Ruler (viewport space; floats above all content and the live stroke).
        if (ruler.visible) drawRuler(r)
    }

    private inline fun paintClippedToPage(r: Renderer, pageIndex: Int?, crossinline paint: () -> Unit) {
        val pi = pageIndex ?: -1
        val pr = state.pageRects.getOrNull(pi) ?: return
        r.withSave {
            r.clipRect(pr)
            r.translate(pr.left, pr.top)
            state.applyPageRotation(r, state.document.pages[pi])
            paint()
        }
    }

    // --- ruler ---

    private fun beginRulerTransform(e: MotionEvent) {
        cancelLongPress()
        val a = Pt(e.getX(0).toDouble(), e.getY(0).toDouble())
        val b = Pt(e.getX(1).toDouble(), e.getY(1).toDouble())
        rulerXformStartCentroid = (a + b) * 0.5
        rulerXformStartFingerAngle = atan2(b.y - a.y, b.x - a.x)
        rulerXformStartCenter = ruler.center
        rulerXformStartRuler = ruler.angleRad
        mode = PointerMode.RULER_TRANSFORM
        requestRender()
    }

    private fun updateRulerTransform(e: MotionEvent) {
        if (e.pointerCount < 2) return
        val a = Pt(e.getX(0).toDouble(), e.getY(0).toDouble())
        val b = Pt(e.getX(1).toDouble(), e.getY(1).toDouble())
        if (!ruler.lockPosition) {
            ruler.center = rulerXformStartCenter + ((a + b) * 0.5 - rulerXformStartCentroid)
        }
        if (!ruler.lockAngle) {
            ruler.angleRad = Ruler.snapToAxes(rulerXformStartRuler + (atan2(b.y - a.y, b.x - a.x) - rulerXformStartFingerAngle))
        }
        requestRender()
    }

    /** How far the rotation handles sit from the ruler centre (kept on-screen). */
    private fun rulerHandleDist(): Double = 0.30 * minOf(state.viewportW, state.viewportH).toDouble()

    /** Drag a rotation handle: spin the ruler about its centre so the grabbed handle tracks the pointer. */
    private fun updateRulerRotate(e: MotionEvent) {
        if (ruler.lockAngle) return
        val idx = e.findPointerIndex(drawingPointerId).coerceAtLeast(0)
        val v = (Pt(e.getX(idx).toDouble(), e.getY(idx).toDouble()) - ruler.center) * rulerRotateSign
        if (v.length() < 1e-3) return
        ruler.angleRad = Ruler.snapToAxes(atan2(v.y, v.x))
        requestRender()
    }

    /**
     * The ruler magnet for a viewport draw point. Engages when the pen enters the snap band beside a
     * long edge, then clamps the ink onto that edge — so a stroke can't pass through the body — and
     * releases only when the pen retreats back out past the band on the engaged side. Updates the
     * engagement state and returns the point to actually draw.
     */
    private fun magnetize(vp: Pt): Pt {
        if (!ruler.visible) return vp
        val ht = ruler.thicknessPx / 2.0
        val band = RULER_SNAP_DP * state.devicePxPerDp
        val across = ruler.signedAcross(vp)
        val active = when {
            snapEngaged -> {
                // Release only on an outward retreat past the band on the engaged side; pushing inward
                // (toward/through the body) stays clamped to the edge, so ink can't cross the ruler.
                val retreated = if (snapTopSide) across > ht + band else across < -(ht + band)
                if (retreated) {
                    snapEngaged = false
                    snapRunStartEdge = null
                    snapCurrentEdge = null
                }
                snapEngaged
            }
            abs(across) <= ht + band -> {
                snapTopSide = across >= 0.0
                snapEngaged = true
                true
            }
            else -> false
        }
        if (!active) return vp
        snapPenViewport = vp
        val edge = ruler.projectToEdge(vp, snapTopSide)
        if (snapRunStartEdge == null) snapRunStartEdge = edge
        snapCurrentEdge = edge
        return edge
    }

    /** Paint the ruler in viewport space: an infinite frosted band, dual-edge graduations and readouts. */
    private fun drawRuler(r: Renderer) {
        val pal = state.palette
        val density = state.devicePxPerDp
        // Visible along-range: project the four viewport corners onto the band's length axis.
        val d = ruler.direction()
        val vw = state.viewportW.toDouble()
        val vh = state.viewportH.toDouble()
        var sMin = Double.MAX_VALUE
        var sMax = -Double.MAX_VALUE
        for (c in listOf(Pt(0.0, 0.0), Pt(vw, 0.0), Pt(0.0, vh), Pt(vw, vh))) {
            val s = Geometry.dot(c - ruler.center, d)
            if (s < sMin) sMin = s
            if (s > sMax) sMax = s
        }
        val pad = 4.0 * density
        sMin -= pad
        sMax += pad

        // Body: a neutral frosted strip with thin neutral edges (no accent).
        val quad = ruler.bodyQuad(sMin, sMax)
        r.fillPolygon(quad, pal.panel.scaleAlpha(if (pal.isDark) 0.6 else 0.5))
        val edgePen = Pen(pal.textDim, 1.2, cosmetic = true)
        r.strokePolyline(listOf(quad[0], quad[1]), edgePen)
        r.strokePolyline(listOf(quad[3], quad[2]), edgePen)

        drawRulerTicks(r, density, pal, sMin, sMax)
        drawRulerButtons(r, pal)
        drawRulerHandles(r, density, pal)

        if (snapEngaged) {
            val a = snapRunStartEdge
            val b = snapCurrentEdge
            val pen = snapPenViewport
            if (a != null && b != null && pen != null) {
                val cm = RulerMath.viewportLenToCm(a.distanceTo(b), state.zoom, document.dpi)
                drawReadout(r, "%.1f cm".format(cm), pen + Pt(30.0, -30.0) * density, density, pal)
            }
        }
    }

    /** The two rotation handles with permanent angle readouts: counter-clockwise from −x on the +x
     *  side, clockwise from +x on the other. Dragging a handle spins the ruler about its centre. */
    private fun drawRulerHandles(r: Renderer, density: Double, pal: Palette) {
        val dist = rulerHandleDist()
        val radius = ruler.handleRadiusPx()
        // Readings are tied to the handle (not the screen side) so they never swap as the ruler turns:
        // the +direction handle reads counter-clockwise from +x, the −direction handle clockwise from −x.
        val phi = Math.toDegrees(atan2(ruler.direction().y, ruler.direction().x))
        val ccwFromPlusX = ((-phi) % 360 + 360) % 360
        val cwFromMinusX = (phi % 360 + 360) % 360
        val handles = ruler.handleCenters(dist)
        for (i in handles.indices) {
            val h = handles[i]
            r.fillCircle(h, radius, pal.menuBg.scaleAlpha(0.95))
            r.strokeEllipse(h, radius, radius, Pen(pal.text, 1.4, cosmetic = true))
            r.strokePolyline(arcPolyline(h, radius * 0.5, 25.0, 155.0, 10), Pen(pal.textDim, 1.3, cosmetic = true))
            r.strokePolyline(arcPolyline(h, radius * 0.5, 205.0, 335.0, 10), Pen(pal.textDim, 1.3, cosmetic = true))
            val deg = if (i == 0) ccwFromPlusX else cwFromMinusX
            val outward = (h - ruler.center).normalized()
            drawReadout(r, "%.0f°".format(deg), h + outward * (radius + 28.0 * density), density, pal)
        }
    }

    /** cm/mm graduations on BOTH long edges; spacing scales with zoom; origin (0) at the ruler centre. */
    private fun drawRulerTicks(r: Renderer, density: Double, pal: Palette, sMin: Double, sMax: Double) {
        val cmPx = RulerMath.contentPxPerCm(document.dpi) * state.zoom
        if (cmPx <= 0.0) return
        val d = ruler.direction()
        val n = ruler.normal()
        val ht = ruler.thicknessPx / 2.0
        val tickPen = Pen(pal.text, 1.0, cosmetic = true)
        val labelFont = FontSpec(5.0 * density)
        val showMinor = cmPx >= 46.0
        val showLabels = cmPx >= 26.0
        val unitPx = if (showMinor) cmPx / 10.0 else cmPx
        val unitsPerLabel = if (showMinor) 10 else 1
        val step = if (showMinor || cmPx >= 12.0) 1 else 5 // crowd guard when zoomed far out
        var j = Math.ceil(sMin / unitPx).toInt()
        val jMax = Math.floor(sMax / unitPx).toInt()
        while (j <= jMax) {
            if (step == 1 || j % step == 0) {
                val mid = ruler.center + d * (j * unitPx)
                val top = mid + n * ht
                val bot = mid - n * ht
                val len = when {
                    !showMinor || j % 10 == 0 -> ht * 0.46
                    j % 5 == 0 -> ht * 0.30
                    else -> ht * 0.18
                }
                r.strokePolyline(listOf(top, top - n * len), tickPen)
                r.strokePolyline(listOf(bot, bot + n * len), tickPen)
                if (showLabels && j % unitsPerLabel == 0) drawTickLabel(r, abs(j / unitsPerLabel), mid, labelFont, pal.textDim)
            }
            j++
        }
    }

    private fun drawTickLabel(r: Renderer, cm: Int, center: Pt, font: FontSpec, color: Rgba) {
        val s = cm.toString()
        val w = s.length * font.pointSize * 1.25 + 2.0
        val h = font.pointSize * 2.1
        r.drawText(s, Rect(center.x - w / 2.0, center.y - h / 2.0, w, h), font, color)
    }

    private fun drawRulerButtons(r: Renderer, pal: Palette) {
        val radius = ruler.buttonRadiusPx()
        for ((btn, c) in ruler.buttonCenters()) {
            val active = when (btn) {
                RulerButton.LOCK_POS -> ruler.lockPosition
                RulerButton.LOCK_ANGLE -> ruler.lockAngle
            }
            r.fillCircle(c, radius, pal.menuBg.scaleAlpha(0.95))
            r.strokeEllipse(c, radius, radius, Pen(if (active) pal.text else pal.border, 1.3, cosmetic = true))
            val pen = Pen(if (active) pal.text else pal.textDim, 1.4, cosmetic = true)
            when (btn) {
                RulerButton.LOCK_POS -> {
                    val rr = radius * 0.5
                    r.strokeEllipse(c, rr * 0.5, rr * 0.5, pen)
                    r.strokePolyline(listOf(Pt(c.x, c.y - rr), Pt(c.x, c.y - rr * 0.5)), pen)
                    r.strokePolyline(listOf(Pt(c.x, c.y + rr * 0.5), Pt(c.x, c.y + rr)), pen)
                    r.strokePolyline(listOf(Pt(c.x - rr, c.y), Pt(c.x - rr * 0.5, c.y)), pen)
                    r.strokePolyline(listOf(Pt(c.x + rr * 0.5, c.y), Pt(c.x + rr, c.y)), pen)
                }
                RulerButton.LOCK_ANGLE -> {
                    val s = radius * 0.5
                    val v = Pt(c.x - s, c.y + s)
                    r.strokePolyline(listOf(v, Pt(c.x + s, c.y + s)), pen)
                    r.strokePolyline(listOf(v, Pt(c.x + s, c.y - s)), pen)
                    r.strokePolyline(arcPolyline(v, s * 0.95, 0.0, -45.0, 6), pen)
                }
            }
        }
    }

    private fun arcPolyline(center: Pt, radius: Double, startDeg: Double, endDeg: Double, segments: Int): List<Pt> {
        val pts = ArrayList<Pt>(segments + 1)
        for (i in 0..segments) {
            val t = Math.toRadians(startDeg + (endDeg - startDeg) * i / segments)
            pts.add(Pt(center.x + radius * cos(t), center.y + radius * sin(t)))
        }
        return pts
    }

    /** A small pill + text readout in viewport space, kept on-screen. */
    private fun drawReadout(r: Renderer, text: String, at: Pt, density: Double, pal: Palette) {
        val font = FontSpec(7.0 * density, bold = true)
        val padX = 7.0 * density
        val padY = 4.0 * density
        val textW = text.length * font.pointSize * 1.25
        val textH = font.pointSize * 2.1
        val w = textW + padX * 2
        val h = textH + padY * 2
        val left = (at.x - w / 2.0).coerceIn(2.0, (state.viewportW - w - 2.0).coerceAtLeast(2.0))
        val top = (at.y - h / 2.0).coerceIn(2.0, (state.viewportH - h - 2.0).coerceAtLeast(2.0))
        r.fillRect(Rect(left, top, w, h), pal.menuBg.scaleAlpha(0.92))
        r.strokeRect(Rect(left, top, w, h), Pen(pal.textDim, 1.2, cosmetic = true))
        r.drawText(text, Rect(left + padX, top + padY, textW + 2.0, textH), font, pal.text)
    }

    companion object {
        const val MIN_SAMPLE_DIST = 1.0

        /** Pen-up reduction tolerance, viewport px at the draw zoom (see [simplifyForCommit]). */
        const val SIMPLIFY_EPS = 0.2

        /** Scale on the ink low-pass lengths for a stroke drawn at [zoom], captured at pen-down
         *  and carried on the stroke. Screen-space like the capture gate and the pen-up tolerance:
         *  the smoothing hides the digitizer's jitter, which is a fixed size on screen, so writing
         *  small at high zoom must not be smoothed as if it were written large. Capped at 1 so
         *  zooming out cannot smear content detail the page will keep. */
        fun smoothScaleFor(zoom: Double): Double =
            if (zoom.isFinite() && zoom > 0.0) (1.0 / zoom).coerceAtMost(1.0) else 1.0

        const val MOVE_EPS = 0.01

        /** Vendor key some pens send for a genuinely held side button (OnePlus Pad Go 2 Stylo,
         *  which reports a real down/up pair with auto-repeat rather than a momentary click). */
        const val VENDOR_HELD_BUTTON_KEYCODE = KeyEvent.KEYCODE_F21

        /** Stylus side-button bits, widened past the S-Pen primary so pens on the secondary/tertiary lines count too. */
        val STYLUS_BUTTON_MASK =
            MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY or
                MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_TERTIARY

        /** Magic wand: idle time (ms) after the last stroke before the held batch fades. */
        const val WAND_HOLD_MS = 1000L

        /** Magic wand: fade-out duration (ms) once the batch starts disappearing. */
        const val WAND_FADE_MS = 500.0

        /** Ruler: a stylus-down within this (dp) of a long edge snaps the stroke to that edge. */
        const val RULER_SNAP_DP = 12.0

        /** Ruler: finger hit radius (dp) for the on-ruler control buttons. */
        const val RULER_BTN_HIT = 22.0

        /** Ruler: finger hit radius (dp) for the rotation handles. */
        const val RULER_HANDLE_HIT = 24.0

        /** Max finger drift (viewport px) from touch-down still counted as a tap (e.g. tap-to-dismiss). */
        const val TAP_SLOP = 12.0

        /** Padding (content px) added around erased items' bounds when repairing
         *  the cache, to cover stroke anti-aliasing at the dirty-rect edge. */
        const val REPAIR_PAD = 2.0

        /** Drawn side length (viewport px) of a resize-handle square. */
        const val HANDLE_SIZE = 16.0

        /** Touch radius (viewport px) around a handle centre — larger than the drawn
         *  square so a fingertip can grab it without the squares obscuring content. */
        const val HANDLE_HIT = 24.0

        /** Gap (viewport px) from the selection's top edge up to the rotate grip. */
        const val ROTATE_ARM = 28.0
        const val SHAPE_MIN_DRAG = 3.0

        /** Min capture size (content px, both axes) for the screenshot tool; below it a drag is a tap. */
        const val SHOT_MIN = 6.0

        /** Min drag (viewport px, either axis) for a text-box gesture to size a box rather than tap-create. */
        const val TEXT_DRAG_SLOP = 14.0

        /** Text point-size clamp for the style bar. */
        const val TEXT_MIN_PT = 6.0
        const val TEXT_MAX_PT = 96.0
        const val LONG_PRESS_MS = 450L
        const val LONG_PRESS_SLOP = 6.0

        /** Hold-to-snap: how long the pen must rest (ms) before a freehand stroke snaps to a shape. */
        const val SHAPE_DWELL_MS = 500L

        /** Hold-to-snap: pen drift (viewport px) above which the dwell timer restarts rather than fires. */
        const val SHAPE_DWELL_SLOP = 4.0

        /** Hold-to-snap: minimum samples before recognition is even attempted (mirrors the recognizer). */
        const val SHAPE_MIN_SAMPLES = 8

        /** Shape size -> thickness: the midpoint of the default pen's pressure width range (m=0.35..1.0),
         *  so a shape reads as thick as a same-size pen instead of as a flat full-width line. */
        const val SHAPE_PEN_PARITY = 0.675

        /** A line/arrow drawn with the shape tool snaps flat when within this angle (deg) of an axis.
         *  Half the recognizer's hold-to-snap angle: a live drag is steadier, so it needs less help. */
        const val SHAPE_AXIS_SNAP_DEG = 4.0

        // Inertial fling tuning (viewport px/s).
        const val VEL_SMOOTH = 0.4 // EMA weight on the previous velocity estimate
        const val FLING_FRICTION = 2.5 // higher = stops sooner; lower = floatier
        const val FLING_MIN_START = 120.0 // minimum flick velocity to start a glide
        const val FLING_MIN_STOP = 24.0 // velocity at which the glide ends

        // Elastic overscroll tuning (pull past the bottom end to add a page).
        const val OVERSCROLL_RESIST = 0.5 // fraction of past-end finger travel that becomes stretch (tighter < 1)
        const val OVERSCROLL_MAX = 320.0 // hard cap on the visible stretch (viewport px)
        const val OVERSCROLL_TRIGGER = 250.0 // stretch at which releasing appends a page
        const val OVERSCROLL_SPRING = 14.0 // spring-back rate toward rest (1/s; higher = snappier)

        // Paginated page-flip tuning.
        const val FLIP_RESIST = 0.55 // fraction of past-edge finger travel that becomes pull
        const val FLIP_TRIGGER = 90.0 // pull (viewport px) at which releasing flips the page
        const val FLIP_FLING_VEL = 900.0 // finger velocity (viewport px/s) that flips from the edge
        const val FLIP_MAX_FRACTION = 0.6 // pull cap, as a fraction of the viewport width
    }
}
