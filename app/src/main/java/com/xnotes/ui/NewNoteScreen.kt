package com.xnotes.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xnotes.R
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.PageTemplates
import com.xnotes.core.model.Rgba
import com.xnotes.core.template.Template
import com.xnotes.core.template.TemplateKind
import com.xnotes.core.template.TemplateValues
import com.xnotes.platform.TemplateLibrary
import com.xnotes.settings.Preferences
import com.xnotes.settings.SwatchRole
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/** Which sheet the screen is styling. */
private enum class Face { PAGES, COVER }

/** New notes are made at the default resolution, so lengths turn into px at it. */
private const val DPI = PageSize.DEFAULT_DPI

/** A length in mm as it is typed: no ".0" on a whole number. */
private fun mmText(v: Double): String =
    if (v == Math.floor(v)) v.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)

/** [s]'s parameter values for [t], its spacing turned back from content px into mm. */
private fun valuesOf(s: PageStyle, t: Template?): TemplateValues {
    val numbers = HashMap<String, Double>()
    s.params?.let(numbers::putAll)
    val sp = t?.spacingParam
    s.spacing?.let { px -> if (sp != null) numbers[sp.name] = px * 25.4 / DPI }
    return TemplateValues(numbers, s.colors ?: emptyMap())
}

/**
 * The full-screen New note. The note sits on the left: its cover and its pages side by side (a tap
 * picks which one to style), their size and orientation, then the picked one's colours and
 * settings. The gallery sits on the right with Pages and Cover tabs. Narrow screens stack the same
 * parts and move the settings into a bottom sheet. Nothing is written until Create.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun NewNoteScreen(
    editor: Editor,
    folderName: String,
    initialName: String,
    error: String?,
    busy: Boolean,
    onImportTemplate: () -> Unit,
    onTemplateFromImage: (TemplateKind) -> Unit,
    onCreate: (String, Editor.NewNoteSetup) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val palette = LocalPalette.current
        val text = palette.text.toComposeColor()
        val dim = palette.textDim.toComposeColor()
        val accent = palette.accent.toComposeColor()
        val border = palette.border.toComposeColor()
        val prefs = editor.preferences

        var name by remember { mutableStateOf(TextFieldValue(initialName, TextRange(0, initialName.length))) }
        var size by remember { mutableStateOf(prefs.defaultPageSize) }
        var orientation by remember { mutableStateOf(prefs.defaultPageOrientation) }
        var customW by remember { mutableStateOf(mmText(prefs.customPageWidthMm)) }
        var customH by remember { mutableStateOf(mmText(prefs.customPageHeightMm)) }
        var style by remember { mutableStateOf(editor.newNoteStyle) }
        val version = TemplateLibraryUi.version
        val all = remember(version) { TemplateLibrary.all() }
        val pages = remember(all) { all.filter { it.template.kind == TemplateKind.PAGE } }
        val covers = remember(all) { all.filter { it.template.kind == TemplateKind.COVER } }
        var coverOn by remember { mutableStateOf(false) }
        var coverStyle by remember { mutableStateOf(PageStyle(template = covers.firstOrNull()?.key)) }
        var face by remember { mutableStateOf(Face.PAGES) }
        var tag by remember { mutableStateOf<String?>(null) }

        // A template the library gains while this is open was imported from here: select it.
        var handled by remember { mutableStateOf(editor.adoptedTemplate) }
        val adopted = editor.adoptedTemplate
        LaunchedEffect(adopted, all) {
            if (adopted == null || adopted == handled) return@LaunchedEffect
            val e = all.firstOrNull { it.key == adopted } ?: return@LaunchedEffect
            handled = adopted
            if (e.template.kind == TemplateKind.COVER) {
                coverStyle = PageStyle(template = e.key)
                coverOn = true
                face = Face.COVER
            } else {
                style = style.withTemplate(e.key, PageTemplates.NONE)
                face = Face.PAGES
            }
        }

        val custom = size == PageSize.CUSTOM
        val range = Preferences.CUSTOM_PAGE_MIN_MM..Preferences.CUSTOM_PAGE_MAX_MM
        val cw = customW.toDoubleOrNull()?.takeIf { it in range }
        val ch = customH.toDoubleOrNull()?.takeIf { it in range }
        val pageMm = when {
            custom -> (cw ?: prefs.customPageWidthMm) to (ch ?: prefs.customPageHeightMm)
            orientation == Orientation.PORTRAIT -> size.shortMm to size.longMm
            else -> size.longMm to size.shortMm
        }
        val portrait = pageMm.first <= pageMm.second
        val canCreate = !busy && (!custom || (cw != null && ch != null))

        val look = TemplateLook.of(style, null, palette.paper)
        val pageKey = style.template ?: PageTemplates.NONE
        val pageT = pages.firstOrNull { it.key == pageKey }?.template
        val coverKey = coverStyle.template
        val coverT = covers.firstOrNull { it.key == coverKey }?.template
        val hasCover = coverOn && coverT != null

        fun create() {
            if (!canCreate) return
            onCreate(name.text, Editor.NewNoteSetup(pageMm.first, pageMm.second, style, coverStyle.takeIf { hasCover }))
        }

        fun setOrientation(o: Orientation) {
            if (custom && (o == Orientation.PORTRAIT) != portrait) {
                val w = customW
                customW = customH
                customH = w
            }
            orientation = o
        }

        fun pickPage(key: String) {
            style = style.withTemplate(key, PageTemplates.NONE)
            face = Face.PAGES
        }

        fun pickCover(key: String) {
            if (coverKey != key) coverStyle = PageStyle(template = key)
            coverOn = true
            face = Face.COVER
        }

        @Composable
        fun Thumb(t: Template?, key: String, values: TemplateValues, w: Dp, h: Dp, keep: Boolean) {
            Box(Modifier.size(w, h).background(look.paper.toComposeColor())) {
                if (t != null) {
                    val spec = if (keep) {
                        TemplateThumbs.spec(key, pageMm, look.ink, look.accent, look.paper, values, thumbPx(w))
                    } else {
                        TemplateThumbs.Spec(key, pageMm, look.ink, look.accent, look.paper, values.numbers, values.colors, thumbPx(w))
                    }
                    rememberTemplateThumb(t, spec, keep)?.let { Image(it, contentDescription = t.name, Modifier.size(w, h)) }
                }
            }
        }

        @Composable
        fun Sheet(label: String, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
            Column(
                Modifier
                    .clip(MaterialTheme.shapes.small)
                    .semantics { this.selected = selected }
                    .clickable(onClick = onClick)
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.border(2.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(5.dp)).padding(4.dp)) { content() }
                Text(label, color = if (selected) accent else dim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }

        @Composable
        fun Dashed(w: Dp, h: Dp, content: @Composable ColumnScope.() -> Unit) {
            Column(
                Modifier.size(w, h).drawBehind {
                    drawRoundRect(
                        color = border.copy(alpha = 1f),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                    )
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                content = content,
            )
        }

        @Composable
        fun MmField(value: String, label: String, onChange: (String) -> Unit) {
            val ok = value.toDoubleOrNull()?.let { it in range } == true
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = text, fontSize = 14.sp, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                modifier = Modifier
                    .width(64.dp)
                    .height(40.dp)
                    .semantics { contentDescription = label }
                    .border(1.dp, if (ok) border else palette.danger.toComposeColor(), MaterialTheme.shapes.small)
                    .background(palette.menuBg.toComposeColor(), MaterialTheme.shapes.small)
                    .padding(vertical = 10.dp),
            )
        }

        @Composable
        fun OrientationButton(o: Orientation, desc: String) {
            val on = (o == Orientation.PORTRAIT) == portrait
            Box(
                Modifier
                    .size(44.dp, 38.dp)
                    .background(if (on) palette.selectionBackground.toComposeColor() else Color.Transparent)
                    .semantics { contentDescription = desc; selected = on }
                    .clickable { setOrientation(o) },
                contentAlignment = Alignment.Center,
            ) {
                val fg = if (on) palette.selectionForeground.toComposeColor() else dim
                Box(Modifier.size(if (o == Orientation.PORTRAIT) 11.dp else 17.dp, if (o == Orientation.PORTRAIT) 17.dp else 11.dp).border(2.dp, fg, RoundedCornerShape(2.dp)))
            }
        }

        @Composable
        fun FormatRow() {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    var open by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .height(40.dp)
                            .clip(MaterialTheme.shapes.small)
                            .border(1.dp, border, MaterialTheme.shapes.small)
                            .background(palette.menuBg.toComposeColor())
                            .clickable { open = true }
                            .padding(start = 14.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(pageSizeLabel(size), color = text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        if (!custom) Text(stringResource(R.string.page_size_mm, mmText(pageMm.first.roundToInt().toDouble()), mmText(pageMm.second.roundToInt().toDouble())), color = dim, fontSize = 13.sp)
                        Icon(XnotesIcons.chevronDown, null, tint = dim, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        for (s in PageSize.entries.filter { it != PageSize.CUSTOM } + PageSize.CUSTOM) {
                            val dims = if (s == PageSize.CUSTOM) "" else {
                                val (a, b) = if (portrait) s.shortMm to s.longMm else s.longMm to s.shortMm
                                stringResource(R.string.page_size_mm, mmText(a.roundToInt().toDouble()), mmText(b.roundToInt().toDouble()))
                            }
                            DropdownMenuItem(
                                text = {
                                    Row(Modifier.widthIn(min = 220.dp)) {
                                        Text(pageSizeLabel(s), Modifier.weight(1f))
                                        Text(dims, color = dim)
                                    }
                                },
                                leadingIcon = {
                                    if (s == size) Icon(XnotesIcons.check, null, tint = accent, modifier = Modifier.size(18.dp)) else Spacer(Modifier.size(18.dp))
                                },
                                onClick = {
                                    if (s == PageSize.CUSTOM && !custom) {
                                        customW = mmText(pageMm.first)
                                        customH = mmText(pageMm.second)
                                    }
                                    size = s
                                    open = false
                                },
                            )
                        }
                    }
                }
                if (custom) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MmField(customW, stringResource(R.string.width_mm)) { customW = it }
                        Text("×", color = dim, fontSize = 13.sp)
                        MmField(customH, stringResource(R.string.height_mm)) { customH = it }
                        Text("mm", color = dim, fontSize = 13.sp)
                    }
                }
                Row(Modifier.clip(MaterialTheme.shapes.small).border(1.dp, border, MaterialTheme.shapes.small)) {
                    OrientationButton(Orientation.PORTRAIT, stringResource(R.string.orientation_portrait))
                    Box(Modifier.width(1.dp).height(38.dp).background(border))
                    OrientationButton(Orientation.LANDSCAPE, stringResource(R.string.orientation_landscape))
                }
            }
        }

        @Composable
        fun Sheets(maxW: Dp, maxH: Dp) {
            val aspect = (pageMm.second / pageMm.first).toFloat()
            var w = maxW
            var h = maxW * aspect
            if (h > maxH) {
                h = maxH
                w = maxH / aspect
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Bottom,
            ) {
                Sheet(stringResource(if (hasCover) R.string.cover else R.string.no_cover), face == Face.COVER, {
                    face = Face.COVER
                    if (!coverOn && coverT != null) coverOn = true
                }) {
                    if (hasCover) {
                        Thumb(coverT, coverKey!!, valuesOf(coverStyle, coverT), w, h, keep = false)
                    } else {
                        Dashed(w, h) {
                            Icon(XnotesIcons.plus, null, tint = dim, modifier = Modifier.size(22.dp))
                            Text(stringResource(R.string.add_cover), color = dim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Sheet(stringResource(R.string.pages), face == Face.PAGES, { face = Face.PAGES }) {
                    Thumb(pageT, pageKey, valuesOf(style, pageT), w, h, keep = false)
                }
            }
        }

        @Composable
        fun ColourRow(caption: String, role: SwatchRole, value: Rgba?, onDefault: () -> Unit, onPick: (Rgba) -> Unit) {
            Spacer(Modifier.size(12.dp))
            StyleCaption(caption)
            Spacer(Modifier.size(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ModeChip(stringResource(R.string.default_choice), value == null, onDefault)
                SwatchCircles(editor, role, value, onPick)
            }
        }

        @Composable
        fun Tune() {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(if (face == Face.PAGES) R.string.every_page else R.string.cover), color = dim, fontSize = 12.sp)
                        Text(
                            when {
                                face == Face.PAGES -> pageT?.name ?: stringResource(R.string.plain_paper)
                                hasCover -> coverT!!.name
                                else -> stringResource(R.string.no_cover)
                            },
                            color = text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        )
                    }
                    ModeChip(stringResource(R.string.reset), false) {
                        if (face == Face.PAGES) {
                            style = PageStyle(template = style.template)
                        } else {
                            coverStyle = PageStyle(template = coverStyle.template)
                        }
                    }
                }
                if (face == Face.PAGES) {
                    ColourRow(stringResource(R.string.caption_paper), SwatchRole.PAPER, style.pageColor, { style = style.copy(pageColor = null) }) {
                        style = style.copy(pageColor = it)
                    }
                    if (pageT == null) {
                        Spacer(Modifier.size(12.dp))
                        Text(stringResource(R.string.pick_template_hint), color = dim, fontSize = 13.sp)
                    } else {
                        // Circles hold the hue at full strength; the opacity slider sets the alpha.
                        ColourRow(stringResource(R.string.caption_pattern_colour), SwatchRole.LINES, style.patternColor?.copy(a = 255), {
                            style = style.copy(patternColor = null)
                        }) { style = style.copy(patternColor = it.copy(a = look.ink.a)) }
                        Spacer(Modifier.size(12.dp))
                        val pct = look.ink.a * 100f / 255f
                        StyleCaption(stringResource(R.string.caption_opacity_percent, pct.roundToInt()))
                        Slider(
                            value = pct,
                            onValueChange = { v -> style = style.copy(patternColor = look.ink.copy(a = (v / 100f * 255f).roundToInt().coerceIn(0, 255))) },
                            valueRange = 0f..100f,
                        )
                        if (pageT.usesAccent) {
                            ColourRow(stringResource(R.string.caption_accent_colour), SwatchRole.ACCENT, style.accentColor?.copy(a = 255), {
                                style = style.copy(accentColor = null)
                            }) { style = style.copy(accentColor = it.copy(a = look.accent.a)) }
                        }
                        TemplateParamControls(pageT, style, null, DPI, editor) { style = it }
                    }
                } else if (hasCover) {
                    TemplateParamControls(coverT!!, coverStyle, null, DPI, editor) { coverStyle = it }
                } else {
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.pick_cover_hint), color = dim, fontSize = 13.sp)
                }
                Spacer(Modifier.size(24.dp))
            }
        }

        @Composable
        fun Tabs() {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp)) {
                for (f in Face.entries) {
                    val on = face == f
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .semantics { selected = on }
                            .clickable { face = f }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(if (f == Face.PAGES) R.string.pages else R.string.cover),
                                color = if (on) accent else dim, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            )
                            if (f == Face.COVER && !hasCover) {
                                Text(
                                    stringResource(R.string.cover_off), color = dim, fontSize = 11.sp,
                                    modifier = Modifier.background(palette.surface.toComposeColor(), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                        if (on) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(accent, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                    }
                }
            }
        }

        @Composable
        fun Tile(label: String, selected: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)?, thumb: @Composable (Dp, Dp) -> Unit) {
            var menu by remember { mutableStateOf(false) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraSmall)
                    .semantics { this.selected = selected }
                    .combinedClickable(onClick = onClick, onLongClick = onRemove?.let { { menu = true } }),
            ) {
                BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio((pageMm.first / pageMm.second).toFloat())) {
                    val w = maxWidth
                    val h = maxHeight
                    Box(
                        Modifier
                            .fillMaxSize()
                            .border(if (selected) 2.dp else 1.dp, if (selected) accent else border, RoundedCornerShape(3.dp))
                            .padding(if (selected) 2.dp else 1.dp),
                    ) { thumb(w - 4.dp, h - 4.dp) }
                    if (selected) {
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).background(accent, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(XnotesIcons.check, null, tint = palette.onAccent.toComposeColor(), modifier = Modifier.size(14.dp)) }
                    }
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    label, color = if (selected) palette.selectionForeground.toComposeColor() else text,
                    fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.remove_template)) }, onClick = { menu = false; onRemove?.invoke() })
                }
            }
        }

        @Composable
        fun ActionTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
            Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraSmall).clickable(onClick = onClick)) {
                BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio((pageMm.first / pageMm.second).toFloat())) {
                    Dashed(maxWidth, maxHeight) { Icon(icon, null, tint = dim, modifier = Modifier.size(26.dp)) }
                }
                Spacer(Modifier.size(8.dp))
                Text(label, color = text, fontSize = 12.sp, maxLines = 2)
            }
        }

        // The grid's builder is not composable, so its headings are looked up here.
        val sBuiltIn = stringResource(R.string.templates_built_in)
        val sExamples = stringResource(R.string.templates_examples)
        val sImported = stringResource(R.string.templates_imported)
        val sImportedHelp = stringResource(R.string.templates_imported_help)
        val sDesigns = stringResource(R.string.cover_designs)

        fun LazyGridScope.heading(title: String, count: Int?, help: String? = null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 18.dp, bottom = 2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, color = dim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        if (count != null) Text(count.toString(), color = dim.copy(alpha = 0.75f), fontSize = 13.sp)
                    }
                    if (help != null) Text(help, color = dim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }

        fun LazyGridScope.gallery() {
            if (face == Face.PAGES) {
                val tags = pages.flatMap { it.template.tags }.groupingBy { it }.eachCount().filter { it.value >= 2 }
                    .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeChip(stringResource(R.string.tag_all), tag == null) { tag = null }
                        for (t in tags) ModeChip(t.replaceFirstChar { it.uppercase() }, tag == t) { tag = t }
                    }
                }
                fun shown(e: TemplateLibrary.Entry) = tag == null || tag in e.template.tags
                val builtIn = pages.filter { it.source == TemplateLibrary.Source.BUILT_IN && shown(it) }
                val examples = pages.filter { it.source == TemplateLibrary.Source.BUNDLED && shown(it) }
                val imported = pages.filter { it.source == TemplateLibrary.Source.IMPORTED && shown(it) }
                if (tag == null || builtIn.isNotEmpty()) {
                    heading(sBuiltIn, builtIn.size + if (tag == null) 1 else 0)
                    if (tag == null) item {
                        Tile(stringResource(R.string.none), pageKey == PageTemplates.NONE, { pickPage(PageTemplates.NONE) }, null) { w, h -> Thumb(null, PageTemplates.NONE, TemplateValues.DEFAULTS, w, h, keep = true) }
                    }
                    items(builtIn, key = { it.key }) { e ->
                        Tile(e.template.name, pageKey == e.key, { pickPage(e.key) }, null) { w, h -> Thumb(e.template, e.key, TemplateValues.DEFAULTS, w, h, keep = true) }
                    }
                }
                if (examples.isNotEmpty()) {
                    heading(sExamples, examples.size)
                    items(examples, key = { it.key }) { e ->
                        Tile(e.template.name, pageKey == e.key, { pickPage(e.key) }, null) { w, h -> Thumb(e.template, e.key, TemplateValues.DEFAULTS, w, h, keep = true) }
                    }
                }
                heading(sImported, null, sImportedHelp)
                items(imported, key = { it.key }) { e ->
                    Tile(e.template.name, pageKey == e.key, { pickPage(e.key) }, { editor.removeTemplate(e.key) }) { w, h ->
                        Thumb(e.template, e.key, TemplateValues.DEFAULTS, w, h, keep = true)
                    }
                }
                item { ActionTile(XnotesIcons.importDoc, stringResource(R.string.import_template_file), onImportTemplate) }
                item { ActionTile(XnotesIcons.image, stringResource(R.string.page_from_image)) { onTemplateFromImage(TemplateKind.PAGE) } }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(palette.menuBg.toComposeColor(), RoundedCornerShape(12.dp))
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(stringResource(R.string.start_with_cover), color = text, fontSize = 15.sp)
                            Text(stringResource(R.string.start_with_cover_help), color = dim, fontSize = 12.sp)
                        }
                        Switch(checked = hasCover, onCheckedChange = { on ->
                            if (on && coverT == null) coverStyle = PageStyle(template = covers.firstOrNull()?.key)
                            coverOn = on
                        })
                    }
                }
                val bundled = covers.filter { it.source != TemplateLibrary.Source.IMPORTED }
                val imported = covers.filter { it.source == TemplateLibrary.Source.IMPORTED }
                heading(sDesigns, bundled.size)
                items(bundled, key = { it.key }) { e ->
                    Tile(e.template.name, hasCover && coverKey == e.key, { pickCover(e.key) }, null) { w, h ->
                        Thumb(e.template, e.key, TemplateValues.DEFAULTS, w, h, keep = true)
                    }
                }
                heading(sImported, null, sImportedHelp)
                items(imported, key = { it.key }) { e ->
                    Tile(e.template.name, hasCover && coverKey == e.key, { pickCover(e.key) }, { editor.removeTemplate(e.key) }) { w, h ->
                        Thumb(e.template, e.key, TemplateValues.DEFAULTS, w, h, keep = true)
                    }
                }
                item { ActionTile(XnotesIcons.importDoc, stringResource(R.string.import_template_file), onImportTemplate) }
                item { ActionTile(XnotesIcons.image, stringResource(R.string.cover_from_image)) { onTemplateFromImage(TemplateKind.COVER) } }
            }
        }

        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

        Surface(Modifier.fillMaxSize(), color = palette.panel.toComposeColor()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val screenW = maxWidth
                val wide = screenW >= 720.dp
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().height(76.dp).padding(start = 8.dp, end = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(onClick = onClose) { Icon(XnotesIcons.close, stringResource(R.string.close_new_note), tint = text) }
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.new_note_in, folderName), color = dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            BasicTextField(
                                value = name,
                                onValueChange = { name = it },
                                singleLine = true,
                                textStyle = TextStyle(color = text, fontSize = 22.sp),
                                cursorBrush = SolidColor(accent),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { create() }),
                                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().focusRequester(focus).padding(vertical = 4.dp),
                            )
                            Box(Modifier.widthIn(max = 480.dp).fillMaxWidth().height(2.dp).background(accent))
                            if (error != null) Text(error, color = palette.danger.toComposeColor(), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        Button(
                            onClick = { create() },
                            enabled = canCreate,
                            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = palette.onAccent.toComposeColor()),
                        ) {
                            Icon(XnotesIcons.check, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.create), fontWeight = FontWeight.Medium)
                        }
                    }
                    HorizontalDivider(color = border)
                    if (wide) {
                        val stageW = (screenW * 0.38f).coerceIn(340.dp, 520.dp)
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            Column(
                                Modifier.width(stageW).fillMaxHeight().background(palette.bg.toComposeColor()).padding(horizontal = 24.dp, vertical = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Sheets((stageW - 48.dp - 16.dp - 24.dp) / 2, 300.dp)
                                FormatRow()
                                HorizontalDivider(color = border)
                                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Tune() }
                            }
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                Tabs()
                                HorizontalDivider(color = border)
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(112.dp),
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 40.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) { gallery() }
                            }
                        }
                    } else {
                        var tuneOpen by remember { mutableStateOf(false) }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(100.dp),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(top = 12.dp).background(palette.bg.toComposeColor(), RoundedCornerShape(12.dp)).padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Sheets(120.dp, 170.dp)
                                        FormatRow()
                                    }
                                }
                                item(span = { GridItemSpan(maxLineSpan) }) { Tabs() }
                                gallery()
                            }
                            Row(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(palette.menuBg.toComposeColor(), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                                    .clickable { tuneOpen = true }
                                    .padding(horizontal = 20.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        when {
                                            face == Face.PAGES -> pageT?.name ?: stringResource(R.string.plain_paper)
                                            hasCover -> coverT!!.name
                                            else -> stringResource(R.string.no_cover)
                                        },
                                        color = text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                    )
                                    Text(stringResource(if (face == Face.PAGES) R.string.every_page else R.string.cover), color = dim, fontSize = 12.sp)
                                }
                                Text(stringResource(R.string.customize), color = accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        if (tuneOpen) {
                            ModalBottomSheet(onDismissRequest = { tuneOpen = false }, containerColor = palette.menuBg.toComposeColor()) {
                                Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) { Tune() }
                            }
                        }
                    }
                }
            }
        }
    }
}
