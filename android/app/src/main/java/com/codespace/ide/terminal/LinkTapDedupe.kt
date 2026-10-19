package com.codespace.ide.terminal

/**
 * BATCH11-S1 (advisor 2026-10-19, report gap 2): ONE resolve per physical tap.
 *
 * READ evidence (vendored com/termux/view/GestureAndScaleRecognizer.java:60-64):
 * the recognizer registers BOTH
 *   - GestureDetector.SimpleOnGestureListener.onSingleTapUp(e)  (fired at UP)
 *   - GestureDetector.OnDoubleTapListener.onSingleTapConfirmed(e) (fired ~300ms
 *     later, once the double-tap window expires)
 * and BOTH route to the SAME mListener.onSingleTapUp — so TerminalPane's handler
 * ran TWICE for one physical tap (the owner's "each tap runs the whole resolve
 * twice" evidence, and the reason [TAPDIAG] logs two ids per tap).
 *
 * Both callbacks carry the SAME MotionEvent downTime (onSingleTapConfirmed
 * receives the original DOWN event, onSingleTapUp the UP event of the same
 * gesture — the downTime identifies the physical tap). A second callback with
 * the same downTime inside the confirm window is a duplicate of the same tap
 * and must not run the tap pipeline again. Two REAL taps always have different
 * downTimes (a second DOWN inside the double-tap window makes it a double-tap
 * and onSingleTapConfirmed never fires for the first).
 *
 * Pure: JVM-testable, no Android imports.
 */
object LinkTapDedupe {

    /** Safety window: onSingleTapConfirmed arrives up to ~336ms after the UP. */
    const val CONFIRM_WINDOW_MS = 700L

    /**
     * Per-terminal-view gate. NOT thread-safe by design: both recognizer
     * callbacks arrive on the main thread.
     */
    class Gate {
        private var lastDownTime = -1L
        private var lastHandledAtMs = 0L

        /**
         * True when this tap should run the pipeline. The FIRST callback for a
         * physical tap (fresh downTime, or the same downTime after the window)
         * returns true; the duplicate callback inside the window returns false.
         */
        fun shouldHandle(downTime: Long, nowMs: Long): Boolean {
            if (downTime == lastDownTime && nowMs - lastHandledAtMs < CONFIRM_WINDOW_MS) {
                return false
            }
            lastDownTime = downTime
            lastHandledAtMs = nowMs
            return true
        }
    }
}
