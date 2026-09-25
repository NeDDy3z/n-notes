package com.xnotes.core.text

/**
 * The pending accent of a physical keyboard's dead key. On international layouts the
 * accent keys (' ` ^ ~ ") type nothing on their own and compose with the character that
 * follows, so a hardware key reports the accent with a flag set instead of a code point.
 * The flow caret inserts hardware keys itself rather than through the IME mirror, so it
 * has to run that composition; feeding the flagged value straight to a code point is
 * what crashed the editor on ' and `.
 *
 * The flag and mask mirror `KeyCharacterMap.COMBINING_ACCENT` / `COMBINING_ACCENT_MASK`,
 * and [combine] is the platform seam for `KeyCharacterMap.getDeadChar`, which answers from
 * the layout the user actually selected, so no accent table is spelled out here. It also
 * resolves the same dead key pressed twice, and a dead key followed by a space, to the
 * plain spacing accent.
 */
class DeadKeyLatch(private val combine: (accent: Int, ch: Int) -> Int) {

    private var pending = 0

    /** True while an accent is waiting for the character it composes with. */
    val armed: Boolean get() = pending != 0

    /** Abandon a waiting accent: the caret moved away, or the session ended. */
    fun clear() {
        pending = 0
    }

    /**
     * The text [unicodeChar] types, or null when it typed nothing (the key only armed an
     * accent, or carried no character at all).
     */
    fun accept(unicodeChar: Int): String? {
        if (unicodeChar and COMBINING_ACCENT != 0) return arm(unicodeChar and COMBINING_ACCENT_MASK)
        val accent = pending
        pending = 0
        val typed = text(unicodeChar) ?: return null
        if (accent == 0) return typed
        val composed = text(combine(accent, unicodeChar))
        // An accent the next character cannot take stands on its own, ahead of it.
        return composed ?: (text(accent).orEmpty() + typed)
    }

    private fun arm(accent: Int): String? {
        val previous = pending
        pending = accent
        if (previous == 0) return null
        // Two accents in a row: either they resolve together, or the first stands alone.
        val composed = text(combine(previous, accent))
        if (composed != null) pending = 0
        return composed ?: text(previous)
    }

    private fun text(codePoint: Int): String? =
        if (codePoint > 0 && Character.isValidCodePoint(codePoint)) String(Character.toChars(codePoint)) else null

    private companion object {
        const val COMBINING_ACCENT = Int.MIN_VALUE // KeyCharacterMap.COMBINING_ACCENT, 0x80000000
        const val COMBINING_ACCENT_MASK = Int.MAX_VALUE // KeyCharacterMap.COMBINING_ACCENT_MASK
    }
}
