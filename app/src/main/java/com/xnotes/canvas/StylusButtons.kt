package com.xnotes.canvas

import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Whether a stylus side button is currently down.
 *
 * Pens do not agree on how to report this, and getting it right took several releases of
 * device-specific work, so both canvases latch it through this rather than each working it out.
 * Three delivery routes are covered:
 *
 *  - the touch stream's `buttonState`, which most pens use;
 *  - the hovering generic-motion stream, for pens that report the button only while hovering and
 *    never in a touch event;
 *  - a `KeyEvent`, which Bluetooth and USI pens use, including one vendor code with no standard
 *    mapping ([InteractionController.VENDOR_HELD_BUTTON_KEYCODE]).
 *
 * The last two are latched apart and either one holds the button: a pen that sends the key can
 * hover with an empty `buttonState` while it is down (the OnePlus Pad Go 2 Stylo does), and that
 * must not let go of the key.
 *
 * A fourth quirk is handled higher up and needs nothing here: some Samsung builds tag a
 * button-held stroke with proprietary action codes instead of DOWN/MOVE/UP, and `MainActivity`
 * rewrites those before dispatch, so any view in the tree gets a normal touch stream.
 *
 * The button masks and the vendor keycode are read from [InteractionController] rather than copied,
 * so the two canvases cannot drift apart on which buttons count.
 */
class StylusButtonLatch {

    private var motionHeld = false

    /** True while a side button is held, from whichever stream reported it. */
    val held: Boolean
        get() = keyHeld || motionHeld

    /**
     * Latch the button off the generic-motion (hover) stream. Returns true when a stylus event
     * leaves the button up, so a caller can end a hovering gesture.
     */
    fun onGenericMotion(e: MotionEvent): Boolean =
        e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS && onStylusMotion(e.buttonState)

    internal fun onStylusMotion(buttonState: Int): Boolean {
        motionHeld = (buttonState and InteractionController.STYLUS_BUTTON_MASK) != 0
        return !held
    }

    /**
     * Latch the button off a key event. Returns true when [keyCode] was a stylus side button, so
     * the host consumes it instead of letting it fall through as a normal key.
     */
    fun onKey(keyCode: Int, down: Boolean): Boolean {
        if (!isStylusButtonKey(keyCode)) return false
        keyHeld = down
        return true
    }

    /** Forget what the hover stream said; a held key belongs to the pen and its key-up clears it. */
    fun reset() {
        motionHeld = false
    }

    /** Whether [e] is a stylus contact with the side button down, on either route. */
    fun heldFor(e: MotionEvent): Boolean =
        e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS && heldWith(e.buttonState)

    internal fun heldWith(buttonState: Int): Boolean =
        (buttonState and InteractionController.STYLUS_BUTTON_MASK) != 0 || held

    companion object {
        /** One pen, so one key state for every latch: its key-up can reach another pane than its key-down. */
        private var keyHeld = false

        /** Let go of a held key whose key-up may never arrive, as when the window loses focus. */
        fun releaseKey() {
            keyHeld = false
        }

        fun isStylusButtonKey(keyCode: Int): Boolean =
            keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY ||
                keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY ||
                keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY ||
                keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL ||
                keyCode == InteractionController.VENDOR_HELD_BUTTON_KEYCODE
    }
}
