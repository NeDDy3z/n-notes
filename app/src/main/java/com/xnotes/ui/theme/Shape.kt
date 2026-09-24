package com.xnotes.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import com.xnotes.settings.CornerStyle

/** Material's shape roles, every radius scaled by [style]. Paper and hairlines keep literal corners. */
fun uiShapes(style: CornerStyle): Shapes {
    val k = style.scale
    return Shapes(
        extraSmall = RoundedCornerShape((4 * k).dp),
        small = RoundedCornerShape((8 * k).dp),
        medium = RoundedCornerShape((12 * k).dp),
        large = RoundedCornerShape((16 * k).dp),
        extraLarge = RoundedCornerShape((28 * k).dp),
    )
}
