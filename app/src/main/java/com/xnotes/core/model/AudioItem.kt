package com.xnotes.core.model

import com.xnotes.core.audio.AudioPill
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextMeasurer

/**
 * A recording or an attached audio file, placed as a pill that plays it: a disc to play or pause,
 * the [name] and length over a waveform that shows how far playback has got. One fixed size, never
 * resized, turned or mirrored; it only moves.
 *
 * [rid] identifies the recording within its document. Items added while it was being recorded carry
 * it in their [SyncMark], which is how playback finds the ink it should fade and seek by.
 */
class AudioItem(
    var audio: AudioData,
    var rect: Rect,
    var name: String,
    var rid: Int,
    val measurer: TextMeasurer,
) : CanvasItem {

    override val kind = KIND
    override val resizable = false
    override val mirrorable = false
    override var locked = false

    /** Where the open player stands on this recording, in ms, or -1 while none is open on it. Never saved. */
    @Volatile
    var playhead: Int = -1

    /** Whether that player is playing rather than paused. */
    @Volatile
    var playing: Boolean = false

    override fun paint(r: Renderer) = AudioPill.paint(r, this, AudioLook.current)

    override fun bounds(): Rect = rect

    override fun translate(dx: Double, dy: Double) {
        rect = rect.translate(dx, dy)
    }

    override fun contains(p: Pt): Boolean = rect.contains(p)

    override fun centroid(): Pt = rect.center

    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean =
        rect.distanceTo(Pt(cx, cy)) <= radius

    override fun snapshotGeometry(): GeometrySnapshot = AudioSnapshot(rect)

    override fun restoreGeometry(snap: GeometrySnapshot) {
        if (snap is AudioSnapshot) rect = snap.rect
    }

    /** A selection's scale, turn or mirror only carries the pill's centre along: it keeps its size and stays upright. */
    override fun applyTransform(t: Affine) {
        val c = t.apply(rect.center)
        rect = Rect(c.x - rect.w / 2.0, c.y - rect.h / 2.0, rect.w, rect.h)
    }

    companion object {
        const val KIND = "audio"

        /** The pill's size on paper. */
        const val WIDTH_MM = 64.0
        const val HEIGHT_MM = 14.0

        /** The pill's box centred on [center], sized for [dpi] content px per inch seen at [zoom]. */
        fun boxAt(center: Pt, dpi: Int, zoom: Double = 1.0): Rect {
            val w = WIDTH_MM / 25.4 * dpi / zoom
            val h = HEIGHT_MM / 25.4 * dpi / zoom
            return Rect(center.x - w / 2.0, center.y - h / 2.0, w, h)
        }
    }
}

/** [this] as an export draws it: recordings are left out of every export, so nothing of them shows. */
fun List<CanvasItem>.withoutAudio(): List<CanvasItem> = if (none { it is AudioItem }) this else filterNot { it is AudioItem }

/** Snapshot of a pill's geometry: only where it sits. */
private data class AudioSnapshot(val rect: Rect) : GeometrySnapshot
