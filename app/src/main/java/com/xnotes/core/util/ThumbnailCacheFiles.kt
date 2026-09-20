package com.xnotes.core.util

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

// A shared generation prevents another pane or an old render from restoring discarded thumbnails.
class ThumbnailCacheFiles(private val dir: File, private val format: Int, private val maxFiles: Int = 256) {
    private class State {
        var theme: String? = null
        var generation = 0L
    }

    private val state = states.getOrPut(dir.canonicalPath) { State() }

    fun useTheme(theme: String): Long = synchronized(state) {
        val signature = "$format\n$theme"
        if (state.theme != signature) {
            state.generation++
            state.theme = signature
            runCatching {
                val mark = File(dir, MARK)
                if (!mark.exists() || mark.readText() != signature) {
                    dir.listFiles()?.forEach { it.delete() }
                    dir.mkdirs()
                    mark.writeText(signature)
                }
            }
        }
        state.generation
    }

    fun isCurrent(generation: Long): Boolean = synchronized(state) {
        state.theme != null && state.generation == generation
    }

    fun <T> load(uri: String, generation: Long, read: (File) -> T?): T? = synchronized(state) {
        if (!isCurrent(generation)) return@synchronized null
        val file = File(dir, "${key(uri)}.png")
        if (!file.exists()) return@synchronized null
        runCatching { read(file) }.getOrNull()
    }

    fun store(uri: String, generation: Long, write: (File) -> Unit) = synchronized(state) {
        if (!isCurrent(generation)) return@synchronized
        runCatching {
            dir.mkdirs()
            write(File(dir, "${key(uri)}.png"))
            val pngs = dir.listFiles { f -> f.name.endsWith(".png") }.orEmpty()
            pngs.sortedByDescending { it.lastModified() }.drop(maxFiles).forEach { removeFile(it) }
        }
        Unit
    }

    fun remove(uri: String) = synchronized(state) {
        removeFile(File(dir, "${key(uri)}.png"))
    }

    fun prune(keep: Set<String>) = synchronized(state) {
        runCatching {
            val keys = keep.mapTo(HashSet()) { key(it) }
            dir.listFiles()?.forEach { file ->
                if (file.name != MARK && file.name.substringBeforeLast('.') !in keys) file.delete()
            }
        }
        Unit
    }

    private fun removeFile(file: File) {
        runCatching { file.delete() }
        runCatching { File(dir, "${file.nameWithoutExtension}.txt").delete() }
    }

    private fun key(uri: String): String =
        MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    private companion object {
        const val MARK = "format"
        val states = ConcurrentHashMap<String, State>()
    }
}
