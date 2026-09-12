package com.codespace.ide.editor

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult

/**
 * MC-TAP-OVERLAY (2026-09-12) — extracted from CodeEditor per the JVM 64KB method rule.
 *
 * ROOT CAUSE this fixes: CodeEditor's double-tap (multi-cursor add) was attached via
 * detectTapGestures on the BasicTextField's own modifier chain. Compose text fields
 * consume tap events in their INTERNAL gesture handler (cursor placement / word select)
 * before an outer modifier-level detector sees them, so on-device the double-tap
 * handler silently never fired: MC chip turned ON (global store worked) but double-tap
 * added nothing, while native word-select masked the dead handler in MC-off mode.
 *
 * FIX: while multi-cursor mode is ON, this transparent Box renders ABOVE the text
 * surface (sibling of the BasicTextField inside the same Box) and becomes the HIT
 * TARGET for all taps — its detector reliably receives them because nothing inner
 * intercepts. Gesture bodies (cursor place / MC add-remove / word select) stay in
 * CodeEditor as handler lambdas so they read the live editor state there; this file
 * owns only the overlay + gesture plumbing.
 *
 * - Drags still scroll: the scroll containers are ANCESTORS in the hit path, and tap
 *   detection does not consume drag movement.
 * - MC off = no overlay = fully native text-field behavior (handles, magnifier, IME).
 * - [layoutProvider] is a lambda (not a captured value) so the always-running
 *   pointerInput(Unit) reads the CURRENT TextLayoutResult at event time.
 */
@Composable
internal fun BoxScope.McTapOverlay(
    layoutProvider: () -> TextLayoutResult?,
    onDoubleTapAt: (Int) -> Unit,
    onTapAt: (Int) -> Unit,
    onLongPressAt: (Int) -> Unit,
) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset: Offset ->
                        val layout = layoutProvider()
                        if (layout != null) onDoubleTapAt(layout.getOffsetForPosition(offset))
                    },
                    onTap = { offset: Offset ->
                        val layout = layoutProvider()
                        if (layout != null) onTapAt(layout.getOffsetForPosition(offset))
                    },
                    onLongPress = { offset: Offset ->
                        val layout = layoutProvider()
                        if (layout != null) onLongPressAt(layout.getOffsetForPosition(offset))
                    },
                )
            }
    )
}
