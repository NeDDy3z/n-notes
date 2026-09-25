package com.xnotes.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadKeyLatchTest {

    private val acute = '\''.code
    private val grave = '`'.code
    private val dead = Int.MIN_VALUE

    // Stands in for KeyCharacterMap.getDeadChar, including its two platform rules:
    // the same accent twice, and an accent then a space, give the plain accent.
    private val table = mapOf(
        acute to mapOf('e'.code to 'é'.code, 'a'.code to 'á'.code),
        grave to mapOf('e'.code to 'è'.code, 'a'.code to 'à'.code),
    )

    private fun latch() = DeadKeyLatch { accent, ch ->
        if (ch == accent || ch == ' '.code) accent else table[accent]?.get(ch) ?: 0
    }

    @Test
    fun deadKeyTypesNothingUntilItsLetterArrives() {
        val latch = latch()
        assertNull(latch.accept(dead or acute))
        assertTrue(latch.armed)
        assertEquals("é", latch.accept('e'.code))
        assertFalse(latch.armed)
    }

    @Test
    fun plainCharactersAreUnaffected() {
        val latch = latch()
        assertEquals("a", latch.accept('a'.code))
        assertEquals("'", latch.accept(acute))
    }

    @Test
    fun theFlaggedValueThatCrashedTheEditorIsNeverACodePoint() {
        // 0x80000027, the ' of an international layout on a Samsung tablet.
        assertNull(latch().accept(dead or acute))
    }

    @Test
    fun accentTheLetterCannotTakeStandsAheadOfIt() {
        val latch = latch()
        latch.accept(dead or acute)
        assertEquals("'z", latch.accept('z'.code))
        assertFalse(latch.armed)
    }

    @Test
    fun accentThenSpaceGivesThePlainAccent() {
        val latch = latch()
        latch.accept(dead or acute)
        assertEquals("'", latch.accept(' '.code))
    }

    @Test
    fun theSameDeadKeyTwiceGivesThePlainAccent() {
        val latch = latch()
        latch.accept(dead or acute)
        assertEquals("'", latch.accept(dead or acute))
        assertFalse(latch.armed)
        assertEquals("e", latch.accept('e'.code))
    }

    @Test
    fun aDifferentDeadKeyFlushesTheFirstAndArmsItself() {
        val latch = latch()
        latch.accept(dead or acute)
        assertEquals("'", latch.accept(dead or grave))
        assertTrue(latch.armed)
        assertEquals("è", latch.accept('e'.code))
    }

    @Test
    fun clearAbandonsTheWaitingAccent() {
        val latch = latch()
        latch.accept(dead or acute)
        latch.clear()
        assertFalse(latch.armed)
        assertEquals("e", latch.accept('e'.code))
    }

    @Test
    fun anyLayoutComposesThroughTheSeamRatherThanATableHere() {
        val circumflex = '^'.code
        val latch = DeadKeyLatch { accent, ch ->
            if (accent == circumflex && ch == 'o'.code) 'ô'.code else 0
        }
        latch.accept(dead or circumflex)
        assertEquals("ô", latch.accept('o'.code))
    }

    @Test
    fun surrogatePairsSurviveUnComposed() {
        assertEquals("😀", latch().accept(0x1F600))
    }

    @Test
    fun aNonsenseCodePointIsDroppedRatherThanThrown() {
        assertNull(latch().accept(0x11FFFF))
    }
}
