package com.xnotes.core.infinite

/**
 * A named saved view. Stored as a content-space centre plus zoom rather than a scroll offset, so
 * jumping back lands on the same content whatever size the screen is now.
 */
data class Waypoint(
    val name: String,
    val cx: Double,
    val cy: Double,
    val zoom: Double,
) {
    companion object {
        /** Longest name the UI accepts; longer ones are truncated on the way in. */
        const val MAX_NAME_LENGTH = 48

        fun sanitizeName(raw: String): String = raw.trim().take(MAX_NAME_LENGTH)
    }
}
