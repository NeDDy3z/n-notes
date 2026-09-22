package com.xnotes.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.LruCache
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.R
import com.xnotes.core.model.Rgba
import com.xnotes.core.util.ReaderKind
import com.xnotes.platform.ReaderHtml
import com.xnotes.platform.ReaderPdf
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipFile

/** A file open in the read-only reader. */
data class ReaderFile(val uri: String, val name: String)

private sealed interface ReaderContent {
    class Html(val body: String, val wide: Boolean) : ReaderContent
    class Pdf(val doc: PdfDoc) : ReaderContent
    object Failed : ReaderContent
}

/** A PdfRenderer is single-threaded and must not render after close, so every use goes through [lock]. */
private class PdfDoc(val pdf: ReaderPdf, val ratios: List<Float>) {
    val lock = Mutex()
    var closed = false
    val pages = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
}

private val LIGHT_BG = Rgba(255, 255, 255)
private val LIGHT_TEXT = Rgba(31, 31, 31)
private val DARK_BG = Rgba(18, 18, 18)
private val DARK_TEXT = Rgba(224, 224, 224)

/**
 * The read-only reader over the explorer: Markdown, Word, PowerPoint and CSV as HTML in a script-less,
 * offline WebView, PDFs as rendered pages. The light/dark choice is an app preference and never touches the file.
 */
@Composable
internal fun ReaderScreen(editor: Editor, file: ReaderFile, onClose: () -> Unit, onWikiLink: (String) -> Unit) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    val prefs = remember(editor.prefsVersion) { editor.preferences }
    val dark = prefs.readerDark ?: palette.isDark
    val background = if (dark) DARK_BG else LIGHT_BG
    val text = if (dark) DARK_TEXT else LIGHT_TEXT
    val labels = ReaderHtml.Labels(
        slide = { n, total -> context.getString(R.string.reader_slide, n, total) },
        notes = stringResource(R.string.reader_notes),
        truncatedRows = { context.getString(R.string.reader_rows_truncated, it) },
        truncatedCells = { r, c -> context.getString(R.string.reader_truncated_cells, r, c) },
        image = stringResource(R.string.reader_image),
        empty = stringResource(R.string.reader_empty),
    )
    BackHandler(onBack = onClose)

    val content by produceState<ReaderContent?>(null, file.uri) {
        value = withContext(Dispatchers.IO) { runCatching { loadReader(context, file, labels) }.getOrNull() ?: ReaderContent.Failed }
        awaitDispose {
            (value as? ReaderContent.Pdf)?.doc?.let { doc ->
                CoroutineScope(Dispatchers.IO).launch { doc.lock.withLock { doc.closed = true; doc.pdf.close() } }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(background.toComposeColor())) {
        ReaderBar(file, dark, onToggleDark = { editor.applyHomePreferences(editor.preferences.copy(readerDark = !dark)) }, onClose)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val c = content) {
                null -> ReaderMessage(stringResource(R.string.loading), text)
                ReaderContent.Failed -> ReaderMessage(stringResource(R.string.reader_open_failed), text)
                is ReaderContent.Html -> {
                    val colors = ReaderHtml.Colors(Rgba.toHex(background), Rgba.toHex(text))
                    val html = remember(c, background, text) { ReaderHtml.page(c.body, colors, c.wide) }
                    ReaderWebView(html, background.toComposeColor()) { url -> openLink(context, url, onWikiLink) }
                }
                // Light shows PDF pages as they are; dark inverts them to the reader colours.
                is ReaderContent.Pdf -> PdfPages(c.doc, background.toComposeColor(), if (dark) duotone(background, text) else null)
            }
        }
    }
}

@Composable
private fun ReaderBar(file: ReaderFile, dark: Boolean, onToggleDark: () -> Unit, onClose: () -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().background(palette.bg.toComposeColor())) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(XnotesIcons.prev, stringResource(R.string.reader_close), tint = palette.text.toComposeColor())
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(file.name, color = palette.text.toComposeColor(), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(XnotesIcons.lock, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(12.dp))
                    Text(stringResource(R.string.reader_read_only), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
                }
            }
            IconButton(onClick = onToggleDark) {
                Icon(
                    if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                    stringResource(if (dark) R.string.reader_switch_to_light else R.string.reader_switch_to_dark),
                    tint = palette.text.toComposeColor(),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border.toComposeColor()))
    }
}

@Composable
private fun ReaderMessage(message: String, text: Rgba) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = text.toComposeColor(), fontSize = 15.sp)
    }
}

@Composable
private fun ReaderWebView(html: String, background: Color, onLink: (String) -> Unit) {
    val onLinkNow = rememberUpdatedState(onLink)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                // Scripts, file access and the network stay off: the page is only the file's own content.
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (url.startsWith("about:blank#")) return false
                        onLinkNow.value(url)
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        // A colour change reloads the same layout, so the old offset still points at the same text.
                        val y = (view.getTag(R.id.reader_scroll) as? Int) ?: return
                        view.postDelayed({ view.scrollTo(0, y) }, 60)
                    }
                }
            }
        },
        update = { wv ->
            wv.setBackgroundColor(background.toArgb())
            if (wv.tag != html) {
                if (wv.tag != null) wv.setTag(R.id.reader_scroll, wv.scrollY)
                wv.tag = html
                wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

private fun openLink(context: Context, url: String, onWikiLink: (String) -> Unit) {
    val uri = Uri.parse(url)
    when (uri.scheme?.lowercase()) {
        ReaderHtml.WIKI_SCHEME -> onWikiLink(Uri.decode(uri.schemeSpecificPart.orEmpty()))
        "http", "https", "mailto", "tel" -> runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Maps a page's white to [background] and its black to [text], so a PDF follows the reader colours. */
private fun duotone(background: Rgba, text: Rgba): ColorFilter {
    fun row(bg: Int, fg: Int): FloatArray {
        val k = (bg - fg) / 255f
        return floatArrayOf(k * 0.299f, k * 0.587f, k * 0.114f, 0f, fg.toFloat())
    }
    val m = row(background.r, text.r) + row(background.g, text.g) + row(background.b, text.b) + floatArrayOf(0f, 0f, 0f, 1f, 0f)
    return ColorFilter.colorMatrix(ColorMatrix(m))
}

/** PDF pages in a column; pinch zooms (and pans sideways), and zoomed-in pages re-render sharper. */
@Composable
private fun PdfPages(doc: PdfDoc, background: Color, filter: ColorFilter?) {
    val palette = LocalPalette.current
    val listState = rememberLazyListState()
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxSize().background(background)) {
        val widthPx = constraints.maxWidth.toFloat()
        fun clampX(x: Float): Float { val limit = (scale - 1f) * widthPx / 2f; return x.coerceIn(-limit, limit) }
        val renderWidth = (widthPx * (if (scale > 1.4f) 2f else 1f)).toInt().coerceIn(1, 3000)
        Box(
            Modifier.fillMaxSize().pointerInput(widthPx) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.changes.count { it.pressed } >= 2) {
                            scale = (scale * e.calculateZoom()).coerceIn(1f, 5f)
                            offsetX = clampX(offsetX + e.calculatePan().x)
                            e.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (scale > 1f) {
                            // One finger pans sideways; the column keeps the vertical scroll.
                            e.changes.firstOrNull()?.let { offsetX = clampX(offsetX + it.positionChange().x) }
                        }
                    } while (e.changes.any { it.pressed })
                }
            },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offsetX },
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(doc.ratios.size) { i ->
                    val key = "$i@$renderWidth"
                    val bmp by produceState(doc.pages.get(key), key) {
                        if (value != null) return@produceState
                        value = withContext(Dispatchers.IO) {
                            doc.lock.withLock {
                                if (doc.closed) null else runCatching { doc.pdf.render(i, renderWidth).asImageBitmap() }.getOrNull()
                            }
                        }?.also { doc.pages.put(key, it) }
                    }
                    // Recoloured pages share the background, so an outline keeps them apart.
                    val edge = if (filter != null) Modifier.border(1.dp, palette.border.toComposeColor()) else Modifier
                    Box(Modifier.fillMaxWidth().aspectRatio(doc.ratios[i]).then(edge).background(if (filter != null) background else Color.White)) {
                        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, colorFilter = filter) }
                    }
                }
            }
        }
        if (doc.ratios.size > 1) {
            Text(
                "${listState.firstVisibleItemIndex + 1} / ${doc.ratios.size}",
                color = palette.text.toComposeColor(),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                    .clip(RoundedCornerShape(10.dp)).background(palette.surface.toComposeColor()).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

private fun loadReader(context: Context, file: ReaderFile, labels: ReaderHtml.Labels): ReaderContent {
    val uri = Uri.parse(file.uri)
    return when (ReaderKind.ofName(file.name)) {
        ReaderKind.MARKDOWN -> ReaderContent.Html(ReaderHtml.markdown(readText(context, uri), labels), wide = false)
        ReaderKind.CSV -> ReaderContent.Html(ReaderHtml.csv(readText(context, uri), labels), wide = true)
        ReaderKind.DOCX -> ReaderContent.Html(withZip(context, uri) { ReaderHtml.docx(it, ::newParser, labels) }, wide = false)
        ReaderKind.PPTX -> ReaderContent.Html(withZip(context, uri) { ReaderHtml.pptx(it, ::newParser, labels) }, wide = false)
        ReaderKind.XLSX -> ReaderContent.Html(withZip(context, uri) { ReaderHtml.xlsx(it, ::newParser, labels) }, wide = true)
        ReaderKind.PDF -> {
            val pdf = ReaderPdf.open(context, uri) ?: return ReaderContent.Failed
            val ratios = runCatching { List(pdf.pageCount) { pdf.ratio(it) } }.getOrNull()
            if (ratios == null || ratios.isEmpty()) { pdf.close(); ReaderContent.Failed } else ReaderContent.Pdf(PdfDoc(pdf, ratios))
        }
        null -> ReaderContent.Failed
    }
}

/** Office XML is read with prefixed names (w:p, r:id), which the converters match on. */
private fun newParser(): XmlPullParser =
    android.util.Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }

private fun <T> withZip(context: Context, uri: Uri, block: (ReaderHtml.ZipRead) -> T): T {
    val temp = File.createTempFile("reader", ".zip", context.cacheDir)
    try {
        context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalStateException("cannot read file")
        return ZipFile(temp).use { zip ->
            block(ReaderHtml.ZipRead { path -> zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } } })
        }
    } finally {
        temp.delete()
    }
}

/** UTF-8, falling back to the local legacy code page for files that are not (Excel's CSV export). */
private fun readText(context: Context, uri: Uri): String {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("cannot read file")
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        val legacy = if (Locale.getDefault().language in setOf("cs", "sk", "pl", "hu", "sl", "hr")) "windows-1250" else "windows-1252"
        String(bytes, Charset.forName(legacy))
    }
}
