package com.xnotes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.GregorianCalendar

class SyncConflictTest {
    private val at = GregorianCalendar(2026, 10, 2, 14, 30).time

    @Test fun copyPathGoesBeforeTheExtension() {
        assertEquals("notes/a (Sync Conflict 2026-11-02 1430).xnote", SyncConflict.copyPath("notes/a.xnote", at))
        assertEquals("notes.d/a (Sync Conflict 2026-11-02 1430)", SyncConflict.copyPath("notes.d/a", at))
    }

    @Test fun newAndOldCopiesAreRecognised() {
        assertTrue(SyncConflict.isCopy("a (Sync Conflict 2026-11-02 1430).xnote"))
        assertTrue(SyncConflict.isCopy("a (Filen conflict 2026-01-01 0900).xnote"))
        assertTrue(SyncConflict.isCopy("a (filen conflict 2026-01-01 0900).xcanvas"))
    }

    @Test fun ordinaryNamesAreNot() {
        assertFalse(SyncConflict.isCopy("a.xnote"))
        assertFalse(SyncConflict.isCopy("Merge conflict notes.xnote"))
        assertFalse(SyncConflict.isCopy("a (2026-11-02).xnote"))
        assertFalse(SyncConflict.isCopy("Meeting (team conflict 2026-03-01 0900).xnote"))
    }

    @Test fun recordsRoundTripAndKeepOthers() {
        val first = SyncConflict.withRecords(null, mapOf("a (Sync Conflict 2026-11-02 1430).xnote" to
            SyncConflict.Record("a.xnote", 1L, "Pixel 8", SyncConflict.Reason.SAME_PAGES, listOf(2, 3))))
        val both = SyncConflict.withRecords(first, mapOf("b (Sync Conflict 2026-11-02 1431).md" to
            SyncConflict.Record("b.md", 2L, "Galaxy S23", SyncConflict.Reason.NOT_A_NOTE)))
        assertEquals(SyncConflict.Record("a.xnote", 1L, "Pixel 8", SyncConflict.Reason.SAME_PAGES, listOf(2, 3)),
            SyncConflict.recordOf(both, "a (Sync Conflict 2026-11-02 1430).xnote"))
        assertEquals(SyncConflict.Reason.NOT_A_NOTE, SyncConflict.recordOf(both, "b (Sync Conflict 2026-11-02 1431).md")?.reason)
        assertEquals(null, SyncConflict.recordOf(both, "c.xnote"))
    }

    @Test fun samePagesNamesThePagesAndTheDevice() {
        val text = SyncConflict.explain("x", SyncConflict.Record("Uni/a.xnote", 1L, "Pixel 8", SyncConflict.Reason.SAME_PAGES, listOf(2, 3, 5)))
        assertTrue(text, text.startsWith("Pages 2, 3 and 5 of \"a.xnote\" were changed on Pixel 8 and on another device"))
        assertTrue(text, text.contains("keeps Pixel 8's version of pages 2, 3 and 5"))
    }

    @Test fun everyReasonExplainsWithoutPlaceholders() {
        for (reason in SyncConflict.Reason.values()) {
            val text = SyncConflict.explain("x", SyncConflict.Record("a.xnote", 1L, "Pixel 8", reason, listOf(1)))
            assertFalse("$reason: $text", text.contains("%s") || text.contains("because ."))
        }
        assertTrue(SyncConflict.explain("x", null).contains("older version"))
    }
}
