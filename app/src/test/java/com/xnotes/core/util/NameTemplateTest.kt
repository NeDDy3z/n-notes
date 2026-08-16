package com.xnotes.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameTemplateTest {

    private fun expand(t: String) = NameTemplate.expand(t, 2026, 8, 16, 9, 5, 7)

    @Test
    fun `expands the documented date and time tokens`() {
        assertEquals("note_2026-08-16_09-05", expand("note_YYYY-MM-DD_HH-mm"))
        assertEquals("26 07", expand("YY ss"))
    }

    @Test
    fun `runs of tokens expand without separators`() {
        assertEquals("20260816", expand("YYYYMMDD"))
        assertEquals("090507", expand("HHmmss"))
    }

    @Test
    fun `tokens inside ordinary words stay literal`() {
        assertEquals("summary", expand("summary"))
        assertEquals("class notes", expand("class notes"))
        assertEquals("addendum", expand("addendum"))
    }

    @Test
    fun `the sequence placeholder survives expansion`() {
        assertEquals("untitled_#", expand(NameTemplate.DEFAULT))
        assertEquals("untitled_3", NameTemplate.withSequence(expand(NameTemplate.DEFAULT), 3))
        assertTrue(NameTemplate.hasSequence("note_#"))
        assertFalse(NameTemplate.hasSequence("note_YYYY"))
    }

    @Test
    fun `illegal file name characters are dropped`() {
        assertEquals("ab", NameTemplate.sanitize("a/b"))
        assertEquals("note", NameTemplate.sanitize("  .note. "))
        assertEquals("untitled", NameTemplate.sanitize("///"))
        assertEquals("untitled", expand("///"))
    }

    @Test
    fun `a two digit year keeps its leading zero`() {
        assertEquals("05", NameTemplate.expand("YY", 2005, 1, 1, 0, 0, 0))
        assertEquals("2005-01-01", NameTemplate.expand("YYYY-MM-DD", 2005, 1, 1, 0, 0, 0))
    }
}
