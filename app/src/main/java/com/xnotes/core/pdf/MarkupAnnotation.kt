package com.xnotes.core.pdf

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.MarkupType
import com.xnotes.core.model.TextMarkup

/**
 * A text markup as the annotation an exported PDF carries (ISO 32000-1, 12.5.6.10): its quads and
 * box in the page's user space, and an appearance stream drawing it as [MarkupPainter] does on
 * screen. The stream is in user space too, so its /BBox is [rect] and its /Matrix the identity. A
 * highlight fills all its quads as one path, so where its lines overlap it darkens once, laid on
 * through the graphics state [GS] (Multiply at its intensity).
 */
class MarkupAnnotation(
    /** Eight numbers a quad: its line's start then end along the glyphs' tops, then along their feet. */
    val quadPoints: FloatArray,
    /** The annotation's /Rect, which holds all it draws; left and top are the smaller x and y. */
    val rect: Rect,
    /** The appearance stream's operators. */
    val content: String,
) {
    companion object {
        /** The name a highlight's appearance gives its Multiply graphics state. */
        const val GS = "GS0"

        /** [m] as an annotation; [toUser] takes a point of the page as displayed, in points, to user space. Null with no quads. */
        fun of(m: TextMarkup, toUser: (Double, Double) -> Pt): MarkupAnnotation? {
            if (m.quads.isEmpty()) return null
            val user = { p: Pt -> toUser(p.x, p.y) }
            val quadPoints = FloatArray(8 * m.quads.size)
            var k = 0
            for (q in m.quads) {
                for (p in MarkupPainter.corners(q).map(user)) {
                    quadPoints[k++] = p.x.toFloat()
                    quadPoints[k++] = p.y.toFloat()
                }
            }
            val sb = StringBuilder()
            fun point(p: Pt, op: String) {
                PdfNumbers.append(sb, p.x)
                sb.append(' ')
                PdfNumbers.append(sb, p.y)
                sb.append(' ').append(op).append('\n')
            }
            fun color(op: String) {
                for (v in intArrayOf(m.color.r, m.color.g, m.color.b)) {
                    PdfNumbers.append(sb, v / 255.0)
                    sb.append(' ')
                }
                sb.append(op).append('\n')
            }
            var shown: Rect? = null
            fun shows(r: Rect) {
                shown = shown?.union(r) ?: r
            }
            if (m.type == MarkupType.HIGHLIGHT) {
                sb.append("q\n/").append(GS).append(" gs\n")
                color("rg")
                for (q in m.quads) {
                    val (ul, ur, ll, lr) = MarkupPainter.corners(q).map(user)
                    point(ul, "m")
                    point(ur, "l")
                    point(lr, "l")
                    point(ll, "l")
                    sb.append("h\n")
                    shows(quadRect(q))
                }
                sb.append("f\nQ\n")
            } else {
                color("RG")
                sb.append("1 J\n1 j\n")
                for (q in m.quads) {
                    val line = MarkupPainter.lineOf(m.type, q)
                    if (line.size < 2) continue
                    val w = MarkupPainter.thickness(q)
                    PdfNumbers.append(sb, w)
                    sb.append(" w\n")
                    point(user(line[0]), "m")
                    for (i in 1 until line.size) point(user(line[i]), "l")
                    sb.append("S\n")
                    // Round caps reach half the width past the line's ends.
                    shows(quadRect(q).outset(w / 2))
                }
            }
            if (m.note != null) MarkupPainter.markerOf(m)?.let { marker ->
                color("rg")
                circle(sb, marker.center, marker.radius, user, ::point)
                sb.append("f\n")
                point(user(marker.tail[0]), "m")
                for (i in 1 until marker.tail.size) point(user(marker.tail[i]), "l")
                sb.append("h\nf\n")
                shows(marker.bounds)
            }
            val d = shown ?: return null
            val rect = Rect.bounding(listOf(Pt(d.left, d.top), Pt(d.right, d.top), Pt(d.left, d.bottom), Pt(d.right, d.bottom)).map(user))
            return MarkupAnnotation(quadPoints, rect, sb.toString())
        }

        private fun quadRect(q: TextQuad): Rect =
            Rect.ltrb(q.left.toDouble(), q.top.toDouble(), q.right.toDouble(), q.bottom.toDouble())

        /** A closed circle as four Béziers, built where it is displayed and written where [user] puts it. */
        private fun circle(sb: StringBuilder, c: Pt, r: Double, user: (Pt) -> Pt, point: (Pt, String) -> Unit) {
            val k = r * 0.5522847498307936
            fun at(dx: Double, dy: Double) = user(Pt(c.x + dx, c.y + dy))
            fun curve(a: Pt, b: Pt, end: Pt) {
                for (p in listOf(a, b, end)) {
                    PdfNumbers.append(sb, p.x)
                    sb.append(' ')
                    PdfNumbers.append(sb, p.y)
                    sb.append(' ')
                }
                sb.append("c\n")
            }
            point(at(r, 0.0), "m")
            curve(at(r, k), at(k, r), at(0.0, r))
            curve(at(-k, r), at(-r, k), at(-r, 0.0))
            curve(at(-r, -k), at(-k, -r), at(0.0, -r))
            curve(at(k, -r), at(r, -k), at(r, 0.0))
            sb.append("h\n")
        }
    }
}
