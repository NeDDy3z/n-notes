package com.xnotes.core.vector

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.MeshBuilder
import com.xnotes.core.infinite.MeshPart
import com.xnotes.core.model.Rgba
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns a [VectorScene] into the triangles the infinite canvas draws, placed in content space.
 *
 * This is what makes a placed SVG resolution-free. The triangles go into the same vertex buffer as
 * the ink, so a pinch costs nothing: the geometry never leaves the GPU and never gets scaled, it
 * gets re-projected. There is no texture to go soft, no bucket to re-rasterize at, and no decode
 * to wait for mid-gesture.
 *
 * The placement transform is applied before flattening rather than after, so the curve tolerance is
 * stated in content pixels once and holds however the item was scaled, turned or squashed.
 */
object VectorMesher {

    /**
     * [scene] meshed into the box [rect], turned by [orientation] quarter-turns' worth of degrees
     * and then by [angle] radians, both about the box's centre — the same placement the raster path
     * gives a bitmap, so an SVG lands where a PNG would.
     *
     * [tolerance] is the deviation allowed from the true curve, in content pixels. Geometry is
     * uploaded once, so this is fixed at the value that stays invisible at the canvas's deepest
     * zoom rather than chased as the view moves.
     */
    fun mesh(
        scene: VectorScene,
        rect: Rect,
        orientation: Int = 0,
        angle: Double = 0.0,
        tolerance: Double,
    ): List<MeshPart> {
        if (scene.isEmpty || rect.w <= 0.0 || rect.h <= 0.0) return emptyList()
        val place = placement(scene, rect, orientation, angle)
        val scale = place.lengthScale()
        val parts = ArrayList<MeshPart>()
        var vertices = 0
        for (path in scene.paths) {
            val clip = path.clip?.let { clipQuad(it, place) }
            if (clip != null && clip.isEmpty()) continue // a clip that hides everything
            val rings = ArrayList<List<Pt>>(path.contours.size)
            val lines = ArrayList<Flat>(path.contours.size)
            for (contour in path.contours) {
                val moved = transform(contour, place)
                val pts = PathFlattener.flatten(moved, tolerance)
                if (pts.size < 2) continue
                if (clip == null) {
                    lines.add(Flat(pts, moved.closed))
                    // An open subpath still fills as if closed, which is what the spec says.
                    if (pts.size >= 3) rings.add(pts)
                } else {
                    // A clipped stroke is cut down its centreline, so it can overhang the clip by
                    // up to half its own width. A clipped fill is exact.
                    for (run in PolygonClip.polyline(pts, moved.closed, clip)) lines.add(Flat(run, false))
                    if (pts.size >= 3) PolygonClip.polygon(pts, clip).takeIf { it.size >= 3 }?.let(rings::add)
                }
            }
            if (lines.isEmpty() && rings.isEmpty()) continue
            path.fill?.let { fillPart(rings, it, path) }?.let {
                parts.add(it)
                vertices += it.mesh.vertexCount
            }
            path.stroke?.let { strokePart(lines, it, path, scale, tolerance) }?.let {
                parts.add(it)
                vertices += it.mesh.vertexCount
            }
            if (vertices >= MAX_VERTICES) break
        }
        return parts
    }

    /** A flattened contour and whether it closes, which decides both filling and capping. */
    private class Flat(val points: List<Pt>, val closed: Boolean)

    /** A document-space clip rectangle as the (possibly turned) convex quad it becomes on screen. */
    private fun clipQuad(clip: Rect, place: Affine): List<Pt> {
        if (clip.w <= 0.0 || clip.h <= 0.0) return emptyList()
        return PolygonClip.wound(
            listOf(
                place.map(Pt(clip.left, clip.top)),
                place.map(Pt(clip.right, clip.top)),
                place.map(Pt(clip.right, clip.bottom)),
                place.map(Pt(clip.left, clip.bottom)),
            ),
        )
    }

    /**
     * The document box mapped onto [rect]. A quarter turn swaps which side of the box each document
     * axis lands on, exactly as the bitmap path does, and the free angle turns the result about the
     * same centre, so the two compose by adding.
     */
    private fun placement(scene: VectorScene, rect: Rect, orientation: Int, angle: Double): Affine {
        val turns = ((orientation % 360) + 360) % 360
        val turned = turns == 90 || turns == 270
        val boxW = if (turned) rect.h else rect.w
        val boxH = if (turned) rect.w else rect.h
        val theta = turns * PI / 180.0 + angle
        val turn = Affine(cos(theta), sin(theta), -sin(theta), cos(theta))
        return Affine.translate(rect.centerX, rect.centerY)
            .times(turn)
            .times(Affine.translate(-boxW / 2.0, -boxH / 2.0))
            .times(Affine.scale(boxW / scene.width, boxH / scene.height))
    }

    private fun transform(c: VectorContour, m: Affine): VectorContour = VectorContour(
        m.map(c.start),
        c.segments.map { seg ->
            when (seg) {
                is VectorSeg.Line -> VectorSeg.Line(m.map(seg.end))
                is VectorSeg.Cubic -> VectorSeg.Cubic(m.map(seg.c1), m.map(seg.c2), m.map(seg.end))
            }
        },
        c.closed,
    )

    /**
     * A filled path as triangles, or as an inverting stencil fill when the triangulator refuses
     * the outline. The stencil version is a fan from one point over every edge: parity of coverage
     * is the even-odd rule, so it is exact for holes and self-crossings alike.
     */
    private fun fillPart(rings: List<List<Pt>>, paint: VectorPaint, path: VectorPath): MeshPart? {
        if (rings.isEmpty()) return null
        val ramp = GradientRamp.of(paint)
        val color = ramp?.average() ?: solid(paint) ?: return null
        if (color.a <= 0) return null
        val mesh = Triangulator.triangulate(rings, path.fillRule)
        if (mesh != null) {
            val data = if (ramp != null) {
                GradientFill.refine(mesh, ramp)
            } else {
                val mb = MeshBuilder(mesh.points.size, mesh.indices.size)
                for (p in mesh.points) mb.vertex(p.x, p.y)
                var i = 0
                while (i < mesh.indices.size) {
                    mb.triangle(mesh.indices[i], mesh.indices[i + 1], mesh.indices[i + 2])
                    i += 3
                }
                if (mb.isEmpty) return null
                mb.build()
            }
            if (data.isEmpty) return null
            // Ear clipping tiles the fill rather than overlapping it, so even a translucent one can
            // go straight into the batched draw with its alpha in the vertices. That is worth
            // having: a diagram full of soft fills would otherwise cost two calls apiece.
            return MeshPart(data, color, InkPass.OPAQUE)
        }
        // The stencil cover is one flat colour, so an outline too tangled to triangulate loses its
        // ramp and takes the average. Nothing real hits both at once.
        val mb = MeshBuilder(64, 96)
        val pivot = mb.vertex(rings[0][0].x, rings[0][0].y)
        for (ring in rings) {
            var prev = mb.vertex(ring[0].x, ring[0].y)
            val first = prev
            for (k in 1 until ring.size) {
                val v = mb.vertex(ring[k].x, ring[k].y)
                mb.triangle(pivot, prev, v)
                prev = v
            }
            mb.triangle(pivot, prev, first)
        }
        if (mb.isEmpty) return null
        return MeshPart(mb.build(), color, InkPass.EVEN_ODD)
    }

    private fun strokePart(
        lines: List<Flat>,
        paint: VectorPaint,
        path: VectorPath,
        scale: Double,
        tolerance: Double,
    ): MeshPart? {
        val ramp = GradientRamp.of(paint)
        val color = ramp?.average() ?: solid(paint) ?: return null
        if (color.a <= 0) return null
        val halfWidth = path.strokeWidth * scale / 2.0
        if (halfWidth <= 0.0) return null
        val mb = MeshBuilder(128, 192)
        val dash = path.dash?.map { it * scale }?.toDoubleArray()?.takeIf { it.sum() > 1e-9 }
        for (line in lines) {
            if (dash == null) {
                StrokeOutliner.outline(
                    mb, line.points, line.closed, halfWidth,
                    path.cap, path.join, path.miterLimit, tolerance,
                )
                continue
            }
            for (run in StrokeOutliner.dash(line.points, line.closed, dash, path.dashOffset * scale)) {
                StrokeOutliner.outline(
                    mb, run, false, halfWidth,
                    path.cap, path.join, path.miterLimit, tolerance,
                )
            }
        }
        if (mb.isEmpty) return null
        val data = mb.build()
        // A gradient has to be per-vertex colour, which rules out the single-colour cover. The
        // ribbon's vertices already track the path closely, so the ramp resolves without refining.
        if (ramp != null) return MeshPart(GradientFill.color(data, ramp), color, InkPass.OPAQUE)
        // A stroke overlaps itself at every join and cap, so a translucent one has to accumulate
        // opaquely and take its alpha back once, the way translucent ink already does.
        return MeshPart(data, color, if (color.a >= 255) InkPass.OPAQUE else InkPass.TRANSLUCENT)
    }

    private fun solid(paint: VectorPaint): Rgba? = (paint as? VectorPaint.Solid)?.color?.takeIf { it.a > 0 }

    /**
     * A ceiling on one image's geometry. A machine-generated map can hold a hundred thousand
     * paths, and the buffer mirrors itself on the heap, so past this the rest of the document is
     * dropped rather than taking the canvas down with it.
     */
    const val MAX_VERTICES = 800_000
}
