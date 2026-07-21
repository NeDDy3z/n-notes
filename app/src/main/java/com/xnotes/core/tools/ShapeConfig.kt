package com.xnotes.core.tools

/** Shape kinds drawn by the shape tool (spec 02 §5.4, 04 §6). Polygon/polyline are
 *  vertex-list shapes produced only by hold-to-snap recognition, not the shape tool. */
enum class ShapeKind(val id: String) {
    LINE("line"),
    ARROW("arrow"),
    RECTANGLE("rectangle"),
    ELLIPSE("ellipse"),
    CIRCLE("circle"),
    TRIANGLE("triangle"),
    POLYGON("polygon"),
    POLYLINE("polyline"),
    CURVE("curve");

    /** Closed shapes are stroked and optionally filled; open shapes never fill. */
    val isClosed: Boolean get() = this == RECTANGLE || this == ELLIPSE || this == CIRCLE || this == TRIANGLE || this == POLYGON
    val isOpen: Boolean get() = !isClosed

    /** Line/arrow resize by dragging their two endpoints; every other kind resizes by its box. */
    val isEndpointShape: Boolean get() = this == LINE || this == ARROW

    companion object {
        /** Kinds the shape tool offers; polygon/polyline arrive only from recognition. */
        val DRAW_TOOL_KINDS = listOf(LINE, ARROW, RECTANGLE, ELLIPSE, CIRCLE, TRIANGLE)

        fun fromId(id: String?): ShapeKind = entries.firstOrNull { it.id == id } ?: RECTANGLE
    }
}

/** The style carried by the shape tool (spec 04 §6); persisted between sessions. */
data class ShapeConfig(
    val shape: ShapeKind = ShapeKind.RECTANGLE,
    val strokeWidth: Double = 3.0,
    val fill: Boolean = false,
    /** Neon glow: a luminous halo + white-hot core on the shape's outline. */
    val neon: Boolean = false,
    /** Glow intensity in [0, 1] (halo size + brightness); used only when [neon]. */
    val neonStrength: Double = 0.6,
    /** Dashed outline: dash/gap run lengths in content px; used only when [dashed]. */
    val dashed: Boolean = false,
    val dashLength: Double = 10.0,
    val dashGap: Double = 8.0,
) {
    companion object {
        /** Reduced opacity applied to the ink colour when a closed shape is filled. */
        const val FILL_ALPHA = 0.25
    }
}
