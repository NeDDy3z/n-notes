package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * A small on-disk cache of note thumbnails so the explorer grid paints instantly across
 * launches instead of re-rendering every note.
 *
 * Each note URI maps to one file keyed by a hash of the URI: `<key>.png`. The cache is
 * authoritative — a present thumbnail is shown as-is; callers drop it (via [remove]) whenever
 * the note's content changes, so a present file is always current. No mtime bookkeeping.
 *
 * Bounded by [trimToCap]: unlike the old recents cache (capped at 10), the explorer can
 * touch many files across folders, so after each [store] the oldest entries beyond
 * [maxFiles] are dropped (LRU by file mtime). Best-effort — failures are swallowed and
 * just cause a re-render.
 */
class NoteThumbnailCache(private val dir: File, private val maxFiles: Int = 256) {

    /** Drops every thumbnail drawn before the current [FORMAT], once, on first use (off the main thread in practice). */
    private val current by lazy {
        runCatching {
            val mark = File(dir, MARK)
            if (mark.takeIf { it.exists() }?.readText()?.trim() != FORMAT.toString()) {
                dir.listFiles()?.forEach { it.delete() }
                dir.mkdirs()
                mark.writeText(FORMAT.toString())
            }
        }
    }

    /**
     * The cached thumbnail, decoded small and 16-bit for the grid, or null when not cached.
     * Tiles are opaque (filled with the paper colour), so RGB_565 halves the bytes of ARGB with
     * no visible loss, and downsampling to ~[DECODE_PX] cuts both the allocation and the GPU
     * texture upload — the two things that drop frames while the grid scrolls.
     */
    fun load(uri: String): Bitmap? {
        current
        val png = File(dir, "${key(uri)}.png")
        if (!png.exists()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(png.path, bounds)
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = sampleSize(bounds.outWidth, DECODE_PX)
            }
            BitmapFactory.decodeFile(png.path, opts)
        }.getOrNull()
    }

    /** Largest power-of-two subsample that keeps the decoded width at or above [reqPx]. */
    private fun sampleSize(srcPx: Int, reqPx: Int): Int {
        if (srcPx <= 0 || reqPx <= 0) return 1
        var s = 1
        while (srcPx / (s * 2) >= reqPx) s *= 2
        return s
    }

    /** Cache [bitmap] for [uri]. */
    fun store(uri: String, bitmap: Bitmap) {
        current
        runCatching {
            dir.mkdirs()
            FileOutputStream(File(dir, "${key(uri)}.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            trimToCap()
        }
    }

    fun remove(uri: String) {
        val key = key(uri)
        runCatching { File(dir, "$key.png").delete() }
        runCatching { File(dir, "$key.txt").delete() }
    }

    /** Delete cached files for any URI not in [keep]. */
    fun prune(keep: Set<String>) {
        runCatching {
            val keepKeys = keep.mapTo(HashSet()) { key(it) }
            dir.listFiles()?.forEach { f ->
                if (f.name != MARK && f.name.substringBeforeLast('.') !in keepKeys) f.delete()
            }
        }
    }

    /** Keep only the newest [maxFiles] thumbnails (by file mtime); drop the rest with their sidecars. */
    private fun trimToCap() {
        runCatching {
            val pngs = dir.listFiles { f -> f.name.endsWith(".png") } ?: return
            if (pngs.size <= maxFiles) return
            pngs.sortedByDescending { it.lastModified() }.drop(maxFiles).forEach { f ->
                f.delete()
                File(dir, "${f.name.substringBeforeLast('.')}.txt").delete()
            }
        }
    }

    private fun key(uri: String): String =
        MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    private companion object {
        /** Target decode width for grid tiles; well above the on-screen tile size on a tablet. */
        const val DECODE_PX = 300

        /** How tiles are drawn; bump it when that changes so old ones are redrawn. 2: wide pages centred. */
        const val FORMAT = 2
        const val MARK = "format"
    }
}
