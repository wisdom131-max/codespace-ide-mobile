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
    // REMOVED PERMANENTLY (owner 2026-11-23): "it lies and I want them both removed".
    // The claim was often false - a natural stop, a filter or a provider ending all produced
    // "the answer above is incomplete" - so the surface is gone rather than reworded. Nothing is
    // shown, no button, no dialog. The composable stays declared so the call site compiles and so
    // the decision is visible here instead of looking like a missing feature later.
    return
}

/** Permanent off-switch for the Resume surface. Not a const, so it cannot be folded at compile time. */
private val turnResumeBarRemoved = true

/** Pure visibility rule, so the composer's behaviour is testable without Compose. */
internal fun isVisibleContinuation(state: ContinuationState): Boolean =
    state == ContinuationState.NEEDS_CONTINUATION ||
        state == ContinuationState.CONTINUING ||
        // 2026-11-12 audit: a continuation that FAILED used to hide the button, which left the user
        // with no way to ask again for an answer the provider was willing to give.
        state == ContinuationState.CONTINUATION_FAILED

/**
 * PHASE 2b: the Resume bar for a turn the process killed. Deliberately a different bar from
 * [ChatContinueBar]: "Continue" means the provider reached its output limit, "Resume" means the
 * app itself was interrupted. Sharing one control would conflate two different events.
 */
@Composable
internal fun ChatResumeBar(
    action: TurnResume.Action,
    onResume: () -> Unit,
) {
    // REMOVED PERMANENTLY (owner 2026-11-23), the second of the two similar surfaces.
    // The guard is kept but always satisfied, so the surface can never render while the rest of the
    // function stays reachable to the compiler (an unreachable block can fail a warnings-as-errors
    // build, and an annotation on a statement is not even valid Kotlin).
    if (action != TurnResume.Action.OFFER_RESUME || turnResumeBarRemoved) return
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
            text = TurnResume.TITLE,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = onResume) { Text(TurnResume.BUTTON) }
    }
}

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
