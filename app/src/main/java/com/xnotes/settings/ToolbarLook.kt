package com.xnotes.settings

/** How the editor toolbar is drawn; one setting for both the paged and the canvas bar. */
data class ToolbarLook(
    val size: ToolbarSize = ToolbarSize.REGULAR,
)

/** How big the toolbar's buttons are. */
enum class ToolbarSize(val id: String) {
    COMPACT("compact"),
    REGULAR("regular"),
    COMFORTABLE("comfortable");

    companion object {
        fun fromId(id: String): ToolbarSize = entries.find { it.id == id } ?: REGULAR
    }
}
