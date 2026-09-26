package com.codespace.ide.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * PG01 (P4f): the Go-to-Line / problem-jump gold BLINK band, extracted from the
 * CodeEditor body.
 *
 * WHY THIS FILE EXISTS: the blink used to live inline in the ~5000-line
 * CodeEditor scope — a `blinkTick` counter incremented every 150ms for up to
 * 6s per jump recomposed the ENTIRE editor (every effect, every lambda capture,
 * all 60-odd remember slots) at ~7Hz purely to animate a single highlight band.
 * The 150ms churn now lives INSIDE this small composable, so it recomposes only
 * the two Boxes it draws. CodeEditor recomposes once per jump (when
 * highlightTargetLine/highlightBlinkStart actually change), which is the
 * correct granularity.
 *
 * BoxScope receiver: the two Boxes align against the caller's overlay Box
 * exactly as they did inline (no wrapper Box, no layout or zIndex-order change).
 *
 * @param topDp computed band Y position in dp (layout-aware, as before — this
 *   recomposes this band on scroll, which is bounded to the band itself).
 * @param gutterWidthDp GUTTER_WIDTH (Float, same dp units the inline code used).
 */
@Composable
internal fun BoxScope.EditorHighlightBlinkBand(
    highlightTargetLine: Int,
    highlightBlinkStartMs: Long,
    topDp: Float,
    lineHeight: Dp,
    gutterWidthDp: Float,
) {
    // Blink animation: tick every 150ms while the highlight is active — confined here.
    var blinkTick by remember(highlightBlinkStartMs) { mutableStateOf(0) }
    LaunchedEffect(highlightBlinkStartMs) {
        if (highlightBlinkStartMs > 0) {
            val deadline = highlightBlinkStartMs + 6000
            while (System.currentTimeMillis() < deadline) {
                blinkTick++
                kotlinx.coroutines.delay(150)
            }
        }
    }
    // Read blinkTick to drive this composable's recomposition (not CodeEditor's).
    @Suppress("UNUSED_VARIABLE") val tick = blinkTick

    val blinkElapsed =
        if (highlightBlinkStartMs > 0) (System.currentTimeMillis() - highlightBlinkStartMs) / 1000f else 0f
    val isBlinking = blinkElapsed < 6f
    val phase = (blinkElapsed * 1000f) % 600f / 600f
    val blinkAlpha = if (isBlinking) {
        if (phase < 0.5f) 0.45f - (phase * 2f * 0.35f) else 0.10f + ((phase - 0.5f) * 2f * 0.35f)
    } else 0.12f
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .offset(x = gutterWidthDp.dp, y = topDp.dp)
            .height(lineHeight)
            .background(Color(0xFFFFD700).copy(alpha = blinkAlpha))
            .zIndex(3.5f),
    )
    // Thin gold bar on the left edge of the highlighted line
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(x = gutterWidthDp.dp, y = topDp.dp)
            .width(3.dp)
            .height(lineHeight)
            .background(Color(0xFFFFD700).copy(alpha = if (isBlinking) 0.9f else 0.4f))
            .zIndex(4.5f),
    )
}
