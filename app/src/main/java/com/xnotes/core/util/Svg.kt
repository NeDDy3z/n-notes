package com.xnotes.core.util

import java.io.File

/**
 * Sniffs whether a file holds SVG markup. Every raster image format opens with a binary magic
 * number, so a leading '<' (after an optional UTF-8 BOM and whitespace) means vector markup.
 * Content-based on purpose: inserted images live in extension-less temp files.
 */
object Svg {
    fun isSvgFile(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(64)
            val n = input.read(head)
            var i = 0
            if (n >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) i = 3
            while (i < n && head[i].toInt().toChar().isWhitespace()) i++
            i < n && head[i] == '<'.code.toByte()
        }
    }.getOrDefault(false)
}
