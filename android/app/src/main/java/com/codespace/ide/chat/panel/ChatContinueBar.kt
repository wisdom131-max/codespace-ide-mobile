package com.codespace.ide.chat.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Area 6 - THE CONTINUE ACTION, in the composer strip.
 *
 * Shown only when the last turn was actually cut off at the output limit ([ContinuationState.
 * NEEDS_CONTINUATION]) or while a continuation is running. A natural answer, a filter, a Stop and
 * an unknown ending all keep it hidden: offering "Continue" on a finished answer would be a lie
 * about what happened.
 *
 * While [loading] the button is gone, not merely disabled, so a second tap cannot start a second
 * continuation.
 */
@Composable
internal fun ChatContinueBar(
    state: ContinuationState,
    onContinue: () -> Unit,
) {
    if (!isVisibleContinuation(state)) return
    ContinueBarContent(loading = state == ContinuationState.CONTINUING, onContinue = onContinue)
}

/** Pure visibility rule, so the composer's behaviour is testable without Compose. */
internal fun isVisibleContinuation(state: ContinuationState): Boolean =
    state == ContinuationState.NEEDS_CONTINUATION || state == ContinuationState.CONTINUING

@Composable
private fun ContinueBarContent(loading: Boolean, onContinue: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (loading) {
                "Continuing the answer\u2026"
            } else {
                "Cut off at the output limit. The answer above is incomplete."
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
        )
        if (!loading) {
            TextButton(onClick = onContinue) { Text("Continue") }
        }
    }
}
