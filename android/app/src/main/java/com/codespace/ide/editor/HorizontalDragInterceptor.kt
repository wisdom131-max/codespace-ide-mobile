package com.codespace.ide.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Horizontal drag interceptor for the editor surface.
 *
 * Placed on the parent Box (before .horizontalScroll) to intercept horizontal
 * drag gestures in PointerEventPass.Initial -- BEFORE BasicTextField's internal
 * text-selection handler can consume them.
 *
 * Uses awaitPointerEventScope { } to get an AwaitPointerEventScope receiver,
 * which is where awaitPointerEvent() is actually defined (NOT on PointerInputScope).
 *
 * State machine:
 *   IDLE      -> finger down -> PENDING (don't consume, let tap/longpress start)
 *   PENDING   -> drag past touchSlop:
 *                  |deltaX| > |deltaY| -> H_DRAG (consume all, scroll hScroll)
 *                  else                -> PASSTHROUGH (don't consume, let child handle)
 *               -> finger up -> IDLE (let tap fire)
 *               -> multi-touch -> IDLE (let pinch-to-zoom handle)
 *   H_DRAG    -> consume all, hScroll.dispatchRawDelta(-deltaX)
 *               -> finger up -> IDLE
 *               -> multi-touch -> IDLE
 *   PASSTHROUGH -> never consume, wait for finger up -> IDLE
 *
 * No fling in this version -- scrolling stops immediately on finger lift.
 */
@Composable
fun Modifier.horizontalDragInterceptor(
    hScroll: ScrollState,
): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        val touchSlop = viewConfiguration.touchSlop

        var state = InterceptorState.IDLE
        var downPos = Offset.Zero
        var lastPos = Offset.Zero

        while (true) {
            when (state) {
                InterceptorState.IDLE -> {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    if (change.pressed && !change.previousPressed) {
                        downPos = change.position
                        lastPos = change.position
                        state = InterceptorState.PENDING
                    }
                }

                InterceptorState.PENDING -> {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue

                    // Multi-touch -- let pinch-to-zoom handle it
                    if (event.changes.size > 1) {
                        state = InterceptorState.IDLE
                        continue
                    }

                    // Finger lifted -- let detectTapGestures fire onTap
                    if (event.changes.all { it.changedToUp }) {
                        state = InterceptorState.IDLE
                        continue
                    }

                    if (!change.pressed) {
                        state = InterceptorState.IDLE
                        continue
                    }

                    val delta = change.position - downPos
                    val totalDrag = hypot(delta.x, delta.y)

                    if (totalDrag >= touchSlop) {
                        if (abs(delta.x) > abs(delta.y)) {
                            // Horizontal drag -- consume and start scrolling
                            event.changes.forEach { it.consume() }
                            lastPos = change.position
                            state = InterceptorState.H_DRAG
                        } else {
                            // Vertical drag -- let child handle (text selection / vertical scroll)
                            state = InterceptorState.PASSTHROUGH
                        }
                    }
                    // else: still below touchSlop, stay in PENDING
                }

                InterceptorState.H_DRAG -> {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue

                    // Multi-touch -- bail, let pinch-to-zoom take over
                    if (event.changes.size > 1) {
                        state = InterceptorState.IDLE
                        continue
                    }

                    // Finger lifted -- stop scrolling (no fling)
                    if (event.changes.all { it.changedToUp }) {
                        state = InterceptorState.IDLE
                        continue
                    }

                    if (!change.pressed) {
                        state = InterceptorState.IDLE
                        continue
                    }

                    // Consume so BasicTextField doesn't select text while scrolling
                    event.changes.forEach { it.consume() }
                    val deltaX = change.position.x - lastPos.x
                    // Negate: drag right = scroll left = see content to the right
                    hScroll.dispatchRawDelta(-deltaX)
                    lastPos = change.position
                }

                InterceptorState.PASSTHROUGH -> {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    // Never consume -- child handlers own this gesture
                    if (event.changes.all { it.changedToUp } || event.changes.isEmpty()) {
                        state = InterceptorState.IDLE
                    }
                }
            }
        }
    }
}

private enum class InterceptorState {
    IDLE,
    PENDING,
    H_DRAG,
    PASSTHROUGH,
}
