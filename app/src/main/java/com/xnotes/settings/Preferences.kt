package com.xnotes.settings

import com.xnotes.core.model.Orientation
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
import com.xnotes.core.util.NameTemplate
import org.json.JSONObject

/**
 * Global, document-independent preferences (spec 09 §4). Forgiving load: every
 * field falls back to its default if missing or malformed.
 */
data class Preferences(
    val uiAppearance: String = "system", // "system" (follows OS dark/light) | "dark" | "light" | "oled"
    val accentColor: Rgba = DEFAULT_ACCENT,
    /** Chrome palette per appearance mode: "classic" (accent-derived) | "material" (Material You). */
    val systemPaletteStyle: String = "material",
    val darkPaletteStyle: String = "material",
    val lightPaletteStyle: String = "material",
    val oledPaletteStyle: String = "classic",
    /** Material palette seed colour; null (default) follows the system dynamic colours. */
    val materialSeed: Rgba? = null,
    val hideWindowDecoration: Boolean = false,
    val pageColor: Rgba? = null, // null ⇒ follow theme paper
    val pageTemplatePdf: String? = null,
    val defaultTemplate: String = "color", // "color" | "pdf"
    val defaultPageSize: PageSize = PageSize.A4,
    /** Filename template for new notes; see [com.xnotes.core.util.NameTemplate]. */
    val newNoteNameTemplate: String = NameTemplate.DEFAULT,
    val defaultPageOrientation: Orientation = Orientation.PORTRAIT,
    /** Whether a finger draws (true) or pans (false, default). The stylus always draws. */
    val fingerDraws: Boolean = false,
    /** Panning allowed while zoom is locked: "single" (default) | "double" | "none". */
    val zoomLockPan: String = "single",
    /** Whether holding a freehand ink stroke still snaps it to a recognized shape. */
    val detectShapes: Boolean = false,
    /** Tool the stylus side button activates while held; "none" disables it. */
    val penButtonTool: String = "eraser",
    /** Whether the side-button tool also activates during hover (no contact needed); eraser/pan only. */
    val penButtonHover: Boolean = false,
    /** Action mapped to a clean two-finger tap; "none" (default) disables it. */
    val twoFingerTap: String = "none",
    /** Action mapped to a clean three-finger tap; "none" (default) disables it. */
    val threeFingerTap: String = "none",
    /** Action mapped to a stylus barrel double-tap (pens with no side button, e.g. Huawei M-Pencil
     *  whose double-tap arrives as keycode 718); "none" (default) disables it. */
    val stylusDoubleTap: String = "none",
    /** Action mapped to a momentary stylus side-button click (pens that report the button as a vendor
     *  key press rather than a held state, e.g. HONOR Magic-Pencil whose click arrives as keycode
     *  333); "none" (default) disables it. */
    val stylusButtonTap: String = "none",
    /** Horizontal margin (px) on each side of the page column; 0 ⇒ fit-width fills the screen. */
    val sideMargin: Double = 16.0,
    /** Whether the hairline outline around every page is left undrawn. */
    val hidePageBorders: Boolean = false,
    /** User zoom floor: when enabled, no zoom path goes below [minZoomPercent]%. */
    val minZoomEnabled: Boolean = false,
    val minZoomPercent: Int = 50,
    /** User zoom ceiling: when enabled, no zoom path goes above [maxZoomPercent]%. */
    val maxZoomEnabled: Boolean = false,
    val maxZoomPercent: Int = 400,
    /** Infinite canvas zoom range, as percentages. Far wider than a page's, since a canvas is
     *  read at both a wall's scale and a hair's. */
    val canvasMinZoomPercent: Int = 2,
    val canvasMaxZoomPercent: Int = 6400,
    /** Long-edge cap (px) for the on-screen page cache; higher holds more of the page ready at deep zoom. */
    val maxCacheResolution: Int = 2048,
    /** Open in fullscreen; null ⇒ auto (on unless the display has a camera cutout). */
    val startFullscreen: Boolean? = null,
    /** An imported Helix code theme's file path, or null for the built-in colours. */
    val codeThemePath: String? = null,
    /** The imported code theme's original file name, shown in preferences. */
    val codeThemeName: String? = null,
    /** Language "Paste as Code" assigns to pasted blocks; "plain" pastes unhighlighted. */
    val defaultCodeLanguage: String = "cpp",
    /** Language the format bar's code toggle last applied; "" until a language is picked. */
    val lastCodeLanguage: String = "",
) {
    /** The palette style of the active appearance mode. */
    val paletteStyle: String get() = paletteStyleFor(uiAppearance)

    fun paletteStyleFor(appearance: String): String = when (appearance) {
        "light" -> lightPaletteStyle
        "oled" -> oledPaletteStyle
        "dark" -> darkPaletteStyle
        else -> systemPaletteStyle
    }

    /** Set the palette style of the active appearance mode, leaving the other modes alone. */
    fun withPaletteStyle(style: String): Preferences = when (uiAppearance) {
        "light" -> copy(lightPaletteStyle = style)
        "oled" -> copy(oledPaletteStyle = style)
        "dark" -> copy(darkPaletteStyle = style)
        else -> copy(systemPaletteStyle = style)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("ui_appearance", uiAppearance)
        .put("accent_color", Rgba.toHex(accentColor))
        .put("system_palette_style", systemPaletteStyle)
        .put("dark_palette_style", darkPaletteStyle)
        .put("light_palette_style", lightPaletteStyle)
        .put("oled_palette_style", oledPaletteStyle)
        .put("hide_window_decoration", hideWindowDecoration)
        .put("page_color", pageColor?.let { Rgba.toHex(it) } ?: JSONObject.NULL)
        .put("page_template_pdf", pageTemplatePdf ?: JSONObject.NULL)
        .put("default_template", defaultTemplate)
        .put("default_page_size", defaultPageSize.displayName)
        .put("new_note_name_template", newNoteNameTemplate)
        .put("default_page_orientation", defaultPageOrientation.toName())
        .put("finger_draws", fingerDraws)
        .put("zoom_lock_pan", zoomLockPan)
        .put("detect_shapes", detectShapes)
        .put("pen_button_tool", penButtonTool)
        .put("pen_button_hover", penButtonHover)
        .put("two_finger_tap", twoFingerTap)
        .put("three_finger_tap", threeFingerTap)
        .put("stylus_double_tap", stylusDoubleTap)
        .put("stylus_button_tap", stylusButtonTap)
        .put("side_margin", sideMargin)
        .put("hide_page_borders", hidePageBorders)
        .put("min_zoom_enabled", minZoomEnabled)
        .put("min_zoom_percent", minZoomPercent)
        .put("max_zoom_enabled", maxZoomEnabled)
        .put("max_zoom_percent", maxZoomPercent)
        .put("canvas_min_zoom_percent", canvasMinZoomPercent)
        .put("canvas_max_zoom_percent", canvasMaxZoomPercent)
        .put("max_cache_resolution", maxCacheResolution)
        .apply {
            materialSeed?.let { put("material_seed", Rgba.toHex(it)) }
            startFullscreen?.let { put("start_fullscreen", it) }
            codeThemePath?.let { put("code_theme_path", it) }
            codeThemeName?.let { put("code_theme_name", it) }
            put("default_code_language", defaultCodeLanguage)
            lastCodeLanguage.takeIf { it.isNotEmpty() }?.let { put("last_code_language", it) }
        }

    companion object {
        val DEFAULT_ACCENT = Rgba(0, 230, 118, 255)

        /** Settable range of the min/max zoom limits, in percent (within the hard zoom bounds). */
        const val ZOOM_LIMIT_MIN_PCT = 20
        const val ZOOM_LIMIT_MAX_PCT = 1600

        fun fromJson(o: JSONObject?): Preferences {
            if (o == null) return Preferences()
            val appearance = o.optString("ui_appearance", "system")
                .let { if (it == "dark" || it == "light" || it == "oled") it else "system" }
            val template = o.optString("default_template", "color").let { if (it == "pdf") "pdf" else "color" }
            val zoomLockPan = o.optString("zoom_lock_pan", "single").let { if (it == "double" || it == "none") it else "single" }
            val tapActions = setOf("none", "undo", "redo", "toggle_pan", "toggle_eraser", "toggle_previous")
            fun tapAction(key: String) = o.optString(key, "none").let { if (it in tapActions) it else "none" }
            fun paletteStyle(key: String, default: String) =
                o.optString(key, default).let { if (it == "classic" || it == "material") it else default }
            return Preferences(
                uiAppearance = appearance,
                accentColor = Rgba.fromHex(o.optString("accent_color")) ?: DEFAULT_ACCENT,
                systemPaletteStyle = paletteStyle("system_palette_style", "material"),
                darkPaletteStyle = paletteStyle("dark_palette_style", "material"),
                lightPaletteStyle = paletteStyle("light_palette_style", "material"),
                oledPaletteStyle = paletteStyle("oled_palette_style", "classic"),
                materialSeed = Rgba.fromHex(o.optString("material_seed")),
                hideWindowDecoration = o.optBoolean("hide_window_decoration", false),
                pageColor = if (o.isNull("page_color")) null else Rgba.fromHex(o.optString("page_color")),
                pageTemplatePdf = if (o.isNull("page_template_pdf")) null else o.optString("page_template_pdf").ifEmpty { null },
                defaultTemplate = template,
                defaultPageSize = PageSize.fromName(o.optString("default_page_size", "A4")),
                newNoteNameTemplate = o.optString("new_note_name_template", NameTemplate.DEFAULT)
                    .ifBlank { NameTemplate.DEFAULT },
                defaultPageOrientation = Orientation.fromName(o.optString("default_page_orientation", "portrait")),
                fingerDraws = o.optBoolean("finger_draws", false),
                zoomLockPan = zoomLockPan,
                detectShapes = o.optBoolean("detect_shapes", false),
                penButtonTool = o.optString("pen_button_tool", "eraser").ifEmpty { "eraser" },
                penButtonHover = o.optBoolean("pen_button_hover", false),
                twoFingerTap = tapAction("two_finger_tap"),
                threeFingerTap = tapAction("three_finger_tap"),
                stylusDoubleTap = tapAction("stylus_double_tap"),
                stylusButtonTap = tapAction("stylus_button_tap"),
                sideMargin = o.optDouble("side_margin", 16.0).coerceIn(0.0, 80.0),
                hidePageBorders = o.optBoolean("hide_page_borders", false),
                minZoomEnabled = o.optBoolean("min_zoom_enabled", false),
                minZoomPercent = o.optInt("min_zoom_percent", 50).coerceIn(ZOOM_LIMIT_MIN_PCT, ZOOM_LIMIT_MAX_PCT),
                maxZoomEnabled = o.optBoolean("max_zoom_enabled", false),
                maxZoomPercent = o.optInt("max_zoom_percent", 400).coerceIn(ZOOM_LIMIT_MIN_PCT, ZOOM_LIMIT_MAX_PCT),
                canvasMinZoomPercent = o.optInt("canvas_min_zoom_percent", 2).coerceIn(1, 100),
                canvasMaxZoomPercent = o.optInt("canvas_max_zoom_percent", 6400).coerceIn(200, 100000),
                maxCacheResolution = o.optInt("max_cache_resolution", 2048).coerceIn(1024, 4096),
                startFullscreen = if (o.has("start_fullscreen")) o.getBoolean("start_fullscreen") else null,
                codeThemePath = o.optString("code_theme_path").ifEmpty { null },
                codeThemeName = o.optString("code_theme_name").ifEmpty { null },
                defaultCodeLanguage = o.optString("default_code_language", "cpp")
                    .lowercase().trim().ifEmpty { "cpp" },
                lastCodeLanguage = o.optString("last_code_language").lowercase().trim(),
            )
        }
    }
}
