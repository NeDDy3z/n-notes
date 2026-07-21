package com.xnotes.format

import java.io.Reader

/** Thrown when the stream is not well-formed JSON. */
internal class JsonPullException(message: String) : Exception(message)

/**
 * Minimal streaming JSON pull parser (android.util.JsonReader-style API, usable in
 * plain-JVM tests). Exists so a dense note's manifest is decoded token-by-token
 * straight off the zip stream: the old org.json DOM held a boxed wrapper for every
 * number, which cost a 58 MB manifest ~150 MB of heap and tens of seconds to open.
 */
internal class JsonPull(private val reader: Reader) {

    enum class Token { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER, BOOLEAN, NULL, END_DOCUMENT }

    private val buf = CharArray(16 * 1024)
    private var pos = 0
    private var limit = 0
    private var eof = false

    private var stack = BooleanArray(32) // true = object, false = array
    private var had = BooleanArray(32) // container has parsed at least one entry
    private var depth = 0
    private var afterName = false
    private var rootConsumed = false
    private var peeked: Token? = null
    private val sb = StringBuilder()

    fun peek(): Token = peeked ?: doPeek().also { peeked = it }

    fun hasNext(): Boolean {
        val t = peek()
        return t != Token.END_OBJECT && t != Token.END_ARRAY && t != Token.END_DOCUMENT
    }

    fun beginObject() {
        expect(Token.BEGIN_OBJECT)
        pos++
        startValue()
        push(true)
    }

    fun endObject() {
        expect(Token.END_OBJECT)
        pos++
        depth--
    }

    fun beginArray() {
        expect(Token.BEGIN_ARRAY)
        pos++
        startValue()
        push(false)
    }

    fun endArray() {
        expect(Token.END_ARRAY)
        pos++
        depth--
    }

    fun nextName(): String {
        expect(Token.NAME)
        val name = readStringBody()
        skipWs()
        if (!ensure() || buf[pos] != ':') throw JsonPullException("Expected ':'")
        pos++
        afterName = true
        return name
    }

    fun nextString(): String {
        expect(Token.STRING)
        val s = readStringBody()
        startValue()
        return s
    }

    fun nextDouble(): Double {
        expect(Token.NUMBER)
        val t = scanNumber()
        startValue()
        return try {
            t.toDouble()
        } catch (_: NumberFormatException) {
            throw JsonPullException("Malformed number")
        }
    }

    fun nextInt(): Int {
        expect(Token.NUMBER)
        val t = scanNumber()
        startValue()
        return t.toIntOrNull() ?: try {
            t.toDouble().toInt()
        } catch (_: NumberFormatException) {
            throw JsonPullException("Malformed number")
        }
    }

    fun nextBoolean(): Boolean {
        expect(Token.BOOLEAN)
        val v = buf[pos] == 't'
        literal(if (v) "true" else "false")
        startValue()
        return v
    }

    fun nextNull() {
        expect(Token.NULL)
        literal("null")
        startValue()
    }

    fun skipValue() {
        when (peek()) {
            Token.BEGIN_OBJECT -> {
                beginObject()
                while (hasNext()) {
                    nextName()
                    skipValue()
                }
                endObject()
            }
            Token.BEGIN_ARRAY -> {
                beginArray()
                while (hasNext()) skipValue()
                endArray()
            }
            Token.STRING -> nextString()
            Token.NUMBER -> {
                expect(Token.NUMBER)
                scanNumber()
                startValue()
            }
            Token.BOOLEAN -> nextBoolean()
            Token.NULL -> nextNull()
            Token.NAME -> {
                nextName()
                skipValue()
            }
            else -> throw JsonPullException("Nothing to skip")
        }
    }

    private fun expect(t: Token) {
        if (peek() != t) throw JsonPullException("Expected $t but was $peeked")
        peeked = null
    }

    private fun literal(text: String) {
        for (c in text) {
            if (!ensure() || buf[pos] != c) throw JsonPullException("Malformed literal")
            pos++
        }
    }

    /** Records that a value began: the enclosing container now needs a ',' before its next entry. */
    private fun startValue() {
        if (depth > 0) {
            had[depth - 1] = true
            afterName = false
        } else {
            rootConsumed = true
        }
    }

    private fun push(isObject: Boolean) {
        if (depth == stack.size) {
            stack = stack.copyOf(depth * 2)
            had = had.copyOf(depth * 2)
        }
        stack[depth] = isObject
        had[depth] = false
        depth++
    }

    private fun doPeek(): Token {
        skipWs()
        if (depth == 0) {
            if (rootConsumed || !ensure()) return Token.END_DOCUMENT
            return valueTokenAt(buf[pos])
        }
        if (!ensure()) throw JsonPullException("Unexpected end of input")
        val inObject = stack[depth - 1]
        if (inObject && !afterName) {
            var c = buf[pos]
            if (c == '}') return Token.END_OBJECT
            if (had[depth - 1]) {
                if (c != ',') throw JsonPullException("Expected ','")
                pos++
                skipWs()
                if (!ensure()) throw JsonPullException("Unexpected end of input")
                c = buf[pos]
                if (c == '}') throw JsonPullException("Trailing comma")
            }
            if (c != '"') throw JsonPullException("Expected a name")
            return Token.NAME
        }
        if (!inObject) {
            var c = buf[pos]
            if (c == ']') return Token.END_ARRAY
            if (had[depth - 1]) {
                if (c != ',') throw JsonPullException("Expected ','")
                pos++
                skipWs()
                if (!ensure()) throw JsonPullException("Unexpected end of input")
                c = buf[pos]
                if (c == ']') throw JsonPullException("Trailing comma")
            }
            return valueTokenAt(c)
        }
        return valueTokenAt(buf[pos])
    }

    private fun valueTokenAt(c: Char): Token = when {
        c == '{' -> Token.BEGIN_OBJECT
        c == '[' -> Token.BEGIN_ARRAY
        c == '"' -> Token.STRING
        c == 't' || c == 'f' -> Token.BOOLEAN
        c == 'n' -> Token.NULL
        c == '-' || c in '0'..'9' -> Token.NUMBER
        else -> throw JsonPullException("Unexpected character '$c'")
    }

    /** Reads a string literal whose opening quote is at [pos]. */
    private fun readStringBody(): String {
        pos++
        sb.setLength(0)
        while (true) {
            var i = pos
            while (i < limit) {
                val c = buf[i]
                if (c == '"' || c == '\\') break
                i++
            }
            sb.append(buf, pos, i - pos)
            pos = i
            if (pos < limit) {
                if (buf[pos] == '"') {
                    pos++
                    return sb.toString()
                }
                pos++
                sb.append(readEscape())
            } else if (!ensure()) {
                throw JsonPullException("Unterminated string")
            }
        }
    }

    private fun readEscape(): Char {
        val c = readChar()
        return when (c) {
            '"', '\\', '/' -> c
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                var v = 0
                repeat(4) {
                    val h = readChar()
                    v = (v shl 4) or when (h) {
                        in '0'..'9' -> h - '0'
                        in 'a'..'f' -> h - 'a' + 10
                        in 'A'..'F' -> h - 'A' + 10
                        else -> throw JsonPullException("Malformed \\u escape")
                    }
                }
                v.toChar()
            }
            else -> throw JsonPullException("Malformed escape")
        }
    }

    private fun scanNumber(): String {
        sb.setLength(0)
        while (true) {
            var i = pos
            while (i < limit && isNumberChar(buf[i])) i++
            sb.append(buf, pos, i - pos)
            pos = i
            if (i < limit || !ensure()) break
        }
        if (sb.isEmpty()) throw JsonPullException("Malformed number")
        return sb.toString()
    }

    private fun isNumberChar(c: Char): Boolean =
        c in '0'..'9' || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E'

    private fun readChar(): Char {
        if (!ensure()) throw JsonPullException("Unexpected end of input")
        return buf[pos++]
    }

    private fun skipWs() {
        while (ensure()) {
            val c = buf[pos]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++ else return
        }
    }

    private fun ensure(): Boolean {
        if (pos < limit) return true
        if (eof) return false
        var n = reader.read(buf)
        while (n == 0) n = reader.read(buf)
        pos = 0
        if (n < 0) {
            limit = 0
            eof = true
            return false
        }
        limit = n
        return true
    }
}
