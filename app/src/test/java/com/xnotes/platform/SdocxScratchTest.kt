package com.xnotes.platform

import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Scratch probe over a real sdocx; run with -Dsdocx=/path/to/file.sdocx. Skipped when unset. */
class SdocxScratchTest {
    @Test
    fun dump() {
        val path = System.getenv("SDOCX") ?: return
        ZipFile(File(path)).use { zip ->
            val entries = zip.entries().asSequence().toList()
            println("ENTRIES: " + entries.joinToString { it.name })
            val order = zip.getEntry("pageIdInfo.dat")?.let {
                zip.getInputStream(it).use { s -> s.readBytes() }
            }
            println("pageIdInfo bytes=${order?.size}")
            val note = zip.getEntry("note.note")?.let {
                zip.getInputStream(it).use { s -> com.xnotes.platform.SdocxReader.readNoteForProbe(s.readBytes()) }
            }
            println("NOTE ok=${note?.getOrNull() != null} err=${note?.exceptionOrNull()} " +
                "title=\"${note?.getOrNull()?.title?.take(60)}\" paragraphs=${note?.getOrNull()?.bodyParagraphs?.size} " +
                "dims=${note?.getOrNull()?.width}x${note?.getOrNull()?.height} pad=${note?.getOrNull()?.paddingX},${note?.getOrNull()?.paddingY}")
            for (e in entries.filter { it.name.endsWith(".page") }) {
                val data = zip.getInputStream(e).use { s -> s.readBytes() }
                try {
                    val page = SdocxReader.readPage(data)
                    println("PAGE ${e.name} ${page.width}x${page.height} color=${page.colorArgb} elements=${page.elements.size}")
                    for (el in page.elements) {
                        when (el) {
                            is SdocxReader.SdocxStroke -> println(
                                "  stroke pts=${el.xs.size} color=${el.colorArgb?.let { Integer.toHexString(it) }} w=${el.penSize} " +
                                    "bbox=${el.bbox.joinToString()} first=(${el.xs.firstOrNull()},${el.ys.firstOrNull()})"
                            )
                            is SdocxReader.SdocxTextBox -> println(
                                "  text \"${el.text.take(60)}\" color=${el.color} size=${el.fontSize} b=${el.bold} embedded=${el.embedded.size}"
                            )
                            is SdocxReader.SdocxImage -> println("  image mediaId=${el.mediaId} visible=${el.visible} bbox=${el.bbox.joinToString()}")
                            else -> println("  ? $el")
                        }
                    }
                    // The mapping the app does, on JVM: how many items actually land on the Page.
                    val mapped = SdocxReader.pageToPage(page, SdocxReader.emptyMediaIndexForProbe(), File("/tmp"), SdocxReader.StubTextMeasurer)
                    println("  MAPPED items=${mapped.items.size} kinds=" + mapped.items.groupingBy { it::class.simpleName }.eachCount())
                } catch (t: Throwable) {
                    println("PAGE ${e.name} FAILED: $t")
                }
            }
        }
    }
}
