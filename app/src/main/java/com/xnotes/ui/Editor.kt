package com.xnotes.ui

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.CanvasView
import com.xnotes.canvas.EditingField
import com.xnotes.canvas.FlowTextController
import com.xnotes.canvas.InitialView
import com.xnotes.canvas.InteractionController
import com.xnotes.canvas.TextBar
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.AddItem
import com.xnotes.core.history.AddPage
import com.xnotes.core.history.Command
import com.xnotes.core.history.CompositeCommand
import com.xnotes.core.history.DeletePage
import com.xnotes.core.history.EraseItems
import com.xnotes.core.history.History
import com.xnotes.core.model.Bookmark
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageMargins
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.Renderer
import com.xnotes.core.model.deepCopy
import com.xnotes.core.model.snapshot
import com.xnotes.core.model.insets
import com.xnotes.core.model.paintMarginPattern
import com.xnotes.core.model.paintPagePattern
import com.xnotes.core.model.resolvedPageColor
import com.xnotes.core.model.resolvedPattern
import com.xnotes.core.model.resolvedPatternColor
import com.xnotes.core.model.resolvedSpacing
import com.xnotes.core.text.CharStyle
import com.xnotes.core.text.FlowDefaults
import com.xnotes.core.text.FlowEditor
import com.xnotes.core.text.FlowFrame
import com.xnotes.core.text.FlowLayout
import com.xnotes.core.text.FlowMargins
import com.xnotes.core.text.FlowPainter
import com.xnotes.core.text.FlowPos
import com.xnotes.core.text.FlowRange
import com.xnotes.core.text.ListKind
import com.xnotes.core.text.PageBox
import com.xnotes.core.text.ParaAlign
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.wordBoundary
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.util.DocumentKind
import com.xnotes.core.util.NameTemplate
import com.xnotes.format.DocumentCodec
import com.xnotes.format.XNoteFormatException
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidSurfaceFactory
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.settings.ExplorerSortKey
import com.xnotes.settings.LiveSettings
import com.xnotes.settings.Preferences
import com.xnotes.settings.Settings
import com.xnotes.settings.SettingsRepository
import com.xnotes.ui.theme.MaterialColors
import com.xnotes.ui.theme.Palette
import com.xnotes.ui.theme.dynamicMaterialColors
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app-side glue between the imperative canvas (CanvasView + CanvasState +
 * InteractionController + History) and the Compose chrome. Exposes Compose-
 * observable state and the actions the toolbar/menus invoke.
 */
/** Target of the long-press paste context menu (viewport position + paste point). */
/**
 * Where a long press landed. [locked] is the pinned item under it, if there was one, and it turns
 * the menu into a single offer to release that item.
 */
data class ContextMenuTarget(
    val viewportX: Double,
    val viewportY: Double,
    val content: com.xnotes.core.geometry.Pt,
    val locked: com.xnotes.core.model.CanvasItem? = null,
)

/**
 * One entry (folder or .xnote file) in the in-app explorer. [documentUri] is a SAF document URI.
 * [modified] is SAF's last-modified time; [created] is the app-tracked creation time used for grid
 * ordering (SAF exposes no creation time — see [CreationTimeStore]). [parentDocId] is the listing
 * folder (where colour writes go), and [color] is the item's explorer colour code, if any.
 */
data class BrowseEntry(
    val name: String,
    val documentUri: String,
    val isDir: Boolean,
    val size: Long = 0,
    val modified: Long = 0,
    val created: Long = 0,
    val parentDocId: String = "",
    val color: Rgba? = null,
)

/** Whether a pending import came from the PDF picker or the system "Open…" file picker. */
enum class ImportKind { PDF, OPEN }

/** A picked file awaiting a name before it's saved into the explorer's current folder. */
data class PendingImport(val kind: ImportKind, val defaultName: String, val uri: String)

/** Per-folder colour sidecar: a hidden ".xnote" dir holding "colors.json" (item name -> hex). */
private const val SIDECAR_DIR = ".xnote"
private const val SIDECAR_FILE = "colors.json"

/**
 * Which pane of a split view an editor drives. [PRIMARY] is the app's one editor in the ordinary
 * single-note case and the first pane of a split; [SECONDARY] is the second pane, built only when a
 * split opens. The two differ in the app-level chrome they own, not in what they can edit: the
 * secondary never purges the shared temp dirs (the primary may have a note live in them), keeps its
 * session in its own slot, and leaves the tool/toolbar preference snapshot to the primary.
 */
enum class Pane { PRIMARY, SECONDARY }

@Stable
class Editor(context: Context, val pane: Pane = Pane.PRIMARY) : ToolPopupHost, SelectionMenuHost, LongPressMenuHost {

    private val appContext = context.applicationContext

    /** The context the views are built from, kept so a second pane can be built the same way. The
     *  application context will not do: it is not attached to a display, so the cutout probe throws
     *  on it and any View made from it misses the activity's theme. */
    private val viewContext = context
    private val settingsRepo = SettingsRepository(context)

    /** Read and written through the process-wide [LiveSettings] so both split panes share one copy. */
    private var settings: Settings
        get() = LiveSettings.get(settingsRepo)
        set(value) { LiveSettings.set(value) }
    private var pdfSource: com.xnotes.platform.PdfSource? = null

    private val deviceHasDisplayCutout = com.xnotes.deviceHasDisplayCutout(context)

    /** Whether the OS is in dark mode right now; resolves the "system" appearance. */
    private var systemInDarkMode = (context.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Whether the window runs in fullscreen. Persisted via [Preferences.startFullscreen]; when unset
     *  it defaults to on unless the display has a camera cutout. The activity observes this and drives
     *  the window. */
    var fullscreen by mutableStateOf(settings.prefs.startFullscreen ?: !deviceHasDisplayCutout)
        private set

    /** Prepares one of the shared temp dirs. The launch purge drops temps orphaned by a crash, which
     *  is safe for the primary editor because no real note is open yet at construction. A secondary
     *  pane is built mid-session, with the primary's note already live in these dirs, so it only
     *  takes the handle. */
    private fun tempDir(name: String): java.io.File =
        java.io.File(appContext.filesDir, name).apply {
            mkdirs()
            if (pane == Pane.PRIMARY) listFiles()?.forEach { it.delete() }
        }

    /** Private dir holding each note's source PDF as a file, so a large PDF is never held whole in
     *  RAM (the renderer memory-maps it). Under filesDir, not the reclaimable cacheDir: the OS could
     *  evict a cacheDir copy mid-session, and the next autosave would then fail to re-embed the PDF.
     *  Both panes share it; the files inside carry unique temp names. */
    private val pdfDir = tempDir("pdfsrc")

    /** Encoded inserted images, streamed to disk so a note full of large images never loads all their
     *  bytes into the heap; the renderer decodes from these files on demand. Under filesDir (not the
     *  reclaimable cacheDir). */
    private val imageDir = tempDir("noteimg")

    /** Scratch dir for [writeNoteSafely]: a note is encoded here in full before any SAF file is
     *  touched, so a failed encode can never truncate a good note. Lives under filesDir (not the
     *  reclaimable cacheDir). */
    private val saveTmpDir = tempDir("savetmp")

    /** The sticker library: encoded image files kept under filesDir across sessions (never purged).
     *  Only [java.io.File] handles are held in memory; thumbnails and inserts decode from disk. */
    // The directory keeps its old "stamps" name so libraries saved before the rename still load.
    private val stickerDir = java.io.File(appContext.filesDir, "stamps").apply { mkdirs() }

    /** Sticker files, oldest first (names embed the add time so a plain name sort is stable). */
    var stickers: List<java.io.File> by mutableStateOf(listStickers())
        private set

    private fun listStickers(): List<java.io.File> = stickerDir.listFiles()?.sortedBy { it.name }.orEmpty()

    /** Re-read the library into both panes: they browse one shared directory, so a sticker added
     *  or removed on one toolbar has to show up on the other. */
    private fun refreshStickers() {
        stickers = listStickers()
        sibling?.let { it.stickers = it.listStickers() }
    }

    /** The temp PDF file backing the currently open document, tracked so it's deleted when the note
     *  is swapped out (transient docs used for export/thumbnails manage their own files locally). */
    private var openPdfTemp: java.io.File? = null

    val state = CanvasState(
        blankDocument().also { stampNewNoteDefaults(it) },
        AndroidSurfaceFactory(),
        buildPalette(settings.prefs),
    )
    val history = History()
    val view = CanvasView(context).also { it.state = state }

    /** The front buffer wet ink goes into, above [view] and transparent whenever no pen is down. */
    val pad = com.xnotes.gl.GlWetPad(context, onTop = true)

    /**
     * The two surfaces, in order. The pad is a sibling above the canvas rather than part of it: the
     * canvas paints into the window and the front buffer has to be a surface of its own.
     */
    val surfaces = android.widget.FrameLayout(context).apply {
        addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
        addView(pad, android.widget.FrameLayout.LayoutParams(-1, -1))
    }
    private val textMeasurer = AndroidTextMeasurer()
    private val imageCodec = AndroidImageCodec()
    private val codec = DocumentCodec(imageCodec, textMeasurer)
    private val canvasCodec = com.xnotes.format.CanvasCodec(imageCodec)

    /** One flow layout + snapshot: repainted from cache threads, so only the published frame is read. */
    private class PublishedFlow(val frame: FlowFrame, val indexOf: Map<Page, Int>)
    private val flowLayout = FlowLayout(textMeasurer)

    private val treeSitter = com.xnotes.platform.TreeSitterHighlighter(appContext)
    private val highlighter: com.xnotes.core.text.CodeHighlighter? =
        treeSitter.takeIf { com.xnotes.platform.TreeSitterNative.loaded }

    /** A user-imported Helix code theme (parsed once); null = the built-in dark/light pair. */
    private var customCodeTheme: com.xnotes.core.text.HighlightTheme? =
        settings.prefs.codeThemePath?.let { path ->
            runCatching { com.xnotes.format.HelixTheme.parse(java.io.File(path).readText()) }.getOrNull()
        }

    private fun activeCodeTheme(): com.xnotes.core.text.HighlightTheme = customCodeTheme
        ?: if (resolvedAppearance(settings.prefs) != "light") {
            com.xnotes.core.text.HighlightTheme.DARK
        } else {
            com.xnotes.core.text.HighlightTheme.LIGHT
        }

    /** Derived spans per code paragraph, keyed by its revision (main thread only). */
    private val highlightCache = HashMap<Paragraph, Pair<Int, List<com.xnotes.core.text.HighlightSpan>>>()
    private var highlightJob: kotlinx.coroutines.Job? = null

    @Volatile private var publishedFlow: PublishedFlow? = null
    private var publishedFlowStamp = -1L
    private var publishedPageList: List<Page> = emptyList()
    /** Each pane keeps its working session in its own slot, so two open notes never overwrite
     *  each other's saved document. */
    private val sessionDir = java.io.File(appContext.filesDir, if (pane == Pane.PRIMARY) "session" else "session-b")
    private val session = com.xnotes.platform.SessionStore(sessionDir, codec, pdfDir, imageDir)
    private val canvasSession = com.xnotes.platform.CanvasSessionStore(sessionDir, canvasCodec, imageDir)
    private val viewStates = com.xnotes.platform.ViewStateStore(com.xnotes.platform.JsonStore.viewStates(appContext))
    private var lastSessionContentVersion = -1
    private var sessionLoaded = false

    /** On-disk note-thumbnail cache (png + source mtime) so the grid paints instantly across launches. */
    private val thumbCache = com.xnotes.platform.NoteThumbnailCache(java.io.File(appContext.filesDir, "note_thumbs"))
    /** In-memory note-tile thumbnails keyed by SAF URI, bounded by bytes (Compose owns the pixels —
     *  no manual recycle). */
    private val noteThumbs = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    /** App-tracked creation times for explorer ordering (SAF exposes only last-modified). */
    private val createdStore = com.xnotes.platform.CreationTimeStore(com.xnotes.platform.JsonStore.createdTimes(appContext))
    /** A single lowest-priority background thread renders explorer thumbnails one at a time, so a
     *  folder of many notes fills in gradually without ever competing with the UI/render threads. */
    private val thumbDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "note-thumbs").apply { priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
    /**
     * Side-panel page thumbnails, rendered once and reused so scrolling the panel doesn't re-render.
     * Keyed by [Page] **identity** (not index) so a drag-reorder keeps each page's bitmap instead of
     * re-rendering every row; the whole cache is dropped when [contentVersion] moves (a real edit).
     */
    private val pageThumbs = object : LruCache<Page, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Page, value: ImageBitmap) = value.width * value.height * 4
    }
    private var pageThumbsVersion = -1
    /** In-memory caches so reopening the backstage paints instantly (seed first, refresh after). */
    private val browseCache = java.util.concurrent.ConcurrentHashMap<String, List<BrowseEntry>>()
    private val rootNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** When non-null, the current note lives in the granted folder and autosaves to this URI. */
    var autosaveUri: String? = null
        private set
    private val autosaveScope = kotlinx.coroutines.MainScope()

    /** The note autosave's debounce timer. Cancelled freely: nothing has been written yet. */
    private var noteDebounceJob: kotlinx.coroutines.Job? = null

    /**
     * The note write itself, from snapshot to bookkeeping. Never cancelled. The write blocks with
     * no suspension point, so cancelling it does not stop the bytes; it only skips everything after
     * them, leaving the note dirty, the thumbnail stale and a fork unadopted (so the next save forks
     * again). Callers [kotlinx.coroutines.Job.join] it instead, which also keeps at most one write
     * in flight and one waiting behind it.
     */
    private var noteWriteJob: kotlinx.coroutines.Job? = null

    /** Serializes every write to a note file so an autosave and a flush/Save-As can't truncate and
     *  copy the same destination at once (which would corrupt it). */
    private val saveLock = Any()

    /** Buffer for the temp-to-storage copy every save ends with. `copyTo`'s 8 KB default turns a
     *  large note into thousands of round trips through the provider; a megabyte at a time doesn't. */
    private val copyBuffer = 1024 * 1024

    var tool by mutableStateOf(Tool.DEFAULT)
        private set
    var palette by mutableStateOf(state.palette)
        private set
    var zoomPercent by mutableStateOf(100)
        private set

    /** Bumped each time a pinch snaps to fit-to-width; the toolbar's transient lock hint observes
     *  this to (re)show itself and re-arm its auto-dismiss timer. */
    var zoomLockHint by mutableStateOf(0)
        private set

    /** Bumped when a pinch breaks past the fit-to-width magnet; the lock hint observes this to
     *  dismiss itself immediately. */
    var zoomLockHintDismiss by mutableStateOf(0)
        private set
    var pageIndex by mutableStateOf(0)
        private set
    var pageCount by mutableStateOf(state.document.pages.size)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    /** Set when a canvas edit changed a persisted style, so the next pause writes it out. */
    private var settingsDirty = false

    var activeColorIndex by mutableStateOf(0)
        private set
    var toolbarColors by mutableStateOf(InkPalette.presets)
        private set
    var toolbarColorCount by mutableStateOf(5)
        private set
    var toolbarLayout by mutableStateOf(ToolbarLayout.DEFAULT)

    /** The infinite canvas's own bar. Its own layout: the two surfaces hold different items. */
    var canvasToolbarLayout by mutableStateOf(ToolbarLayout.CANVAS_DEFAULT)
        private set
    var renderScale by mutableStateOf(1.0)
        private set
    var sidebarVisible by mutableStateOf(false)
    /** Granted explorer root (a SAF tree URI), or null until the user picks a folder. */
    var browseRoot by mutableStateOf(settings.browseRoot)
        private set
    /** A picked PDF/.xnote awaiting a name before it's saved into the explorer; drives the inline name field. */
    var pendingImport by mutableStateOf<PendingImport?>(null)
        private set
    /** True while a committed import is being written off-thread; drives the "Importing…" dialog. */
    var importing by mutableStateOf(false)
        private set
    /** Flipped by the import dialog's Cancel so the in-flight stream-copy aborts at its next buffer. */
    private val importCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    /** True while a tapped note is being read off-thread; drives the "Opening note…" dialog. */
    var opening by mutableStateOf(false)
        private set
    /** True while a dirty note is being flushed to its folder file off-thread on close; drives the
     *  "Saving your notes…" dialog so quitting a large note never freezes the UI (ANR). */
    var savingNote by mutableStateOf(false)
        private set
    /** Flipped by the open dialog's Cancel so a slow open is discarded instead of swapped in. */
    private val openCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    var zoomLocked by mutableStateOf(false)
        private set

    /** Global View-menu defaults every note without overrides follows (persisted in settings.json). */
    var viewDefaults by mutableStateOf(settings.viewDefaults)
        private set

    /** The open note's View-menu overrides (stored app-side like zoom/scroll). */
    var viewOverrides by mutableStateOf(com.xnotes.canvas.ViewOverrides())
        private set

    /** The open note's effective View settings: [viewOverrides] resolved over [viewDefaults].
     *  This is what the canvas, caches and thumbnails all consume. */
    var viewSettings by mutableStateOf(settings.viewDefaults)
        private set
    var rulerVisible by mutableStateOf(false)
        private set
    var wandEnabled by mutableStateOf(false)
        private set
    var hasSelection by mutableStateOf(false)
        private set
    var shapeConfig by mutableStateOf(ShapeConfig())
        private set
    var message by mutableStateOf<String?>(null)

    /** Bumped on every preferences change so open panes refresh (e.g. after an .scm import). */
    var prefsVersion by mutableStateOf(0)
        private set
    var editingField by mutableStateOf<EditingField?>(null)
        private set

    /** The floating text style bar's target (active box rect + style), or null when no box is active. */
    var textBar by mutableStateOf<TextBar?>(null)
        private set

    /** Viewport rect to anchor the on-selection menu, or null when hidden. */
    var selectionMenu by mutableStateOf<com.xnotes.core.geometry.Rect?>(null)
        private set

    override val selectionMenuRect: com.xnotes.core.geometry.Rect? get() = selectionMenu

    /** Viewport rect to anchor the screenshot tool's "copy as image" menu, or null when hidden. */
    var screenshotMenu by mutableStateOf<com.xnotes.core.geometry.Rect?>(null)
        private set

    /** Long-press paste context menu target, or null when hidden. */
    override var contextMenu by mutableStateOf<ContextMenuTarget?>(null)
        private set
    var title by mutableStateOf(state.document.title)
        private set
    var dirty by mutableStateOf(false)
        private set

    /** True when a note is open (the editor is pushed on top of backstage); false = backstage is the
     *  bare root of the stack. Starts false so every launch lands on home with no phantom note. */
    var noteOpen by mutableStateOf(false)
        private set

    /** True when the open document is an infinite canvas rather than a paged note, so the editor
     *  layer hosts the GL canvas and its own chrome. Meaningful only while [noteOpen]. */
    var canvasOpen by mutableStateOf(false)
        private set

    private var infiniteOrNull: InfiniteEditor? = null

    /** The infinite canvas's orchestrator, built on first use. It hangs off this editor rather
     *  than standing beside it so it can never race the temp-directory purge in this constructor,
     *  which would delete an open note's live PDF and image files out from under it. */
    val infinite: InfiniteEditor
        get() = infiniteOrNull ?: InfiniteEditor(appContext).also {
            infiniteOrNull = it
            it.applyPalette(palette)
            // Both editors write inserted images into the same purged-on-launch temp dir.
            it.imageDir = imageDir
            for (t in ToolDefaults.persistedTools) it.setToolConfig(t, settings.configFor(t))
            it.armShapeConfig(settings.shapeConfig)
            it.toolbarColors = toolbarColors
            it.recentColors = recentColors
            it.toolbarColorCount = toolbarColorCount
            it.toolbarLayout = canvasToolbarLayout
            it.pickColor(activeColorIndex)
            // A style tuned on the canvas is the same style, so it persists through this editor.
            it.onToolStyleChanged = { settingsDirty = true }
            it.onSwatchColorChanged = { index, color -> adoptSwatchColor(index, color) }
            it.onColorRemembered = { color ->
                settings = settings.rememberColor(color)
                it.recentColors = recentColors
            }
        }

    /** Take on a swatch the canvas recoloured, so both bars show it and the next save keeps it. */
    private fun adoptSwatchColor(index: Int, color: Rgba) {
        if (index !in toolbarColors.indices) return
        toolbarColors = toolbarColors.toMutableList().also { it[index] = color }
        activeColorIndex = index
        controller.pickInk(color)
        settingsDirty = true
    }

    /** The canvas's autosave binding, the sibling of [autosaveUri] for the paged note. */
    var canvasAutosaveUri: String? = null
        private set

    /** The canvas siblings of [noteDebounceJob] / [noteWriteJob], for the same reasons. */
    private var canvasDebounceJob: kotlinx.coroutines.Job? = null
    private var canvasWriteJob: kotlinx.coroutines.Job? = null

    /** Open a fresh, unsaved infinite canvas on top of backstage. */
    fun newCanvas() {
        openCanvasDocument(com.xnotes.core.infinite.InfiniteDocument(), uri = null, displayName = null)
    }

    /**
     * Read the `.xcanvas` at [uri] and push its editor on top of backstage. IO, so call it off the
     * main thread; the model swap itself hops back to the main thread.
     */
    suspend fun openCanvasAsync(uri: String, name: String?): Boolean {
        val doc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                appContext.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
                    canvasCodec.read(it, imageDir)
                }
            }.getOrNull()
        } ?: return false
        openCanvasDocument(doc, uri, name)
        return true
    }

    private fun openCanvasDocument(
        doc: com.xnotes.core.infinite.InfiniteDocument,
        uri: String?,
        displayName: String?,
    ) {
        flushAutosave() // a paged note may be open underneath; do not leave its edits unwritten
        doc.path = uri
        doc.displayName = displayName
        val canvas = infinite
        canvas.replaceDocument(doc)
        canvas.applyPalette(palette)
        canvas.applyInputPrefs(settings.prefs.fingerDraws, controller.penButtonTool, settings.prefs.zoomLockPan)
        canvas.applyZoomRange(settings.prefs.canvasMinZoomPercent, settings.prefs.canvasMaxZoomPercent)
        canvas.onContentChanged = { scheduleCanvasAutosave() }
        // Only a canvas living under the granted folder autosaves; anything else is left alone,
        // matching how a note opened from outside the root behaves.
        canvasAutosaveUri = if (uri != null && browseRoot?.let { isUnderTree(uri, it) } == true) uri else null
        lastCanvasStamp = canvasAutosaveUri?.let { stampOf(it) }
        canvasOpen = true
        noteOpen = true
    }

    /** Write [doc] to [uri] through a private temp, so a failed encode never truncates a good file. */
    private fun writeCanvasSafely(uri: String, doc: com.xnotes.core.infinite.InfiniteDocument): Boolean =
        synchronized(saveLock) {
            runCatching {
                val tmp = java.io.File.createTempFile("save", ".xcanvas", saveTmpDir)
                try {
                    java.io.FileOutputStream(tmp).use { canvasCodec.write(doc, it) }
                    val out = appContext.contentResolver.openOutputStream(android.net.Uri.parse(uri), "wt")
                        ?: return@runCatching false
                    out.use { java.io.FileInputStream(tmp).use { input -> input.copyTo(it, copyBuffer) } }
                    lastCanvasStamp = stampOf(uri)
                    true
                } finally {
                    tmp.delete()
                }
            }.getOrDefault(false)
        }

    private fun scheduleCanvasAutosave() {
        val uri = canvasAutosaveUri ?: return
        canvasDebounceJob?.cancel()
        canvasDebounceJob = autosaveScope.launch {
            kotlinx.coroutines.delay(1200L) // debounce: write after a short idle
            canvasWriteJob?.join() // wait out a write already going out rather than racing it
            val canvas = infiniteOrNull ?: return@launch
            val doc = canvas.document
            if (!doc.dirty) return@launch
            startCanvasWrite(uri, doc, doc.displayName ?: doc.title)
        }
    }

    /**
     * The canvas sibling of [startNoteWrite], tracked as [canvasWriteJob] and never cancelled. The
     * writer gets its own item list over the live items ([InfiniteDocument.snapshotForWrite]), taken
     * here on the main thread; the bookkeeping stays on the live [doc].
     */
    private fun startCanvasWrite(
        uri: String,
        doc: com.xnotes.core.infinite.InfiniteDocument,
        title: String,
        onDone: (() -> Unit)? = null,
    ) {
        val snapshot = doc.snapshotForWrite()
        val wasDirty = doc.dirty
        doc.dirty = false // the snapshot holds these edits; see [startNoteWrite]
        canvasWriteJob = autosaveScope.launch {
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                saveCanvasGuarded(uri, snapshot, title)
            }
            if (res != null) {
                if (infiniteOrNull?.document === doc) res.fork?.let { adoptCanvasFork(it) }
                invalidateThumb(res.uri)
            } else if (wasDirty && infiniteOrNull?.document === doc) {
                doc.dirty = true // nothing was written; the edits are still unsaved
            }
            onDone?.invoke()
        }
    }

    /** Write the open canvas to its file now; a no-op when it is not a folder canvas or not dirty. */
    fun flushCanvasAutosave() {
        canvasDebounceJob?.cancel() // only the debounce; a write already going out is left to finish
        val uri = canvasAutosaveUri ?: return
        val canvas = infiniteOrNull ?: return
        if (!canvas.document.dirty) return
        val doc = canvas.document
        val res = saveCanvasGuarded(uri, doc, doc.displayName ?: doc.title) ?: return // synchronous: no snapshot needed
        doc.dirty = false
        res.fork?.let { adoptCanvasFork(it) }
        invalidateThumb(res.uri)
    }

    /**
     * Flush the open canvas to its folder file off the main thread, then run [onDone] on the main
     * thread. Shows the "Saving your notes…" overlay while it runs when [showOverlay]. Runs [onDone]
     * at once (no save) when the canvas isn't a folder canvas or isn't dirty, so the common case
     * stays instant. The sibling of [flushThen], for the same reason: a dense canvas takes long
     * enough to write that doing it on the main thread freezes the UI on close.
     */
    private fun flushCanvasThen(showOverlay: Boolean, onDone: () -> Unit) {
        canvasDebounceJob?.cancel()
        val uri = canvasAutosaveUri
        val doc = infiniteOrNull?.document
        if (uri == null || doc == null || !doc.dirty) { onDone(); return }
        val title = doc.displayName ?: doc.title
        if (showOverlay) savingNote = true
        autosaveScope.launch {
            canvasWriteJob?.join() // a write already going out finishes first; it may be all there was
            if (!doc.dirty) { savingNote = false; onDone(); return@launch }
            startCanvasWrite(uri, doc, title) {
                savingNote = false
                onDone()
            }
        }
    }

    /** Bumped whenever page content changes, to refresh thumbnails. */
    var contentVersion by mutableStateOf(0)
        private set

    /** Bumped when the bookmark list changes. */
    var bookmarkVersion by mutableStateOf(0)
        private set

    /** The open PDF's extracted outline (its table of contents), empty for non-PDF notes or PDFs with
     *  no outline. Parsed off-thread on document open; [tocVersion] bumps when it arrives. */
    private var toc: List<com.xnotes.platform.PdfOutlineEntry> = emptyList()
    val tableOfContents: List<com.xnotes.platform.PdfOutlineEntry> get() = toc
    var tocVersion by mutableStateOf(0)
        private set

    /** True while a filtered PDF's embedded-image colours are still being parsed off-thread (only
     *  when the note's resolved View settings filter pages and keep image colours); drives the
     *  canvas hint. */
    var isRefiningPdf by mutableStateOf(false)
        private set

    /** Up-front sweep progress for the "Refining PDF colours k/N pages…" hint: PDF pages whose
     *  embedded-image boxes are parsed, and the total PDF page count. Both 0 when not refining. */
    var refiningDone by mutableStateOf(0)
        private set
    var refiningTotal by mutableStateOf(0)
        private set

    /** Bumped when the sweep finishes a PDF page that has a cached side-panel thumbnail, so the panel
     *  re-renders that row from inverted to real image colours. Observed by the thumbnail producer. */
    var pdfThumbTick by mutableStateOf(0)
        private set

    /** Pages the side panel has selected, by **identity** so reorder/delete never breaks the set. */
    private val selectedPages = mutableStateListOf<Page>()

    /** Deep-cloned pages held for paste (cleared when the document changes). A snapshot list so
     *  paste affordances recompose when it gains/loses contents. */
    private val pageClipboard = mutableStateListOf<Page>()

    /** The current document's storage location (a SAF content URI string), or null. */
    val currentUri: String? get() = state.document.path

    val bookmarks: List<Bookmark> get() = state.document.bookmarks.toList()

    val controller: InteractionController = InteractionController(
        state,
        history,
        textMeasurer,
        requestRender = { onRender() },
        onContentChanged = { refreshContent() },
        onViewChanged = { refreshView() },
        onFitWidthSnapped = { showZoomLockHint() },
        onFitWidthReleased = { hideZoomLockHint() },
        onSelectionChanged = { selected -> hasSelection = selected; refreshTextBar() },
        onToolChanged = { t -> tool = t },
        onTextEditStart = { field -> editingField = field; refreshTextBar() },
        onTextEditEnd = { editingField = null; refreshTextBar() },
        onSelectionMenu = { rect -> selectionMenu = rect },
        onScreenshotMenu = { rect -> screenshotMenu = rect },
        onContextMenu = { vp, content, locked -> contextMenu = ContextMenuTarget(vp.x, vp.y, content, locked) },
        onAddPageAtEnd = { addPageAtEnd() },
        onHaptic = { runCatching { view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } },
    )

    val flowText: FlowTextController = FlowTextController(
        state,
        history,
        flow = { state.document.flow },
        frame = { publishedFlow?.frame },
        slotHeight = { flowLayout.defaultSlotHeight(state.document.flow) },
        onChanged = { live -> onFlowChanged(live) },
        onFlushed = { onFlowFlushed() },
        onSessionChanged = { active -> onFlowSessionChanged(active) },
        onViewChanged = { refreshView() },
        requestRender = { onRender() },
    ).also { ctrl ->
        controller.flowText = ctrl
        ctrl.onHaptic = {
            runCatching { view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) }
        }
        ctrl.onCaretChanged = {
            flowSelTick++
            flowContextMenu = null
        }
        ctrl.onContextMenu = { viewport -> flowContextMenu = flowMenuAnchor(viewport) }
        ctrl.caretMetricsFor = { style ->
            val flow = state.document.flow
            val para = flow.paragraphs.getOrNull(ctrl.selection.normalized().start.para) ?: Paragraph()
            textMeasurer.metrics(com.xnotes.core.text.resolveFont(flow, para, style))
        }
    }

    /** Bumped whenever the flow caret/selection or pending style moves (the format bar keys on it). */
    var flowSelTick by mutableStateOf(0)
        private set

    private val flowInput = com.xnotes.canvas.FlowInput(view, flowText, { state.document.flow })
        .also {
            view.flowInput = it
            it.onPaste = { pastePlainAtCaret() }
            it.onBackspaceSpecial = { flowBackspaceSpecial() }
            it.onForwardDeleteSpecial = { flowForwardDeleteSpecial() }
        }

    /** True while the inline text caret session is live (drives the bottom format bar/IME). */
    var flowEditingActive by mutableStateOf(false)
        private set

    /** The clipboard's text content, or null. */
    private fun clipboardText(): String? {
        val cm = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return null
        val clip = cm.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
        return clip.getItemAt(0).coerceToText(appContext)?.toString()?.takeIf { it.isNotEmpty() }
    }

    fun clipboardHasText(): Boolean = clipboardText() != null

    /** Viewport bounds the flow-editing action bar anchors to, or null when closed. */
    var flowContextMenu by mutableStateOf<Rect?>(null)
        private set

    fun dismissFlowContextMenu() {
        flowContextMenu = null
    }

    /** The flow selection's viewport bounds (the caret's rect when collapsed). */
    private fun flowMenuAnchor(fallback: Pt): Rect {
        val sel = flowText.selection.normalized()
        val rects = publishedFlow?.frame?.let { f ->
            val local = if (sel.collapsed) listOfNotNull(f.caretRect(sel.end)) else f.selectionRects(sel)
            local.mapNotNull { (pi, r) ->
                state.pageRects.getOrNull(pi)?.let { pr -> r.translate(pr.left, pr.top) }
            }
        }.orEmpty()
        if (rects.isEmpty()) return Rect(fallback.x, fallback.y, 0.0, 0.0)
        val tl = state.contentToViewport(Pt(rects.minOf { it.left }, rects.minOf { it.top }))
        val br = state.contentToViewport(Pt(rects.maxOf { it.right }, rects.maxOf { it.bottom }))
        return Rect(tl.x, tl.y, br.x - tl.x, br.y - tl.y)
    }

    /** Paste the clipboard's text verbatim at the flow caret. */
    fun pastePlainAtCaret() {
        if (!flowText.active) return
        val text = clipboardText() ?: return
        flowText.replaceExternal(flowText.selection, text)
    }

    /** Parse the clipboard's text as markdown and paste it at the caret's font size. */
    fun pasteMarkdownAtCaret() {
        if (!flowText.active) return
        val text = clipboardText() ?: return
        val caretSize = flowCaretStyle().sizePt
        val paras = com.xnotes.core.text.MarkdownParser.parse(text, caretSize ?: flowDefaultSizePt())
        if (caretSize != null) {
            for (p in paras) {
                for (run in p.runs) {
                    if (run.style.sizePt == null) run.style = run.style.copy(sizePt = caretSize)
                }
            }
        }
        insertFlowParagraphs(paras)
    }

    /** Paste the clipboard's text as a code block, at the caret's font size. */
    fun pasteAsCodeAtCaret() {
        if (!flowText.active) return
        val text = clipboardText() ?: return
        val lang = settings.prefs.defaultCodeLanguage.takeIf { it != "plain" } ?: ""
        val style = CharStyle(sizePt = flowCaretStyle().sizePt)
        insertFlowParagraphs(
            text.split('\n').map { line ->
                Paragraph(
                    if (line.isEmpty()) mutableListOf() else mutableListOf(com.xnotes.core.text.Run(line, style)),
                    codeLang = lang,
                )
            },
        )
    }

    /** Splice ready-made paragraphs at the caret as one undo step (rich paste). */
    private fun insertFlowParagraphs(paras: List<Paragraph>) {
        if (paras.isEmpty()) return
        flowText.flushBurst()
        val flow = state.document.flow
        val ed = FlowEditor(flow)
        val cmds = mutableListOf<Command>()
        val sel = flowText.selection.normalized()
        var p = sel.start
        if (!sel.collapsed) {
            val (c, np) = ed.replaceRange(sel, "")
            c?.let(cmds::add)
            p = np
        }
        val caretPara = flow.paragraphs.getOrNull(p.para)
        val lastIndex: Int
        when {
            caretPara == null -> {
                cmds.add(ed.insertParagraphs(0, paras))
                lastIndex = paras.size - 1
            }
            caretPara.length == 0 && caretPara.isDefaultStyle() -> {
                // Pasting on an empty plain line replaces it, so no stray blank stays above.
                cmds.add(ed.replaceParagraphs(p.para, 1, paras))
                lastIndex = p.para + paras.size - 1
            }
            else -> {
                val (c2, _) = ed.replaceRange(FlowRange.caret(p), "\n")
                c2?.let(cmds::add)
                cmds.add(ed.insertParagraphs(p.para + 1, paras))
                lastIndex = p.para + paras.size
            }
        }
        val cmd = if (cmds.size == 1) cmds[0] else CompositeCommand(cmds)
        val caretTo = FlowPos(lastIndex, flow.paragraphs.getOrNull(lastIndex)?.length ?: 0)
        flowText.commitEdit(cmd, caretTo)
    }

    /**
     * A flow mutation landed. While the session is live the flow is lifted out of the
     * caches, so keystrokes only republish the layout snapshot and repaint; everything
     * else (checkbox taps, menu pastes, session-less edits) takes the full invalidate +
     * refresh path so the baked layer and chrome follow.
     */
    private fun onFlowChanged(live: Boolean) {
        state.document.dirty = true
        republishFlow(invalidate = !live)
        if (live) onRender() else refreshContent()
    }

    /** A burst landed. */
    private fun onFlowFlushed() {
        refreshContent()
    }

    private fun onFlowSessionChanged(active: Boolean) {
        flowEditingActive = active
        if (active) {
            flowInput.startSession()
        } else {
            flowContextMenu = null
            flowInput.endSession()
            refreshContent()
        }
        onRender()
    }

    /**
     * A repaint was asked for. The canvas skips it while the front buffer owns the stroke under the
     * pen: it is not drawing that ink, so the frame would come out the same, and the blit is work
     * on the thread the pen's samples have to get through.
     */
    private fun onRender() {
        if (controller.frontInk?.live != true) view.requestRender()
    }

    init {
        view.input = { ev ->
            // Any fresh canvas touch quietly retires the flow action bar and still does its job.
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) flowContextMenu = null
            controller.onTouch(ev)
        }
        view.onTwoFingerTap = { dispatchTapGesture(preferences.twoFingerTap) }
        view.onThreeFingerTap = { dispatchTapGesture(preferences.threeFingerTap) }
        view.hover = { controller.onHover(it) }
        view.genericMotion = { controller.onGenericMotion(it) }
        view.drawOverlay = { renderer, _ -> controller.drawOverlay(renderer) }
        controller.frontInk = com.xnotes.canvas.FrontInk(state, view, pad)
        view.debugOverlay.frontHud = { controller.frontInk?.hud }
        view.afterLayout = { refreshView() }
        view.onScrollbarScrolled = { refreshView() }
        // The canvas starts at built-in defaults; push any non-default global View settings
        // (mode/rotation/scroll direction/scrollbar) into it before the first document lands.
        onViewSettingsChanged(com.xnotes.canvas.ViewSettings(), viewSettings)
        view.onKey = { e -> e.action == android.view.KeyEvent.ACTION_DOWN && handleKeyDown(e) }
        controller.clipboardHasImage = { clipboardImageUri() != null }
        controller.onLinkTap = onLinkTap@{ pageIndex, pageLocal ->
            val src = pdfSource ?: return@onLinkTap false
            val page = state.document.pages.getOrNull(pageIndex) ?: return@onLinkTap false
            val pdfIdx = page.pdfPage ?: return@onLinkTap false
            if (page.width <= 0.0 || page.height <= 0.0) return@onLinkTap false
            val fx = (pageLocal.x / page.width).toFloat()
            val fy = (pageLocal.y / page.height).toFloat()
            if (src.hasLinks(pdfIdx)) {
                val link = src.linkAt(pdfIdx, fx, fy) ?: return@onLinkTap false
                followLink(link)
                true
            } else {
                // Not parsed yet: parse off the main thread, then open on the main thread. The tap
                // is not blocked or consumed; on the first tap of a page the link opens a moment later.
                src.requestLinks(pdfIdx) {
                    view.post { if (pdfSource === src) src.linkAt(pdfIdx, fx, fy)?.let { followLink(it) } }
                }
                false
            }
        }
        maybeAutoEnableFingerDraw()
        applySettings()
        rebuildPdfSource()
    }

    // --- selection menu / clipboard ---

    override val hasClipboardItems: Boolean get() = controller.hasClipboardItems()
    override val clipboardHasImage: Boolean get() = clipboardImageUri() != null

    override fun copySelection() = controller.copySelection()
    override fun cutSelection() = controller.cutSelection()
    override fun duplicateSelection() = controller.duplicateSelection()
    override fun lockSelection() = controller.lockSelection()
    override fun unlockItem(item: com.xnotes.core.model.CanvasItem) = controller.unlockItem(item)
    override fun dismissSelectionMenu() { selectionMenu = null }
    override fun dismissContextMenu() { contextMenu = null }
    fun dismissScreenshot() = controller.clearScreenshot()

    /** Render the screenshot tool's capture rectangle to a PNG and put it on the system clipboard. */
    fun copyScreenshotAsImage() {
        val rect = controller.screenshotRect ?: return
        val bmp = renderRegionBitmap(rect)
        val ok = bmp != null && putBitmapOnClipboard(bmp, "xnotes capture")
        controller.clearScreenshot()
        controller.switchBackAfterScreenshot() // return to the previous pen, like the eraser
        message = if (ok) "Image copied. Paste it anywhere." else "Couldn’t copy the image."
    }

    /** Render a content-space rectangle (whatever it overlaps: pages, backgrounds, ink, the gap)
     *  into a bitmap, the screenshot tool's "what I see" capture. */
    private fun renderRegionBitmap(content: com.xnotes.core.geometry.Rect): android.graphics.Bitmap? {
        if (content.w <= 0.0 || content.h <= 0.0) return null
        // 2x content for crispness, but cap the longest side so a big capture can't blow up memory.
        val maxDim = 4096.0
        val res = minOf(2.0, maxDim / content.w, maxDim / content.h).coerceAtLeast(0.05)
        val w = kotlin.math.ceil(content.w * res).toInt().coerceIn(1, maxDim.toInt())
        val h = kotlin.math.ceil(content.h * res).toInt().coerceIn(1, maxDim.toInt())
        val surface = com.xnotes.platform.AndroidRasterSurface.create(w, h)
        surface.fill(state.palette.bg) // the canvas/gap colour shows through any off-page area
        val r = surface.renderer()
        r.scale(res, res)
        r.translate(-content.left, -content.top) // content (left, top) -> output (0, 0)
        for (i in state.document.pages.indices) {
            val pr = state.pageRects.getOrNull(i) ?: continue
            if (!pr.intersects(content)) continue
            val page = state.document.pages[i]
            r.withSave {
                r.clipRect(pr)
                r.fillRect(pr, state.paperColor(page))
                val cover = state.footprint(page)
                r.translate(pr.left - cover.left, pr.top - cover.top) // into page space (past the margins)
                val local = com.xnotes.core.geometry.Rect.ltrb(
                    (content.left - pr.left).coerceAtLeast(0.0) + cover.left,
                    (content.top - pr.top).coerceAtLeast(0.0) + cover.top,
                    (content.right - pr.left).coerceAtMost(cover.w) + cover.left,
                    (content.bottom - pr.top).coerceAtMost(cover.h) + cover.top,
                )
                if (local.w > 0.0 && local.h > 0.0) {
                    state.paintPageBackground?.invoke(page, r, res, local)
                    state.paintFlow?.invoke(page, r, local)
                }
                for (item in itemsSnapshot(page)) item.paint(r)
            }
        }
        return surface.bitmap
    }

    /** Write [bmp] to the FileProvider-exposed cache and set it as the primary clip (an image uri). */
    private fun putBitmapOnClipboard(bmp: android.graphics.Bitmap, label: String): Boolean = runCatching {
        val dir = java.io.File(appContext.cacheDir, "clipboard").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = java.io.File(dir, "capture.png")
        java.io.FileOutputStream(file).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val uri = androidx.core.content.FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        val cm = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newUri(appContext.contentResolver, label, uri))
        true
    }.getOrDefault(false)

    override fun pasteItemsAt(content: com.xnotes.core.geometry.Pt) {
        controller.pasteItemsAt(content)
    }

    override fun pasteClipboardImageAt(content: com.xnotes.core.geometry.Pt) {
        val uri = clipboardImageUri() ?: run {
            clipboardSvgBytes()?.let { insertImageAt(it, content) }
                ?: run { message = "The clipboard has no image to paste." }
            return
        }
        runCatching { appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            .getOrNull()
            ?.let { insertImageAt(it, content) }
            ?: run { message = "The clipboard has no image to paste." }
    }

    /** SVG markup sitting on the clipboard as plain text (copied source), as insertable bytes. */
    private fun clipboardSvgBytes(): ByteArray? = com.xnotes.platform.SystemClipboard.svgBytes(appContext)

    private fun clipboardImageUri(): android.net.Uri? =
        com.xnotes.platform.SystemClipboard.imageUri(appContext)

    private fun rebuildPdfSource() {
        pdfSource?.close()
        pdfSource = state.document.pdfFile?.let { com.xnotes.platform.PdfSource.create(appContext, it) }
        pdfSource?.onImagesReady = { index ->
            view.post {
                // The sweep found page [index]'s locations: snap the canvas background (refreshBackground
                // self-skips off-screen pages) and drop the now-stale inverted side-panel thumbnail so the
                // panel re-renders it un-inverted (pdfThumbTick is bumped only when a cached thumb existed).
                state.document.pages.forEach { if (it.pdfPage == index) state.refreshBackground(it) }
                if (evictPageThumbnails(index)) pdfThumbTick++
                view.requestRender()
            }
        }
        pdfSource?.onImagesProgress = { done, total ->
            view.post {
                if (pdfSource != null && pdfKeepsImageColors()) {
                    refiningTotal = total
                    refiningDone = done
                    isRefiningPdf = done < total
                }
            }
        }
        installPageBackground()
        installFlowPainter()
        installHighlighter()
        republishFlow(invalidate = false)
        state.invalidateAllCaches()
        refreshToc()
        startPdfRefine() // kick the up-front sweep over all pages + drive the "Refining k/N" hint
    }

    /** Re-extract the open PDF's outline off-thread (a one-shot PdfBox read, see [com.xnotes.platform.PdfOutline]),
     *  publishing it to the Contents tab when it lands. Clears the TOC immediately so a non-PDF note or a
     *  document swap never shows the previous note's outline; a stale parse that finishes after another
     *  swap is dropped by the file-identity guard. */
    private fun refreshToc() {
        toc = emptyList()
        tocVersion++
        val file = state.document.pdfFile ?: return
        autosaveScope.launch {
            val entries = withContext(Dispatchers.IO) { com.xnotes.platform.PdfOutline.extract(appContext, file) }
            if (state.document.pdfFile === file) {
                toc = entries
                tocVersion++
            }
        }
    }

    /** Navigate to the page an outline [entry] points at: the note page whose [Page.pdfPage] is the
     *  entry's source PDF page, falling back to the nearest preceding imported page so an entry into a
     *  trimmed import still lands somewhere. No-op when the entry has no resolvable page. */
    fun goToTocEntry(entry: com.xnotes.platform.PdfOutlineEntry) {
        val dest = entry.destPage
        if (dest < 0) return
        val pages = state.document.pages
        val exact = pages.indexOfFirst { it.pdfPage == dest }
        val target = if (exact >= 0) exact else pages.indexOfLast { (it.pdfPage ?: -1) in 0..dest }
        if (target >= 0) goToPage(target)
    }

    /** Records [doc] as the open note for source-PDF temp-file lifetime: deletes the previously open
     *  note's temp PDF (unless [doc] reuses the same file) and tracks [doc]'s. Call on every document
     *  swap so a closed note's (possibly huge) cached PDF doesn't linger on disk. */
    private fun adoptOpenPdf(doc: Document) {
        val keep = doc.pdfFile
        openPdfTemp?.let { if (it != keep) it.delete() }
        openPdfTemp = keep
    }

    /**
     * Installs the page-background painter: the source-PDF raster (when the page links one) plus the
     * page-style ruling (lines/dots/grid) on top. Always non-null — so plain notes get rulings too —
     * and reads [pdfSource] live, so a single install survives document swaps; [CanvasState.hasPageBackground]
     * gates which pages actually allocate a background surface (a plain colour page allocates none).
     */
    private fun installPageBackground() {
        state.paintPageBackground = { page, renderer, res, region ->
            val src = pdfSource
            val pi = page.pdfPage
            val content = com.xnotes.core.geometry.Rect(0.0, 0.0, page.width, page.height)
            if (src != null && pi != null) {
                // The raster covers the page's content box only; a margin is paper beside it.
                val slice = clampToContent(region, page)
                if (slice != null) {
                    val fullW = (page.width * res).toInt()
                    val fullH = (page.height * res).toInt()
                    val rx = (slice.left * res).toInt()
                    val ry = (slice.top * res).toInt()
                    val rw = kotlin.math.ceil(slice.w * res).toInt()
                    val rh = kotlin.math.ceil(slice.h * res).toInt()
                    src.renderRegion(pi, fullW, fullH, rx, ry, rw, rh, pdfPageFilter())?.let { bg ->
                        renderer.drawRaster(bg, slice)
                        bg.recycle()
                    }
                }
            }
            // A ruling covers a blank note page whole; on an imported PDF page it rules the
            // margins only, so the page itself is never drawn over.
            val pattern = state.effectivePattern(page)
            if (pattern != PagePattern.NONE) {
                val color = state.effectivePatternColor(page)
                val spacing = state.effectiveSpacing(page)
                val cover = state.footprint(page)
                if (pi == null) {
                    paintPagePattern(renderer, pattern, color, spacing, cover, region)
                } else {
                    paintMarginPattern(renderer, pattern, color, spacing, cover, content, region)
                }
            }
        }
    }

    /** [region] cropped to [page]'s content box (its stored size), or null when it misses it. */
    private fun clampToContent(region: com.xnotes.core.geometry.Rect, page: Page): com.xnotes.core.geometry.Rect? {
        val l = region.left.coerceAtLeast(0.0)
        val t = region.top.coerceAtLeast(0.0)
        val r = region.right.coerceAtMost(page.width)
        val b = region.bottom.coerceAtMost(page.height)
        return if (r > l && b > t) com.xnotes.core.geometry.Rect(l, t, r - l, b - t) else null
    }

    /**
     * Installs the flow-text painter: it draws the PUBLISHED layout snapshot for the page
     * (never the live model — cache threads call this), under the ink and over the page
     * background via [CanvasState.paintFlow]. A page unknown to the snapshot paints nothing.
     */
    private fun installFlowPainter() {
        state.paintFlow = { page, renderer, region ->
            val pf = publishedFlow
            val idx = pf?.indexOf?.get(page)
            if (pf != null && idx != null) FlowPainter.paintPage(renderer, pf.frame, idx, region)
        }
    }

    /** Feed the layout derived highlight colours (published on the main thread only). */
    private fun installHighlighter() {
        flowLayout.codeSpans = spans@{ para ->
            val (rev, spans) = highlightCache[para] ?: return@spans null
            if (rev != para.rev) return@spans null
            val theme = activeCodeTheme()
            spans.mapNotNull { s ->
                theme.colorFor(s.capture)?.let { com.xnotes.core.text.CodeSpan(s.start, s.end, it) }
            }
        }
        flowLayout.codeBackground = { activeCodeTheme().background }
        flowLayout.autoColor = { defaultTextColor() }
    }

    /**
     * Debounced async highlighting: snapshot the dirty contiguous same-language code
     * blocks on the main thread, parse each block as ONE text off-thread (so
     * multi-line strings/comments highlight right), then publish spans per paragraph
     * only when its revision still matches, and republish the layout.
     */
    private fun scheduleHighlight() {
        val hl = highlighter ?: return
        highlightJob?.cancel()
        highlightJob = autosaveScope.launch {
            kotlinx.coroutines.delay(150)
            val flow = state.document.flow
            highlightCache.keys.retainAll(flow.paragraphs.toHashSet())
            class Block(val paras: List<Paragraph>, val revs: IntArray, val text: String, val lang: String)
            val blocks = mutableListOf<Block>()
            var i = 0
            while (i < flow.paragraphs.size) {
                val lang = flow.paragraphs[i].codeLang
                if (lang.isNullOrEmpty() || !hl.supports(lang)) {
                    i++
                    continue
                }
                val start = i
                while (i < flow.paragraphs.size && flow.paragraphs[i].codeLang == lang) i++
                val paras = flow.paragraphs.subList(start, i).toList()
                if (paras.any { highlightCache[it]?.first != it.rev }) {
                    val revs = IntArray(paras.size) { paras[it].rev }
                    blocks.add(Block(paras, revs, paras.joinToString("\n") { it.plainText() }, lang))
                }
            }
            if (blocks.isEmpty()) return@launch
            val results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                blocks.map { it to hl.highlight(it.text, it.lang) }
            }
            var changed = false
            for ((block, spans) in results) {
                if (spans == null) continue
                if (block.paras.withIndex().any { (k, p) -> p.rev != block.revs[k] }) continue
                val sorted = spans.sortedBy { it.start }
                var offset = 0
                for (p in block.paras) {
                    val len = p.length
                    val mine = sorted.mapNotNull { s ->
                        val a = (s.start - offset).coerceAtLeast(0)
                        val b = (s.end - offset).coerceAtMost(len)
                        if (b > a) com.xnotes.core.text.HighlightSpan(a, b, s.capture) else null
                    }
                    highlightCache[p] = p.rev to mine
                    offset += len + 1
                    changed = true
                }
            }
            if (changed) {
                republishFlow(invalidate = !flowText.active)
                onRender()
            }
        }
    }

    /** A cheap content fingerprint of the flow (structure + every paragraph revision). */
    private fun flowStamp(): Long {
        val flow = state.document.flow
        var s = flow.rev.toLong()
        for (p in flow.paragraphs) s = s * 31 + p.rev
        return s
    }

    /**
     * Recompute and publish the flow layout for the current document. With [invalidate]
     * the pages the flow covered before or after the change drop their ink caches, so
     * the baked layer follows the text; the undo path instead repaints in place.
     */
    private fun republishFlow(invalidate: Boolean) {
        val doc = state.document
        val oldFlow = publishedFlow
        val oldPages = publishedPageList
        val frame = flowLayout.layout(doc.flow, doc.pages.map { PageBox(it.width, it.height) }, doc.dpi)
        val index = HashMap<Page, Int>(doc.pages.size * 2)
        doc.pages.forEachIndexed { i, p -> index[p] = i }
        publishedFlow = PublishedFlow(frame, index)
        publishedFlowStamp = flowStamp()
        publishedPageList = doc.pages.toList()
        scheduleHighlight()
        if (invalidate) {
            val stale = HashSet<Page>()
            oldFlow?.frame?.pagesWithLines()?.forEach { i -> oldPages.getOrNull(i)?.let(stale::add) }
            frame.pagesWithLines().forEach { i -> publishedPageList.getOrNull(i)?.let(stale::add) }
            stale.forEach { if (index.containsKey(it)) state.invalidatePage(it) }
        }
    }

    /** Republish the flow when its content or the page list moved (cheap no-op otherwise). */
    private fun republishFlowIfStale() {
        if (publishedFlowStamp == flowStamp() && publishedPageList == state.document.pages) return
        republishFlow(invalidate = true)
    }

    /** Act on a tapped PDF link: open a web/mail URL externally, or jump to an internal destination
     *  page (mapped from the source-PDF page index to whichever document page carries it). */
    private fun followLink(link: com.xnotes.platform.PdfLink) {
        val url = link.url
        if (url != null) {
            openUrl(url)
            return
        }
        val dest = link.destPage ?: return
        val docPage = state.document.pages.indexOfFirst { it.pdfPage == dest }
        if (docPage >= 0) goToPage(docPage)
    }

    /** Open an external web/mail URL in the system handler. Restricted to safe schemes; never throws. */
    private fun openUrl(url: String) {
        val u = url.trim()
        val ok = u.startsWith("http://", ignoreCase = true) ||
            u.startsWith("https://", ignoreCase = true) ||
            u.startsWith("mailto:", ignoreCase = true)
        if (!ok) return
        runCatching {
            appContext.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Kick off (once per source) the up-front background sweep that parses every PDF page's embedded-
     * image boxes, currently-visible pages first, and prime the "Refining PDF colours k/N pages…"
     * hint. A no-op that clears the hint unless dark mode + keep-image-colours are both on, since the
     * boxes are only needed to un-invert images. Safe to call repeatedly (on open and whenever the
     * preference toggles): [PdfSource.prepAllImages] enqueues the sweep only the first time.
     */
    private fun startPdfRefine() {
        val src = pdfSource
        if (src == null || !pdfKeepsImageColors()) {
            isRefiningPdf = false
            refiningDone = 0
            refiningTotal = 0
            return
        }
        refiningTotal = src.pageCount
        refiningDone = src.parsedPageCount()
        isRefiningPdf = refiningDone < refiningTotal
        if (isRefiningPdf) src.prepAllImages()
    }

    fun insertImage(bytes: ByteArray) = insertImageAt(bytes, null)

    /** Insert an image, centred on [atContent] (or on the current page when null). */
    fun insertImageAt(bytes: ByteArray, atContent: com.xnotes.core.geometry.Pt?) {
        val file = runCatching {
            java.io.File.createTempFile("img", null, imageDir).apply { writeBytes(bytes) }
        }.getOrNull()
        val size = file?.let { imageCodec.probeFile(it.path) }
        if (file == null || size == null || size.width <= 0 || size.height <= 0) {
            file?.delete()
            message = "Could not read the image."
            return
        }
        val index = (atContent?.let { state.pageIndexAtContent(it) } ?: state.currentPageIndex())
            .coerceIn(0, state.document.pages.lastIndex)
        val page = state.document.pages[index]
        val pr = state.pageRects.getOrNull(index)
        val maxW = page.width * 0.6
        val maxH = page.height * 0.6
        val scale = minOf(1.0, maxW / size.width, maxH / size.height)
        val w = size.width * scale
        val h = size.height * scale
        // Placed anywhere on the paper, margins included, but never hanging off it.
        val cover = state.footprint(page)
        val rect = if (atContent != null && pr != null) {
            Rect(
                (atContent.x - pr.left - w / 2 + cover.left).coerceIn(cover.left, cover.right - w),
                (atContent.y - pr.top - h / 2 + cover.top).coerceIn(cover.top, cover.bottom - h),
                w, h,
            )
        } else {
            Rect((page.width - w) / 2.0, (page.height - h) / 2.0, w, h)
        }
        val item = ImageItem(ImageData(file, size.width, size.height), rect)
        page.items.add(item)
        state.appendToCache(page, item)
        history.push(AddItem(page, item))
        state.document.dirty = true
        refreshContent()
        view.requestRender()
    }

    /** Save an encoded image into the on-disk sticker library (validated by a probe decode). */
    fun addSticker(bytes: ByteArray) {
        val file = runCatching {
            java.io.File.createTempFile("stamp-${System.currentTimeMillis()}-", null, stickerDir)
                .apply { writeBytes(bytes) }
        }.getOrNull()
        val size = file?.let { imageCodec.probeFile(it.path) }
        if (file == null || size == null || size.width <= 0 || size.height <= 0) {
            file?.delete()
            message = "Could not read the image."
            return
        }
        refreshStickers()
    }

    fun removeSticker(file: java.io.File) {
        file.delete()
        refreshStickers()
    }

    /** Insert a sticker as a fresh copy, so the note never references the library file itself. */
    fun insertSticker(file: java.io.File) {
        val bytes = runCatching { file.takeIf { it.isFile }?.readBytes() }.getOrNull()
        if (bytes == null) {
            message = "Could not read the sticker."
            refreshStickers()
            return
        }
        insertImage(bytes)
    }

    fun pasteImage() {
        val clipboard = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val uri = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        if (uri == null) {
            clipboardSvgBytes()?.let { insertImage(it) }
                ?: run { message = "The clipboard has no image to paste." }
            return
        }
        runCatching { appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            .getOrNull()
            ?.let { insertImage(it) }
            ?: run { message = "The clipboard has no image to paste." }
    }

    /**
     * A [FlowLayout] carrying the active code theme, for transient layout passes (PDF
     * export, explorer tiles). The async [highlightCache] only feeds the live published
     * layout, so [flow]'s code blocks are parsed synchronously here — cheap (blocks are
     * small) and safe off-thread (the highlighter is stateless per call).
     */
    private fun themedFlowLayout(flow: com.xnotes.core.text.TextFlow): FlowLayout {
        val layout = FlowLayout(textMeasurer)
        val theme = activeCodeTheme()
        layout.codeBackground = { theme.background }
        layout.autoColor = { defaultTextColor() }
        val hl = highlighter ?: return layout
        val spans = HashMap<Paragraph, List<com.xnotes.core.text.CodeSpan>>()
        var i = 0
        while (i < flow.paragraphs.size) {
            val lang = flow.paragraphs[i].codeLang
            if (lang.isNullOrEmpty() || !hl.supports(lang)) {
                i++
                continue
            }
            val start = i
            while (i < flow.paragraphs.size && flow.paragraphs[i].codeLang == lang) i++
            val paras = flow.paragraphs.subList(start, i)
            // Contiguous same-language paragraphs parse as ONE text, like [scheduleHighlight].
            val sorted = hl.highlight(paras.joinToString("\n") { it.plainText() }, lang)
                ?.sortedBy { it.start } ?: continue
            var offset = 0
            for (p in paras) {
                val len = p.length
                spans[p] = sorted.mapNotNull { s ->
                    val a = (s.start - offset).coerceAtLeast(0)
                    val b = (s.end - offset).coerceAtMost(len)
                    if (b > a) theme.colorFor(s.capture)?.let { com.xnotes.core.text.CodeSpan(a, b, it) } else null
                }
                offset += len + 1
            }
        }
        layout.codeSpans = { spans[it] }
        return layout
    }

    /**
     * Flow painters for a PDF export of [doc]: a private layout pass over its own pages
     * (exports run off-thread and may target transient/subset documents, so the published
     * on-screen snapshot is never reused). Pages foreign to [doc] paint nothing.
     */
    private fun flowExportHooks(doc: Document): com.xnotes.platform.PdfExporter.FlowExport {
        if (doc.flow.isEmpty) return com.xnotes.platform.PdfExporter.FlowExport.NONE
        val frame = themedFlowLayout(doc.flow)
            .layout(doc.flow, doc.pages.map { PageBox(it.width, it.height) }, doc.dpi)
        val index = HashMap<Page, Int>(doc.pages.size * 2)
        doc.pages.forEachIndexed { i, p -> index[p] = i }
        return com.xnotes.platform.PdfExporter.FlowExport(
            paint = { page, r, region -> index[page]?.let { FlowPainter.paintPage(r, frame, it, region) } },
            bounds = { page -> index[page]?.let { frame.pageFlowBounds(it) } },
        )
    }

    /**
     * Flatten the open note to a PDF written to [out], reporting per-page progress and
     * polling [isCancelled] so a long export can show a dialog and be aborted. The caller
     * runs this off the main thread; it throws on failure (no message side-effects) so the
     * caller can tell success / failure / cancel apart.
     */
    fun exportPdf(
        out: OutputStream,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        // Render against a private PdfSource built from the document's own bytes, not the
        // live [pdfSource]: export now runs off the main thread, and Android's PdfRenderer
        // is single-threaded — sharing it with the canvas's background cache builder would
        // crash. A plain note has null bytes and renders identically.
        val src = state.document.pdfFile?.let { com.xnotes.platform.PdfSource.create(appContext, it) }
        try {
            com.xnotes.platform.PdfExporter.export(
                appContext, state.document, src, out,
                { exportPaper(state.document, it) },
                { page, r -> paintExportRuling(state.document, page, r) },
                onProgress, isCancelled,
                flow = flowExportHooks(state.document),
            )
        } finally {
            src?.close()
        }
    }

    val preferences: Preferences get() = settings.prefs

    /**
     * First-run only: a device with no stylus/pen cannot draw at all under the default
     * finger-pans behaviour, so turn finger-draw on automatically. Runs once per install
     * (guarded by [Settings.fingerDrawAutoChecked]); only ever flips the default off→on,
     * never on a device that has a pen — so a stylus tablet keeps finger-pans and any
     * later choice in the Preferences dialog is preserved.
     */
    private fun maybeAutoEnableFingerDraw() {
        if (settings.fingerDrawAutoChecked) return
        var prefs = settings.prefs
        if (!prefs.fingerDraws && !com.xnotes.platform.DeviceCapabilities.hasStylus(appContext)) {
            prefs = prefs.copy(fingerDraws = true)
        }
        settings = settings.copy(prefs = prefs, fingerDrawAutoChecked = true)
        settingsRepo.save(settings)
    }

    private fun applySettings() {
        toolbarColors = settings.toolbarColors
        toolbarColorCount = settings.toolbarColorCount
        toolbarLayout = settings.toolbarLayout
        canvasToolbarLayout = settings.canvasToolbarLayout
        activeColorIndex = settings.activeColor.coerceIn(0, toolbarColorCount - 1)
        // Render always at 1x (the DPI/supersampling control was removed).
        renderScale = 1.0
        state.renderScale = 1.0
        sidebarVisible = settings.sidebarVisible
        shapeConfig = settings.shapeConfig
        controller.shapeConfig = settings.shapeConfig
        for (t in ToolDefaults.persistedTools) controller.setToolConfig(t, settings.configFor(t))
        controller.inkColor = toolbarColors[activeColorIndex]
        selectTool(settings.lastTool)
        pushToolsToCanvas()
        applyPagePrefsToState(settings.prefs)
    }

    /** The appearance mode to render: "system" follows the OS dark/light state. */
    private fun resolvedAppearance(p: Preferences): String =
        if (p.uiAppearance == "system") (if (systemInDarkMode) "dark" else "light") else p.uiAppearance

    /** The chrome palette for [p]: Material You (system scheme, accent-seeded below Android 12)
     *  when the active appearance mode picked the material style, else the classic accent chrome. */
    private fun buildPalette(p: Preferences): Palette {
        val appearance = resolvedAppearance(p)
        val dark = appearance != "light"
        if (p.paletteStyle == "material") {
            val m = p.materialSeed?.let { MaterialColors.seeded(it, dark = dark) }
                ?: dynamicMaterialColors(appContext, dark = dark)
                ?: MaterialColors.seeded(p.accentColor, dark = dark)
            return Palette.material(appearance, m)
        }
        return Palette.forAppearance(appearance, p.accentColor)
    }

    /** The OS dark/light state flipped (uiMode arrives via onConfigurationChanged, no activity
     *  recreation): under the "system" appearance, rebuild the chrome and themed content live. */
    fun onSystemDarkModeChanged(dark: Boolean) {
        secondary?.onSystemDarkModeChanged(dark) // the other pane rethemes with this one
        if (systemInDarkMode == dark) return
        systemInDarkMode = dark
        if (settings.prefs.uiAppearance != "system") return
        applyPagePrefsToState(settings.prefs)
        republishFlow(invalidate = true)
        state.invalidateAllCaches()
        refreshView()
        prefsVersion++
        view.requestRender()
    }

    private fun applyPagePrefsToState(p: Preferences) {
        palette = buildPalette(p)
        state.palette = palette
        infiniteOrNull?.applyPalette(palette)
        infiniteOrNull?.applyInputPrefs(
            p.fingerDraws,
            if (p.penButtonTool == "none") null else (Tool.fromId(p.penButtonTool) ?: Tool.ERASER),
            p.zoomLockPan,
        )
        infiniteOrNull?.applyZoomRange(p.canvasMinZoomPercent, p.canvasMaxZoomPercent)
        state.pageColorOverride = if (p.defaultTemplate == "color") p.pageColor else null
        controller.fingerDraws = p.fingerDraws
        controller.zoomLockPan = p.zoomLockPan
        controller.detectShapes = p.detectShapes
        controller.penButtonTool = if (p.penButtonTool == "none") null else (Tool.fromId(p.penButtonTool) ?: Tool.ERASER)
        controller.penButtonHover = p.penButtonHover
        state.sideMargin = p.sideMargin
        state.pageBorders = !p.hidePageBorders
        state.maxCachePx = p.maxCacheResolution.toDouble()
        state.minZoom = if (p.minZoomEnabled) p.minZoomPercent / 100.0 else CanvasState.MIN_ZOOM
        state.maxZoom = (if (p.maxZoomEnabled) p.maxZoomPercent / 100.0 else CanvasState.MAX_ZOOM)
            .coerceAtLeast(state.minZoom)
        state.clampZoomToLimits()
        state.relayout()
    }

    /** Set fullscreen and persist it as an explicit choice (used by the toolbar, F11, and the
     *  Preferences toggle); lightweight, no canvas refresh. */
    fun setFullscreenPref(v: Boolean) {
        fullscreen = v
        settings = settings.copy(prefs = settings.prefs.copy(startFullscreen = v))
        settingsRepo.save(settings)
    }

    fun toggleFullscreen() = setFullscreenPref(!fullscreen)

    /** Apply edited preferences live and persist (used by the Preferences dialog). */
    fun applyPreferences(p: Preferences) {
        val marginChanged = p.sideMargin != settings.prefs.sideMargin
        settings = settings.copy(prefs = p)
        fullscreen = p.startFullscreen ?: !deviceHasDisplayCutout // keep in sync (e.g. Reset to defaults)
        applyPagePrefsToState(p)
        republishFlow(invalidate = true) // re-bake flow colours (default text, code) for the new appearance
        state.invalidateAllCaches()
        if (marginChanged) {
            state.fitWidth() // re-fit so the new side margin takes effect immediately
        }
        refreshView() // margin re-fits and zoom-limit clamps must surface in the toolbar readouts
        settingsRepo.save(settings)
        prefsVersion++
        view.requestRender()
    }

    /** Apply an edited toolbar layout live and persist (used by the toolbar customiser). */
    fun applyToolbarLayout(layout: ToolbarLayout) {
        toolbarLayout = layout
        settings = settings.copy(toolbarLayout = layout)
        settingsRepo.save(settings)
    }

    /** The same, for the infinite canvas's bar. */
    fun applyCanvasToolbarLayout(layout: ToolbarLayout) {
        canvasToolbarLayout = layout
        infiniteOrNull?.toolbarLayout = layout
        settings = settings.copy(canvasToolbarLayout = layout)
        settingsRepo.save(settings)
    }

    /** Set how many colour swatches the toolbar shows (1-7) and persist. */
    fun applyToolbarColorCount(count: Int) {
        val c = count.coerceIn(1, 7)
        toolbarColorCount = c
        infiniteOrNull?.toolbarColorCount = c
        if (activeColorIndex >= c) pickColor(c - 1)
        settings = settings.copy(toolbarColorCount = c)
        settingsRepo.save(settings)
    }

    /**
     * Hand the canvas the same persisted pen styles, shape style and ink the paged editor uses, so
     * a pen tuned on one surface is that pen on the other rather than a second one that happens to
     * look similar.
     */
    private fun pushToolsToCanvas() {
        val canvas = infiniteOrNull ?: return
        for (t in ToolDefaults.persistedTools) canvas.setToolConfig(t, settings.configFor(t))
        canvas.armShapeConfig(settings.shapeConfig)
        canvas.toolbarColors = toolbarColors
        canvas.recentColors = recentColors
        canvas.toolbarColorCount = toolbarColorCount
        canvas.toolbarLayout = canvasToolbarLayout
        canvas.pickColor(activeColorIndex)
    }

    /** Snapshot live state into settings and save (call on pause/stop). */
    fun persist() {
        // A style tuned on the canvas is the same style, so whichever surface was last used wins.
        val fromCanvas = infiniteOrNull
        if (fromCanvas != null && canvasOpen) {
            for (t in ToolDefaults.persistedTools) controller.setToolConfig(t, fromCanvas.toolConfig(t))
            controller.shapeConfig = fromCanvas.shapeConfig
            shapeConfig = fromCanvas.shapeConfig
            activeColorIndex = fromCanvas.activeColorIndex
        }
        // Both panes of a split hold their own live tool state, so only the primary snapshots it
        // back into settings; otherwise the second pane's pens would overwrite the first pane's.
        if (pane == Pane.PRIMARY) {
            val tools = ToolDefaults.persistedTools.associateWith { controller.configFor(it) }
            settings = settings.copy(
                tools = tools,
                shapeConfig = controller.shapeConfig,
                toolbarColors = toolbarColors,
                toolbarColorCount = toolbarColorCount,
                activeColor = activeColorIndex,
                lastTool = tool,
                sidebarVisible = sidebarVisible,
                renderScale = renderScale,
            )
        }
        if (noteOpen) saveViewState() // remember this folder note's view for next time
        settingsRepo.save(settings)
        // The note + session writes are heavy for a big note; run them off the main thread so pressing
        // Home never freezes the UI. The process survives to finish them; the debounced autosave during
        // editing bounds any loss on an immediate hard kill.
        flushThen(showOverlay = false) {}
        flushCanvasAutosave()
        saveSession()
        saveCanvasSession()
        secondary?.persist() // the other pane's note has to be flushed on pause too
    }

    /** Persist the open canvas, or clear the stored one when a canvas is not what is open. */
    private fun saveCanvasSession() {
        val canvas = infiniteOrNull
        if (!canvasOpen || canvas == null) {
            canvasSession.clear()
            return
        }
        canvasSession.save(canvas.document, writeDocument = true)
    }

    /** Persist the working session (open document + zoom/scroll) so the next launch
     *  reopens this note where the user left off, unsaved edits included. */
    private fun saveSession() {
        if (!sessionLoaded) return // don't overwrite the saved note before restore has applied
        if (!noteOpen || canvasOpen) { session.clear(); return } // nothing paged on top: wipe any stale session
        val contentChanged = contentVersion != lastSessionContentVersion
        lastSessionContentVersion = contentVersion
        // Snapshot on the main thread, then write the session off-thread, so saving a big note's
        // session never freezes the UI. Unconditionally, even for a view-state-only refresh: the
        // store re-encodes whatever it is handed when the session file is missing, and handing it
        // the live document would put the writer back on the mutating model.
        val snapshot = state.document.snapshot()
        val zoom = state.zoom
        val sx = state.scrollX
        val sy = state.scrollY
        val locked = zoomLocked
        val vo = viewOverrides
        autosaveScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                session.save(snapshot, zoom, sx, sy, locked, vo, writeDocument = contentChanged)
            }
        }
    }

    /** Reopen the last session (document + view state). The heavy load runs off the
     *  main thread; the apply runs on the caller's (main) dispatcher. A no-op when
     *  there is no saved session. Drives the launch loader, so it's safe to await. */
    suspend fun restoreSession() {
        // A canvas was open last time: it takes precedence, since only one document is ever on top.
        val canvasDoc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (canvasSession.exists()) canvasSession.load() else null
        }
        if (canvasDoc != null) {
            openCanvasDocument(canvasDoc, canvasDoc.path, canvasDoc.displayName)
            sessionLoaded = true
            return
        }
        val snap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { session.load() }
        if (snap != null) {
            state.document = snap.document
            rebuildPdfSource()
            adoptOpenPdf(snap.document) // track the restored note's temp PDF for later cleanup
            history.clear()
            controller.resetGestureState()
            state.invalidateAllCaches()
            // Prefer this note's own remembered view (folder notes); fall back to the session's
            // saved view for a non-folder/unsaved note; otherwise fit width.
            val saved = viewKey(snap.document.path)?.let { viewStates.get(it) }
            installViewOverrides(saved?.overrides ?: snap.viewOverrides)
            state.pendingInitialView = when {
                saved != null -> InitialView.Restore(saved.zoom, saved.scrollX, saved.scrollY)
                snap.zoom > 0.0 -> InitialView.Restore(snap.zoom, snap.scrollX, snap.scrollY)
                else -> InitialView.FitWidth
            }
            state.didInitialFit = false
            zoomLocked = snap.zoomLocked
            state.zoomLocked = snap.zoomLocked
            state.relayout()
            if (state.viewportW > 0) state.establishInitialView()
            maybeBindAutosave(state.document.path) // resume autosave if the restored note is in the folder
            refreshContent()
            view.requestRender()
        }
        sessionLoaded = true
    }

    private fun refreshView() {
        zoomPercent = (state.zoom * 100).roundToInt()
        pageIndex = state.currentPageIndex()
        warmVisibleLinks(pageIndex)
        if (controller.editingItem != null) editingField = controller.editingField()
        refreshTextBar()
    }

    /** Proactively parse the current page's PDF links off-thread (coalesce + cancel-stale) so a tap
     *  lands on a warm cache. Cheap and idempotent; a fast scroll keeps moving the target, so only the
     *  page it settles on is parsed, never a backlog. The on-tap parse stays as a fallback. */
    private fun warmVisibleLinks(pageIndex: Int) {
        val src = pdfSource ?: return
        val pdfIdx = state.document.pages.getOrNull(pageIndex)?.pdfPage ?: return
        src.warmLinks(pdfIdx)
    }

    /** Recompute the floating text style bar's anchor + values (it follows pan/zoom/selection). */
    private fun refreshTextBar() {
        textBar = controller.computeTextBar()
    }

    /** Set the active text box's font family (and the family new boxes are created with). */
    fun setTextFace(face: FontFace) {
        controller.setTextFace(face)
        refreshTextBar()
    }

    /** Set the active text box's point size (and the size new boxes are created with). */
    fun setTextPointSize(size: Double) {
        controller.setTextPointSize(size)
        refreshTextBar()
    }

    fun updateEditingText(text: String) {
        controller.updateEditingText(text)
    }

    fun commitText(text: String? = null) {
        controller.commitTextEdit(text, restoreTool = true)
    }

    /** A one-finger drag over the editor scrolls the page (edit stays open), not the box's own text. */
    fun panWhileEditing(dxFinger: Float, dyFinger: Float) {
        controller.panWhileEditing(dxFinger.toDouble(), dyFinger.toDouble())
    }

    // --- page styles (paper colour + ruling): document-wide ("All Pages") and per-page ---

    /** The document-wide style override; per-page styles layer on top (see [PageStyle]). */
    val documentStyle: PageStyle get() = state.document.style

    /** The current page's own style override (an empty [PageStyle] when there is no page). */
    val currentPageStyle: PageStyle
        get() = state.document.pages.getOrNull(state.currentPageIndex())?.style ?: PageStyle()

    /** The saved All Pages style stamped onto newly created notes (empty ⇒ app built-ins). */
    var newNoteStyle by mutableStateOf(settings.newNoteStyle)
        private set

    /** Save (or, passing an empty style, forget) the All Pages style new notes start with. */
    fun saveNewNoteStyle(style: PageStyle) {
        if (newNoteStyle == style) return
        newNoteStyle = style
        settings = settings.copy(newNoteStyle = style)
        settingsRepo.save(settings)
    }

    /** The saved flow defaults stamped onto newly created notes (empty ⇒ app built-ins). */
    var newNoteFlow by mutableStateOf(settings.newNoteFlow)
        private set

    /** Save (or, passing empty defaults, forget) the flow defaults new notes start with. */
    fun saveNewNoteFlow(defaults: FlowDefaults) {
        if (newNoteFlow == defaults) return
        newNoteFlow = defaults
        settings = settings.copy(newNoteFlow = defaults)
        settingsRepo.save(settings)
    }

    /** A blank document at the user's default page size, custom dimensions included. */
    private fun blankDocument(): Document {
        val (w, h) = settings.prefs.newPagePixels()
        return Document.blankPixels(Document.DEFAULT_NEW_PAGES, w, h)
    }

    /** Stamp the saved new-note defaults (page style + flow config) onto a fresh [doc]. */
    private fun stampNewNoteDefaults(doc: Document): Document {
        doc.style = settings.newNoteStyle
        settings.newNoteFlow.applyTo(doc.flow)
        return doc
    }

    /** Replace the document-wide ("All Pages") style override. */
    fun setDocumentStyle(style: PageStyle) {
        val prev = state.document.style
        if (prev == style) return
        state.document.style = style
        applyStyleChange(prev, style, state.document.pages.toList())
    }

    /** Replace the current page's style override. */
    fun setCurrentPageStyle(style: PageStyle) {
        val page = state.document.pages.getOrNull(state.currentPageIndex()) ?: return
        val prev = page.style
        if (prev == style) return
        page.style = style
        applyStyleChange(prev, style, listOf(page))
    }

    // --- page margins (extra paper on any edge): document-wide ("All Pages") and per-page ---

    /** The document-wide margin override; per-page margins layer on top (see [PageMargins]). */
    val documentMargins: PageMargins get() = state.document.margins

    /** The current page's own margin override (an empty [PageMargins] when there is no page). */
    val currentPageMargins: PageMargins
        get() = state.document.pages.getOrNull(state.currentPageIndex())?.margins ?: PageMargins()

    /** Replace the document-wide ("All Pages") margin override. */
    fun setDocumentMargins(margins: PageMargins) {
        if (state.document.margins == margins) return
        state.document.margins = margins
        applyMarginChange()
    }

    /** Replace the current page's margin override. */
    fun setCurrentPageMargins(margins: PageMargins) {
        val page = state.document.pages.getOrNull(state.currentPageIndex()) ?: return
        if (page.margins == margins) return
        page.margins = margins
        applyMarginChange()
    }

    /**
     * Apply a margin change and persist it (dirty -> autosave) — deliberately **not** onto the undo
     * stack, like a style change. A margin resizes the paper, so every cached surface is now the
     * wrong shape: they are dropped rather than repaired, and the document is laid out again.
     */
    private fun applyMarginChange() {
        state.invalidatePageGeometry()
        state.relayout()
        state.document.dirty = true
        refreshContent()
        view.requestRender()
    }

    /**
     * Apply a style change to the caches and persist it (dirty -> autosave) — deliberately **not**
     * onto the undo stack. The paper colour is filled live each frame, so a colour-only change just
     * repaints; a ruling change ([pages] are the pages it may affect) rebuilds their background caches.
     */
    private fun applyStyleChange(prev: PageStyle, next: PageStyle, pages: List<Page>) {
        val rulingChanged = prev.pattern != next.pattern ||
            prev.patternColor != next.patternColor ||
            prev.spacing != next.spacing
        if (rulingChanged) {
            if (pages.size == 1) state.invalidateBackground(pages[0]) else state.invalidateAllBackgrounds()
        } else {
            state.invalidatePaper()
        }
        state.document.dirty = true
        refreshContent()
        view.requestRender()
    }

    // --- export-time style resolution: resolved against the document being exported (which may be a
    //     closed note loaded by URI, or a page subset), not necessarily the open one ---

    private fun exportPaper(doc: Document, page: Page): Rgba =
        page.resolvedPageColor(doc, state.pageColorOverride) ?: state.palette.paper

    private fun paintExportRuling(doc: Document, page: Page, r: Renderer) {
        val pattern = page.resolvedPattern(doc)
        if (pattern == PagePattern.NONE) return
        val color = page.resolvedPatternColor(doc)
        val spacing = page.resolvedSpacing(doc)
        val content = com.xnotes.core.geometry.Rect(0.0, 0.0, page.width, page.height)
        val cover = exportFootprint(doc, page)
        // Mirrors the canvas: a blank note page is ruled whole, an imported PDF page only in its margins.
        if (page.pdfPage == null) {
            paintPagePattern(r, pattern, color, spacing, cover, cover)
        } else {
            paintMarginPattern(r, pattern, color, spacing, cover, content, cover)
        }
    }

    /** [page]'s whole paper in page space, margins included, resolved against [doc] (export/thumbnails). */
    private fun exportFootprint(doc: Document, page: Page): com.xnotes.core.geometry.Rect {
        val i = page.insets(doc)
        return com.xnotes.core.geometry.Rect(-i.left, -i.top, i.left + page.width + i.right, i.top + page.height + i.bottom)
    }

    private fun refreshContent() {
        republishFlowIfStale()
        canUndo = history.canUndo
        canRedo = history.canRedo
        pageCount = state.document.pages.size
        dirty = state.document.dirty
        title = state.document.title
        contentVersion++
        refreshView()
        if (autosaveUri != null && state.document.dirty) scheduleAutosave()
    }

    // --- side panel ---

    /** A live snapshot of the document's pages, for the side panel (recompose keyed on [contentVersion]). */
    fun pagesSnapshot(): List<Page> = state.document.pages.toList()

    fun pageAt(index: Int): Page? = state.document.pages.getOrNull(index)

    /**
     * A page's display height/width ratio (rotation-aware). The side panel reserves each thumbnail
     * row's height from this so rows don't grow when their bitmap finishes loading — that resizing
     * was what made the scrollbar thumb wobble while scrolling (its size is derived from the
     * visible rows' heights).
     */
    fun pageAspectRatio(page: Page): Float = (state.displayH(page) / state.displayW(page)).toFloat()

    /**
     * Renders a page to a thumbnail bitmap (paper + PDF/template background + items). [active] is
     * polled before the costly steps (PDF background, each item) so a render abandoned mid-flight —
     * the side-panel row scrolled out of view — bails out instead of burning CPU the scroll needs.
     */
    fun renderThumbnail(page: Page, widthPx: Int, active: () -> Boolean = { true }): android.graphics.Bitmap? {
        val cover = state.footprint(page)
        val scale = widthPx / cover.w
        val w = widthPx.coerceAtLeast(1)
        val h = (cover.h * scale).toInt().coerceAtLeast(1)
        val surface = com.xnotes.platform.AndroidRasterSurface.create(w, h)
        surface.fill(state.paperColor(page))
        val r = surface.renderer()
        r.scale(scale, scale)
        r.translate(-cover.left, -cover.top)
        if (!active()) return null
        val src = pdfSource
        val pi = page.pdfPage
        if (src != null && pi != null) {
            // Pure consumer: never parses. Stamps real image colours only if the linear sweep has
            // already found this page's locations, otherwise draws it filtered; the row re-renders
            // with real colours once the sweep reaches the page (see onImagesReady → pdfThumbTick).
            val pw = (page.width * scale).toInt().coerceAtLeast(1)
            val ph = (page.height * scale).toInt().coerceAtLeast(1)
            src.renderPage(pi, pw, ph, pdfPageFilter())?.let { bg ->
                r.drawRaster(bg, com.xnotes.core.geometry.Rect(0.0, 0.0, page.width, page.height))
                bg.recycle()
            }
            // The margins are outside the raster, so their ruling still has to be painted.
            state.paintPageBackground?.invoke(page, r, scale, cover)
        } else {
            state.paintPageBackground?.invoke(page, r, scale, cover)
        }
        state.paintFlow?.invoke(page, r, cover)
        for (item in itemsSnapshot(page)) {
            if (!active()) return null
            item.paint(r)
        }
        // Painting built this page's ribbons, and the grid renders pages nowhere near the viewport.
        // Register it so the frame loop's sweep reclaims them like any other off-band page.
        state.noteGeometryBuilt(page)
        return surface.bitmap
    }

    /**
     * A defensive copy of a page's items: thumbnails render off the main thread, so iterating
     * [Page.items] directly can race a main-thread edit and throw [ConcurrentModificationException].
     * Retries a few times (an edit is momentary), then gives up rather than crash — a dropped frame
     * re-renders on the next [contentVersion] bump.
     */
    private fun itemsSnapshot(page: Page): List<CanvasItem> {
        repeat(8) {
            try {
                return ArrayList(page.items)
            } catch (_: java.util.ConcurrentModificationException) {
                // a main-thread edit landed mid-copy; retry
            }
        }
        return emptyList()
    }

    /** An already-rendered side-panel thumbnail for [page] at the current content, or null. */
    fun cachedPageThumbnail(page: Page): ImageBitmap? = synchronized(pageThumbs) {
        if (pageThumbsVersion != contentVersion) {
            pageThumbs.evictAll()
            pageThumbsVersion = contentVersion
        }
        pageThumbs.get(page)
    }

    /**
     * The side-panel thumbnail for [page], rendered off the main thread and cached so scrolling the
     * panel reuses bitmaps instead of re-rendering each page on every pass — that re-render churn
     * (heap allocation + GC) was what made the panel scroll janky. Keyed by page identity, so a
     * reorder keeps it; dropped wholesale when [contentVersion] moves (see [cachedPageThumbnail]).
     */
    suspend fun pageThumbnail(page: Page, widthPx: Int): ImageBitmap? {
        cachedPageThumbnail(page)?.let { return it }
        return withContext(Dispatchers.Default) {
            cachedPageThumbnail(page)?.let { return@withContext it }
            // Render in page space sized so the rotated result lands at [widthPx] wide.
            val renderW = (state.outerW(page) * (widthPx / state.displayW(page))).roundToInt().coerceAtLeast(1)
            val bmp = renderThumbnail(page, renderW, active = { isActive })
                ?.let { rotateForView(it) }?.asImageBitmap() ?: return@withContext null
            synchronized(pageThumbs) { pageThumbs.put(page, bmp) }
            bmp
        }
    }

    /** Rotate a page-space bitmap by the view rotation, for thumbnails that mirror the canvas. */
    private fun rotateForView(bmp: android.graphics.Bitmap): android.graphics.Bitmap {
        val deg = viewSettings.rotation
        if (deg == 0) return bmp
        val m = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
        return android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /** Drop any cached side-panel thumbnails backed by PDF page [pdfPageIndex] (its colours just
     *  finished parsing), so the panel re-renders them un-inverted. Returns true if one was evicted. */
    private fun evictPageThumbnails(pdfPageIndex: Int): Boolean = synchronized(pageThumbs) {
        var evicted = false
        state.document.pages.forEach { if (it.pdfPage == pdfPageIndex && pageThumbs.remove(it) != null) evicted = true }
        evicted
    }

    fun addBookmark(label: String) {
        state.document.bookmarks.add(Bookmark(state.currentPageIndex(), label))
        state.document.dirty = true
        bookmarkVersion++
        dirty = true
    }

    fun removeBookmark(index: Int) {
        if (index in state.document.bookmarks.indices) {
            state.document.bookmarks.removeAt(index)
            state.document.dirty = true
            bookmarkVersion++
            dirty = true
        }
    }

    // --- file operations (SAF streams provided by the activity) ---

    /**
     * Open the note at [uri], driving the "Opening note…" dialog via [opening]. The heavy part is
     * [DocumentCodec.read] streaming a (possibly large) embedded PDF out to a temp file; on the main
     * thread that would freeze the UI and stall the spinner, so only the read runs on IO and the
     * document swap ([replaceDocument], which only invalidates caches and requests an off-thread
     * render) stays on the caller's main thread. A mid-read Cancel ([cancelOpenInProgress]) discards
     * the loaded note and leaves the explorer as it was. A second tap while one open is in flight is
     * ignored, so two reads never race to swap in a document.
     */
    suspend fun openAsync(uri: String, name: String? = null) {
        if (opening) return
        openCancelled.set(false)
        opening = true
        val t0 = System.nanoTime()
        var readMs = -1L
        try {
            val readStart = System.nanoTime()
            var fileBytes = -1L
            val doc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                fileBytes = fileSizeOf(uri)
                appContext.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { codec.read(it, pdfDir, imageDir) }
            }
            readMs = (System.nanoTime() - readStart) / 1_000_000
            if (doc == null) { message = "Could not open that note."; return }
            if (openCancelled.get()) { doc.pdfFile?.delete(); deleteImageTemps(doc); return } // tapped Cancel mid-read; stay put
            doc.path = uri
            doc.displayName = name
            doc.dirty = false
            replaceDocument(doc)
            state.openFileBytes = fileBytes
            state.lastSaveBytes = fileBytes // the on-disk size, until the first autosave rewrites it
            maybeBindAutosave(uri) // resume autosaving if this note lives in the granted folder
            noteOpen = true // push the editor on top of backstage (only on a successful open)
        } catch (e: XNoteFormatException) {
            message = e.message ?: "Not an xnotes document."
        } catch (e: Exception) {
            message = "Could not open the note."
        } finally {
            // Record the timings for the debug overlay, so a genuinely fast open (read < 160ms, no
            // spinner) can be told apart from a bug where the spinner is wrongly skipped.
            state.lastOpenReadMs = readMs
            state.lastOpenTotalMs = (System.nanoTime() - t0) / 1_000_000
            opening = false
        }
    }

    /** Aborts an in-flight open (the dialog's Cancel) so the loaded note is discarded, not shown. */
    fun cancelOpenInProgress() { openCancelled.set(true) }

    fun save(output: OutputStream, uri: String, name: String? = null) {
        try {
            synchronized(saveLock) { codec.write(state.document, output) }
            state.document.path = uri
            if (name != null) state.document.displayName = name
            state.document.dirty = false
            maybeBindAutosave(uri) // saving into the folder makes it autosave thereafter
            refreshContent()
            invalidateThumb(uri) // content changed; re-render its tile next time it's shown
        } catch (e: Throwable) {
            // Throwable, not Exception: an OutOfMemoryError mid-encode must surface a message, not crash.
            message = "Could not save the note."
        }
    }

    /** Save the current document over its existing SAF file at [uri] via [writeNoteSafely], so a
     *  failed encode never truncates the note. Returns false (and shows a message) on failure, e.g.
     *  so the caller can fall back to a Save-As picker. */
    fun saveTo(uri: String): Boolean {
        val startNs = System.nanoTime()
        if (!writeNoteSafely(uri, state.document)) {
            message = "Could not save the note."
            return false
        }
        state.document.path = uri
        state.document.dirty = false
        maybeBindAutosave(uri)
        refreshContent()
        invalidateThumb(uri)
        state.lastSaveSnapshotMs = 0L // writes the live document; no snapshot phase
        state.lastSaveTotalMs = msSince(startNs)
        return true
    }

    // --- explorer thumbnails & document identity ---

    /**
     * Canonical identity of a document — provider authority + document id — so the same file
     * reached as a tree URI (the in-app explorer / a folder note) and as a plain document URI
     * (the system "Open…" picker) maps to one key. Shared by the per-note view state ([viewKey])
     * and the creation-time store. Falls back to the raw string for non-document URIs.
     */
    private fun documentKey(uri: String): String = runCatching {
        val u = android.net.Uri.parse(uri)
        "${u.authority}|${android.provider.DocumentsContract.getDocumentId(u)}"
    }.getOrDefault(uri)

    /** The storage display name for a document/tree URI (no extension stripped), or null. */
    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i) else null
            } else null
        }
    }.getOrNull()

    /** The first page of a loaded document rendered to a [sidePx]×[sidePx] tile, cropped to the page
     *  top (the square surface clips the overflow). Uses [itemsSnapshot] because the close-hook renders
     *  the live document off-thread, which a main-thread edit can mutate underneath it. [filter] and
     *  [rotation] are the note's own View settings resolved against the global defaults. */
    private fun renderDocThumbnailSquare(doc: Document, sidePx: Int, filter: com.xnotes.canvas.PdfPageFilter, rotation: Int = 0): android.graphics.Bitmap? {
        val page = doc.pages.firstOrNull() ?: return null
        val side = sidePx.coerceAtLeast(1)
        val cover = exportFootprint(doc, page)
        val scale = side.toDouble() / cover.w
        val surface = com.xnotes.platform.AndroidRasterSurface.create(side, side)
        // Resolve the paper colour against this note's own document/page style (not the open note's).
        surface.fill(page.resolvedPageColor(doc, state.pageColorOverride) ?: state.palette.paper)
        val r = surface.renderer()
        r.scale(scale, scale)
        r.translate(-cover.left, -cover.top)
        doc.pdfFile?.let { file ->
            runCatching {
                com.xnotes.platform.PdfSource.create(appContext, file)?.let { src ->
                    page.pdfPage?.let { pi ->
                        if (filter.stampImages) src.ensureImageRects(pi)
                        val pw = (page.width * scale).toInt().coerceAtLeast(1)
                        val ph = (page.height * scale).toInt().coerceAtLeast(1)
                        src.renderPage(pi, pw, ph, filter)?.let { bg ->
                            r.drawRaster(bg, Rect(0.0, 0.0, page.width, page.height))
                            bg.recycle()
                        }
                    }
                    src.close()
                }
            }
        }
        // A closed note's flow is laid out locally (the published snapshot serves the open note only).
        if (!doc.flow.isEmpty) {
            val frame = themedFlowLayout(doc.flow)
                .layout(doc.flow, doc.pages.map { PageBox(it.width, it.height) }, doc.dpi)
            FlowPainter.paintPage(r, frame, 0, Rect(0.0, 0.0, page.width, page.height))
        }
        for (item in itemsSnapshot(page)) item.paint(r)
        val bmp = surface.bitmap
        if (rotation == 0) return bmp
        val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
        return android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /**
     * The square tile for an infinite canvas: its content framed with a little margin, drawn with
     * the ordinary CPU renderer rather than through GL. The items are the same [CanvasItem]s the
     * paged note holds, so they paint themselves, and doing it here keeps thumbnails off the render
     * thread entirely, with no readback and nothing that needs a live EGL context.
     */
    private fun renderCanvasThumbnailSquare(
        doc: com.xnotes.core.infinite.InfiniteDocument,
        sidePx: Int,
    ): android.graphics.Bitmap? {
        val side = sidePx.coerceAtLeast(1)
        val surface = com.xnotes.platform.AndroidRasterSurface.create(side, side)
        surface.fill(doc.background.paperColor ?: state.palette.paper)
        val bounds = doc.contentBounds()
        if (bounds != null && bounds.w > 0.0 && bounds.h > 0.0) {
            val r = surface.renderer()
            val margin = side * 0.06
            val scale = ((side - 2 * margin) / maxOf(bounds.w, bounds.h)).coerceAtMost(1.0)
            // Centre the content in the square, whichever way round it is.
            r.translate(
                margin + (side - 2 * margin - bounds.w * scale) / 2.0,
                margin + (side - 2 * margin - bounds.h * scale) / 2.0,
            )
            r.scale(scale, scale)
            r.translate(-bounds.left, -bounds.top)
            for (item in doc.items.toList()) item.paint(r)
        }
        return surface.bitmap
    }

    /** The square thumbnail for the canvas at [uri]; null when it is not a readable canvas. */
    suspend fun canvasTileThumbnail(uri: String): ImageBitmap? {
        synchronized(noteThumbs) { noteThumbs.get(uri)?.let { return it } }
        return withContext(thumbDispatcher) {
            delay(150)
            if (!isActive) return@withContext null
            synchronized(noteThumbs) { noteThumbs.get(uri)?.let { return@withContext it } }
            val bmp = thumbCache.load(uri) ?: run {
                val doc = runCatching {
                    appContext.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
                        canvasCodec.read(it, imageDir)
                    }
                }.getOrNull() ?: return@withContext null
                try {
                    renderCanvasThumbnailSquare(doc, tilePx)?.also { thumbCache.store(uri, it) }
                        ?: return@withContext null
                } finally {
                    for (item in doc.items) if (item is ImageItem) runCatching { item.image.file.delete() }
                }
            }
            val img = bmp.asImageBitmap()
            synchronized(noteThumbs) { noteThumbs.put(uri, img) }
            img
        }
    }

    /** The square tile for the document at [uri], whichever kind it is. */
    suspend fun tileThumbnail(uri: String, name: String): ImageBitmap? =
        if (com.xnotes.core.util.DocumentKind.ofName(name) == com.xnotes.core.util.DocumentKind.CANVAS) {
            canvasTileThumbnail(uri)
        } else {
            noteTileThumbnail(uri)
        }

    /** The square side (px) explorer tiles render at — fixed so rotation/column changes don't re-render. */
    private val tilePx = 600

    /** An already-loaded tile for [uri] at its current content, to seed the grid instantly. */
    fun cachedNoteTile(uri: String): ImageBitmap? = synchronized(noteThumbs) { noteThumbs.get(uri) }

    /**
     * The square thumbnail for the note at [uri], for the explorer grid. Returns a cached hit
     * (memory, then disk) as-is; otherwise renders off the main thread on the single low-priority
     * [thumbDispatcher] (one note at a time) so a folder of many notes fills in gradually without
     * stalling the UI. The cache is authoritative — a cached tile is shown unconditionally; a content
     * change drops it ([invalidateThumb]) so it re-renders, and closing a note regenerates it.
     */
    suspend fun noteTileThumbnail(uri: String): ImageBitmap? {
        synchronized(noteThumbs) { noteThumbs.get(uri)?.let { return it } }
        return withContext(thumbDispatcher) {
            delay(150) // let a quick scroll-past cancel this before any heavy work begins
            if (!isActive) return@withContext null
            synchronized(noteThumbs) { noteThumbs.get(uri)?.let { return@withContext it } }
            val bmp = thumbCache.load(uri) ?: run {
                val doc = runCatching {
                    appContext.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { codec.read(it, pdfDir, imageDir) }
                }.getOrNull() ?: return@withContext null
                try {
                    val vs = viewSettingsFor(uri)
                    renderDocThumbnailSquare(doc, tilePx, pdfPageFilterFor(vs), vs.rotation)?.also { thumbCache.store(uri, it) } ?: return@withContext null
                } finally {
                    doc.pdfFile?.delete() // transient doc loaded just for a thumbnail; drop its extracts
                    deleteImageTemps(doc)
                }
            }
            val img = bmp.asImageBitmap()
            synchronized(noteThumbs) { noteThumbs.put(uri, img) }
            img
        }
    }

    /** Delete the temp image files backing a transient document (thumbnail/export/cancelled open), so
     *  they don't pile up in [imageDir] until the next launch purge. Never called on the open note. */
    private fun deleteImageTemps(doc: Document) {
        for (page in doc.pages) for (item in page.items) {
            if (item is ImageItem) runCatching { item.image.file.delete() }
        }
    }

    /** Drop a note's cached tile (memory + disk) so it re-renders with fresh content next time it's shown. */
    private fun invalidateThumb(uri: String) {
        synchronized(noteThumbs) { noteThumbs.remove(uri) }
        thumbCache.remove(uri)
    }

    /**
     * Render the just-closed note's tile from the in-memory document and cache it (memory + disk), so
     * the grid shows it instantly and current. Runs on the low-priority [thumbDispatcher]; identity-
     * guarded so that if the user has already opened another note (state.document changed) it bails
     * rather than caching the wrong pixels. Called from [goHome] — never during editing.
     */
    private suspend fun regenerateClosedNoteThumb(uri: String) {
        val doc = state.document
        // The closing note's own filter + rotation, captured before any swap.
        val filter = pdfPageFilter()
        val rotation = viewSettings.rotation
        withContext(thumbDispatcher) {
            if (state.document !== doc) return@withContext // a new note was opened; don't cache stale pixels
            val bmp = runCatching { renderDocThumbnailSquare(doc, tilePx, filter, rotation) }.getOrNull() ?: return@withContext
            thumbCache.store(uri, bmp)
            val img = bmp.asImageBitmap()
            synchronized(noteThumbs) { noteThumbs.put(uri, img) }
        }
    }

    /** Whether the next launch should open the home screen (true) or the last-open note (false). */
    val startOnHome: Boolean get() = settings.startOnHome

    /** Record whether the home screen is the current surface, so relaunch returns to it. */
    fun setStartOnHome(home: Boolean) {
        if (settings.startOnHome != home) {
            settings = settings.copy(startOnHome = home)
            settingsRepo.save(settings)
        }
    }

    // --- in-app file explorer (a user-granted SAF tree) ---

    fun updateBrowseRoot(treeUri: String) {
        browseRoot = treeUri
        settings = settings.copy(browseRoot = treeUri)
        settingsRepo.save(settings)
        browseCache.clear()
        rootNameCache.clear()
    }

    /** Browse the app's own private storage instead of a granted folder; no SAF grant needed. */
    fun useInternalStorage() {
        updateBrowseRoot(com.xnotes.platform.AppStorageDocumentsProvider.treeUri(appContext).toString())
    }

    /** The field the explorer grid sorts by, and whether that sort is reversed. */
    val explorerSortKey: ExplorerSortKey get() = settings.explorerSortKey
    val explorerSortDescending: Boolean get() = settings.explorerSortDescending

    /** Change the explorer sort, persist it, and re-sort every cached folder listing in place. */
    fun setExplorerSort(key: ExplorerSortKey, descending: Boolean) {
        if (settings.explorerSortKey == key && settings.explorerSortDescending == descending) return
        settings = settings.copy(explorerSortKey = key, explorerSortDescending = descending)
        settingsRepo.save(settings)
        val cmp = explorerComparator(key, descending) { it.created }
        browseCache.replaceAll { _, v -> v.sortedWith(cmp) }
    }

    /** Forget the granted folder: release its SAF permission and clear the root. */
    fun clearBrowseRoot() {
        browseRoot?.let { old ->
            runCatching {
                appContext.contentResolver.releasePersistableUriPermission(
                    android.net.Uri.parse(old),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        browseRoot = null
        settings = settings.copy(browseRoot = null)
        settingsRepo.save(settings)
        browseCache.clear()
        rootNameCache.clear()
        viewStates.clear() // forget every remembered per-note view for the released folder
        createdStore.clear() // and every tracked creation time — the keys only meant anything for that folder
        synchronized(noteThumbs) { noteThumbs.evictAll() }
        thumbCache.prune(emptySet()) // every cached tile belonged to the released folder
    }

    /** The granted root folder's display name (e.g. "Documents"), or null. */
    fun browseRootName(treeUri: String): String? {
        val tree = android.net.Uri.parse(treeUri)
        val root = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            tree, android.provider.DocumentsContract.getTreeDocumentId(tree),
        )
        return queryDisplayName(root)?.also { rootNameCache[treeUri] = it }
    }

    /** Cached root-folder name, to seed the breadcrumb instantly before the refresh. */
    fun cachedRootName(treeUri: String): String? = rootNameCache[treeUri]

    /** Creates a subfolder [name] under [parentDocId] in tree [treeUri]; IO, call off-thread. */
    fun createFolder(treeUri: String, parentDocId: String, name: String): Boolean = runCatching {
        val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(android.net.Uri.parse(treeUri), parentDocId)
        android.provider.DocumentsContract.createDocument(
            appContext.contentResolver, parent, android.provider.DocumentsContract.Document.MIME_TYPE_DIR, name,
        ) != null
    }.getOrDefault(false)

    /**
     * The stem a new note gets from the filename template, expanded against the current moment.
     * A template carrying `#` advances its number past every name in [takenLower] (lowercased,
     * with extension); one that doesn't is returned as-is, for the caller to de-duplicate.
     */
    fun newNoteStem(takenLower: Set<String>): String {
        val template = settings.prefs.newNoteNameTemplate
        val c = java.util.Calendar.getInstance()
        val stem = NameTemplate.expand(
            template,
            c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH),
            c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE),
            c.get(java.util.Calendar.SECOND),
        )
        if (!NameTemplate.hasSequence(template)) return stem
        var n = 1
        while (DocumentKind.entries.any { "${NameTemplate.withSequence(stem, n).lowercase()}${it.suffix}" in takenLower }) n++
        return NameTemplate.withSequence(stem, n)
    }

    /** Resolves a document file name: blank -> the filename template; else ensures the extension
     *  for [kind] and avoids conflicts with a "_N" suffix. */
    private fun uniqueDocumentName(
        treeUri: String,
        parentDocId: String,
        raw: String,
        kind: com.xnotes.core.util.DocumentKind,
    ): String {
        val ext = kind.suffix
        val taken = browseChildren(treeUri, parentDocId).map { it.name.lowercase() }.toSet()
        val base = com.xnotes.core.util.DocumentKind.stripSuffix(raw.trim()).trim()
        if (base.isEmpty()) {
            val stem = newNoteStem(taken)
            if ("${stem.lowercase()}$ext" !in taken) return "$stem$ext"
            var n = 1
            while ("${stem.lowercase()}_$n$ext" in taken) n++
            return "${stem}_$n$ext"
        }
        if ("${base.lowercase()}$ext" !in taken) return "$base$ext"
        var n = 1
        while ("${base.lowercase()}_$n$ext" in taken) n++
        return "${base}_$n$ext"
    }

    /** Creates a new `.xnote` named [name] under [parentDocId], written by [write]; returns its URI, or null.
     *  If [write] throws (an IO error, or a cancelled import), the half-written file is deleted so a failed
     *  import never leaves an empty/partial note behind. */
    private fun createNoteFile(treeUri: String, parentDocId: String, name: String, write: (OutputStream) -> Unit): String? {
        val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(android.net.Uri.parse(treeUri), parentDocId)
        val uri = runCatching {
            android.provider.DocumentsContract.createDocument(appContext.contentResolver, parent, "application/octet-stream", name)
        }.getOrNull() ?: return null
        return runCatching {
            appContext.contentResolver.openOutputStream(uri, "wt")?.use { write(it) }
            uri.toString()
        }.getOrElse {
            runCatching { android.provider.DocumentsContract.deleteDocument(appContext.contentResolver, uri) }
            null
        }
    }

    /** Creates a blank `.xcanvas` under [parentDocId]; returns its URI, or null. IO — call off-thread. */
    fun createBlankCanvasFile(treeUri: String, parentDocId: String, rawName: String): String? {
        val name = uniqueDocumentName(treeUri, parentDocId, rawName, com.xnotes.core.util.DocumentKind.CANVAS)
        return createNoteFile(treeUri, parentDocId, name) {
            canvasCodec.write(com.xnotes.core.infinite.InfiniteDocument(), it)
        }
    }

    /** Creates a blank `.xnote` under [parentDocId]; returns its URI, or null. IO — call off-thread. */
    fun createBlankNoteFile(treeUri: String, parentDocId: String, rawName: String): String? {
        val name = uniqueDocumentName(treeUri, parentDocId, rawName, com.xnotes.core.util.DocumentKind.NOTE)
        val blank = blankDocument().also { stampNewNoteDefaults(it) }
        return createNoteFile(treeUri, parentDocId, name) { codec.write(blank, it) }
    }

    /** Imports the PDF at [pdfFile] into a new `.xnote` under [parentDocId] (named after [rawName]);
     *  returns its URI, or null. The PDF is streamed straight into the bundle, never held in RAM. IO. */
    fun createPdfNoteFile(treeUri: String, parentDocId: String, rawName: String, pdfFile: java.io.File): String? {
        val source = com.xnotes.platform.PdfSource.create(appContext, pdfFile) ?: return null
        val doc = com.xnotes.platform.PdfImporter.import(source, state.document.dpi) // doc.pdfFile = pdfFile
        stampNewNoteDefaults(doc)
        val name = uniqueDocumentName(treeUri, parentDocId, rawName, com.xnotes.core.util.DocumentKind.NOTE)
        val uri = createNoteFile(treeUri, parentDocId, name) { codec.write(doc, it) { importCancelled.get() } }
        source.close()
        return uri
    }

    /** Saves the picked `.xnote` at [file] into a new file under [parentDocId] (named after [rawName]);
     *  returns its URI, or null. Streamed copy, so a big embedded PDF never loads into RAM. IO. */
    fun createNoteFileFromFile(treeUri: String, parentDocId: String, rawName: String, file: java.io.File): String? {
        runCatching { java.io.FileInputStream(file).use { codec.read(it) } }.getOrNull() ?: return null // validate it's a real .xnote (no PDF extraction)
        val name = uniqueDocumentName(treeUri, parentDocId, rawName, com.xnotes.core.util.DocumentKind.NOTE)
        return createNoteFile(treeUri, parentDocId, name) { out -> copyStream(java.io.FileInputStream(file), out) { importCancelled.get() } }
    }

    /** Streams [input] to a private temp file for a pending import; returns it, or null. The caller
     *  owns the file (it's handed to [requestImport]). Copies in small buffers so a large pick never
     *  loads into RAM. IO — call off the main thread. */
    private fun stageImport(input: InputStream): java.io.File? {
        val f = runCatching { java.io.File.createTempFile("import", ".tmp", pdfDir) }.getOrNull() ?: return null
        return runCatching {
            java.io.FileOutputStream(f).use { copyStream(input, it) { importCancelled.get() } } // closes input
            f
        }.getOrElse { f.delete(); null } // failed or cancelled mid-copy: drop the partial temp
    }

    /** Copies [input] to [out] in small buffers, polling [isCancelled] so a long copy can abort
     *  (throwing [DocumentCodec.WriteCancelled], which [createNoteFile] turns into a discarded file). */
    private fun copyStream(input: InputStream, out: OutputStream, isCancelled: () -> Boolean) {
        val buf = ByteArray(64 * 1024)
        input.use {
            while (true) {
                if (isCancelled()) throw DocumentCodec.WriteCancelled()
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
    }

    /** A picked PDF/.xnote (referenced by content [uri]) now awaits a name before being saved into the
     *  folder. The file is deliberately **not** copied yet — that happens at [commitImport], under the
     *  import loader — so the name dialog can appear instantly instead of after a big copy. */
    fun requestImport(kind: ImportKind, defaultName: String, uri: String) {
        pendingImport = PendingImport(kind, defaultName, uri)
    }

    /** Discards a pending import (the user cancelled the name prompt). Nothing was copied yet. */
    fun cancelImport() { pendingImport = null }

    /** Saves a pending import into [parentDocId] under [treeUri] as [rawName]; returns its URI, or null.
     *  Copies the picked file to a local temp first (the slow part, shown under the import loader), then
     *  builds the note and drops the temp. Clears the request on success or cancel; keeps it on a genuine
     *  failure so the user can retry the name. IO — call off-thread. */
    fun commitImport(treeUri: String, parentDocId: String, rawName: String): String? {
        val pending = pendingImport ?: return null
        importCancelled.set(false)
        val staged = runCatching {
            appContext.contentResolver.openInputStream(android.net.Uri.parse(pending.uri))?.let { stageImport(it) }
        }.getOrNull()
        val uri = if (staged == null) null else try {
            when (pending.kind) {
                ImportKind.PDF -> createPdfNoteFile(treeUri, parentDocId, rawName, staged)
                ImportKind.OPEN -> createNoteFileFromFile(treeUri, parentDocId, rawName, staged)
            }
        } finally {
            staged.delete()
        }
        if (uri != null || importCancelled.get()) {
            pendingImport = null
        }
        return uri
    }

    /** Commits the pending import off the main thread while driving the "Importing…" dialog via
     *  [importing]. Returns the new note's URI, or null on failure/cancel. On cancel [pendingImport]
     *  is cleared (dialog dismisses); on a genuine failure it's kept so the caller can show an error
     *  and let the user retry. */
    suspend fun commitImportAsync(treeUri: String, parentDocId: String, rawName: String): String? {
        importing = true
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                commitImport(treeUri, parentDocId, rawName)
            }
        } finally {
            importing = false
        }
    }

    /** Aborts an in-flight import (the dialog's Cancel) so its stream-copy stops at the next buffer. */
    fun cancelImportInProgress() { importCancelled.set(true) }

    /** Renames a document (file or folder) to [newName]; follows the open note. IO, call off-thread. */
    fun renameDocument(docUri: String, newName: String): Boolean {
        val result = runCatching {
            android.provider.DocumentsContract.renameDocument(appContext.contentResolver, android.net.Uri.parse(docUri), newName)
        }
        if (result.isFailure) return false
        val resultUri = result.getOrNull()?.toString() ?: docUri
        if (state.document.path == docUri) {
            state.document.path = resultUri
            state.document.displayName = newName
            if (autosaveUri == docUri) autosaveUri = resultUri
            title = state.document.title
        }
        if (resultUri != docUri) {
            // The id (hence the key) changed, but it's the same logical note — carry its created time
            // across so a rename keeps its place in the grid instead of looking newly created.
            createdStore.rekey(documentKey(docUri), documentKey(resultUri))
            invalidateThumb(docUri)
        }
        return true
    }

    /**
     * Renames the currently open note. With a backing file it renames the file (and
     * follows it, like [renameDocument]); with none it just sets the in-memory title
     * the next save will use. Main thread — touches Compose state; the file rename is
     * a quick provider call. Returns false on a blank name or a failed file rename.
     */
    fun renameCurrentDocument(rawName: String): Boolean {
        val name = rawName.trim()
        if (name.isEmpty()) return false
        val fileName = if (name.endsWith(".xnote", ignoreCase = true)) name else "$name.xnote"
        val uri = currentUri
        return if (uri != null) {
            renameDocument(uri, fileName)
        } else {
            state.document.displayName = fileName
            title = state.document.title
            true
        }
    }

    /** Deletes a document (file or folder), then erases every trace of it. IO, call off-thread. */
    fun deleteDocument(docUri: String): Boolean = runCatching {
        val ok = android.provider.DocumentsContract.deleteDocument(appContext.contentResolver, android.net.Uri.parse(docUri))
        if (ok) purgeDeleted(docUri)
        ok
    }.getOrDefault(false)

    /**
     * Erase every trace of a just-deleted document — or, when it's a folder, everything beneath
     * it: discard the open note if it was the deleted file, drop matching recents, and discard
     * their cached thumbnails and remembered views. Matching is by document identity (authority +
     * id), not the raw URI string, so a file reached through more than one URI form is fully
     * purged. Discarding the open note is what stops it from coming back — via [persist] re-adding
     * it to recents, autosave rewriting its file, or the unsaved-changes guard offering to save it.
     */
    private fun purgeDeleted(docUri: String) {
        val target = android.net.Uri.parse(docUri)
        val delId = runCatching { android.provider.DocumentsContract.getDocumentId(target) }.getOrNull() ?: return
        val auth = target.authority
        fun matches(uri: String): Boolean {
            val u = android.net.Uri.parse(uri)
            val rid = runCatching { android.provider.DocumentsContract.getDocumentId(u) }.getOrNull() ?: return false
            return u.authority == auth && (rid == delId || rid.startsWith("$delId/"))
        }

        // Forget the deleted note's remembered zoom/scroll — and every note's under a deleted folder.
        // View-state and creation-time keys are document identities ("$auth|$id", see documentKey), so
        // match them by prefix. This runs whether or not the note is on screen, so a same-named file later
        // created in this folder (the local provider reuses the path-derived id) starts at fit-width
        // instead of inheriting the dead note's view.
        val keyPrefix = "$auth|$delId"
        viewStates.removeMatching { it == keyPrefix || it.startsWith("$keyPrefix/") }

        // The note on screen was just deleted. Detach it at once — cancel autosave, drop its path,
        // mark it clean — so nothing can rewrite the file or prompt to "save" it back, then drop the
        // document itself for a fresh blank note on the main thread. The identity guard skips that
        // reset if the user has meanwhile opened another note.
        if (currentUri?.let { matches(it) } == true) {
            val deleted = state.document
            noteDebounceJob?.cancel()
            autosaveUri = null
            deleted.path = null
            deleted.dirty = false
            dirty = false
            // Only reset to a fresh page if the deleted note is actually on screen; while on backstage
            // (noteOpen == false) the detached buffer is left as-is so we don't pop into a blank editor.
            autosaveScope.launch { if (state.document === deleted && noteOpen) newNote() }
        }

        // Forget the deleted item's tracked creation time (and the whole subtree's, for a folder),
        // matched the same way as the view state, and drop its cached tile.
        createdStore.removeMatching { it == keyPrefix || it.startsWith("$keyPrefix/") }
        invalidateThumb(docUri)
    }

    /**
     * Copies [sourceUri] into the folder [targetParentDocId] within [treeUri]. On a name clash — most
     * often pasting a copy into the same folder — a file is duplicated under a free "… copy" name
     * rather than failing. (A folder can't be byte-streamed, so a folder name clash still fails.)
     * IO, call off-thread.
     */
    fun copyDocumentInto(treeUri: String, sourceUri: String, targetParentDocId: String): Boolean = runCatching {
        val tree = android.net.Uri.parse(treeUri)
        val target = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, targetParentDocId)
        val src = android.net.Uri.parse(sourceUri)
        // Native copy first: it succeeds outright when there's no name clash (e.g. a different folder).
        // Wrapped on its own, because providers *throw* (rather than return null) on a same-name clash —
        // we must catch that here so it falls through to making a renamed duplicate below instead of
        // failing the whole paste.
        val direct = runCatching {
            android.provider.DocumentsContract.copyDocument(appContext.contentResolver, src, target)
        }.getOrNull()
        if (direct != null) return@runCatching true
        // The clash case (usually pasting into the same folder): duplicate under a free "… copy" name.
        val isDir = appContext.contentResolver.getType(src) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
        val srcName = queryDisplayName(src) ?: return@runCatching false
        val taken = browseChildren(treeUri, targetParentDocId).mapTo(HashSet()) { it.name.lowercase() }
        val newName = uniqueCopyName(srcName, taken, splitExtension = !isDir)
        if (isDir) {
            copyFolderAs(treeUri, src, target, newName)
        } else {
            val newUri = android.provider.DocumentsContract.createDocument(
                appContext.contentResolver, target, "application/octet-stream", newName,
            ) ?: return@runCatching false
            val copied = runCatching {
                appContext.contentResolver.openInputStream(src)?.use { input ->
                    appContext.contentResolver.openOutputStream(newUri, "wt")?.use { output -> input.copyTo(output); true } ?: false
                } ?: false
            }.getOrDefault(false)
            if (!copied) { runCatching { android.provider.DocumentsContract.deleteDocument(appContext.contentResolver, newUri) }; return@runCatching false }
            true
        }
    }.getOrDefault(false)

    /**
     * Duplicates folder [srcFolder] into [targetParent] under [newName]. The new folder starts empty, so
     * each child copies in with no name clash and the provider's native copy recurses into subfolders.
     * Best-effort: returns false if any child failed (a partial copy is left in place). IO, call off-thread.
     */
    private fun copyFolderAs(treeUri: String, srcFolder: android.net.Uri, targetParent: android.net.Uri, newName: String): Boolean {
        val dest = android.provider.DocumentsContract.createDocument(
            appContext.contentResolver, targetParent, android.provider.DocumentsContract.Document.MIME_TYPE_DIR, newName,
        ) ?: return false
        val srcDocId = android.provider.DocumentsContract.getDocumentId(srcFolder)
        var ok = true
        for (child in childDocumentUris(treeUri, srcDocId)) {
            val copied = runCatching {
                android.provider.DocumentsContract.copyDocument(appContext.contentResolver, child, dest)
            }.getOrNull()
            if (copied == null) ok = false
        }
        return ok
    }

    /** Every child document URI under [folderDocId] in tree [treeUri] (all kinds, not just notes/folders). */
    private fun childDocumentUris(treeUri: String, folderDocId: String): List<android.net.Uri> {
        val tree = android.net.Uri.parse(treeUri)
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderDocId)
        val out = ArrayList<android.net.Uri>()
        runCatching {
            appContext.contentResolver.query(
                childrenUri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    out.add(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id))
                }
            }
        }
        return out
    }

    /** A free name for a duplicate: the original if it's free, else "<stem> copy<.ext>" then "copy 2", "copy 3", … */
    private fun uniqueCopyName(name: String, taken: Set<String>, splitExtension: Boolean): String {
        if (name.lowercase() !in taken) return name
        val dot = if (splitExtension) name.lastIndexOf('.') else -1
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = "$stem copy$ext"
        var n = 2
        while (candidate.lowercase() in taken) { candidate = "$stem copy $n$ext"; n++ }
        return candidate
    }

    /** Moves [sourceUri] from [sourceParentDocId] into [targetParentDocId] within [treeUri]; follows the open note. IO. */
    fun moveDocumentInto(treeUri: String, sourceUri: String, sourceParentDocId: String, targetParentDocId: String): Boolean = runCatching {
        // Pasting a cut into the folder the items already live in is a no-op (and SAF would reject the
        // same-parent move), so report success without touching anything.
        if (sourceParentDocId == targetParentDocId) return@runCatching true
        val tree = android.net.Uri.parse(treeUri)
        val sourceParent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, sourceParentDocId)
        val target = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, targetParentDocId)
        val newUri = android.provider.DocumentsContract.moveDocument(appContext.contentResolver, android.net.Uri.parse(sourceUri), sourceParent, target)
        if (newUri != null && state.document.path == sourceUri) {
            state.document.path = newUri.toString()
            if (autosaveUri == sourceUri) autosaveUri = newUri.toString()
        }
        newUri != null
    }.getOrDefault(false)

    // --- autosave (notes living in the granted folder write back automatically) ---

    private fun isUnderTree(fileUri: String, treeUri: String): Boolean = runCatching {
        val f = android.net.Uri.parse(fileUri)
        val t = android.net.Uri.parse(treeUri)
        if (f.authority != t.authority) return false
        val treeId = android.provider.DocumentsContract.getTreeDocumentId(t)
        val fileId = android.provider.DocumentsContract.getDocumentId(f)
        fileId == treeId || fileId.startsWith("$treeId/")
    }.getOrDefault(false)

    // --- divergence guard (something else rewrote the file underneath the open editor) ---

    /** Size + last-modified of a SAF document: the evidence that a file did or did not move under us. */
    private data class DocStamp(val size: Long, val modified: Long)

    /** The stamp the note's autosave file carried after this editor last wrote it. */
    @Volatile private var lastNoteStamp: DocStamp? = null

    /** The canvas sibling of [lastNoteStamp]. */
    @Volatile private var lastCanvasStamp: DocStamp? = null

    /** Where a fork landed: the new file's uri, and the name to show the user. */
    private class Fork(val uri: String, val name: String)

    /** Where a guarded save landed; [fork] is null when it wrote the file in place as usual. */
    private class SaveResult(val uri: String, val fork: Fork?)

    /** Size + mtime of the document at [uri], or null when the provider will not report either. */
    private fun stampOf(uri: String): DocStamp? = runCatching {
        appContext.contentResolver.query(
            android.net.Uri.parse(uri),
            arrayOf(
                android.provider.OpenableColumns.SIZE,
                android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val size = if (c.isNull(0)) -1L else c.getLong(0)
            val modified = if (c.isNull(1)) -1L else c.getLong(1)
            if (size < 0L && modified < 0L) null else DocStamp(size, modified)
        }
    }.getOrNull()

    /**
     * Has something else rewritten [uri] since this editor last wrote it? A folder-sync app pulling
     * in the other device's copy does exactly that, and so does the other split pane holding the same
     * note. Writing in place would then throw away whatever they put there, with no error and no
     * conflict copy, so the caller forks instead.
     *
     * An unreadable stamp is **not** evidence. A provider that won't report size or mtime must never
     * trigger a fork: a spurious duplicate file is a worse default than the plain write.
     */
    private fun changedUnderneath(uri: String, known: DocStamp?): Boolean {
        if (known == null) return false
        val now = stampOf(uri) ?: return false
        return now != known
    }

    /** The parent folder's document id for the file at [uri], or null when it has none. */
    private fun parentDocIdOf(uri: String): String? {
        val id = runCatching {
            android.provider.DocumentsContract.getDocumentId(android.net.Uri.parse(uri))
        }.getOrNull() ?: return null
        return id.substringBeforeLast('/', "").ifEmpty { null }
    }

    /** The name a fork of [title] takes: the note's own name, de-duplicated the usual way. */
    private fun forkName(root: String, parentId: String, title: String, kind: com.xnotes.core.util.DocumentKind): String =
        uniqueDocumentName(root, parentId, com.xnotes.core.util.DocumentKind.stripSuffix(title), kind)

    /**
     * Write [doc] to a new sibling of [uri], leaving the file that changed underneath us untouched.
     * Both versions survive and both sync onward; nothing is merged and nothing is overwritten.
     */
    private fun forkNote(uri: String, doc: Document, title: String): Fork? {
        val root = browseRoot ?: return null
        val parentId = parentDocIdOf(uri) ?: return null
        val name = forkName(root, parentId, title, com.xnotes.core.util.DocumentKind.NOTE)
        val forked = createNoteFile(root, parentId, name) { codec.write(doc, it) } ?: return null
        lastNoteStamp = stampOf(forked)
        return Fork(forked, name)
    }

    /** The canvas sibling of [forkNote]. */
    private fun forkCanvas(uri: String, doc: com.xnotes.core.infinite.InfiniteDocument, title: String): Fork? {
        val root = browseRoot ?: return null
        val parentId = parentDocIdOf(uri) ?: return null
        val name = forkName(root, parentId, title, com.xnotes.core.util.DocumentKind.CANVAS)
        val forked = createNoteFile(root, parentId, name) { canvasCodec.write(doc, it) } ?: return null
        lastCanvasStamp = stampOf(forked)
        return Fork(forked, name)
    }

    /** Autosave [doc] to [uri], forking instead of overwriting when the file moved under us. IO. */
    private fun saveNoteGuarded(uri: String, doc: Document, title: String): SaveResult? = synchronized(saveLock) {
        if (changedUnderneath(uri, lastNoteStamp)) {
            val fork = forkNote(uri, doc, title) ?: return null
            return SaveResult(fork.uri, fork)
        }
        if (!writeNoteSafely(uri, doc)) return null
        SaveResult(uri, null)
    }

    /** The canvas sibling of [saveNoteGuarded]. IO. */
    private fun saveCanvasGuarded(
        uri: String,
        doc: com.xnotes.core.infinite.InfiniteDocument,
        title: String,
    ): SaveResult? = synchronized(saveLock) {
        if (changedUnderneath(uri, lastCanvasStamp)) {
            val fork = forkCanvas(uri, doc, title) ?: return null
            return SaveResult(fork.uri, fork)
        }
        if (!writeCanvasSafely(uri, doc)) return null
        SaveResult(uri, null)
    }

    /** Point the open note at the copy it just forked into, and say so. Main thread. */
    private fun adoptNoteFork(fork: Fork) {
        state.document.path = fork.uri
        state.document.displayName = fork.name
        autosaveUri = fork.uri
        refreshContent()
        message = "This note changed elsewhere. Your edits are now in \u201c${com.xnotes.core.util.DocumentKind.stripSuffix(fork.name)}\u201d."
    }

    /** The canvas sibling of [adoptNoteFork]. Main thread. */
    private fun adoptCanvasFork(fork: Fork) {
        val canvas = infiniteOrNull ?: return
        canvas.document.path = fork.uri
        canvas.document.displayName = fork.name
        canvasAutosaveUri = fork.uri
        refreshContent()
        message = "This canvas changed elsewhere. Your edits are now in \u201c${com.xnotes.core.util.DocumentKind.stripSuffix(fork.name)}\u201d."
    }

    private fun maybeBindAutosave(uri: String?) {
        autosaveUri = if (uri != null && browseRoot?.let { isUnderTree(uri, it) } == true) uri else null
        lastNoteStamp = autosaveUri?.let { stampOf(it) }
    }

    /**
     * Write [doc] into its SAF file at [uri] without ever truncating a good note on a failed encode.
     * The bytes are serialized to a private temp first, so a missing embedded PDF, an OOM, or any
     * codec error aborts *before* the destination is opened; only a complete temp is copied over.
     * The old path opened the destination in "wt" (truncate) and encoded straight into it, so a
     * throw mid-encode left the note as 0 bytes. Returns true only when [uri] now holds the note.
     */
    private fun writeNoteSafely(uri: String, doc: Document): Boolean = synchronized(saveLock) {
        runCatching {
            val tmp = java.io.File.createTempFile("save", ".xnote", saveTmpDir)
            try {
                val encodeStart = System.nanoTime()
                val timing = com.xnotes.format.DocumentCodec.WriteTiming()
                java.io.FileOutputStream(tmp).use { codec.write(doc, it, timing = timing) }
                val copyStart = System.nanoTime()
                val out = appContext.contentResolver.openOutputStream(android.net.Uri.parse(uri), "wt")
                    ?: return@runCatching false
                out.use { java.io.FileInputStream(tmp).use { input -> input.copyTo(it, copyBuffer) } }
                state.lastSaveEncodeMs = msBetween(encodeStart, copyStart) // all four for the debug overlay
                state.lastSaveCopyMs = msSince(copyStart)
                state.lastSaveManifestMs = timing.manifestMs
                state.lastSaveAssetsMs = timing.assetsMs
                state.lastSaveBytes = tmp.length() // live file size for the debug overlay
                lastNoteStamp = stampOf(uri) // read back what the provider reports, not what we wrote
                true
            } finally {
                tmp.delete()
            }
        }.getOrDefault(false)
    }

    /** Elapsed milliseconds since a [System.nanoTime] mark, for the debug overlay's save timings. */
    private fun msSince(startNs: Long): Long = msBetween(startNs, System.nanoTime())

    private fun msBetween(startNs: Long, endNs: Long): Long = (endNs - startNs) / 1_000_000L

    /** The on-disk size of the SAF document at [uri] in bytes, or -1 when the provider won't say. */
    private fun fileSizeOf(uri: String): Long = runCatching {
        appContext.contentResolver.query(
            android.net.Uri.parse(uri),
            arrayOf(android.provider.OpenableColumns.SIZE),
            null, null, null,
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
    }.getOrDefault(-1L)

    private fun scheduleAutosave() {
        val uri = autosaveUri ?: return
        noteDebounceJob?.cancel()
        state.autosaveStatus = "pending" // debounce running; drives the debug overlay
        noteDebounceJob = autosaveScope.launch {
            kotlinx.coroutines.delay(1200L) // debounce: write after a short idle
            noteWriteJob?.join() // wait out a write already going out rather than racing it
            val startNs = System.nanoTime() // the debug overlay's save timings start once the debounce fires
            // Pointers, on the main thread: the writer gets its own page lists over the live items
            // (see [snapshot]), so it never iterates a list the pen is adding to.
            val snapshot = state.document.snapshot()
            state.lastSaveSnapshotMs = msSince(startNs)
            startNoteWrite(uri, snapshot, state.document.displayName ?: state.document.title, startNs)
        }
    }

    /**
     * Write [snapshot] to [uri] off the main thread and do the bookkeeping after it, tracked as
     * [noteWriteJob]. Launched into [autosaveScope] rather than as a child of the caller, so that
     * cancelling a debounce (or a flush) can never cancel a write that is already going out.
     * [startNs] is when the caller began the save, for the debug overlay's total.
     */
    private fun startNoteWrite(
        uri: String,
        snapshot: Document,
        title: String,
        startNs: Long,
        onDone: (() -> Unit)? = null,
    ) {
        val doc = state.document
        // Clean from here, not once the bytes land: [snapshot] already holds these edits, so an edit
        // arriving mid-write has to mark the document again and be carried by the next autosave.
        // Clearing the flag afterwards instead would swallow that edit until something else set it.
        val wasDirty = doc.dirty
        doc.dirty = false
        dirty = false
        state.autosaveStatus = "in progress"
        noteWriteJob = autosaveScope.launch {
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                saveNoteGuarded(uri, snapshot, title)
            }
            if (res != null) {
                // The note may have been closed or switched while the bytes were going out. The write
                // still counts, but only the document it belongs to may have its flags touched.
                if (state.document === doc) res.fork?.let { adoptNoteFork(it) }
                invalidateThumb(res.uri) // file changed on disk; drop the stale tile so the grid re-renders it
            } else if (wasDirty && state.document === doc) {
                doc.dirty = true; dirty = true // nothing was written; the edits are still unsaved
            }
            state.autosaveStatus = if (res != null) "done" else "failed"
            state.lastSaveTotalMs = msSince(startNs)
            onDone?.invoke()
        }
    }

    /** Write the current note to its autosave file now (synchronous, main thread); a no-op when not autosaving. */
    fun flushAutosave() {
        noteDebounceJob?.cancel() // only the debounce; a write already going out is left to finish
        val uri = autosaveUri ?: return
        if (!state.document.dirty) return
        val title = state.document.displayName ?: state.document.title
        val startNs = System.nanoTime()
        val res = saveNoteGuarded(uri, state.document, title) ?: return
        state.document.dirty = false; dirty = false
        res.fork?.let { adoptNoteFork(it) }
        invalidateThumb(res.uri)
        state.lastSaveSnapshotMs = 0L // this path writes the live document, so there is no copy
        state.lastSaveTotalMs = msSince(startNs)
    }

    /**
     * Flush the open note to its folder file off the main thread, then run [onDone] on the main thread.
     * Shows the "Saving your notes…" overlay while it runs when [showOverlay]. Runs [onDone] at once
     * (no save) when the note isn't a folder note or isn't dirty, so the common case stays instant.
     * Off-threading the write is what stops a large note from freezing the UI (ANR) on close/pause.
     */
    private fun flushThen(showOverlay: Boolean, onDone: () -> Unit) {
        noteDebounceJob?.cancel()
        val uri = autosaveUri
        if (uri == null || !state.document.dirty) { onDone(); return }
        if (showOverlay) savingNote = true
        autosaveScope.launch {
            noteWriteJob?.join() // a write already going out finishes first; it may be all there was
            if (!state.document.dirty) { savingNote = false; onDone(); return@launch }
            val startNs = System.nanoTime()
            val snapshot = state.document.snapshot() // main thread: its own page lists over the live items
            state.lastSaveSnapshotMs = msSince(startNs)
            startNoteWrite(uri, snapshot, state.document.displayName ?: state.document.title, startNs) {
                savingNote = false
                onDone()
            }
        }
    }

    // --- per-document view state (folder notes remember their own zoom + scroll) ---

    /**
     * The view-state key for a note in the granted folder — its document identity, shared with
     * [documentKey] — or null when it isn't a folder document, so only folder notes remember a view.
     */
    private fun viewKey(uri: String?): String? {
        val u = uri ?: return null
        val root = browseRoot ?: return null
        return if (isUnderTree(u, root)) documentKey(u) else null
    }

    /** Remember the current note's view (zoom + scroll); a no-op unless it's a laid-out folder note. */
    private fun saveViewState() {
        if (!state.didInitialFit || state.viewportW <= 0) return // nothing meaningful established yet
        val key = viewKey(currentUri) ?: return
        viewStates.put(key, state.zoom, state.scrollX, state.scrollY, viewOverrides)
    }

    /** Update the global View-menu defaults (the menu's "Default for all notes" checkbox),
     *  persist them, and re-resolve the open note — a note only follows the change where it
     *  has no override of its own. */
    fun updateViewDefaults(new: com.xnotes.canvas.ViewSettings) {
        if (viewDefaults == new) return
        viewDefaults = new
        settings = settings.copy(viewDefaults = new)
        settingsRepo.save(settings)
        applyResolvedViewSettings()
    }

    /** Update the open note's View-menu overrides and persist them. */
    fun updateViewOverrides(new: com.xnotes.canvas.ViewOverrides) {
        if (viewOverrides == new) return
        viewOverrides = new
        applyResolvedViewSettings()
        saveViewState()
    }

    /** Install a just-opened note's View-menu overrides (no persistence — nothing changed yet). */
    private fun installViewOverrides(o: com.xnotes.canvas.ViewOverrides) {
        viewOverrides = o
        applyResolvedViewSettings()
    }

    /** Re-resolve the effective settings and react to whatever actually changed. */
    private fun applyResolvedViewSettings() {
        val prev = viewSettings
        val resolved = viewOverrides.resolve(viewDefaults)
        if (prev == resolved) return
        viewSettings = resolved
        onViewSettingsChanged(prev, resolved)
    }

    /** Push a settings change into the canvas/caches; each View-menu feature reacts here. */
    private fun onViewSettingsChanged(prev: com.xnotes.canvas.ViewSettings, new: com.xnotes.canvas.ViewSettings) {
        if (prev.mode != new.mode || prev.rotation != new.rotation || prev.verticalScroll != new.verticalScroll) {
            // Re-group / re-orient / re-flow the pages, keep the reader on the same page, and
            // re-fit a fit-width view to the new row width (a Double spread is about twice as
            // wide; a 90 degree turn swaps every page's footprint).
            val cur = if (state.didInitialFit) state.currentPageIndex() else 0
            state.viewingMode = new.mode
            state.rotationDeg = new.rotation
            state.verticalScroll = new.verticalScroll
            state.flipOffsetX = 0.0
            if (new.verticalScroll) state.fitHeightActive = false // a paginated-only magnet
            state.relayout()
            if (state.didInitialFit) {
                state.currentRow = state.rowIndexOf(cur) // paginated fits read the row
                when {
                    state.fitWidthActive -> state.zoom = state.fitWidthZoom()
                    state.fitHeightActive -> state.fitHeightZoom().takeIf { it > 0.0 }?.let { state.zoom = it }
                }
                state.invalidateCachesForZoom()
                state.goToPage(cur)
                refreshView()
            }
        }
        if (prev.rotation != new.rotation) {
            // Every thumbnail mirrors the canvas orientation: drop them all to re-render.
            synchronized(pageThumbs) { pageThumbs.evictAll() }
            pdfThumbTick++
            currentUri?.let { invalidateThumb(it) }
        }
        val filterChanged = prev.contrast != new.contrast || prev.invert != new.invert ||
            prev.brightness != new.brightness || prev.sepia != new.sepia ||
            prev.keepImages != new.keepImages
        if (filterChanged) {
            state.invalidateAllBackgrounds()
            if (evictPdfPageThumbnails()) pdfThumbTick++
            currentUri?.let { invalidateThumb(it) } // the recents tile re-renders with the new filter
            startPdfRefine() // a filter change can newly require the image-box sweep
        }
        view.scrollbarEnabled = new.scrollbar
        view.requestRender()
    }

    /** The open note's PDF colour filter (its resolved View-menu settings). */
    private fun pdfPageFilter(): com.xnotes.canvas.PdfPageFilter = pdfPageFilterFor(viewSettings)

    private fun pdfPageFilterFor(vs: com.xnotes.canvas.ViewSettings): com.xnotes.canvas.PdfPageFilter =
        com.xnotes.canvas.PdfPageFilter.of(vs.contrast, vs.invert, vs.brightness, vs.sepia, keepImages = vs.keepImages)

    /** A note's resolved View-menu settings by URI: the open note's live value, a folder note's
     *  remembered overrides over the global defaults, else the defaults themselves. */
    private fun viewSettingsFor(uri: String?): com.xnotes.canvas.ViewSettings = when {
        uri != null && uri == currentUri -> viewSettings
        else -> (viewKey(uri)?.let { viewStates.get(it)?.overrides } ?: com.xnotes.canvas.ViewOverrides())
            .resolve(viewDefaults)
    }

    /** True when the open note's PDF render stamps image colours (so image boxes are needed). */
    private fun pdfKeepsImageColors(): Boolean = viewSettings.keepImages &&
        !com.xnotes.canvas.PdfColorFilter.isIdentity(
            viewSettings.contrast, viewSettings.invert, viewSettings.brightness, viewSettings.sepia,
        )

    /** Drop every cached side-panel thumbnail backed by a PDF page (the filter changed). */
    private fun evictPdfPageThumbnails(): Boolean = synchronized(pageThumbs) {
        var evicted = false
        state.document.pages.forEach { if (it.pdfPage != null && pageThumbs.remove(it) != null) evicted = true }
        evicted
    }

    /**
     * Choose a just-installed document's initial view — its remembered view for a folder note,
     * else fit-width — and apply it now if the viewport is sized, else on the next layout. Setting
     * it explicitly is what stops the previous document's zoom/scroll from carrying over. The
     * note's View-menu settings ride along (folder notes remember them; anything else resets).
     */
    private fun installInitialView(path: String?) {
        val saved = viewKey(path)?.let { viewStates.get(it) }
        installViewOverrides(saved?.overrides ?: com.xnotes.canvas.ViewOverrides())
        state.pendingInitialView =
            if (saved != null) InitialView.Restore(saved.zoom, saved.scrollX, saved.scrollY) else InitialView.FitWidth
        state.didInitialFit = false
        if (state.viewportW > 0) state.establishInitialView()
    }

    /** The document id of the explorer root, for listing its top-level children. */
    fun browseRootDocId(treeUri: String): String =
        android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(treeUri))

    /** The document id of a folder entry, for descending into it. */
    fun browseDocId(documentUri: String): String =
        android.provider.DocumentsContract.getDocumentId(android.net.Uri.parse(documentUri))

    /** Lists folders and `.xnote` files under [parentDocId] within tree [treeUri]; IO, call off-thread. */
    fun browseChildren(treeUri: String, parentDocId: String): List<BrowseEntry> {
        val tree = android.net.Uri.parse(treeUri)
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val out = ArrayList<BrowseEntry>()
        var sidecarDocId: String? = null
        runCatching {
            appContext.contentResolver.query(
                childrenUri,
                arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                    android.provider.DocumentsContract.Document.COLUMN_SIZE,
                    android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val id = c.getString(1) ?: continue
                    val isDir = c.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
                    if (isDir) {
                        // The colour sidecar is captured (read below) but never listed; other
                        // dot-folders stay hidden from the explorer too.
                        if (name == SIDECAR_DIR) { sidecarDocId = id; continue }
                        if (name.startsWith(".")) continue
                    } else if (!com.xnotes.core.util.DocumentKind.isDocument(name)) {
                        continue
                    }
                    val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                    val size = if (!c.isNull(3)) c.getLong(3) else 0L
                    val modified = if (!c.isNull(4)) c.getLong(4) else 0L
                    out.add(BrowseEntry(name, docUri, isDir, size, modified, parentDocId = parentDocId))
                }
            }
        }
        val colors = sidecarDocId?.let { readSidecarColors(tree, it) }.orEmpty()
        // Stamp any item we haven't seen before with the moment we discovered it, so the grid can
        // order by creation even though SAF reports only last-modified: items the app created are
        // discovered on the listing right after, and an externally-added file is "created" when found.
        val now = System.currentTimeMillis()
        createdStore.stampMissing(out.map { documentKey(it.documentUri) }, now)
        val withCreated = out.map {
            it.copy(created = createdStore.get(documentKey(it.documentUri)) ?: now, color = colors[it.name])
        }
        val result = withCreated.sortedWith(explorerComparator(settings.explorerSortKey, settings.explorerSortDescending) { it.created })
        browseCache["$treeUri|$parentDocId"] = result
        return result
    }

    // --- per-folder colour sidecar (a hidden ".xnote/colors.json" beside the items it colours) ---

    /** Doc id of the child named [name] under [parentDocId] (a dir when [dir], else a file), or null. */
    private fun findChildDocId(tree: android.net.Uri, parentDocId: String, name: String, dir: Boolean): String? = runCatching {
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        appContext.contentResolver.query(
            childrenUri,
            arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val isDir = c.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
                if (isDir == dir && c.getString(0) == name) return@use c.getString(1)
            }
            null
        }
    }.getOrNull()

    /** Reads the colour map (item name -> colour) from sidecar dir [sidecarDocId]. Forgiving. */
    private fun readSidecarColors(tree: android.net.Uri, sidecarDocId: String): Map<String, Rgba> {
        val fileId = findChildDocId(tree, sidecarDocId, SIDECAR_FILE, dir = false) ?: return emptyMap()
        val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, fileId)
        val text = runCatching {
            appContext.contentResolver.openInputStream(fileUri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return emptyMap()
        return runCatching {
            val obj = org.json.JSONObject(text).optJSONObject("colors") ?: return emptyMap()
            val map = HashMap<String, Rgba>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                Rgba.fromHex(obj.optString(k))?.let { map[k] = it }
            }
            map
        }.getOrDefault(emptyMap())
    }

    /** Overwrites sidecar dir [sidecarDocId]'s colors.json with [map], creating the file if needed. */
    private fun writeSidecarColors(tree: android.net.Uri, sidecarDocId: String, map: Map<String, Rgba>): Boolean {
        var fileId = findChildDocId(tree, sidecarDocId, SIDECAR_FILE, dir = false)
        if (fileId == null) {
            val dirUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, sidecarDocId)
            val created = android.provider.DocumentsContract.createDocument(
                appContext.contentResolver, dirUri, "application/octet-stream", SIDECAR_FILE,
            ) ?: return false
            fileId = android.provider.DocumentsContract.getDocumentId(created)
        }
        val colors = org.json.JSONObject()
        for ((k, v) in map) colors.put(k, Rgba.toHex(v))
        val obj = org.json.JSONObject().put("version", 1).put("colors", colors)
        val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, fileId)
        return runCatching {
            appContext.contentResolver.openOutputStream(fileUri, "wt")?.use { it.write(obj.toString().toByteArray(Charsets.UTF_8)) }
            true
        }.getOrDefault(false)
    }

    /** Sets (or clears, when [color] is null) the explorer colour for [itemName] in folder [parentDocId];
     *  persisted to that folder's hidden ".xnote/colors.json". IO, call off-thread. */
    fun setItemColor(treeUri: String, parentDocId: String, itemName: String, color: Rgba?): Boolean = runCatching {
        val tree = android.net.Uri.parse(treeUri)
        val sidecarId = findChildDocId(tree, parentDocId, SIDECAR_DIR, dir = true) ?: run {
            if (color == null) return@runCatching true // nothing stored, nothing to clear
            val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId)
            val created = android.provider.DocumentsContract.createDocument(
                appContext.contentResolver, parent, android.provider.DocumentsContract.Document.MIME_TYPE_DIR, SIDECAR_DIR,
            ) ?: return@runCatching false
            android.provider.DocumentsContract.getDocumentId(created)
        }
        val map = readSidecarColors(tree, sidecarId).toMutableMap()
        if (color == null) map.remove(itemName) else map[itemName] = color
        writeSidecarColors(tree, sidecarId, map)
    }.getOrDefault(false)

    /** Carries a colour across a rename: moves key [oldName] -> [newName] in [parentDocId]'s sidecar. */
    fun moveItemColor(treeUri: String, parentDocId: String, oldName: String, newName: String): Boolean = runCatching {
        val tree = android.net.Uri.parse(treeUri)
        val sidecarId = findChildDocId(tree, parentDocId, SIDECAR_DIR, dir = true) ?: return@runCatching true
        val map = readSidecarColors(tree, sidecarId).toMutableMap()
        val c = map.remove(oldName) ?: return@runCatching true
        map[newName] = c
        writeSidecarColors(tree, sidecarId, map)
    }.getOrDefault(false)

    /** Last-listed children for a folder, to seed the explorer instantly before the refresh. */
    fun cachedChildren(treeUri: String, parentDocId: String): List<BrowseEntry>? = browseCache["$treeUri|$parentDocId"]

    /**
     * Recursively finds notes at or below [startDocId] in tree [treeUri] whose name (sans `.xnote`)
     * contains [query], case-insensitively. Folders are descended into but not themselves returned;
     * results follow the explorer's chosen sort order. Does one provider query per folder, so it's IO
     * and can be slow on deep trees — call off-thread (and debounce the keystrokes that drive it).
     */
    fun searchNotes(treeUri: String, startDocId: String, query: String): List<BrowseEntry> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val out = ArrayList<BrowseEntry>()
        val seen = HashSet<String>()
        val stack = ArrayDeque<String>().apply { addLast(startDocId) }
        while (stack.isNotEmpty()) {
            val docId = stack.removeLast()
            if (!seen.add(docId)) continue // guard against any cyclic SAF links
            for (e in browseChildren(treeUri, docId)) {
                if (e.isDir) {
                    stack.addLast(browseDocId(e.documentUri))
                } else {
                    val display = if (e.name.endsWith(".xnote", ignoreCase = true)) e.name.dropLast(6) else e.name
                    if (display.contains(needle, ignoreCase = true)) out.add(e)
                }
            }
        }
        return out.sortedWith(explorerComparator(settings.explorerSortKey, settings.explorerSortDescending) { it.created })
    }

    /** Warm the backstage caches off-thread (after launch) so its first open paints instantly. */
    fun prewarmBackstage() {
        autosaveScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    browseRoot?.let { root ->
                        browseRootName(root)
                        browseChildren(root, browseRootDocId(root))
                    }
                }
            }
        }
    }

    // --- per-file actions in the explorer (operate on a stored note URI, not the open document) ---

    /** Streams a stored note's raw bytes to [out] (share-as-.xnote / save-a-copy). */
    fun copyFileTo(srcUri: String, out: OutputStream) {
        appContext.contentResolver.openInputStream(android.net.Uri.parse(srcUri))?.use { it.copyTo(out) }
    }

    /** Loads the note at [srcUri] and writes it flattened to a PDF in [out] (share-as-PDF / export). */
    fun exportFileToPdf(
        srcUri: String,
        out: OutputStream,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        val doc = appContext.contentResolver.openInputStream(android.net.Uri.parse(srcUri))?.use { codec.read(it, pdfDir, imageDir) } ?: return
        val src = doc.pdfFile?.let { com.xnotes.platform.PdfSource.create(appContext, it) }
        try {
            com.xnotes.platform.PdfExporter.export(
                appContext, doc, src, out,
                { exportPaper(doc, it) },
                { page, r -> paintExportRuling(doc, page, r) },
                onProgress, isCancelled,
                flow = flowExportHooks(doc),
            )
        } finally {
            src?.close()
            doc.pdfFile?.delete() // transient doc loaded just for export; drop its extracts
            deleteImageTemps(doc)
        }
    }

    private fun replaceDocument(doc: Document) {
        saveViewState() // remember the outgoing folder note's view before switching away
        flowText.endSession() // flushes the typing burst so the autosave below carries it
        flushAutosave() // save the outgoing note if it was autosaving to the folder
        autosaveUri = null
        // File readouts belong to the outgoing note; a file-backed open re-sets them after the swap.
        state.lastOpenCompacted = doc.compactedOnLoad
        state.openFileBytes = -1L
        state.lastSaveBytes = -1L
        controller.commitTextEdit()
        controller.clearSelection()
        controller.resetGestureState() // drop the outgoing note's fling/elastic so it can't bleed in
        clearPageSelection()
        pageClipboard.clear() // clones reference the outgoing document; don't paste them into another
        state.document = doc
        rebuildPdfSource()
        adoptOpenPdf(doc) // outgoing note's PDF source is now closed; delete its temp file
        history.clear()
        state.invalidateAllCaches()
        state.relayout()
        installInitialView(doc.path) // this note's remembered view, or fit width — never the last note's
        refreshContent()
        view.requestRender()
    }

    // --- tools & colour ---

    fun selectTool(t: Tool) {
        controller.setTool(t)
        tool = t
    }

    /** Run the action a two/three-finger tap or stylus double-tap is mapped to; "none" does nothing. */
    private fun dispatchTapGesture(action: String) = when (action) {
        "undo" -> undo()
        "redo" -> redo()
        "toggle_pan" -> toggleTool(Tool.PAN)
        "toggle_eraser" -> toggleTool(Tool.ERASER)
        "toggle_previous" -> toggleToPreviousTool()
        else -> Unit
    }

    /** Arm [target], or if it is already armed, return to the previous tool (no-op if none yet). */
    private fun toggleTool(target: Tool) {
        if (controller.tool == target) controller.previousTool?.let { selectTool(it) }
        else selectTool(target)
    }

    /** Switch to the single previous tool; no-op on a fresh launch with no previous tool. */
    private fun toggleToPreviousTool() {
        controller.previousTool?.let { selectTool(it) }
    }

    fun pickColor(index: Int) {
        activeColorIndex = index
        // pickInk also recolours the active text box (editing or selected), so the 5 toolbar
        // swatches double as the text colour control.
        controller.pickInk(toolbarColors[index.coerceIn(0, toolbarColors.lastIndex)])
        refreshTextBar()
    }

    /** Live swatch recolour while the picker is open: applies to the canvas but does *not* yet
     *  commit to recents (a spectrum drag fires this on every sample and would flood the list). */
    override fun setSwatchColor(index: Int, color: Rgba) {
        toolbarColors = toolbarColors.toMutableList().also { it[index] = color }
        pickColor(index)
    }

    /** Commit the swatch's current colour to the recent-colours list — called once the picker closes. */
    override fun rememberSwatchColor(index: Int) {
        toolbarColors.getOrNull(index)?.let { settings = settings.rememberColor(it) }
    }

    fun setShapeKind(kind: com.xnotes.core.tools.ShapeKind) {
        shapeConfig = shapeConfig.copy(shape = kind)
        controller.shapeConfig = shapeConfig
    }

    override fun updateShapeConfig(config: ShapeConfig) {
        shapeConfig = config
        controller.shapeConfig = config
    }

    /** The live config for a stroke tool (read by its config popup). */
    override fun toolConfig(tool: Tool): com.xnotes.core.tools.ToolConfig = controller.configFor(tool)

    override val hostShapeConfig: ShapeConfig get() = shapeConfig
    override val hostToolbarColors: List<Rgba> get() = toolbarColors
    override val hostActiveColorIndex: Int get() = activeColorIndex
    override val hostRecentColors: List<Rgba> get() = recentColors

    override fun updateToolConfig(tool: Tool, config: com.xnotes.core.tools.ToolConfig) {
        controller.setToolConfig(tool, config.copy(rgba = controller.inkColor))
    }

    val recentColors: List<Rgba> get() = settings.recentColors

    // --- history ---

    fun undo() {
        flowText.flushBurst() // the open typing burst is the first thing Ctrl+Z takes back
        val command = history.nextUndo
        val pagesBefore = state.document.pages.size
        val was = touchedRegions(command)
        history.undo()
        afterHistory(
            structural = state.document.pages.size != pagesBefore,
            regions = spanning(was, touchedRegions(command)),
        )
    }

    fun redo() {
        flowText.flushBurst()
        val command = history.nextRedo
        val pagesBefore = state.document.pages.size
        val was = touchedRegions(command)
        history.redo()
        afterHistory(
            structural = state.document.pages.size != pagesBefore,
            regions = spanning(was, touchedRegions(command)),
        )
    }

    /**
     * Where [command]'s items sit right now, page-local. Read on both sides of an undo/redo so an
     * item that moved, resized or reflowed is repaired where it was *and* where it landed; null
     * (the command can't say) asks for the old full repaint of every cached page.
     */
    private fun touchedRegions(command: Command?): List<Pair<Page, Rect>>? {
        if (command == null) return emptyList()
        return command.touched(itemPageLocator())?.map { (page, item) -> page to item.paintBounds() }
    }

    private fun spanning(
        before: List<Pair<Page, Rect>>?,
        after: List<Pair<Page, Rect>>?,
    ): List<Pair<Page, Rect>>? = if (before == null || after == null) null else before + after

    /**
     * Finds the page an item sits on, for commands that hold items but not pages. The index is built
     * on first use and only then: most commands carry their own page and never ask.
     */
    private fun itemPageLocator(): (CanvasItem) -> Page? {
        var index: HashMap<CanvasItem, Page>? = null
        return { item ->
            val built = index ?: HashMap<CanvasItem, Page>().also { map ->
                for (page in state.document.pages) for (it in page.items) map[it] = page
                index = map
            }
            built[item]
        }
    }

    private fun afterHistory(structural: Boolean, regions: List<Pair<Page, Rect>>?) {
        controller.clearSelection()
        if (structural) state.relayout() // page add/remove shifts layout; page-keyed caches survive
        // The in-place repaint below reads the published flow snapshot: republish it first
        // so an undone/redone flow edit repaints at its post-history layout.
        republishFlowIfStale()
        // Repair the ink caches rather than dropping them — dropping blanked every visible page to
        // bare paper for a frame (the undo/redo flicker). Only AddPage/DeletePage change the page
        // set, so relayout (which re-renders the sharp viewport) is gated on that. A command that
        // named its regions repairs just those, here and now; one that couldn't hands every cached
        // page to the cache thread instead, because repainting them all inline is a stall long
        // enough to time out input on a dense note.
        if (regions == null) state.refreshAllInk() else state.repairInkRegions(regions)
        if (flowText.active) flowInput.reconcile() // undone/redone text must reach the IME mirror
        state.document.dirty = true
        state.clampScroll()
        refreshContent()
        view.requestRender()
    }

    // --- view ---

    private fun afterView() {
        refreshView()
        view.requestRender()
    }

    fun zoomIn() { state.zoomByStep(true); afterView() }
    fun zoomOut() { state.zoomByStep(false); afterView() }
    fun fitWidth() { state.fitWidth(); afterView() }
    fun fitHeight() { state.fitHeight(); afterView() }
    fun fitPage() { state.fitPage(); afterView() }
    fun prevPage() { state.goToPage(state.prevPageIndex(state.currentPageIndex())); afterView() }
    fun nextPage() { state.goToPage(state.nextPageIndex(state.currentPageIndex())); afterView() }
    fun goToPage(index: Int) { state.goToPage(index); afterView() }

    fun toggleZoomLock() {
        zoomLocked = !zoomLocked
        state.zoomLocked = zoomLocked
    }

    fun toggleRuler() {
        controller.toggleRuler()
        rulerVisible = controller.rulerVisible()
    }

    fun toggleWand() {
        controller.toggleWand()
        wandEnabled = controller.wandEnabled()
    }

    /** Show (or re-arm) the transient "lock zoom" hint after a pinch snaps to fit-to-width. */
    fun showZoomLockHint() { zoomLockHint += 1 }

    /** Dismiss the "lock zoom" hint when a pinch breaks past the fit-to-width magnet. */
    fun hideZoomLockHint() { zoomLockHintDismiss += 1 }

    // --- pages ---

    /**
     * Insert a blank page at [index] (clamped into range), sized from the page at [refIndex] so the
     * note stays uniform (falling back to A4 portrait). Undoable; relayouts and refreshes. Returns
     * the new page's final index.
     */
    private fun insertBlankPageAt(index: Int, refIndex: Int): Int {
        val pages = state.document.pages
        val ref = pages.getOrNull(refIndex) ?: pages.getOrNull(index) ?: pages.lastOrNull()
        val (w, h) = if (ref != null) ref.width to ref.height else PageSize.A4.pixels(Orientation.PORTRAIT, state.document.dpi)
        val at = index.coerceIn(0, pages.size)
        val page = Page(w, h)
        controller.clearSelection() // inserting shifts later page indices; drop any stale item selection
        pages.add(at, page)
        history.push(AddPage(state.document, page, at))
        state.document.dirty = true
        state.relayout()
        refreshContent()
        view.requestRender()
        return at
    }

    /** Common tail for a side-panel page edit: re-layout, refresh the chrome, repaint. */
    private fun afterPageEdit() {
        controller.clearSelection()
        state.document.dirty = true
        state.relayout()
        state.clampScroll()
        refreshContent()
        view.requestRender()
    }

    /** Toolbar "Add page": insert a blank page right after the current one (sized from it) and go to it. */
    fun addPage() {
        val current = state.currentPageIndex()
        val at = insertBlankPageAt(current + 1, current)
        goToPage(at)
    }

    /**
     * Append a blank page at the very end — used by the pull-past-the-end gesture. Stays at the
     * current scroll position so the user is not yanked to the new page; they can scroll to it.
     */
    fun addPageAtEnd() {
        insertBlankPageAt(state.document.pages.size, state.document.pages.lastIndex)
    }

    fun deleteCurrentPage() {
        if (state.document.pages.size <= 1) {
            message = "A note must keep at least one page."
            return
        }
        val index = state.currentPageIndex()
        val page = state.document.pages[index]
        state.document.pages.removeAt(index)
        history.push(DeletePage(state.document, page, index))
        state.document.dirty = true
        state.invalidatePage(page)
        state.relayout()
        refreshContent()
        view.requestRender()
    }

    // --- side-panel page operations (operate on explicit page indices) ---

    /** Insert a blank page right after [index] (sized from it) and reveal it. */
    fun insertPageAfter(index: Int) {
        goToPage(insertBlankPageAt(index + 1, index))
    }

    /** Clear all of a page's items but keep the page (and its PDF/template background). Undoable. */
    fun erasePage(index: Int) {
        val page = pageAt(index) ?: return
        if (page.items.isEmpty()) { message = "That page is already empty."; return }
        val removals = page.items.map { page to it }
        page.items.clear()
        history.push(EraseItems(removals))
        state.invalidatePage(page)
        afterPageEdit()
    }

    /** Deep-clone [indices] (document order) into the page clipboard for a later paste. */
    fun copyPages(indices: List<Int>) {
        val pages = indices.distinct().sorted().mapNotNull { pageAt(it) }
        if (pages.isEmpty()) return
        pageClipboard.clear()
        pages.forEach { pageClipboard.add(it.deepCopy(textMeasurer)) }
    }

    /** Copy [indices] to the clipboard then delete them (kept ≥ 1 page). */
    fun cutPages(indices: List<Int>) {
        if (indices.isEmpty()) return
        if (indices.distinct().size >= state.document.pages.size) {
            message = "A note must keep at least one page."
            return
        }
        copyPages(indices)
        deletePages(indices)
    }

    /** Insert fresh clones of the page clipboard right after [index]; selects nothing, reveals the first. */
    fun pastePagesAfter(index: Int) {
        if (pageClipboard.isEmpty()) return
        val pages = state.document.pages
        val firstAt = (index + 1).coerceIn(0, pages.size)
        var at = firstAt
        val cmds = ArrayList<Command>()
        for (src in pageClipboard) {
            val clone = src.deepCopy(textMeasurer) // fresh clone each paste, so repeated pastes are independent
            pages.add(at, clone)
            cmds.add(AddPage(state.document, clone, at))
            at++
        }
        history.push(CompositeCommand(cmds))
        afterPageEdit()
        goToPage(firstAt)
    }

    /** Delete [indices] as one undoable edit, refusing to empty the note. */
    fun deletePages(indices: List<Int>) {
        val pages = state.document.pages
        val targets = indices.filter { it in pages.indices }.distinct().sortedDescending()
        if (targets.isEmpty()) return
        if (targets.size >= pages.size) {
            message = "A note must keep at least one page."
            return
        }
        val cmds = ArrayList<Command>()
        for (i in targets) { // descending, so each removeAt index stays valid and DeletePage stores the original index
            val page = pages[i]
            pages.removeAt(i)
            state.invalidatePage(page)
            cmds.add(DeletePage(state.document, page, i))
        }
        history.push(CompositeCommand(cmds))
        clearPageSelection()
        afterPageEdit()
    }

    // --- side-panel page selection (multi-select) ---

    val canPastePages: Boolean get() = pageClipboard.isNotEmpty()
    val pageSelectionCount: Int get() = selectedPages.size
    val inPageSelectionMode: Boolean get() = selectedPages.isNotEmpty()

    fun isPageSelected(index: Int): Boolean {
        val p = pageAt(index) ?: return false
        return selectedPages.any { it === p }
    }

    /** Selected page indices in document order. */
    fun selectedPageIndices(): List<Int> =
        state.document.pages.mapIndexedNotNull { i, p -> if (selectedPages.any { it === p }) i else null }

    /** Toggle a page's membership in the selection (entering selection mode on the first add). */
    fun togglePageSelection(index: Int) {
        val p = pageAt(index) ?: return
        val at = selectedPages.indexOfFirst { it === p }
        if (at >= 0) selectedPages.removeAt(at) else selectedPages.add(p)
    }

    fun clearPageSelection() {
        if (selectedPages.isNotEmpty()) selectedPages.clear()
    }

    // --- export a subset of pages (side-panel Share / Save as) ---

    /** Flatten the pages at [indices] (document order) into a PDF written to [out]. */
    fun exportPagesToPdf(
        indices: List<Int>,
        out: OutputStream,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        val pages = indices.distinct().sorted().mapNotNull { pageAt(it) }
        if (pages.isEmpty()) return
        val sub = Document(dpi = state.document.dpi, pdfFile = state.document.pdfFile)
        sub.pages.addAll(pages) // share the page objects; export only reads them
        sub.style = state.document.style // carry the note's "all pages" style into the subset export
        // A private source per export — see [exportPdf]: the canvas's cache thread may be
        // touching the live [pdfSource], and PdfRenderer can't be shared across threads. The
        // shared PDF file is read-only and owned by the open document, so closing src won't delete it.
        val src = sub.pdfFile?.let { com.xnotes.platform.PdfSource.create(appContext, it) }
        try {
            com.xnotes.platform.PdfExporter.export(
                appContext, sub, src, out,
                { exportPaper(sub, it) },
                { page, r -> paintExportRuling(sub, page, r) },
                onProgress, isCancelled,
                // The subset shares the open note's page objects, so its flow lines map through.
                flow = flowExportHooks(state.document),
            )
        } finally {
            src?.close()
        }
    }

    /** PNG bytes for page [index], rendered at full page resolution (paper + background + items), or null. */
    fun pageImagePng(index: Int): ByteArray? {
        val page = pageAt(index) ?: return null
        // One-shot export: find this page's image locations now so the PNG is correct regardless of how
        // far the background sweep has reached (renderThumbnail itself is a pure consumer).
        if (pdfKeepsImageColors()) {
            page.pdfPage?.let { pi -> pdfSource?.ensureImageRects(pi) }
        }
        val bmp = renderThumbnail(page, state.outerW(page).toInt().coerceAtLeast(1)) ?: return null
        return java.io.ByteArrayOutputStream().use { out ->
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    // --- selection edits ---

    override fun deleteSelection() = controller.deleteSelection()
    fun selectAll() = controller.selectAll()
    override fun bringToFront() = controller.bringToFront()
    override fun selectionStyles() = controller.selectionStyles()

    override fun restyleSelection(color: Rgba?, width: Double?, preview: Boolean) {
        controller.restyleSelection(color, width, preview)
        if (!preview) color?.let { settings = settings.rememberColor(it) }
    }

    fun escape() = controller.escape()

    fun toggleSidebar() {
        sidebarVisible = !sidebarVisible
    }

    // --- keyboard shortcuts (spec 11 §2) ---

    /** File-ish actions that live in the Compose layer (SAF launchers, dialogs). */
    class KeyActions(
        val newNote: () -> Unit = {},
        val open: () -> Unit = {},
        val save: () -> Unit = {},
        val saveAs: () -> Unit = {},
        val exportPdf: () -> Unit = {},
        val preferences: () -> Unit = {},
        val fullscreen: () -> Unit = {},
    )

    var keyActions = KeyActions()

    fun handleKeyDown(e: android.view.KeyEvent): Boolean {
        // A canvas is on top: it owns the keyboard, and understands only its own shortcuts.
        if (canvasOpen) return infinite.handleKeyDown(e)
        // A live flow caret session owns the keyboard first (Ctrl+B means bold here).
        if (flowText.active && handleFlowKey(e)) return true
        // While editing a text box, let the field consume keys (only Escape commits).
        if (editingField != null) {
            if (e.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) { escape(); return true }
            return false
        }
        val ctrl = e.isCtrlPressed
        val shift = e.isShiftPressed
        when {
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_Z && shift -> redo()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_Z -> undo()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_N -> keyActions.newNote()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_O -> keyActions.open()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_S && shift -> keyActions.saveAs()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_S -> keyActions.save()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_E -> keyActions.exportPdf()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_A -> selectAll()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_B -> toggleSidebar()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_COMMA -> keyActions.preferences()
            ctrl && (e.keyCode == android.view.KeyEvent.KEYCODE_PLUS || e.keyCode == android.view.KeyEvent.KEYCODE_EQUALS) -> zoomIn()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_MINUS -> zoomOut()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_0 -> fitWidth()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_9 -> fitPage()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_8 -> fitHeight()
            ctrl -> return false
            e.keyCode == android.view.KeyEvent.KEYCODE_DEL || e.keyCode == android.view.KeyEvent.KEYCODE_FORWARD_DEL -> deleteSelection()
            e.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE -> escape()
            e.keyCode == android.view.KeyEvent.KEYCODE_PAGE_UP -> prevPage()
            e.keyCode == android.view.KeyEvent.KEYCODE_PAGE_DOWN -> nextPage()
            e.keyCode == android.view.KeyEvent.KEYCODE_F11 -> keyActions.fullscreen()
            e.keyCode == android.view.KeyEvent.KEYCODE_P -> selectTool(Tool.PEN)
            e.keyCode == android.view.KeyEvent.KEYCODE_C -> selectTool(Tool.CALLIGRAPHY)
            e.keyCode == android.view.KeyEvent.KEYCODE_H -> selectTool(Tool.HIGHLIGHTER)
            e.keyCode == android.view.KeyEvent.KEYCODE_E -> selectTool(Tool.ERASER)
            e.keyCode == android.view.KeyEvent.KEYCODE_V -> selectTool(Tool.SELECT)
            e.keyCode == android.view.KeyEvent.KEYCODE_L -> selectTool(Tool.LASSO)
            e.keyCode == android.view.KeyEvent.KEYCODE_S -> selectTool(Tool.SHAPE)
            e.keyCode == android.view.KeyEvent.KEYCODE_T -> selectTool(Tool.TEXT)
            else -> return false
        }
        return true
    }

    /** Keys while the flow caret is live: editing, navigation and formatting shortcuts. */
    private fun handleFlowKey(e: android.view.KeyEvent): Boolean {
        val ctrl = e.isCtrlPressed
        val shift = e.isShiftPressed
        when {
            e.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE -> flowText.endSession()
            ctrl && shift && e.keyCode == android.view.KeyEvent.KEYCODE_Z -> redo()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_Z -> undo()
            ctrl && shift && e.keyCode == android.view.KeyEvent.KEYCODE_X ->
                flowToggleStyle({ it.strike }) { s, v -> s.copy(strike = v) }
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_B ->
                flowToggleStyle({ it.bold }) { s, v -> s.copy(bold = v) }
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_I ->
                flowToggleStyle({ it.italic }) { s, v -> s.copy(italic = v) }
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_U ->
                flowToggleStyle({ it.underline }) { s, v -> s.copy(underline = v) }
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_A -> flowText.selectAll()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_C -> flowCopySelection(cut = false)
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_X -> flowCopySelection(cut = true)
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_V -> pastePlainAtCaret()
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT -> flowMoveWord(forward = false, extend = shift)
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> flowMoveWord(forward = true, extend = shift)
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_DEL -> flowDeleteWord(forward = false)
            ctrl && e.keyCode == android.view.KeyEvent.KEYCODE_FORWARD_DEL -> flowDeleteWord(forward = true)
            ctrl -> return false // other Ctrl combos (save, zoom...) stay global
            e.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                e.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER ->
                flowText.applyReplace(flowText.selection, "\n")
            e.keyCode == android.view.KeyEvent.KEYCODE_DEL -> flowDeleteKey(forward = false)
            e.keyCode == android.view.KeyEvent.KEYCODE_FORWARD_DEL -> flowDeleteKey(forward = true)
            e.keyCode == android.view.KeyEvent.KEYCODE_TAB -> flowText.applyReplace(flowText.selection, "\t")
            e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT -> flowMoveHorizontal(-1, shift)
            e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> flowMoveHorizontal(1, shift)
            e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP -> flowMoveVertical(-1, shift)
            e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN -> flowMoveVertical(1, shift)
            e.keyCode == android.view.KeyEvent.KEYCODE_MOVE_HOME -> flowLineEdge(start = true, extend = shift)
            e.keyCode == android.view.KeyEvent.KEYCODE_MOVE_END -> flowLineEdge(start = false, extend = shift)
            else -> {
                val ch = e.unicodeChar
                if (ch == 0 || e.isAltPressed) return false
                flowText.applyReplace(flowText.selection, String(Character.toChars(ch)))
            }
        }
        return true
    }

    // --- flow formatting (the bottom format bar + shortcuts) ---

    /** The style the bar reports: pending/typing style at a caret, the first char of a selection. */
    fun flowCaretStyle(): CharStyle {
        val sel = flowText.selection.normalized()
        val editor = FlowEditor(state.document.flow)
        return if (sel.collapsed) {
            flowText.pendingStyle ?: editor.charStyleAt(sel.start)
        } else {
            editor.styleAtRangeStart(sel)
        }
    }

    /** The paragraph under the caret / selection start, for the bar's paragraph toggles. */
    fun flowCaretParagraph(): Paragraph? =
        state.document.flow.paragraphs.getOrNull(flowText.selection.normalized().start.para)

    fun flowDefaultSizePt(): Double = state.document.flow.defaultSizePt
    /**
     * The flow's base text colour for the active paper: the near-white default on
     * dark/OLED paper, a near-black on light paper (where near-white would vanish).
     */
    private fun defaultTextColor(): Rgba =
        if (palette.isDark) com.xnotes.core.text.TextFlow.DEFAULT_COLOR else Rgba(28, 28, 28, 255)

    /** The effective base text colour: the flow's explicit default, else the theme auto colour. */
    fun flowDefaultColor(): Rgba = state.document.flow.defaultColor ?: defaultTextColor()
    fun flowDefaultFace(): FontFace = state.document.flow.defaultFace

    /** Rewrite character styles over the selection, or arm them for the next typed run. */
    fun flowSetChar(apply: (CharStyle) -> CharStyle) {
        if (!flowText.active) return
        val sel = flowText.selection.normalized()
        if (sel.collapsed) {
            flowText.pendingStyle = apply(flowCaretStyle())
            flowSelTick++
            onRender() // the caret previews the pending style (e.g. its new size)
            return
        }
        flowText.flushBurst()
        flowText.commitEdit(FlowEditor(state.document.flow).setCharStyle(sel) { apply(it) }, null)
        flowSelTick++
    }

    /** Toggle a character style on the selection, or arm it for the next typed run. */
    private fun flowToggleStyle(prop: (CharStyle) -> Boolean, apply: (CharStyle, Boolean) -> CharStyle) {
        val target = !prop(flowCaretStyle())
        flowSetChar { apply(it, target) }
    }

    fun flowToggleBold() = flowToggleStyle({ it.bold }) { s, v -> s.copy(bold = v) }
    fun flowToggleItalic() = flowToggleStyle({ it.italic }) { s, v -> s.copy(italic = v) }
    fun flowToggleUnderline() = flowToggleStyle({ it.underline }) { s, v -> s.copy(underline = v) }
    fun flowToggleStrike() = flowToggleStyle({ it.strike }) { s, v -> s.copy(strike = v) }
    fun flowSetCharColor(c: Rgba?) = flowSetChar { it.copy(color = c) }
    fun flowSetCharHighlight(c: Rgba?) = flowSetChar { it.copy(highlight = c) }
    fun flowSetCharFace(f: FontFace?) = flowSetChar { it.copy(face = f) }

    /** Step the bar's shown size by [delta] and apply that one size across the selection. */
    fun flowAdjustSize(delta: Double) {
        val shown = flowCaretStyle().sizePt ?: flowDefaultSizePt()
        val target = (shown + delta).coerceIn(6.0, 96.0)
        flowSetChar { it.copy(sizePt = target) }
    }

    /** Apply a paragraph-property change over the selection as one undo step. */
    private fun flowParaOp(mutate: (Paragraph) -> Unit) {
        if (!flowText.active) return
        flowText.flushBurst()
        flowText.commitEdit(FlowEditor(state.document.flow).setParaStyle(flowText.selection, mutate), null)
        flowSelTick++
    }

    /** Toggle the paragraph(s) into [kind], or back to plain text when already that kind. */
    fun flowToggleList(kind: ListKind) {
        val target = if (flowCaretParagraph()?.list == kind) ListKind.NONE else kind
        flowParaOp {
            it.list = target
            if (target != ListKind.CHECK) it.checked = false
            if (target != ListKind.NONE) it.codeLang = null
        }
    }

    /** Toggle the paragraph(s) into code lines in the last-used language, or back to plain. */
    fun flowToggleCode() {
        if (flowCaretParagraph()?.codeLang != null) {
            flowParaOp { it.codeLang = null }
        } else {
            flowStartCode(lastCodeLanguage())
        }
    }

    /** Language token ("plain" or a tree-sitter id) the code toggle arms next. */
    fun lastCodeLanguage(): String =
        settings.prefs.lastCodeLanguage.ifEmpty { settings.prefs.defaultCodeLanguage }

    /** Tokens the language menu offers: plain plus every highlightable language. */
    fun codeLanguageChoices(): List<String> =
        listOf("plain") + (if (treeSitterAvailable) scmLanguages() else emptyList())

    /** Pick a code language: retarget the caret's whole code block, or start one. */
    fun flowSetCodeLanguage(token: String) {
        if (!flowText.active) return
        if (flowCaretParagraph()?.codeLang != null) {
            val lang = if (token == "plain") "" else token
            val flow = state.document.flow
            val sel = flowText.selection.normalized()
            var first = sel.start.para
            while (first > 0 && flow.paragraphs[first - 1].codeLang != null) first--
            var last = sel.end.para.coerceAtMost(flow.paragraphs.size - 1)
            while (last + 1 < flow.paragraphs.size && flow.paragraphs[last + 1].codeLang != null) last++
            flowText.flushBurst()
            flowText.commitEdit(
                FlowEditor(flow).setParaStyle(FlowRange(FlowPos(first, 0), FlowPos(last, 0))) {
                    if (it.codeLang != null) it.codeLang = lang
                },
                null,
            )
            flowSelTick++
        } else {
            flowStartCode(token)
        }
        settings = settings.copy(prefs = settings.prefs.copy(lastCodeLanguage = token))
        settingsRepo.save(settings)
    }

    /** Make the selected paragraph(s) code lines in [token]'s language. */
    private fun flowStartCode(token: String) {
        val lang = if (token == "plain") "" else token
        flowParaOp {
            it.codeLang = lang
            it.list = ListKind.NONE
            it.checked = false
        }
    }

    fun flowCycleAlign() {
        val next = when (flowCaretParagraph()?.align ?: ParaAlign.LEFT) {
            ParaAlign.LEFT -> ParaAlign.CENTER
            ParaAlign.CENTER -> ParaAlign.RIGHT
            ParaAlign.RIGHT -> ParaAlign.JUSTIFY
            ParaAlign.JUSTIFY -> ParaAlign.LEFT
        }
        flowParaOp { it.align = next }
    }

    fun flowIndent(delta: Int) = flowParaOp { it.indent += delta }

    // --- flow document config (the text tool's popup: margins + defaults; not undoable) ---

    /** The document's flow defaults as one value, for the text tool's config popup. */
    fun flowConfigValue(): FlowDefaults = FlowDefaults.of(state.document.flow)

    /** Replace the document's flow defaults (clamped), relayout, and mark it dirty. */
    fun setFlowConfig(config: FlowDefaults) {
        val next = config.copy(
            sizePt = config.sizePt.coerceIn(6.0, 96.0),
            margins = FlowMargins(
                config.margins.leftMm.coerceIn(FlowMargins.MIN_MM, FlowMargins.MAX_MM),
                config.margins.topMm.coerceIn(FlowMargins.MIN_MM, FlowMargins.MAX_MM),
                config.margins.rightMm.coerceIn(FlowMargins.MIN_MM, FlowMargins.MAX_MM),
                config.margins.bottomMm.coerceIn(FlowMargins.MIN_MM, FlowMargins.MAX_MM),
            ),
        )
        if (next == FlowDefaults.of(state.document.flow)) return
        next.applyTo(state.document.flow)
        flowConfigChanged()
    }

    private fun flowConfigChanged() {
        state.document.dirty = true
        republishFlow(invalidate = true)
        if (flowText.active) flowText.ensureCaretVisible()
        refreshContent()
        onRender()
    }

    // --- user code themes (Helix .toml) ---

    val treeSitterAvailable: Boolean get() = highlighter != null

    fun scmLanguages(): List<String> = com.xnotes.platform.TreeSitterHighlighter.SUPPORTED.sorted()

    val hasCustomCodeTheme: Boolean get() = customCodeTheme != null

    /** Adopt a Helix theme file for code colours; reports via [message]. */
    fun importCodeTheme(bytes: ByteArray, sourceName: String? = null) {
        val parsed = com.xnotes.format.HelixTheme.parse(String(bytes, Charsets.UTF_8))
        if (parsed == null) {
            message = "Not a usable Helix theme (a self-contained .toml is needed)."
            return
        }
        val file = java.io.File(java.io.File(appContext.filesDir, "theme").apply { mkdirs() }, "code.toml")
        runCatching { file.writeBytes(bytes) }.onFailure {
            message = "Couldn't store the theme file."
            return
        }
        customCodeTheme = parsed
        applyPreferences(settings.prefs.copy(codeThemePath = file.path, codeThemeName = sourceName))
        retheme()
        message = "Code theme imported."
    }

    /** Back to the built-in dark/light code colours. */
    fun resetCodeTheme() {
        settings.prefs.codeThemePath?.let { runCatching { java.io.File(it).delete() } }
        customCodeTheme = null
        applyPreferences(settings.prefs.copy(codeThemePath = null, codeThemeName = null))
        retheme()
    }

    /** Colours changed, classification didn't: republish so frames rebuild with the new theme. */
    private fun retheme() {
        republishFlow(invalidate = true)
        onRender()
    }

    // --- user fonts (Preferences imports .ttf/.otf; notes reference them by name) ---

    val customFonts = mutableStateListOf<com.xnotes.platform.FontCatalog.Choice>().apply {
        addAll(com.xnotes.platform.FontCatalog.customFonts())
    }

    /** Adopt a font file for the pickers; reports via [message]. */
    fun importFont(bytes: ByteArray, sourceName: String?) {
        com.xnotes.platform.FontCatalog.importFont(bytes, sourceName)
            .onSuccess { message = "Font \"${it.id}\" imported." }
            .onFailure { message = it.message ?: "Could not import that font." }
        fontsChanged()
    }

    fun removeCustomFont(face: FontFace) {
        com.xnotes.platform.FontCatalog.removeCustomFont(face)
        fontsChanged()
    }

    /** Font resolution moved under open content: re-shape, re-bake, re-list. */
    private fun fontsChanged() {
        customFonts.clear()
        customFonts.addAll(com.xnotes.platform.FontCatalog.customFonts())
        invalidateComposeFamilies()
        state.document.flow.reshapeAll()
        republishFlow(invalidate = true)
        state.invalidateAllCaches()
        refreshContent()
        onRender()
    }

    val flowHasSelection: Boolean get() = !flowText.selection.collapsed

    fun flowCut() = flowCopySelection(cut = true)

    fun flowCopy() = flowCopySelection(cut = false)

    fun flowDeleteSelection() {
        val sel = flowText.selection.normalized()
        if (!sel.collapsed) flowText.replaceExternal(sel, "")
    }

    private fun flowCopySelection(cut: Boolean) {
        val sel = flowText.selection.normalized()
        if (sel.collapsed) return
        val flow = state.document.flow
        val text = flow.plainText().substring(flow.globalOffset(sel.start), flow.globalOffset(sel.end))
        val cm = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return
        cm.setPrimaryClip(android.content.ClipData.newPlainText("xnotes text", text))
        if (cut) flowText.replaceExternal(sel, "")
    }

    /**
     * Backspace at the start of an EMPTY code line strips the code property (the
     * line becomes plain text) instead of deleting anything. Also the only way out
     * for an empty code line at the very start of the document, where there is no
     * preceding character to merge into. Returns true when it applied.
     */
    fun flowBackspaceSpecial(): Boolean {
        if (!flowText.active) return false
        val sel = flowText.selection.normalized()
        if (!sel.collapsed || sel.start.offset != 0) return false
        val flow = state.document.flow
        val para = flow.paragraphs.getOrNull(sel.start.para) ?: return false
        if (para.codeLang == null || para.length != 0) return false
        flowText.flushBurst()
        flowText.commitEdit(
            FlowEditor(flow).setParaStyle(FlowRange.caret(sel.start)) { it.codeLang = null },
            sel.start,
        )
        return true
    }

    /**
     * Forward delete at the end of a line whose NEXT line is a block (list item,
     * checkbox, code): the line is prepended into the block, which keeps its
     * paragraph properties, instead of the block being flattened into a plain
     * line. Only fires when the current line is not itself a block, so merging
     * two list items keeps the current item's state. Returns true when it applied.
     */
    fun flowForwardDeleteSpecial(): Boolean {
        if (!flowText.active) return false
        val sel = flowText.selection.normalized()
        if (!sel.collapsed) return false
        val flow = state.document.flow
        val para = flow.paragraphs.getOrNull(sel.start.para) ?: return false
        if (sel.start.offset < para.length) return false
        if (para.list != ListKind.NONE || para.codeLang != null) return false
        val next = flow.paragraphs.getOrNull(sel.start.para + 1) ?: return false
        if (next.list == ListKind.NONE && next.codeLang == null) return false
        flowText.flushBurst()
        val range = FlowRange(FlowPos(sel.start.para, para.length), FlowPos(sel.start.para + 1, 0))
        val (cmd, caret) = FlowEditor(flow).replaceRange(range, "", adoptEndProps = true)
        flowText.commitEdit(cmd, caret)
        return true
    }

    private fun flowDeleteKey(forward: Boolean) {
        if (!forward && flowBackspaceSpecial()) return
        if (forward && flowForwardDeleteSpecial()) return
        val sel = flowText.selection.normalized()
        if (!sel.collapsed) {
            flowText.applyReplace(sel, "")
            return
        }
        val flow = state.document.flow
        val g = flow.globalOffset(sel.start)
        val range = if (forward) {
            if (g >= flow.globalOffset(flow.endPos())) return
            FlowRange(sel.start, flow.posAtGlobal(g + 1))
        } else {
            if (g <= 0) return
            FlowRange(flow.posAtGlobal(g - 1), sel.start)
        }
        flowText.applyReplace(range, "")
    }

    private fun flowMoveHorizontal(delta: Int, extend: Boolean) {
        val flow = state.document.flow
        val g = (flow.globalOffset(flowText.selection.end) + delta)
            .coerceIn(0, flow.globalOffset(flow.endPos()))
        flowMoveTo(flow.posAtGlobal(g), extend)
    }

    private fun flowMoveWord(forward: Boolean, extend: Boolean) {
        val flow = state.document.flow
        flowMoveTo(wordBoundary(flow, flowText.selection.end, forward), extend)
    }

    private fun flowDeleteWord(forward: Boolean) {
        val sel = flowText.selection.normalized()
        if (!sel.collapsed) {
            flowText.applyReplace(sel, "")
            return
        }
        val flow = state.document.flow
        val to = wordBoundary(flow, sel.start, forward)
        if (to == sel.start) return
        val range = if (forward) FlowRange(sel.start, to) else FlowRange(to, sel.start)
        flowText.applyReplace(range, "")
    }

    private fun flowMoveVertical(dir: Int, extend: Boolean) {
        val pos = publishedFlow?.frame?.moveVertical(flowText.selection.end, dir) ?: return
        flowMoveTo(pos, extend)
    }

    private fun flowLineEdge(start: Boolean, extend: Boolean) {
        val pos = publishedFlow?.frame?.lineEdge(flowText.selection.end, start) ?: return
        flowMoveTo(pos, extend)
    }

    private fun flowMoveTo(pos: FlowPos, extend: Boolean) {
        if (extend) {
            flowText.setSelection(FlowRange(flowText.selection.start, pos))
        } else {
            flowText.placeCaret(pos)
        }
        flowText.ensureCaretVisible()
    }

    /** Feeder C entry point: route stylus side-button key presses (Bluetooth/USI pens) to the
     *  controller's held latch, and the vendor double-tap/click keycodes to their gesture handlers.
     *  Returns true when consumed, so the host swallows the key. */
    fun onStylusButtonKey(e: android.view.KeyEvent): Boolean {
        if (e.keyCode == penDoubleTapKeycode) return onPenDoubleTapKey(e)
        if (e.keyCode in penButtonTapKeycodes) return onPenButtonTapKey(e)
        val down = when (e.action) {
            android.view.KeyEvent.ACTION_DOWN -> true
            android.view.KeyEvent.ACTION_UP -> false
            else -> return false
        }
        // A canvas is on top: the same key stream drives its pen, so a Bluetooth or USI side
        // button behaves there exactly as it does on a note.
        if (canvasOpen) return infinite.onStylusButtonKey(e.keyCode, down)
        return controller.onStylusButtonKey(e.keyCode, down)
    }

    // Pens with no side button (e.g. Huawei M-Pencil) report a barrel double-tap as a vendor key
    // code with no standard mapping (718). One physical double-tap arrives as two quick presses, so
    // a pair within the window fires the mapped gesture once. Consumed only when the gesture is set.
    private val penDoubleTapKeycode = 718
    private val penDoubleTapMs = 600L
    private var lastPenTapMs = 0L
    private fun onPenDoubleTapKey(e: android.view.KeyEvent): Boolean {
        if (preferences.stylusDoubleTap == "none") return false
        if (e.action == android.view.KeyEvent.ACTION_DOWN) {
            val t = e.eventTime
            if (lastPenTapMs != 0L && t - lastPenTapMs <= penDoubleTapMs) {
                lastPenTapMs = 0L
                dispatchTapGesture(preferences.stylusDoubleTap)
            } else {
                lastPenTapMs = t
            }
        }
        return true
    }

    // Some pens report the side button as a momentary vendor key click, not a held state, so it can't
    // drive the hold latch; fire the mapped gesture on key-down, ignore up. HONOR Magic-Pencil 4s ->
    // 333; Lenovo Tab Pen Plus -> 601 (its one code with a clean down; it also emits 600/603/604 ups).
    private val penButtonTapKeycodes = setOf(333, 601)
    private fun onPenButtonTapKey(e: android.view.KeyEvent): Boolean {
        if (preferences.stylusButtonTap == "none") return false
        if (e.action == android.view.KeyEvent.ACTION_DOWN) dispatchTapGesture(preferences.stylusButtonTap)
        return true
    }

    fun newNote() {
        saveViewState()
        flushAutosave()
        autosaveUri = null
        state.document = blankDocument().also { stampNewNoteDefaults(it) }
        rebuildPdfSource() // close the outgoing note's PDF source (a blank note has none)
        adoptOpenPdf(state.document) // and reclaim its temp PDF file now it's released
        history.clear()
        controller.clearSelection()
        controller.resetGestureState() // drop the outgoing note's fling/elastic so it can't bleed in
        clearPageSelection()
        pageClipboard.clear()
        state.invalidateAllCaches()
        state.relayout()
        installInitialView(null) // a fresh in-memory note: fit width
        refreshContent()
        view.requestRender()
        noteOpen = true // push the editor on top of backstage
    }

    // --- split view ---
    //
    // A split is two editors side by side, one per open file, each with its own toolbar. The primary
    // editor owns the arrangement (the secondary is a plain second instance that knows nothing about
    // it) and also owns the app-level chrome, so the backstage underneath always talks to the
    // primary. Which panes are drawn follows straight from [noteOpen]: both open is a split, one open
    // is that pane full-screen, neither is the backstage. Closing a pane is just its own [goHome].

    /** The second pane's editor while a split is open, else null. Only ever set on the primary. */
    var secondary: Editor? by mutableStateOf(null)
        private set

    /** The other pane's editor, from either side, while a split is open. */
    var sibling: Editor? = null
        private set

    /** The divider position, as the first pane's share of the split axis. */
    var splitRatio by mutableStateOf(0.5f)

    /** The pane that keyboard shortcuts and the file/export actions act on. */
    var focusedPane by mutableStateOf(Pane.PRIMARY)

    /** True while two panes are open together. */
    val inSplit: Boolean get() = secondary?.noteOpen == true && noteOpen

    /** The editor a note-scoped action should run against: the focused pane while both are open,
     *  otherwise whichever pane still has a note. */
    val active: Editor
        get() {
            val other = secondary ?: return this
            if (focusedPane == Pane.SECONDARY && other.noteOpen) return other
            if (!noteOpen && other.noteOpen) return other
            return this
        }

    /** Every pane with a note open, first pane first. Empty on the backstage. */
    val openPanes: List<Editor>
        get() = listOfNotNull(takeIf { it.noteOpen }, secondary?.takeIf { it.noteOpen })

    /** Build the second pane on first use. It shares the temp dirs and the live settings with this
     *  one, and keeps its own document, history, canvas and session slot. */
    fun secondaryPane(): Editor = secondary ?: Editor(viewContext, Pane.SECONDARY).also {
        it.keyActions = keyActions // the shortcuts already resolve their target pane themselves
        it.sibling = this
        sibling = it
        secondaryStarted = false
        secondary = it
    }

    /** Give up on a second pane whose file never opened, so a failed split leaves no stray editor. */
    fun abandonSecondary() {
        val other = secondary ?: return
        if (other.noteOpen) return
        secondaryStarted = true // it is not coming, so let the release go through
        releaseClosedSecondary()
    }

    /** Give [pane] the keyboard and the file actions. */
    fun focusPane(pane: Pane) {
        if (focusedPane != pane) focusedPane = pane
    }

    /** Set once the second pane has actually had a document pushed onto it, so it is not released in
     *  the moment between being built and its file finishing its off-thread read. */
    private var secondaryStarted = false

    /**
     * Drop the second pane once it has no note open, releasing its canvas and GL surfaces. Called by
     * the shell after a pane closes; a no-op while the split is live or still loading.
     */
    fun releaseClosedSecondary() {
        val other = secondary ?: return
        if (other.noteOpen) { secondaryStarted = true; return }
        if (!secondaryStarted) return
        secondaryStarted = false
        other.sibling = null
        sibling = null
        secondary = null
        focusedPane = Pane.PRIMARY
    }

    /** Close every open pane, so the backstage is what's left. */
    fun goHomeAll() {
        secondary?.goHome()
        goHome()
    }

    /** Pop back to backstage: detach the current note (flush autosave, drop the binding) and clear
     *  [noteOpen] so the editor is removed from the stack. The document stays as an inert buffer. */
    fun goHome() {
        if (!noteOpen) return
        // A canvas has none of the paged note's text sessions, autosave binding or thumbnails yet,
        // so leaving one is just popping the layer.
        if (canvasOpen) {
            flushCanvasThen(showOverlay = true) {
                canvasAutosaveUri = null
                canvasOpen = false
                noteOpen = false
            }
            return
        }
        commitText() // commit an open text box before leaving (also hides its keyboard)
        flowText.endSession() // end any live flow caret (flushes typing, hides the keyboard)
        saveViewState() // remember this folder note's view before leaving
        flushThen(showOverlay = true) {
            // Regenerate this folder note's grid tile now that editing is done (off-thread, low priority);
            // a non-folder note (no autosave binding) isn't in the explorer, so there's nothing to do.
            autosaveUri?.let { uri -> autosaveScope.launch { regenerateClosedNoteThumb(uri) } }
            autosaveUri = null
            noteOpen = false
        }
    }
}
