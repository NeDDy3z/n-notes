package com.xnotes.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import kotlin.random.Random

/**
 * [JsonPull.nextDouble] reads plain fixed-point numbers straight out of its buffer instead of
 * building a String and handing it to the general parser. It has to land on exactly the double
 * [String.toDouble] would, or a note reopens as slightly different ink, so these compare the two
 * across the shapes a manifest contains and the ones that must fall back.
 */
class JsonPullNumberTest {

    private fun parse(text: String): List<Double> {
        val p = JsonPull(StringReader(text))
        val out = mutableListOf<Double>()
        p.beginArray()
        while (p.hasNext()) out += p.nextDouble()
        p.endArray()
        return out
    }

    /** Every literal read back as the exact same double the platform parser gives. */
    private fun sameAsPlatform(literals: List<String>) {
        val got = parse(literals.joinToString(",", "[", "]"))
        assertEquals(literals.size, got.size)
        for (i in literals.indices) {
            assertEquals(
                literals[i],
                java.lang.Double.doubleToLongBits(literals[i].toDouble()),
                java.lang.Double.doubleToLongBits(got[i]),
            )
        }
    }

    @Test fun theNumbersAManifestIsMadeOf() {
        val r = Random(42)
        val literals = mutableListOf<String>()
        repeat(4000) {
            val mag = listOf(1, 10, 1000, 100000, 9999999).random(r)
            val v = (r.nextDouble() - 0.5) * 2 * mag
            literals += (Math.round(v * 100.0) / 100.0).toString()
            literals += (Math.round(r.nextDouble() * 1000.0) / 1000.0).toString()
        }
        sameAsPlatform(literals)
    }

    @Test fun theEdgesOfTheFastPath() {
        sameAsPlatform(
            listOf(
                "0", "-0", "0.0", "1", "-1", "0.01", "-0.01", "0.001", "-0.001",
                "123.45", "-123.45", "9999999.99", "1234567890123.75",
                "0.1", "0.2", "0.3", "0.7", "2.675", "4503599627370496",
                "9007199254740992", "90071992547409921", // the second is past an exact mantissa
                "0.00000000000000000001", "12345678901234567890.5",
            ),
        )
    }

    /** Scientific notation has no fast path and must come back through the general parser. */
    @Test fun exponentsStillParse() {
        sameAsPlatform(listOf("1.0E-4", "1e7", "-2.5E10", "5.0E-324", "1.7976931348623157E308"))
    }

    private fun samples(text: String): List<List<Double>> {
        val p = JsonPull(StringReader(text))
        val out = mutableListOf<List<Double>>()
        val tuple = DoubleArray(4)
        p.beginArray()
        while (p.hasNext()) {
            p.nextSample(tuple)
            out += tuple.toList()
        }
        p.endArray()
        return out
    }

    @Test fun samplesComeBackWholeAndInOrder() {
        val got = samples("[[1,2,0.5],[-3.25,4,1,16],[0,0,0.001]]")
        assertEquals(3, got.size)
        assertEquals(listOf(1.0, 2.0, 0.5), got[0].take(3))
        assertTrue(got[0][3].isNaN()) // no time channel on that one
        assertEquals(listOf(-3.25, 4.0, 1.0, 16.0), got[1])
        assertEquals(0.001, got[2][2], 0.0)
    }

    /** Whitespace has no fast path; the general reader has to produce the same thing. */
    @Test fun spacedSamplesReadTheSame() {
        assertEquals(samples("[[1,2,0.5]]"), samples("[ [ 1 , 2 , 0.5 ] ]"))
    }

    /** Loading is forgiving: a slot that is not a number is skipped, and does not shift the rest. */
    @Test fun anUnreadableSlotDoesNotShiftTheOnesAfterIt() {
        val got = samples("[[1,null,0.5],[\"2.5\",3,1],[4,5,6,7,8]]")
        assertEquals(1.0, got[0][0], 0.0)
        assertTrue(got[0][1].isNaN())
        assertEquals(0.5, got[0][2], 0.0)
        assertEquals(listOf(2.5, 3.0, 1.0), got[1].take(3))
        assertEquals(listOf(4.0, 5.0, 6.0, 7.0), got[2]) // the extras are dropped, not misplaced
    }

    @Test fun anEmptySampleIsAllAbsent() {
        val got = samples("[[]]")
        assertTrue(got[0].all { it.isNaN() })
    }

    /** A sample straddling the read buffer must fall back rather than be read in half. */
    @Test fun samplesAcrossTheBufferBoundary() {
        val text = (0 until 4000).joinToString(",", "[", "]") { "[$it.25,$it.5,0.75]" }
        val got = samples(text)
        assertEquals(4000, got.size)
        for (i in got.indices) {
            assertEquals(i + 0.25, got[i][0], 0.0)
            assertEquals(i + 0.5, got[i][1], 0.0)
            assertEquals(0.75, got[i][2], 0.0)
        }
    }

    /** A number straddling the read buffer must not be cut in half by the fast path. */
    @Test fun numbersAcrossTheBufferBoundary() {
        val literals = (0 until 3000).map { "%d.%02d".format(it, it % 100) }
        sameAsPlatform(literals)
    }
}
