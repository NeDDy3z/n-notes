package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class CanvasCodecTest {

    private val codec = CanvasCodec(FakeImageCodec())

    private fun imageFile(bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): File =
        File.createTempFile("img", null).apply { writeBytes(bytes); deleteOnExit() }

    private fun bytesOf(doc: InfiniteDocument): ByteArray =
        ByteArrayOutputStream().also { codec.write(doc, it) }.toByteArray()

    private fun roundTrip(doc: InfiniteDocument): InfiniteDocument {
        val imageDir = Files.createTempDirectory("xnotes-canvas-img").toFile()
        return codec.read(ByteArrayInputStream(bytesOf(doc)), imageDir = imageDir)
    }

    /** A bundle whose manifest is [manifest] verbatim, for the forgiving-load tests. */
    private fun bundleOf(manifest: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json").apply { method = ZipEntry.DEFLATED })
            zos.write(manifest.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    private fun readManifest(manifest: String): InfiniteDocument =
        codec.read(ByteArrayInputStream(bundleOf(manifest)))

    private fun manifestText(doc: InfiniteDocument): String {
        ZipInputStream(ByteArrayInputStream(bytesOf(doc))).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name == "manifest.json") return zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        throw AssertionError("no manifest")
    }

    // --- round trip ---

    @Test fun fullRoundTrip() {
        val doc = InfiniteDocument(dpi = 150)
        doc.add(
            Stroke(
                Tool.CALLIGRAPHY,
                ToolConfig(6.0, true, 0.40, 0.60, Rgba(0, 230, 118, 255)),
                mutableListOf(Sample(10.0, 20.0, 0.5), Sample(-30.0, 40.0, 0.9)),
            ),
        )
        doc.add(ImageItem(ImageData(imageFile(), 64, 48), Rect(5.0, 6.0, 64.0, 48.0)))
        doc.add(ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(50.0, 30.0), Rgba(255, 92, 92, 255), 3.0, Rgba(255, 92, 92, 64)))
        doc.background = CanvasBackground(PagePattern.DOTS, Rgba(10, 20, 30, 200), 42.0, Rgba(1, 2, 3, 255))
        doc.lastView = Waypoint("", 1234.5, -678.25, 3.5)
        doc.waypoints.add(Waypoint("origin", 0.0, 0.0, 1.0))
        doc.waypoints.add(Waypoint("far", 90000.0, -80000.0, 0.05))

        val back = roundTrip(doc)

        assertEquals(150, back.dpi)
        assertEquals(3, back.itemCount)

        val stroke = back.items[0] as Stroke
        assertEquals(Tool.CALLIGRAPHY, stroke.tool)
        assertEquals(0.60, stroke.config.directionStrength, 1e-9)
        assertEquals(2, stroke.samples.size)
        assertEquals(Sample(10.0, 20.0, 0.5), stroke.samples[0])
        assertEquals(-30.0, stroke.samples[1].x, 1e-9)

        val image = back.items[1] as ImageItem
        assertEquals(Rect(5.0, 6.0, 64.0, 48.0), image.rect)
        assertEquals(64, image.image.width)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), image.image.file.readBytes())

        val shape = back.items[2] as ShapeItem
        assertEquals(ShapeKind.RECTANGLE, shape.shape)
        assertNotNull(shape.fillRgba)

        assertEquals(PagePattern.DOTS, back.background.pattern)
        assertEquals(Rgba(10, 20, 30, 200), back.background.patternColor)
        assertEquals(42.0, back.background.spacing, 1e-9)
        assertEquals(Rgba(1, 2, 3, 255), back.background.paperColor)

        assertEquals(3.5, back.lastView!!.zoom, 1e-9)
        assertEquals(1234.5, back.lastView!!.cx, 1e-9)
        assertEquals(listOf("origin", "far"), back.waypoints.map { it.name })
        assertEquals(0.05, back.waypoints[1].zoom, 1e-12)
    }

    @Test fun anEmptyCanvasRoundTrips() {
        val back = roundTrip(InfiniteDocument())
        assertTrue(back.isEmpty)
        assertNull(back.lastView)
        assertTrue(back.waypoints.isEmpty())
        assertEquals(PagePattern.GRID, back.background.pattern)
    }

    @Test fun loadedItemsAreIndexedAndOrdered() {
        val doc = InfiniteDocument()
        val far = ShapeItem(ShapeKind.RECTANGLE, Pt(9000.0, 9000.0), Pt(9100.0, 9100.0), Rgba(1, 1, 1, 255))
        val near = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(50.0, 50.0), Rgba(2, 2, 2, 255))
        doc.add(far)
        doc.add(near)

        val back = roundTrip(doc)
        assertEquals(2, back.index.size)
        assertEquals(1, back.visibleItems(Rect(-10.0, -10.0, 100.0, 100.0)).size)
        // z-order is list order: the far shape was added first and stays behind.
        val all = back.visibleItems(Rect(-1e5, -1e5, 2e5, 2e5))
        assertEquals(9000.0, (all[0] as ShapeItem).start.x, 1e-9)
    }

    @Test fun everyStrokeToolRoundTrips() {
        val doc = InfiniteDocument()
        val tools = listOf(Tool.PEN, Tool.DASHED, Tool.CALLIGRAPHY, Tool.SPEED, Tool.TAPER, Tool.HIGHLIGHTER)
        for (t in tools) {
            doc.add(
                Stroke(
                    t,
                    ToolDefaults.configFor(t),
                    mutableListOf(Sample(0.0, 0.0, 1.0, 0.0), Sample(10.0, 10.0, 0.5, 16.0)),
                    speedScale = 2.5,
                ),
            )
        }
        val back = roundTrip(doc)
        assertEquals(tools, back.items.map { (it as Stroke).tool })
        val speed = back.items[3] as Stroke
        assertEquals(2.5, speed.speedScale, 1e-9)
        assertEquals(16.0, speed.samples[1].t, 1e-9) // times only survive for the speed pen
        assertEquals(0.0, (back.items[0] as Stroke).samples[1].t, 1e-9)
        val taper = back.items[4] as Stroke
        assertTrue(taper.config.taperEnabled)
        assertEquals(ToolDefaults.DEFAULT_TAPER_TIP, taper.config.taperMinFactor, 1e-9)
        val hl = back.items[5] as Stroke
        assertEquals(0.50, hl.config.highlighterAlpha, 1e-9)
        val dashed = back.items[1] as Stroke
        assertEquals(10.0, dashed.config.dashLength, 1e-9)
        assertEquals(8.0, dashed.config.dashGap, 1e-9)
    }

    @Test fun neonAndStraightSurvive() {
        val doc = InfiniteDocument()
        doc.add(
            Stroke(
                Tool.PEN,
                ToolConfig(neon = true, neonStrength = 0.9),
                mutableListOf(Sample(0.0, 0.0, 1.0), Sample(20.0, 0.0, 1.0)),
                straight = true,
            ),
        )
        val back = roundTrip(doc).items[0] as Stroke
        assertTrue(back.config.neon)
        assertEquals(0.9, back.config.neonStrength, 1e-9)
        assertTrue(back.straight)
    }

    @Test fun everyShapeKindRoundTrips() {
        val doc = InfiniteDocument()
        for (k in ShapeKind.entries) {
            if (k == ShapeKind.POLYGON || k == ShapeKind.POLYLINE) continue
            doc.add(ShapeItem(k, Pt(0.0, 0.0), Pt(30.0, 20.0), Rgba(9, 9, 9, 255), 2.0))
        }
        val back = roundTrip(doc)
        assertEquals(
            ShapeKind.entries.filter { it != ShapeKind.POLYGON && it != ShapeKind.POLYLINE },
            back.items.map { (it as ShapeItem).shape },
        )
    }

    @Test fun polygonVerticesRoundTripInContentSpace() {
        val doc = InfiniteDocument()
        val verts = listOf(Pt(10.0, 10.0), Pt(90.0, 20.0), Pt(50.0, 80.0))
        doc.add(ShapeItem.poly(ShapeKind.POLYGON, verts, Rgba(3, 3, 3, 255), 2.0, null, false, 0.6, false, 10.0, 8.0))
        val back = roundTrip(doc).items[0] as ShapeItem
        val out = back.vertices()!!
        assertEquals(3, out.size)
        for (i in verts.indices) {
            assertEquals(verts[i].x, out[i].x, 1e-6)
            assertEquals(verts[i].y, out[i].y, 1e-6)
        }
    }

    @Test fun dashedShapesRoundTrip() {
        val doc = InfiniteDocument()
        doc.add(
            ShapeItem(
                ShapeKind.ELLIPSE, Pt(0.0, 0.0), Pt(40.0, 40.0), Rgba(1, 2, 3, 255), 4.0,
                dashed = true, dashLength = 7.0, dashGap = 3.0,
            ),
        )
        val back = roundTrip(doc).items[0] as ShapeItem
        assertTrue(back.dashed)
        assertEquals(7.0, back.dashLength, 1e-9)
        assertEquals(3.0, back.dashGap, 1e-9)
    }

    @Test fun severalImagesKeepTheirOwnBytesAndSlots() {
        val doc = InfiniteDocument()
        doc.add(ImageItem(ImageData(imageFile(byteArrayOf(1)), 8, 8), Rect(0.0, 0.0, 8.0, 8.0)))
        doc.add(Stroke(Tool.PEN, ToolConfig(), mutableListOf(Sample(0.0, 0.0, 1.0))))
        doc.add(
            ImageItem(ImageData(imageFile(byteArrayOf(2, 2)), 9, 9), Rect(9.0, 9.0, 9.0, 9.0), orientation = 90, angle = 0.75),
        )

        val back = roundTrip(doc)
        assertEquals(3, back.itemCount)
        assertTrue(back.items[0] is ImageItem)
        assertTrue(back.items[1] is Stroke)
        val second = back.items[2] as ImageItem
        assertEquals(90, second.orientation)
        assertEquals(0.75, second.angle, 1e-9)
        assertEquals(0.0, (back.items[0] as ImageItem).angle, 1e-9)
        assertArrayEquals(byteArrayOf(1), (back.items[0] as ImageItem).image.file.readBytes())
        assertArrayEquals(byteArrayOf(2, 2), second.image.file.readBytes())
    }

    @Test fun animageWhoseAssetIsMissingIsDroppedWithoutShiftingTheRest() {
        val doc = InfiniteDocument()
        doc.add(ImageItem(ImageData(imageFile(), 8, 8), Rect(0.0, 0.0, 8.0, 8.0)))
        doc.add(ShapeItem(ShapeKind.LINE, Pt(0.0, 0.0), Pt(1.0, 1.0), Rgba(1, 1, 1, 255)))
        // Reading with no imageDir skips every asset, exactly like a validation-only read.
        val back = codec.read(ByteArrayInputStream(bytesOf(doc)))
        assertEquals(1, back.itemCount)
        assertTrue(back.items[0] is ShapeItem)
    }

    // --- bundle shape ---

    @Test fun theBundleIsADeflatedManifestPlusStoredAssets() {
        val doc = InfiniteDocument()
        doc.add(ImageItem(ImageData(imageFile(ByteArray(4096) { 7 }), 8, 8), Rect(0.0, 0.0, 8.0, 8.0)))
        val names = mutableListOf<String>()
        val methods = mutableMapOf<String, Int>()
        ZipInputStream(ByteArrayInputStream(bytesOf(doc))).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                names += e.name
                zis.readBytes()
                methods[e.name] = e.method
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        assertEquals(listOf("manifest.json", "assets/image-000.png"), names)
        assertEquals(ZipEntry.DEFLATED, methods["manifest.json"])
        assertEquals(ZipEntry.STORED, methods["assets/image-000.png"])
    }

    @Test fun theManifestCarriesItsFormatTag() {
        val text = manifestText(InfiniteDocument())
        assertTrue(text.startsWith("{\"format\":\"xcanvas\",\"version\":1,\"writer\":"))
        assertTrue("no page array belongs in a canvas manifest", !text.contains("\"pages\""))
    }

    @Test fun optionalSectionsAreOmittedWhenUnset() {
        val text = manifestText(InfiniteDocument())
        assertTrue(!text.contains("\"view\""))
        assertTrue(!text.contains("\"waypoints\""))
        assertTrue(!text.contains("\"paper_color\""))
        assertTrue(text.contains("\"background\""))
    }

    @Test fun resavingAnUntouchedCanvasIsByteStable() {
        val doc = InfiniteDocument()
        doc.add(
            Stroke(
                Tool.PEN, ToolConfig(),
                mutableListOf(Sample(1.23456, 2.34567, 0.87654), Sample(9.87654, 8.76543, 0.12345)),
            ),
        )
        doc.lastView = Waypoint("", 3.0, 4.0, 1.5)
        val first = manifestText(doc)
        val reloaded = codec.read(ByteArrayInputStream(bytesOf(doc)))
        assertEquals(first, manifestText(reloaded))
    }

    // --- forgiving load ---

    @Test fun aBundleWithNoManifestIsRejected() {
        val empty = ByteArrayOutputStream().also { ZipOutputStream(it).close() }.toByteArray()
        assertThrows(XCanvasFormatException::class.java) { codec.read(ByteArrayInputStream(empty)) }
    }

    @Test fun anXnoteBundleIsRejected() {
        assertThrows(XCanvasFormatException::class.java) {
            readManifest("""{"format":"xnote","version":1,"pages":[]}""")
        }
    }

    @Test fun garbageIsRejected() {
        assertThrows(XCanvasFormatException::class.java) {
            codec.read(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)))
        }
    }

    @Test fun unknownItemKindsAreSkipped() {
        val doc = readManifest(
            """{"format":"xcanvas","items":[
               {"kind":"hologram","fancy":true},
               {"kind":"stroke","tool":"pen","samples":[[1,2,1]]},
               {"kind":"text","text":"nope"}
            ]}""",
        )
        assertEquals(1, doc.itemCount)
        assertTrue(doc.items[0] is Stroke)
    }

    @Test fun unknownTopLevelKeysAreSkipped() {
        val doc = readManifest(
            """{"format":"xcanvas","layers":[1,2,3],"future":{"a":{"b":[null,true]}},"dpi":300,"items":[]}""",
        )
        assertEquals(300, doc.dpi)
    }

    @Test fun unknownConfigKeysAreSkipped() {
        val doc = readManifest(
            """{"format":"xcanvas","items":[{"kind":"stroke","tool":"pen",
               "config":{"base_width":9,"sparkle":42},"samples":[[0,0,1]]}]}""",
        )
        assertEquals(9.0, (doc.items[0] as Stroke).config.baseWidth, 1e-9)
    }

    @Test fun missingFieldsTakeModelDefaults() {
        val doc = readManifest("""{"format":"xcanvas","items":[{"kind":"stroke"}]}""")
        val s = doc.items[0] as Stroke
        assertEquals(Tool.PEN, s.tool)
        assertEquals(ToolConfig().baseWidth, s.config.baseWidth, 1e-9)
        assertTrue(s.samples.isEmpty())
        assertEquals(150, doc.dpi)
        assertEquals(PagePattern.GRID, doc.background.pattern)
    }

    @Test fun anUnknownBackgroundPatternFallsBackToTheDefault() {
        val doc = readManifest("""{"format":"xcanvas","background":{"pattern":"hexagons"},"items":[]}""")
        assertEquals(PagePattern.GRID, doc.background.pattern)
    }

    @Test fun malformedWaypointsAreDroppedNotFatal() {
        val doc = readManifest(
            """{"format":"xcanvas","waypoints":[
               {"name":"good","cx":1,"cy":2,"zoom":3},
               "nonsense",
               {"name":"zeroZoom","cx":0,"cy":0,"zoom":0},
               {"name":"ok2","cx":-5,"cy":-6,"zoom":0.25}
            ],"items":[]}""",
        )
        assertEquals(listOf("good", "ok2"), doc.waypoints.map { it.name })
    }

    @Test fun aWrongTypedValueFallsBackRatherThanThrowing() {
        val doc = readManifest(
            """{"format":"xcanvas","dpi":"not a number","background":{"spacing":[1,2]},
               "items":[{"kind":"stroke","samples":"gone"}]}""",
        )
        assertEquals(150, doc.dpi)
        assertEquals(64.0, doc.background.spacing, 1e-9)
        assertTrue((doc.items[0] as Stroke).samples.isEmpty())
    }

    @Test fun numbersWrittenAsStringsStillParse() {
        val doc = readManifest(
            """{"format":"xcanvas","dpi":"300","items":[{"kind":"stroke",
               "config":{"base_width":"7.5","pressure_enabled":"false"},"samples":[["1","2","0.5"]]}]}""",
        )
        assertEquals(300, doc.dpi)
        val s = doc.items[0] as Stroke
        assertEquals(7.5, s.config.baseWidth, 1e-9)
        assertTrue(!s.config.pressureEnabled)
        assertEquals(1.0, s.samples[0].x, 1e-9)
    }

    @Test fun aSampleShortOfItsFieldsTakesDefaults() {
        val doc = readManifest("""{"format":"xcanvas","items":[{"kind":"stroke","samples":[[5],[6,7],[8,9,0.25,99]]}]}""")
        val s = doc.items[0] as Stroke
        assertEquals(Sample(5.0, 0.0, 1.0, 0.0), s.samples[0])
        assertEquals(Sample(6.0, 7.0, 1.0, 0.0), s.samples[1])
        assertEquals(Sample(8.0, 9.0, 0.25, 99.0), s.samples[2])
    }

    @Test fun anItemsArrayOfTheWrongShapeIsSkipped() {
        val doc = readManifest("""{"format":"xcanvas","items":{"not":"an array"}}""")
        assertTrue(doc.isEmpty)
    }

    @Test fun legacyTaperStrokesReloadTapered() {
        val doc = readManifest(
            """{"format":"xcanvas","items":[{"kind":"stroke","tool":"taper",
               "config":{"taper_enabled":true},"samples":[[0,0,1]]}]}""",
        )
        val s = doc.items[0] as Stroke
        assertTrue(s.config.taperEnabled)
        assertEquals(ToolDefaults.DEFAULT_TAPER_TIP, s.config.taperMinFactor, 1e-9)
    }

    @Test fun legacyHighlightersReloadAtTheHistoricalAlpha() {
        val doc = readManifest(
            """{"format":"xcanvas","items":[{"kind":"stroke","tool":"highlighter","samples":[[0,0,1]]}]}""",
        )
        assertEquals(0.35, (doc.items[0] as Stroke).config.highlighterAlpha, 1e-9)
    }

    @Test fun farFlungCoordinatesSurviveTheRounding() {
        val doc = InfiniteDocument()
        doc.add(
            Stroke(
                Tool.PEN, ToolConfig(),
                mutableListOf(Sample(1_234_567.89, -987_654.32, 1.0)),
            ),
        )
        val s = roundTrip(doc).items[0] as Stroke
        assertEquals(1_234_567.89, s.samples[0].x, 1e-6)
        assertEquals(-987_654.32, s.samples[0].y, 1e-6)
    }

    @Test fun waypointNamesAreSanitizedOnTheWayIn() {
        val long = "x".repeat(Waypoint.MAX_NAME_LENGTH + 20)
        val doc = readManifest("""{"format":"xcanvas","waypoints":[{"name":"  $long  ","cx":0,"cy":0,"zoom":1}],"items":[]}""")
        assertEquals(Waypoint.MAX_NAME_LENGTH, doc.waypoints[0].name.length)
    }

    @Test fun writeCancelledAbortsTheAssetCopy() {
        val doc = InfiniteDocument()
        doc.add(ImageItem(ImageData(imageFile(ByteArray(256 * 1024)), 8, 8), Rect(0.0, 0.0, 8.0, 8.0)))
        assertThrows(CanvasCodec.WriteCancelled::class.java) {
            codec.write(doc, ByteArrayOutputStream()) { true }
        }
    }
}
