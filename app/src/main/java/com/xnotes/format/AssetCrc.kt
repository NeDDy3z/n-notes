package com.xnotes.format

import java.io.File
import java.io.FileInputStream
import java.util.zip.CRC32

/**
 * Remembered CRC32s of the files that go into a bundle as STORED entries: images and the embedded
 * source PDF.
 *
 * A STORED entry has to carry its size and checksum in the header that precedes it, so the writer
 * used to read every asset twice per save, once to checksum and once to copy. On a note built from
 * a 500-page PDF that is the whole PDF read twice for a save that changed one stroke. A file that
 * has not changed cannot have a new checksum, so the second read is pure waste.
 *
 * Keyed on path, length and last-modified together, so a file replaced in place is still noticed.
 * Bounded, because a session can touch a lot of images and this must never become a leak.
 */
internal object AssetCrc {

    private const val MAX_ENTRIES = 64

    private val cache = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > MAX_ENTRIES
    }

    fun of(file: File): Long {
        val key = "${file.path}|${file.length()}|${file.lastModified()}"
        synchronized(cache) { cache[key] }?.let { return it }
        val crc = compute(file)
        synchronized(cache) { cache[key] = crc }
        return crc
    }

    private fun compute(file: File): Long {
        val crc = CRC32()
        val buf = ByteArray(64 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        return crc.value
    }
}
