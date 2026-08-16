package com.xnotes.core.util

/**
 * The user's filename template for new notes, e.g. `note_YYYY-MM-DD_HH-mm`.
 *
 * Date/time tokens ([TOKENS]) expand against the creation moment; `#` expands to the sequence
 * number that makes the name free in its folder. A token is only recognized where it stands
 * apart from ordinary words — the character on either side has to be a non-letter or another
 * token — so a literal like `summary` keeps its `mm` instead of turning into a timestamp.
 */
object NameTemplate {

    const val DEFAULT = "untitled_#"

    /** The sequence placeholder: replaced by the number that makes the name unique. */
    const val SEQUENCE = "#"

    /** Recognized tokens, longest first so `YYYY` wins over `YY`. */
    private val TOKENS = listOf("YYYY", "YY", "MM", "DD", "HH", "mm", "ss")

    /** True when [template] carries a sequence placeholder. */
    fun hasSequence(template: String): Boolean = template.contains(SEQUENCE)

    /** Substitute the sequence placeholder in an already date-expanded name. */
    fun withSequence(expanded: String, n: Int): String = expanded.replace(SEQUENCE, n.toString())

    /**
     * Expand [template]'s date/time tokens against the given wall-clock fields, leaving any
     * sequence placeholder in place. The result is sanitized, so it is always a usable file stem.
     */
    fun expand(
        template: String,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): String {
        val out = StringBuilder(template.length + 8)
        var i = 0
        var prevWasToken = false
        while (i < template.length) {
            val token = tokenAt(template, i)
            if (token != null && standsAlone(template, i, token.length, prevWasToken)) {
                out.append(valueOf(token, year, month, day, hour, minute, second))
                i += token.length
                prevWasToken = true
            } else {
                out.append(template[i])
                i++
                prevWasToken = false
            }
        }
        return sanitize(out.toString())
    }

    /**
     * Strip anything a file name can't hold (path separators, the characters FAT/SAF reject,
     * control codes) and trim leading/trailing dots and spaces. Falls back to `untitled` when
     * nothing usable is left, so a bad template can never produce an unnamed file.
     */
    fun sanitize(name: String): String {
        val cleaned = buildString(name.length) {
            for (c in name) {
                when {
                    c.code < 0x20 -> Unit
                    c in "/\\:*?\"<>|" -> Unit
                    else -> append(c)
                }
            }
        }.trim().trim('.').trim()
        return cleaned.ifEmpty { "untitled" }
    }

    private fun tokenAt(s: String, i: Int): String? =
        TOKENS.firstOrNull { s.startsWith(it, i) }

    /** A token counts only where a word doesn't run into it: neighbours are non-letters, or tokens. */
    private fun standsAlone(s: String, i: Int, len: Int, prevWasToken: Boolean): Boolean {
        val leftOk = i == 0 || prevWasToken || !s[i - 1].isLetter()
        val j = i + len
        val rightOk = j >= s.length || !s[j].isLetter() || tokenAt(s, j) != null
        return leftOk && rightOk
    }

    private fun valueOf(token: String, y: Int, mo: Int, d: Int, h: Int, mi: Int, sec: Int): String = when (token) {
        "YYYY" -> pad(y, 4)
        "YY" -> pad(y % 100, 2)
        "MM" -> pad(mo, 2)
        "DD" -> pad(d, 2)
        "HH" -> pad(h, 2)
        "mm" -> pad(mi, 2)
        else -> pad(sec, 2)
    }

    private fun pad(v: Int, width: Int): String = v.toString().padStart(width, '0')
}
