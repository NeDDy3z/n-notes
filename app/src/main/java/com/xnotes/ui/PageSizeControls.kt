package com.xnotes.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.core.model.PageSize
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** The page size menu: every named size and Custom, after an [unset] choice (picking null) when one is named. */
@Composable
internal fun SizeDropdown(size: PageSize?, unset: String? = null, onSelect: (PageSize?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    Box {
        Box(
            Modifier
                .clip(MaterialTheme.shapes.small)
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.small)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(size?.let { pageSizeLabel(it) } ?: unset.orEmpty(), color = palette.text.toComposeColor(), fontSize = 14.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (unset != null) DropdownMenuItem(text = { Text(unset) }, onClick = { onSelect(null); expanded = false })
            PageSize.entries.forEach { s ->
                DropdownMenuItem(text = { Text(pageSizeLabel(s)) }, onClick = { onSelect(s); expanded = false })
            }
        }
    }
}

/** A typed side of a custom page in millimetres, or null unless it parses inside the settable range. */
internal fun parseMm(raw: String): Double? =
    raw.trim().toDoubleOrNull()?.takeIf { it in PageSize.CUSTOM_MIN_MM..PageSize.CUSTOM_MAX_MM }

/** Drop a whole number's ".0" so the field reads "210", not "210.0". */
internal fun formatMm(v: Double): String =
    if (v == Math.floor(v) && !v.isInfinite()) v.toInt().toString() else v.toString()
