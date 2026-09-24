package com.xnotes.settings

enum class MaterialStyle(val id: String) {
    TONAL_SPOT("tonal_spot"),
    VIBRANT("vibrant"),
    FIDELITY("fidelity"),
    EXPRESSIVE("expressive"),
    FRUIT_SALAD("fruit_salad"),
    RAINBOW("rainbow"),
    NEUTRAL("neutral"),
    MONOCHROME("monochrome");

    companion object {
        fun fromId(id: String): MaterialStyle = entries.find { it.id == id } ?: TONAL_SPOT
    }
}

/** How round the chrome's corners are; [scale] multiplies every shape role's radius. */
enum class CornerStyle(val id: String, val scale: Float) {
    SHARP("sharp", 0.35f),
    ROUNDED("rounded", 1f),
    SOFT("soft", 1.75f);

    companion object {
        fun fromId(id: String): CornerStyle = entries.find { it.id == id } ?: ROUNDED
    }
}
