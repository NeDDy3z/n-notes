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
