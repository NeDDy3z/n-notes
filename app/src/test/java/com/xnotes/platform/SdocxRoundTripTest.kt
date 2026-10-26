package com.xnotes.platform

import com.xnotes.format.DocumentCodec
import com.xnotes.core.model.CanvasItem
import org.junit.Test
import java.io.File

/** Full JVM pipeline probe: sdocx -> Document -> .xnote bytes -> Document. Skipped without SDOCX. */
class SdocxRoundTripTest {
    @Test
    fun roundTrip() {
        val path = System.getenv("SDOCX") ?: return
        val src = File(path)
        val imageDir = File("/tmp/sdocx-img").apply { mkdirs() }
        val result = SdocxReader.readForProbe(src, imageDir, SdocxReader.StubTextMeasurer, 160)
        val doc = result.getOrNull()
        println("READ err=${result.exceptionOrNull()} pages=${doc?.pages?.size} items=" + doc?.pages?.map { it.items.size } +
            " flow=" + doc?.flow?.paragraphs?.map { it.plainText().take(30) })
        if (doc == null) return

        val out = File("/tmp/sdocx-rt.xnote")
        val codec = DocumentCodec(SdocxTestImageCodec, SdocxReader.StubTextMeasurer)
        codec.write(doc, out.outputStream().buffered()) { false }
        val back = codec.read(out.inputStream().buffered())
        println("ROUNDTRIP pages=${back.pages.size} items=" + back.pages.map { it.items.size } +
            " flowParas=" + back.flow.paragraphs.size + " flowText=" + back.flow.plainText().take(60))
        for ((i, p) in back.pages.withIndex()) {
            println("  page $i: " + p.items.groupingBy { it::class.simpleName }.eachCount() + " style=${p.style?.pageColor}")
        }
    }
}

/** Image codec stub: probe nothing, just pass the bytes through. */
object SdocxTestImageCodec : com.xnotes.core.pal.ImageCodec {
    override fun probeFile(path: String): com.xnotes.core.pal.ImageSize? = null
}
