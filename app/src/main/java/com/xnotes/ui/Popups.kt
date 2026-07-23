package com.xnotes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.xnotes.canvas.ViewOverrides
import com.xnotes.canvas.ViewSettings
import com.xnotes.canvas.ViewingMode
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontFace
import com.xnotes.core.tools.EraseMode
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.tools.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConversions
import com.xnotes.platform.FontCatalog
import com.xnotes.settings.Preferences
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/**
 * Stroke-tool configuration popup (spec 10 §3): PRESSURE / SENSITIVITY, then the
 * tool's signature control: MULTIPLIER (calligraphy), SPEED (speed pen) or
 * TIP WIDTH PERCENTAGE (taper pen), then WIDTH, and a NEON toggle (with INTENSITY) on
 * any stroke tool but the highlighter.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolConfigPopup(editor: Editor, tool: Tool, onDismiss: () -> Unit) {
    val base = remember { editor.toolConfig(tool) }
    var pressure by remember { mutableStateOf(base.pressureEnabled) }
    var sensitivity by remember { mutableStateOf(ToolConversions.minFactorToSensitivity(base.pressureMinFactor).toFloat()) }
    var multiplier by remember { mutableStateOf(ToolConversions.directionStrengthToMultiplier(base.directionStrength).toFloat()) }
    var speed by remember { mutableStateOf(ToolConversions.strengthToSpeed(base.speedStrength).toFloat()) }
    var taperTip by remember { mutableStateOf((base.taperMinFactor * 100).toFloat()) }
    var width by remember { mutableStateOf(base.baseWidth.toFloat()) }
    var glow by remember { mutableStateOf(base.neon) }
    var glowIntensity by remember { mutableStateOf(ToolConversions.neonStrengthToIntensity(base.neonStrength).toFloat()) }
    var dashLen by remember { mutableStateOf(base.dashLength.toFloat()) }
    var gapLen by remember { mutableStateOf(base.dashGap.toFloat()) }
    var straight by remember { mutableStateOf(base.straightLine) }
    var scale by remember { mutableStateOf(base.scale) }
    var intensity by remember { mutableStateOf(ToolConversions.highlighterAlphaToIntensity(base.highlighterAlpha).toFloat()) }
    var colorOverride by remember { mutableStateOf(base.colorOverride) }

    fun emit() {
        val m = ToolConversions.sensitivityToMinFactor(sensitivity.toDouble())
        val ds = if (tool == Tool.CALLIGRAPHY) ToolConversions.multiplierToDirectionStrength(multiplier.toDouble()) else 0.0
        val sp = if (tool == Tool.SPEED) ToolConversions.speedToStrength(speed.toDouble()) else 0.0
        val tmf = if (tool == Tool.TAPER) taperTip.toDouble() / 100.0 else base.taperMinFactor
        val ha = if (tool == Tool.HIGHLIGHTER) ToolConversions.intensityToHighlighterAlpha(intensity.toDouble()) else base.highlighterAlpha
        editor.updateToolConfig(
            tool,
            base.copy(
                baseWidth = width.toDouble(),
                pressureEnabled = pressure,
                pressureMinFactor = m,
                directionStrength = ds,
                speedStrength = sp,
                taperMinFactor = tmf,
                neon = glow,
                neonStrength = ToolConversions.intensityToNeonStrength(glowIntensity.toDouble()),
                dashLength = dashLen.toDouble(),
                dashGap = gapLen.toDouble(),
                straightLine = straight,
                scale = scale,
                highlighterAlpha = ha,
                colorOverride = colorOverride,
            ),
        )
    }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(250.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(tool.name)
            // COLOUR override: "Default" follows the toolbar's active ink colour; pick a hue to pin
            // this tool to it regardless of the toolbar selection.
            StyleCaption("COLOUR")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip("Default", colorOverride == null) { colorOverride = null; emit() }
                ColorPickerDot(
                    colorOverride,
                    custom = colorOverride != null,
                    onPick = { colorOverride = it; emit() },
                    dismissOnPick = false,
                ) { d, p ->
                    ColorPickerPopup(
                        initial = colorOverride ?: editor.toolbarColors.getOrNull(editor.activeColorIndex),
                        recents = editor.recentColors,
                        onDismiss = d,
                        onPick = p,
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            val hasPressure = tool == Tool.PEN || tool == Tool.CALLIGRAPHY || tool == Tool.SPEED || tool == Tool.TAPER
            if (hasPressure) {
                ToggleRow("PRESSURE", pressure) { pressure = it; emit() }
                SliderRow("SENSITIVITY", sensitivity, 0f..100f, enabled = pressure) { sensitivity = it; emit() }
            }
            if (tool == Tool.CALLIGRAPHY) {
                SliderRow("MULTIPLIER", multiplier, 1f..5f) { multiplier = it; emit() }
            }
            if (tool == Tool.SPEED) {
                SliderRow("SPEED", speed, 0f..100f) { speed = it; emit() }
            }
            if (tool == Tool.TAPER) {
                SliderRow("TIP WIDTH PERCENTAGE", taperTip, 0f..100f) { taperTip = it; emit() }
            }
            val range = ToolConversions.widthRange(tool)
            SliderRow("WIDTH", width, range.start.toFloat()..range.endInclusive.toFloat()) { width = it; emit() }
            // SCALE off: ink keeps a constant on-screen thickness whatever zoom you draw at.
            ToggleRow("SCALE", scale) { scale = it; emit() }
            if (tool == Tool.DASHED) {
                SliderRow("DASH", dashLen, 2f..40f) { dashLen = it; emit() }
                SliderRow("GAP", gapLen, 2f..40f) { gapLen = it; emit() }
            }
            // The highlighter's strength (translucency) and an optional straight-segment lock
            // (for ruling/underlining).
            if (tool == Tool.HIGHLIGHTER) {
                SliderRow("INTENSITY", intensity, 10f..90f) { intensity = it; emit() }
                ToggleRow("STRAIGHT LINE", straight) { straight = it; emit() }
            }
            // Glow is offered on every stroke tool except the highlighter (translucent) and the
            // dashed pen (it draws a line, not a fillable ribbon, so a halo has nothing to hug).
            if (tool.isStroke && tool != Tool.HIGHLIGHTER && tool != Tool.DASHED) {
                ToggleRow("NEON", glow) { glow = it; emit() }
                if (glow) {
                    SliderRow("INTENSITY", glowIntensity, 0f..100f) { glowIntensity = it; emit() }
                }
            }
        }
    }
}

/**
 * Page-styles popup (spec 10): two tabs — "All Pages" (the document-wide override) and "Current
 * Page" — each editing the same controls: paper colour, a ruling (None/Lines/Dots/Grid), its spacing
 * and colour. Every control is tri-state: "Default" leaves the field unset so it inherits the level
 * below (page → document → the global page-colour preference / a built-in default); the global
 * default itself is unchanged here (it lives in Preferences). Like [ToolConfigPopup], the popup holds
 * the edited style locally and pushes each change to the [Editor] (which persists, but never undoes).
 * The All Pages tab also offers making its style the default stamped onto new notes, plus a Reset
 * back to all-Default.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StylesPopup(editor: Editor, onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(0) } // 0 = All Pages, 1 = Current Page
    var docStyle by remember { mutableStateOf(editor.documentStyle) }
    var pageStyle by remember { mutableStateOf(editor.currentPageStyle) }
    val style = if (tab == 0) docStyle else pageStyle
    // "Default for new notes" shows once the All Pages style differs from the saved new-note
    // default, and stays for the rest of the popup session (so it doesn't vanish when checked).
    // An all-Default style hides it regardless (e.g. after Reset): there is nothing to save.
    var showNewNoteRow by remember { mutableStateOf(editor.documentStyle != editor.newNoteStyle) }
    fun apply(next: PageStyle) {
        if (tab == 0) {
            docStyle = next; editor.setDocumentStyle(next)
            if (next != editor.newNoteStyle) showNewNoteRow = true
        } else { pageStyle = next; editor.setCurrentPageStyle(next) }
    }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(286.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle("STYLES")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip("All Pages", tab == 0) { tab = 0 }
                ModeChip("Current Page", tab == 1) { tab = 1 }
            }

            Spacer(Modifier.size(12.dp))
            StyleCaption("PAGE COLOUR")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ModeChip("Default", style.pageColor == null) { apply(style.copy(pageColor = null)) }
                pageColorPresets.forEach { c ->
                    ColorDot(c.toComposeColor(), style.pageColor == c) { apply(style.copy(pageColor = c)) }
                }
                ColorPickerDot(
                    style.pageColor,
                    custom = style.pageColor != null && style.pageColor !in pageColorPresets,
                    onPick = { apply(style.copy(pageColor = it)) },
                    dismissOnPick = false,
                ) { d, p -> PageColorGridPopup(style.pageColor, d, p) }
            }

            Spacer(Modifier.size(12.dp))
            StyleCaption("PATTERN")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ModeChip("Default", style.pattern == null) { apply(style.copy(pattern = null)) }
                ModeChip("None", style.pattern == PagePattern.NONE) { apply(style.copy(pattern = PagePattern.NONE)) }
                ModeChip("Lines", style.pattern == PagePattern.LINES) { apply(style.copy(pattern = PagePattern.LINES)) }
                ModeChip("Dots", style.pattern == PagePattern.DOTS) { apply(style.copy(pattern = PagePattern.DOTS)) }
                ModeChip("Grid", style.pattern == PagePattern.GRID) { apply(style.copy(pattern = PagePattern.GRID)) }
            }

            Spacer(Modifier.size(12.dp))
            val spacing = style.spacing ?: PageStyle.DEFAULT_SPACING
            StyleCaption("SPACING  ${spacing.toInt()} px" + if (style.spacing == null) "  (default)" else "")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("Default", style.spacing == null) { apply(style.copy(spacing = null)) }
                Slider(
                    value = spacing.toFloat().coerceIn(PageStyle.MIN_SPACING.toFloat(), PageStyle.MAX_SPACING.toFloat()),
                    onValueChange = { apply(style.copy(spacing = it.toDouble())) },
                    valueRange = PageStyle.MIN_SPACING.toFloat()..PageStyle.MAX_SPACING.toFloat(),
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.size(12.dp))
            // Effective pattern colour: the page's own, else (on the Current Page tab) the document's,
            // else the built-in grey. Its alpha is the opacity the slider below edits.
            val effPatternColor = style.patternColor
                ?: (if (tab == 1) docStyle.patternColor else null)
                ?: PageStyle.DEFAULT_PATTERN_COLOR
            StyleCaption("PATTERN COLOUR")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip("Default", style.patternColor == null) { apply(style.copy(patternColor = null)) }
                ColorPickerDot(
                    style.patternColor?.copy(a = 255), // show the hue at full strength; OPACITY sets the alpha
                    custom = style.patternColor != null,
                    onPick = { apply(style.copy(patternColor = it.copy(a = effPatternColor.a))) }, // keep current opacity
                    dismissOnPick = false,
                ) { d, p -> PageColorGridPopup(style.patternColor?.copy(a = 255), d, p) }
            }

            Spacer(Modifier.size(12.dp))
            val opacityPct = effPatternColor.a * 100f / 255f
            StyleCaption("OPACITY  ${opacityPct.roundToInt()}%")
            Slider(
                value = opacityPct,
                onValueChange = { pct ->
                    apply(style.copy(patternColor = effPatternColor.copy(a = (pct / 100f * 255f).roundToInt().coerceIn(0, 255))))
                },
                valueRange = 0f..100f,
            )

            if (tab == 0) {
                Spacer(Modifier.size(8.dp))
                if (showNewNoteRow && !docStyle.isEmpty) {
                    // The checkbox's 48dp touch frame insets the drawn box; pull the row back to align it.
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().offset(x = (-14).dp)) {
                        Checkbox(
                            checked = !editor.newNoteStyle.isEmpty && docStyle == editor.newNoteStyle,
                            onCheckedChange = { on ->
                                editor.saveNewNoteStyle(if (on) docStyle else PageStyle())
                            },
                        )
                        Text(
                            "Default for new notes",
                            color = LocalPalette.current.text.toComposeColor(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.size(4.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ModeChip("Reset", false) { apply(PageStyle()) }
                }
            }
        }
    }
}

@Composable
private fun StyleCaption(text: String) {
    Text(
        text,
        color = LocalPalette.current.textDim.toComposeColor(),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
    )
}

/**
 * The toolbar's View menu: one set of controls always showing the open note's effective
 * (resolved) view settings; a change writes that field's per-note override — stored
 * app-side like zoom/scroll, never in the file itself. Like [StylesPopup], the current
 * values can be saved as the global defaults every note without overrides follows
 * ("Default for all notes"), and Reset drops the note's overrides back onto them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ViewMenuPopup(editor: Editor, onDismiss: () -> Unit) {
    val defaults = editor.viewDefaults
    val overrides = editor.viewOverrides
    val vs = editor.viewSettings
    // Same session-sticky rule as the styles popup's "Default for new notes" row.
    var showDefaultRow by remember { mutableStateOf(editor.viewSettings != editor.viewDefaults) }

    fun apply(new: ViewOverrides) {
        editor.updateViewOverrides(new)
        if (editor.viewSettings != editor.viewDefaults) showDefaultRow = true
    }
    fun setMode(v: ViewingMode) = apply(overrides.copy(mode = v))
    fun setVerticalScroll(v: Boolean) = apply(overrides.copy(verticalScroll = v))
    fun setContrast(v: Int) = apply(overrides.copy(contrast = v))
    fun setInvert(v: Int) = apply(overrides.copy(invert = v))
    fun setBrightness(v: Int) = apply(overrides.copy(brightness = v))
    fun setSepia(v: Int) = apply(overrides.copy(sepia = v))
    fun setKeepImages(v: Boolean) = apply(overrides.copy(keepImages = v))
    fun setRotation(v: Int) = apply(overrides.copy(rotation = v))
    fun setScrollbar(v: Boolean) = apply(overrides.copy(scrollbar = v))

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(300.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle("VIEW")
            StyleCaption("VIEWING MODE")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip("Single", vs.mode == ViewingMode.SINGLE) { setMode(ViewingMode.SINGLE) }
                ModeChip("Double", vs.mode == ViewingMode.DOUBLE) { setMode(ViewingMode.DOUBLE) }
                ModeChip("Cover", vs.mode == ViewingMode.COVER) { setMode(ViewingMode.COVER) }
            }

            Spacer(Modifier.size(10.dp))
            ToggleRow("VERTICAL SCROLLING", vs.verticalScroll) { setVerticalScroll(it) }

            Spacer(Modifier.size(10.dp))
            StyleCaption("PDF COLOUR FILTERS")
            FilterSpinRow("Contrast", vs.contrast, 0, 200) { setContrast(it) }
            FilterSpinRow("Invert", vs.invert, 0, 100) { setInvert(it) }
            FilterSpinRow("Brightness", vs.brightness, 0, 200) { setBrightness(it) }
            FilterSpinRow("Sepia", vs.sepia, 0, 100) { setSepia(it) }
            ToggleRow("DON'T FILTER IMAGES", vs.keepImages) { setKeepImages(it) }

            Spacer(Modifier.size(10.dp))
            StyleCaption("ROTATE  ${vs.rotation}°")
            Slider(
                value = vs.rotation.toFloat(),
                onValueChange = { setRotation((it / 90f).roundToInt() * 90) },
                valueRange = 0f..270f,
                steps = 2,
            )

            ToggleRow("SCROLLBAR", vs.scrollbar) { setScrollbar(it) }

            Spacer(Modifier.size(8.dp))
            if (showDefaultRow && overrides != ViewOverrides()) {
                // The checkbox's 48dp touch frame insets the drawn box; pull the row back to align it.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().offset(x = (-14).dp)) {
                    Checkbox(
                        checked = defaults != ViewSettings() && vs == defaults,
                        onCheckedChange = { on ->
                            editor.updateViewDefaults(if (on) vs else ViewSettings())
                        },
                    )
                    Text(
                        "Default for all notes",
                        color = LocalPalette.current.text.toComposeColor(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.size(4.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ModeChip("Reset", false) { apply(ViewOverrides()) }
            }
        }
    }
}

/** A labelled percentage spinfield (minus / value / plus, stepping by 5) for the PDF filters. */
@Composable
private fun FilterSpinRow(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = palette.textDim.toComposeColor(), fontSize = 13.sp, modifier = Modifier.width(84.dp))
        Box(Modifier.size(34.dp).clickable { onChange((value - 5).coerceIn(min, max)) }, contentAlignment = Alignment.Center) {
            Text("−", color = palette.text.toComposeColor(), fontSize = 18.sp)
        }
        Text(
            "$value%",
            color = palette.text.toComposeColor(),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(46.dp),
        )
        Box(Modifier.size(34.dp).clickable { onChange((value + 5).coerceIn(min, max)) }, contentAlignment = Alignment.Center) {
            Text("+", color = palette.text.toComposeColor(), fontSize = 18.sp)
        }
    }
}

/** Page-nav popup: type a page number (1-based) and jump to it; Done/GO both commit. */
@Composable
fun PageJumpPopup(editor: Editor, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("${editor.pageIndex + 1}") }
    fun go() {
        val n = text.toIntOrNull() ?: return
        editor.goToPage(n - 1)
        onDismiss()
    }
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle("GO TO PAGE")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldFrame(Modifier.width(72.dp)) {
                    NativeField(
                        value = text,
                        onText = { text = it },
                        modifier = Modifier.weight(1f),
                        numeric = true,
                        maxLen = 5,
                        endAlign = true,
                        autoFocus = true,
                        onDone = { go() },
                    )
                }
                Text(
                    "/ ${editor.pageCount}",
                    color = LocalPalette.current.textDim.toComposeColor(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
                ModeChip("GO", selected = true) { go() }
            }
        }
    }
}

/** Zoom menu: optional MIN/MAX zoom limits. While a limit is on, every zoom path (pinch,
 *  buttons, keyboard, fit) clamps to it; its spin field greys out when the toggle is off. */
@Composable
fun ZoomMenuPopup(editor: Editor, onDismiss: () -> Unit) {
    val base = remember { editor.preferences }
    var minOn by remember { mutableStateOf(base.minZoomEnabled) }
    var minPct by remember { mutableStateOf(base.minZoomPercent) }
    var maxOn by remember { mutableStateOf(base.maxZoomEnabled) }
    var maxPct by remember { mutableStateOf(base.maxZoomPercent) }

    fun emit() = editor.applyPreferences(
        editor.preferences.copy(
            minZoomEnabled = minOn, minZoomPercent = minPct,
            maxZoomEnabled = maxOn, maxZoomPercent = maxPct,
        ),
    )

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(280.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle("ZOOM")
            ZoomLimitRow(
                "MIN ZOOM", minOn, minPct,
                onToggle = {
                    minOn = it
                    if (minOn && maxOn && minPct > maxPct) minPct = maxPct
                    emit()
                },
                onValue = {
                    minPct = it.coerceIn(Preferences.ZOOM_LIMIT_MIN_PCT, Preferences.ZOOM_LIMIT_MAX_PCT)
                        .coerceAtMost(if (maxOn) maxPct else Preferences.ZOOM_LIMIT_MAX_PCT)
                    emit()
                },
            )
            ZoomLimitRow(
                "MAX ZOOM", maxOn, maxPct,
                onToggle = {
                    maxOn = it
                    if (maxOn && minOn && maxPct < minPct) maxPct = minPct
                    emit()
                },
                onValue = {
                    maxPct = it.coerceIn(Preferences.ZOOM_LIMIT_MIN_PCT, Preferences.ZOOM_LIMIT_MAX_PCT)
                        .coerceAtLeast(if (minOn) minPct else Preferences.ZOOM_LIMIT_MIN_PCT)
                    emit()
                },
            )
        }
    }
}

/** One zoom-limit row: label, a percent spinfield (stepping by 10, greyed while off), a toggle. */
@Composable
private fun ZoomLimitRow(label: String, enabled: Boolean, value: Int, onToggle: (Boolean) -> Unit, onValue: (Int) -> Unit) {
    val palette = LocalPalette.current
    val color = (if (enabled) palette.text else palette.textDim).toComposeColor()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(76.dp))
        Box(Modifier.size(34.dp).clickable(enabled = enabled) { onValue(value - 10) }, contentAlignment = Alignment.Center) {
            Text("−", color = color, fontSize = 18.sp)
        }
        Text(
            "$value%",
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(52.dp),
        )
        Box(Modifier.size(34.dp).clickable(enabled = enabled) { onValue(value + 10) }, contentAlignment = Alignment.Center) {
            Text("+", color = color, fontSize = 18.sp)
        }
        Spacer(Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

/** Eraser configuration popup: a STROKE/AREA mode picker and a SIZE slider (the eraser radius). */
@Composable
fun EraserConfigPopup(editor: Editor, onDismiss: () -> Unit) {
    val base = remember { editor.toolConfig(Tool.ERASER) }
    var area by remember { mutableStateOf(base.eraseMode == EraseMode.AREA) }
    var size by remember { mutableStateOf(base.baseWidth.toFloat()) }
    var switchBack by remember { mutableStateOf(base.switchBackAfterErase) }
    var scale by remember { mutableStateOf(base.scale) }

    fun emit() = editor.updateToolConfig(
        Tool.ERASER,
        base.copy(
            baseWidth = size.toDouble(),
            eraseMode = if (area) EraseMode.AREA else EraseMode.STROKE,
            switchBackAfterErase = switchBack,
            scale = scale,
        ),
    )

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(250.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle("ERASER")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip("STROKE", selected = !area) { area = false; emit() }
                ModeChip("AREA", selected = area) { area = true; emit() }
            }
            val r = ToolConversions.widthRange(Tool.ERASER)
            SliderRow("SIZE", size, r.start.toFloat()..r.endInclusive.toFloat()) { size = it; emit() }
            // SCALE off: the eraser holds a constant on-screen size whatever zoom you are at.
            ToggleRow("SCALE", scale) { scale = it; emit() }
            // Re-arm the previous pen/highlighter once an erase lifts, so a quick fix doesn't strand
            // you in the eraser.
            ToggleRow("SWITCH BACK", switchBack) { switchBack = it; emit() }
        }
    }
}

/** Select-tool configuration popup: just a SWITCH BACK toggle, mirroring the eraser's. */
@Composable
fun SelectConfigPopup(editor: Editor, onDismiss: () -> Unit) {
    val base = remember { editor.toolConfig(Tool.SELECT) }
    var switchBack by remember { mutableStateOf(base.switchBackAfterSelect) }

    fun emit() = editor.updateToolConfig(Tool.SELECT, base.copy(switchBackAfterSelect = switchBack))

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(250.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle("SELECT")
            // Re-arm the previous pen/highlighter once a selection action (move, resize, delete,
            // cut, copy, duplicate) finishes, so a quick edit doesn't strand you in select.
            ToggleRow("SWITCH BACK", switchBack) { switchBack = it; emit() }
        }
    }
}

/** Shape-tool configuration popup (spec 10 §3 / 04 §6): kind picker, WIDTH, FILL, DASHED, NEON. */
@Composable
fun ShapeConfigPopup(editor: Editor, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(editor.shapeConfig.shape) }
    var width by remember { mutableStateOf(editor.shapeConfig.strokeWidth.toFloat()) }
    var fill by remember { mutableStateOf(editor.shapeConfig.fill) }
    var glow by remember { mutableStateOf(editor.shapeConfig.neon) }
    var glowIntensity by remember { mutableStateOf(ToolConversions.neonStrengthToIntensity(editor.shapeConfig.neonStrength).toFloat()) }
    var dashed by remember { mutableStateOf(editor.shapeConfig.dashed) }
    var dashLen by remember { mutableStateOf(editor.shapeConfig.dashLength.toFloat()) }
    var gapLen by remember { mutableStateOf(editor.shapeConfig.dashGap.toFloat()) }

    fun emit() = editor.updateShapeConfig(
        ShapeConfig(
            kind, width.toDouble(), fill, glow, ToolConversions.intensityToNeonStrength(glowIntensity.toDouble()),
            dashed, dashLen.toDouble(), gapLen.toDouble(),
        ),
    )

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(284.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle("SHAPE")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ShapeKind.DRAW_TOOL_KINDS.forEach { k ->
                    KindChip(shapeIcon(k), k.id, selected = kind == k) { kind = k; emit() }
                }
            }
            SliderRow("WIDTH", width, 1f..20f) { width = it; emit() }
            ToggleRow("FILL", fill) { fill = it; emit() }
            ToggleRow("DASHED", dashed) { dashed = it; emit() }
            if (dashed) {
                SliderRow("DASH", dashLen, 2f..40f) { dashLen = it; emit() }
                SliderRow("GAP", gapLen, 2f..40f) { gapLen = it; emit() }
            }
            ToggleRow("NEON", glow) { glow = it; emit() }
            if (glow) {
                SliderRow("INTENSITY", glowIntensity, 0f..100f) { glowIntensity = it; emit() }
            }
        }
    }
}

/** Colour switcher (spec 10 §4): the toolbar swatch picker — opens the shared [ColorPickerPopup]
 *  and writes the chosen colour back to swatch [index]. Picks apply live; the final colour is
 *  remembered into recents when the popup closes. */
@Composable
fun ColorSwitcherPopup(editor: Editor, index: Int, onDismiss: () -> Unit) {
    ColorPickerPopup(
        initial = editor.toolbarColors.getOrNull(index),
        recents = editor.recentColors,
        onDismiss = { editor.rememberSwatchColor(index); onDismiss() },
        onPick = { editor.setSwatchColor(index, it) },
    )
}

@Composable
private fun PopupTitle(text: String) {
    Text(
        text,
        color = LocalPalette.current.accent.toComposeColor(),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.width(220.dp)) {
        Text(label, color = LocalPalette.current.text.toComposeColor(), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean = true, onChange: (Float) -> Unit) {
    Column {
        Text(
            "$label  ${"%.0f".format(value)}",
            color = (if (enabled) LocalPalette.current.text else LocalPalette.current.textDim).toComposeColor(),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled)
    }
}

/** Glyph shown in the shape-kind picker for each [ShapeKind]. */
private fun shapeIcon(kind: ShapeKind): ImageVector = when (kind) {
    ShapeKind.LINE, ShapeKind.POLYLINE, ShapeKind.CURVE -> XnotesIcons.shapeLine
    ShapeKind.ARROW -> XnotesIcons.shapeArrow
    ShapeKind.RECTANGLE -> XnotesIcons.shapeRect
    ShapeKind.ELLIPSE -> XnotesIcons.shapeEllipse
    ShapeKind.CIRCLE -> XnotesIcons.shapeCircle
    ShapeKind.TRIANGLE, ShapeKind.POLYGON -> XnotesIcons.shapeTriangle
}

@Composable
private fun KindChip(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) palette.accentAlpha(48).toComposeColor() else palette.surface.toComposeColor())
            .border(1.dp, if (selected) palette.accent.toComposeColor() else palette.border.toComposeColor(), RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) palette.accent.toComposeColor() else palette.text.toComposeColor(),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The app's dropdown menu: material's, pinned to the palette menu surface and given a
 * hairline border so it reads against same-tone surfaces (the backstage, OLED black)
 * where a shadow alone vanishes. Shadows material3's composable for every same-package
 * caller that doesn't import material's directly.
 */
@Composable
internal fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalPalette.current
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        properties = properties,
        containerColor = palette.menuBg.toComposeColor(),
        border = BorderStroke(1.dp, palette.border.toComposeColor()),
        content = content,
    )
}

/** A text-label chip for a segmented picker (e.g. the eraser's STROKE/AREA modes). */
@Composable
internal fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) palette.accentAlpha(48).toComposeColor() else palette.surface.toComposeColor())
            .border(1.dp, if (selected) palette.accent.toComposeColor() else palette.border.toComposeColor(), RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (selected) palette.accent.toComposeColor() else palette.text.toComposeColor(),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
    }
}

/**
 * The items of a font dropdown, shared by the flow format bar, the text tool
 * config popup and the text box style bar. Each entry previews in its own
 * family; [monoOnly] restricts the list to monospace families. A null pick
 * (offered when [withDefault]) means "inherit the document default".
 */
@Composable
fun FontMenuItems(
    current: FontFace?,
    monoOnly: Boolean = false,
    withDefault: Boolean = false,
    onPick: (FontFace?) -> Unit,
) {
    val palette = LocalPalette.current
    if (withDefault) {
        DropdownMenuItem(
            text = {
                Text(
                    "Default",
                    color = (if (current == null) palette.accent else palette.text).toComposeColor(),
                    fontSize = 14.sp,
                )
            },
            onClick = { onPick(null) },
        )
    }
    for (choice in FontCatalog.choices()) {
        if (monoOnly && !choice.mono) continue
        DropdownMenuItem(
            text = {
                Text(
                    choice.label,
                    color = (if (choice.face == current) palette.accent else palette.text).toComposeColor(),
                    style = TextStyle(fontFamily = choice.face.toComposeFamily(), fontSize = 14.sp),
                )
            },
            onClick = { onPick(choice.face) },
        )
    }
}
