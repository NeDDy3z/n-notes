package com.xnotes.format

import java.io.Writer

/**
 * Minimal streaming JSON writer, the write-side twin of [JsonPull]. Emits the same
 * compact form org.json's DOM produced on Android (its number formatting, escape
 * rules and separator placement), so a note re-saved through this writer is
 * byte-identical to one the old path wrote, without ever materializing the DOM
 * or the whole manifest string.
 */
internal class JsonWrite(private val out: Writer) {

    private var first = BooleanArray(32)
    private var depth = 0
    private var afterName = false

    fun beginObject(): JsonWrite {
        element()
        out.write("{")
        push()
        return this
    }

    fun endObject(): JsonWrite {
        out.write("}")
        depth--
        return this
    }

    fun beginArray(): JsonWrite {
        element()
        out.write("[")
        push()
        return this
    }

    fun endArray(): JsonWrite {
        out.write("]")
        depth--
        return this
    }

    fun name(key: String): JsonWrite {
        if (first[depth - 1]) first[depth - 1] = false else out.write(",")
        string(key)
        out.write(":")
        afterName = true
        return this
    }

    fun value(v: String): JsonWrite {
        element()
        string(v)
        return this
    }

    fun value(v: Double): JsonWrite {
        element()
        out.write(formatDouble(v))
        return this
    }

    fun value(v: Int): JsonWrite {
        element()
        out.write(v.toString())
        return this
    }

    fun value(v: Boolean): JsonWrite {
        element()
        out.write(if (v) "true" else "false")
        return this
    }

    fun nullValue(): JsonWrite {
        element()
        out.write("null")
        return this
    }

    private fun push() {
        if (depth == first.size) first = first.copyOf(depth * 2)
        first[depth] = true
        depth++
    }

    /** Emits the ',' before an array element; a named value's ',' came with its name. */
    private fun element() {
        if (afterName) {
            afterName = false
            return
        }
        if (depth == 0) return
        if (first[depth - 1]) first[depth - 1] = false else out.write(",")
    }

    /** org.json's number form: integral doubles print as longs ("30", not "30.0"). */
    private fun formatDouble(v: Double): String {
        require(!v.isNaN() && !v.isInfinite()) { "Forbidden numeric value: $v" }
        if (v == 0.0 && 1.0 / v < 0) return "-0"
        val l = v.toLong()
        return if (l.toDouble() == v) l.toString() else v.toString()
    }

    private fun string(s: String) {
        out.write("\"")
        for (c in s) {
            when (c) {
                '"', '\\', '/' -> {
                    out.write('\\'.code)
                    out.write(c.code)
                }
                '\t' -> out.write("\\t")
                '\b' -> out.write("\\b")
                '\n' -> out.write("\\n")
                '\r' -> out.write("\\r")
                '\u000C' -> out.write("\\f")
                else -> if (c < ' ') {
                    out.write(String.format(java.util.Locale.ROOT, "\\u%04x", c.code))
                } else {
                    out.write(c.code)
                }
            }
        }
        out.write("\"")
    }
}
