package com.xnotes.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocKeysTest {

    private val from = "com.android.externalstorage.documents|primary:Notes/Lectures"
    private val to = "com.android.externalstorage.documents|primary:Notes/Archive/Lectures"

    @Test
    fun `a moved document takes the new key`() {
        assertEquals(to, DocKeys.moved(from, from, to))
    }

    @Test
    fun `documents under a moved folder follow it`() {
        assertEquals("$to/Robotics/kinematics.xnote", DocKeys.moved("$from/Robotics/kinematics.xnote", from, to))
    }

    @Test
    fun `a sibling sharing the name prefix is left alone`() {
        assertNull(DocKeys.moved("$from 2/old.xnote", from, to))
        assertNull(DocKeys.moved("${from}2", from, to))
        assertFalse(DocKeys.within("${from}2", from))
        assertTrue(DocKeys.within("$from/a.xnote", from))
    }

    @Test
    fun `a chain walks path-like ids from the top down`() {
        assertEquals(listOf("root", "root/a", "root/a/b"), DocKeys.chain("root", "root/a/b"))
        assertEquals(listOf("root/a", "root/a/b"), DocKeys.chain("root/a", "root/a/b"))
        assertEquals(listOf("root"), DocKeys.chain("root", "root"))
        assertNull(DocKeys.chain("root/a", "root/ab"))
    }

    @Test
    fun `the earliest clue wins and unknown clues are ignored`() {
        assertEquals(100L, DocKeys.inferCreated(100L, 250L))
        assertEquals(90L, DocKeys.inferCreated(100L, 90L))
        assertEquals(100L, DocKeys.inferCreated(100L, null))
        assertEquals(90L, DocKeys.inferCreated(null, 90L))
        assertEquals(100L, DocKeys.inferCreated(100L, -1L))
        assertNull(DocKeys.inferCreated(null, 0L))
    }
}
