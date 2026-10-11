package com.xnotes.core.tools

/**
 * The magic wand's two timings: how long the ink is held before it starts to go, and how long it
 * takes to go. Persisted between sessions, and shared by both surfaces — the wand is one mode over
 * everything drawn, not a per-note thing.
 *
 * Pure model (no Android, no JSON) so the clamping and the round trip are unit-testable on the JVM;
 * the settings layer persists it and the UI renders from it.
 */
data class WandConfig(
    /**
     * Idle time after the last stroke before the held batch begins to fade, in milliseconds. This
     * is a debounce: a batch only starts going once the pen has been up for the whole of it, so a
     * long stroke is never cut off mid-drawing.
     */
    val holdMs: Long = HOLD_MS,

    /** How long the fade itself takes, in milliseconds, from solid to gone. */
    val fadeMs: Double = FADE_MS,
) {

    /** This config with both timings inside the ranges the wand can actually run at. */
    fun clamped(): WandConfig = copy(
        holdMs = holdMs.coerceIn(HOLD_MS_MIN, HOLD_MS_MAX),
        fadeMs = fadeMs.coerceIn(FADE_MS_MIN, FADE_MS_MAX),
    )

    companion object {
        /** The wand's timings as they have always been: a second of stillness, half a second to melt. */
        const val HOLD_MS = 1000L
        const val FADE_MS = 500.0

        /**
         * How far each timing may be moved.
         *
         * The floor keeps both above zero: a zero fade is an instant vanish rather than a fade, and
         * a zero hold would start fading the instant the pen landed, which is the one thing the
         * debounce exists to prevent. The ceiling is a minute of stillness or a minute-long melt —
         * past that the wand is off by accident, not by choice.
         */
        const val HOLD_MS_MIN = 100L
        const val HOLD_MS_MAX = 60_000L
        const val FADE_MS_MIN = 100.0
        const val FADE_MS_MAX = 60_000.0

        /** Step the sliders move by, in milliseconds. */
        const val STEP_MS = 100L
    }
}

/**
 * How solid one held item is [nowMs], given that its own fade began at [fadeStartMs] and takes
 * [fadeMs]. 1 is untouched, 0 is gone.
 *
 * Per item rather than per batch, which is the whole point: each stroke melts on the clock that
 * started when it was drawn, so one stroke landing does not hold up the ones already going.
 *
 * The ease is quadratic, so a batch melts away rather than blinking off.
 */
fun wandAlphaAt(nowMs: Long, fadeStartMs: Long, fadeMs: Double): Double {
    val t = ((nowMs - fadeStartMs).toDouble() / fadeMs).coerceIn(0.0, 1.0)
    val left = 1.0 - t
    return left * left
}