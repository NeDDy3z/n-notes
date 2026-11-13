package com.xnotes.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WandConfigTest {

    @Test fun theDefaultsAreTheTimingsTheWandHasAlwaysRunAt() {
        val c = WandConfig()
        assertEquals(1000L, c.holdMs)
        assertEquals(500.0, c.fadeMs, 0.0)
    }

    @Test fun bothTimingsStayInsideTheRangeTheyCanActuallyRunAt() {
        val safe = WandConfig(holdMs = 5L, fadeMs = 0.5).clamped()
        assertEquals(WandConfig.HOLD_MS_MIN, safe.holdMs)
        assertEquals(WandConfig.FADE_MS_MIN, safe.fadeMs, 0.0)
        // A zero hold would fade the ink the instant the pen landed, which is the one thing the
        // debounce exists to prevent; a zero fade is a vanish rather than a fade.
        assertTrue(safe.holdMs > 0L)
        assertTrue(safe.fadeMs > 0.0)
    }

    @Test fun aHandEditedOrOlderFileCannotMoveTheTimingsOutOfRange() {
        val high = WandConfig(holdMs = 10_000_000L, fadeMs = 10_000_000.0).clamped()
        assertEquals(WandConfig.HOLD_MS_MAX, high.holdMs)
        assertEquals(WandConfig.FADE_MS_MAX, high.fadeMs, 0.0)
    }

    @Test fun aConfigAlreadyInRangeIsLeftExactlyAsItIs() {
        val c = WandConfig(holdMs = 2500L, fadeMs = 1200.0)
        assertEquals(c, c.clamped())
    }

    // --- one clock per stroke, not one per batch ---

    @Test fun anItemIsUntouchedUntilItsOwnFadeBegins() {
        assertEquals(1.0, wandAlphaAt(nowMs = 5_000L, fadeStartMs = 5_000L, fadeMs = 500.0), 0.0)
        // The hold delay has passed but the fade has not started: still fully solid.
        assertEquals(1.0, wandAlphaAt(nowMs = 4_000L, fadeStartMs = 5_000L, fadeMs = 500.0), 0.0)
    }

    @Test fun twoStrokesFadeOnTheirOwnClocksRatherThanTogether() {
        // A stroke still melting and a stroke drawn just after it, inside the longer hold.
        val fade = 1_000.0
        val older = 5_000L
        val newer = 6_000L
        val now = 5_750L
        // At one instant the older stroke is most of the way gone while the newer is untouched.
        // A single shared batch alpha cannot express that: it would put both at one value.
        assertTrue(wandAlphaAt(now, older, fade) < 0.1)
        assertEquals(1.0, wandAlphaAt(now, newer, fade), 0.0)
        // And the same point in their own fades reads the same alpha, which is what "each on its
        // own clock" means: the two are one second apart, and stay one second apart throughout.
        assertEquals(wandAlphaAt(6_750L, newer, fade), wandAlphaAt(5_750L, older, fade), 1e-9)
    }

    @Test fun aNewStrokeArrivingDoesNotBringBackOneAlreadyFading() {
        // The whole regression in one assertion: the later "now" is when a second stroke landed,
        // and the older stroke must keep going down rather than snap back to solid.
        val first = 0L
        val beforeSecondStroke = wandAlphaAt(400L, first, 500.0)
        val afterSecondStroke = wandAlphaAt(500L, first, 500.0)
        assertTrue("a fade in progress must never be pushed back", afterSecondStroke < beforeSecondStroke)
    }

    @Test fun aStrokeIsGoneOnceItsOwnFadeHasRunItsLength() {
        assertEquals(0.0, wandAlphaAt(nowMs = 600L, fadeStartMs = 0L, fadeMs = 500.0), 0.0)
        // Well past the end, and past the point where the arithmetic would otherwise wrap.
        assertEquals(0.0, wandAlphaAt(nowMs = 100_000L, fadeStartMs = 0L, fadeMs = 500.0), 0.0)
    }

    private fun nowMs() = 0L
}