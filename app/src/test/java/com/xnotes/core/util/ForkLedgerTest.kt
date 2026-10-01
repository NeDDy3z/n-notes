package com.xnotes.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ForkLedgerTest {

    private val original = "content://notes/document/Embedded%2Fweek-02.xnote"
    private val fork = "content://notes/document/Embedded%2Fweek-02_1.xnote"
    private val forkOfFork = "content://notes/document/Embedded%2Fweek-02_2.xnote"
    private val note = Any()

    @Test
    fun `a late save aimed at the forked file lands in the fork`() {
        val ledger = ForkLedger()
        ledger.record(original, fork, note)
        assertEquals(fork, ledger.target(original, note))
        assertEquals(fork, ledger.target(fork, note))
    }

    @Test
    fun `a chain of forks is followed to the newest`() {
        val ledger = ForkLedger()
        ledger.record(original, fork, note)
        ledger.record(fork, forkOfFork, note)
        assertEquals(forkOfFork, ledger.target(original, note))
        assertEquals(forkOfFork, ledger.target(fork, note))
    }

    @Test
    fun `another document opened on the original saves to the original`() {
        val ledger = ForkLedger()
        ledger.record(original, fork, note)
        assertEquals(original, ledger.target(original, Any()))
    }

    @Test
    fun `a file that was never forked is its own target`() {
        assertEquals(original, ForkLedger().target(original, note))
    }

    @Test
    fun `a fork created at a reused uri does not loop`() {
        val ledger = ForkLedger()
        ledger.record(original, fork, note)
        ledger.record(fork, original, note)
        assertEquals(original, ledger.target(original, note))
        assertEquals(original, ledger.target(fork, note))
    }
}
