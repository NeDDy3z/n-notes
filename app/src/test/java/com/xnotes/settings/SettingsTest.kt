package com.xnotes.settings

import com.xnotes.core.model.Orientation
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolbarLayout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {

    @Test fun emptyJsonYieldsDefaults() {
        val s = Settings.fromJson(JSONObject())
        assertEquals(7, s.toolbarColors.size)
        assertEquals(5, s.toolbarColorCount)
        assertEquals(0, s.activeColor)
        assertEquals(1.0, s.renderScale, 1e-9)
        assertFalse(s.sidebarVisible)
        assertEquals(PageSize.A4, s.prefs.defaultPageSize)
        assertEquals("system", s.prefs.uiAppearance)
        assertEquals(com.xnotes.canvas.ViewSettings(), s.viewDefaults)
    }

    @Test fun viewDefaultsRoundTrip() {
        val original = Settings(
            viewDefaults = com.xnotes.canvas.ViewSettings(
                mode = com.xnotes.canvas.ViewingMode.DOUBLE,
                invert = 100,
                keepImages = true,
                scrollbar = true,
            ),
        )
        val back = Settings.fromJson(original.toJson())
        assertEquals(original.viewDefaults, back.viewDefaults)
    }

    @Test fun legacyPdfDarkModeSeedsTheViewDefaults() {
        // Settings written before the View menu's Global tab: the old checkboxes migrate.
        val legacy = JSONObject().put(
            "prefs",
            JSONObject().put("pdf_dark_mode", true).put("pdf_keep_image_colors", true),
        )
        val s = Settings.fromJson(legacy)
        assertEquals(100, s.viewDefaults.invert)
        assertTrue(s.viewDefaults.keepImages)
        // Once written back, the migrated defaults persist on their own.
        val back = Settings.fromJson(s.toJson())
        assertEquals(100, back.viewDefaults.invert)
        assertTrue(back.viewDefaults.keepImages)
    }

    @Test fun roundTripPreservesValues() {
        val original = Settings(
            tools = mapOf(Tool.PEN to com.xnotes.core.tools.ToolConfig(5.0, false, 0.2, 0.1, Rgba(1, 2, 3, 255))),
            toolbarColors = listOf(Rgba(0, 230, 118), Rgba(1, 1, 1), Rgba(2, 2, 2), Rgba(3, 3, 3), Rgba(4, 4, 4)),
            activeColor = 2,
            renderScale = 1.5,
            sidebarVisible = true,
            prefs = Preferences(
                uiAppearance = "light",
                accentColor = Rgba(255, 138, 30),
                defaultPageSize = PageSize.LETTER,
                defaultPageOrientation = Orientation.LANDSCAPE,
                pageColor = Rgba(20, 20, 20),
            ),
        )
        val back = Settings.fromJson(original.toJson())
        assertEquals(5.0, back.configFor(Tool.PEN).baseWidth, 1e-9)
        assertFalse(back.configFor(Tool.PEN).pressureEnabled)
        assertEquals(2, back.activeColor)
        assertEquals(1.5, back.renderScale, 1e-9)
        assertTrue(back.sidebarVisible)
        assertEquals("light", back.prefs.uiAppearance)
        assertEquals(PageSize.LETTER, back.prefs.defaultPageSize)
        assertEquals(Orientation.LANDSCAPE, back.prefs.defaultPageOrientation)
        assertEquals(Rgba(20, 20, 20, 255), back.prefs.pageColor)
    }

    @Test fun malformedAppearanceFallsBackToSystem() {
        val o = JSONObject().put("prefs", JSONObject().put("ui_appearance", "rainbow"))
        assertEquals("system", Settings.fromJson(o).prefs.uiAppearance)
    }

    @Test fun toolbarColorsPaddedToSeven() {
        val o = JSONObject().put(
            "toolbar_colors",
            org.json.JSONArray().put(org.json.JSONArray().put(0).put(0).put(0).put(255)),
        )
        assertEquals(7, Settings.fromJson(o).toolbarColors.size)
    }

    @Test fun toolbarColorCountDefaultsToFive() {
        assertEquals(5, Settings.fromJson(JSONObject()).toolbarColorCount)
    }

    @Test fun toolbarColorCountRoundTripsAndClamps() {
        assertEquals(7, Settings.fromJson(Settings(toolbarColorCount = 7).toJson()).toolbarColorCount)
        assertEquals(1, Settings.fromJson(Settings(toolbarColorCount = 0).toJson()).toolbarColorCount)
        assertEquals(7, Settings.fromJson(Settings(toolbarColorCount = 99).toJson()).toolbarColorCount)
    }

    @Test fun rememberColorDedupesAndCaps() {
        var s = Settings()
        repeat(30) { s = s.rememberColor(Rgba(it, it, it)) }
        assertEquals(24, s.recentColors.size)
        s = s.rememberColor(Rgba(5, 5, 5))
        assertEquals(Rgba(5, 5, 5, 255), s.recentColors.first())
        assertEquals(24, s.recentColors.size)
    }

    @Test fun pageColorNullByDefault() {
        assertNull(Settings.fromJson(JSONObject()).prefs.pageColor)
    }

    @Test fun newNoteStyleEmptyByDefaultAndUnwritten() {
        val s = Settings.fromJson(JSONObject())
        assertTrue(s.newNoteStyle.isEmpty)
        assertFalse(s.toJson().has("new_note_style"))
    }

    @Test fun newNoteStyleRoundTrips() {
        val style = com.xnotes.core.model.PageStyle(
            pageColor = Rgba(255, 250, 230),
            pattern = com.xnotes.core.model.PagePattern.GRID,
            patternColor = Rgba(100, 120, 140, 80),
            spacing = 48.0,
        )
        val back = Settings.fromJson(Settings(newNoteStyle = style).toJson())
        assertEquals(style, back.newNoteStyle)
    }

    @Test fun newNoteStylePartialFieldsStayNull() {
        val style = com.xnotes.core.model.PageStyle(pattern = com.xnotes.core.model.PagePattern.LINES)
        val back = Settings.fromJson(Settings(newNoteStyle = style).toJson())
        assertEquals(style, back.newNoteStyle)
        assertNull(back.newNoteStyle.pageColor)
        assertNull(back.newNoteStyle.spacing)
    }

    @Test fun fingerDrawAutoCheckedDefaultsFalse() {
        assertFalse(Settings.fromJson(JSONObject()).fingerDrawAutoChecked)
    }

    @Test fun fingerDrawAutoCheckedRoundTrips() {
        val back = Settings.fromJson(Settings(fingerDrawAutoChecked = true).toJson())
        assertTrue(back.fingerDrawAutoChecked)
    }

    @Test fun toolbarLayoutDefaultsWhenAbsent() {
        assertEquals(ToolbarLayout.DEFAULT, Settings.fromJson(JSONObject()).toolbarLayout)
    }

    @Test fun toolbarLayoutRoundTrips() {
        val custom = ToolbarLayout.DEFAULT.toggleVisible(2, 0).addSection()
        val back = Settings.fromJson(Settings(toolbarLayout = custom).toJson())
        assertEquals(custom, back.toolbarLayout)
    }

    @Test fun tapGesturesDefaultToNone() {
        val p = Preferences.fromJson(JSONObject())
        assertEquals("none", p.twoFingerTap)
        assertEquals("none", p.threeFingerTap)
    }

    @Test fun tapGesturesRoundTrip() {
        val back = Preferences.fromJson(
            Preferences(twoFingerTap = "undo", threeFingerTap = "toggle_eraser").toJson(),
        )
        assertEquals("undo", back.twoFingerTap)
        assertEquals("toggle_eraser", back.threeFingerTap)
    }

    @Test fun tapGestureMalformedFallsBackToNone() {
        val o = JSONObject().put("two_finger_tap", "explode")
        assertEquals("none", Preferences.fromJson(o).twoFingerTap)
    }

    @Test fun startFullscreenNullByDefaultAndUnwritten() {
        assertNull(Preferences.fromJson(JSONObject()).startFullscreen)
        assertFalse(Preferences().toJson().has("start_fullscreen"))
    }

    @Test fun paletteStyleDefaultsPerMode() {
        val p = Preferences.fromJson(JSONObject())
        assertEquals("material", p.systemPaletteStyle)
        assertEquals("material", p.darkPaletteStyle)
        assertEquals("material", p.lightPaletteStyle)
        assertEquals("classic", p.oledPaletteStyle)
        assertEquals("material", p.paletteStyle)
        assertEquals("classic", p.copy(uiAppearance = "oled").paletteStyle)
    }

    @Test fun paletteStyleRoundTrips() {
        val back = Preferences.fromJson(
            Preferences(
                systemPaletteStyle = "classic",
                darkPaletteStyle = "classic",
                lightPaletteStyle = "classic",
                oledPaletteStyle = "material",
            ).toJson(),
        )
        assertEquals("classic", back.systemPaletteStyle)
        assertEquals("classic", back.darkPaletteStyle)
        assertEquals("classic", back.lightPaletteStyle)
        assertEquals("material", back.oledPaletteStyle)
    }

    @Test fun paletteStyleMalformedFallsBackPerMode() {
        val o = JSONObject()
            .put("system_palette_style", "neon")
            .put("dark_palette_style", "neon")
            .put("light_palette_style", "neon")
            .put("oled_palette_style", "neon")
        val p = Preferences.fromJson(o)
        assertEquals("material", p.systemPaletteStyle)
        assertEquals("material", p.darkPaletteStyle)
        assertEquals("material", p.lightPaletteStyle)
        assertEquals("classic", p.oledPaletteStyle)
    }

    @Test fun materialSeedNullByDefaultAndUnwritten() {
        assertNull(Preferences.fromJson(JSONObject()).materialSeed)
        assertFalse(Preferences().toJson().has("material_seed"))
    }

    @Test fun materialSeedRoundTrips() {
        val back = Preferences.fromJson(Preferences(materialSeed = Rgba(33, 150, 243)).toJson())
        assertEquals(Rgba(33, 150, 243, 255), back.materialSeed)
        val cleared = Preferences.fromJson(back.copy(materialSeed = null).toJson())
        assertNull(cleared.materialSeed)
    }

    @Test fun withPaletteStyleTouchesOnlyTheActiveMode() {
        val p = Preferences(
            uiAppearance = "oled",
            systemPaletteStyle = "classic",
            darkPaletteStyle = "classic",
            lightPaletteStyle = "classic",
        ).withPaletteStyle("material")
        assertEquals("material", p.oledPaletteStyle)
        assertEquals("classic", p.systemPaletteStyle)
        assertEquals("classic", p.darkPaletteStyle)
        assertEquals("classic", p.lightPaletteStyle)
    }

    @Test fun startFullscreenRoundTrips() {
        assertEquals(false, Preferences.fromJson(Preferences(startFullscreen = false).toJson()).startFullscreen)
        assertEquals(true, Preferences.fromJson(Preferences(startFullscreen = true).toJson()).startFullscreen)
    }
}
