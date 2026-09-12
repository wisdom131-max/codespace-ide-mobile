package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * R7-FOLLOW-UPS (VS Code "suggested follow-ups" parity): 2-3 deterministic
 * suggestion chips under the last assistant reply. Tapping inserts the text
 * into the input (never auto-sends — the user reviews, then taps send).
 *
 * VS Code generates these model-side; ours are context-derived client-side so
 * they cost nothing and always relate to the panel's live state.
 */
internal fun suggestedFollowUps(
    isAgentMode: Boolean,
    hasPendingStaged: Boolean,
    planAwaitingReview: Boolean,
    planInProgress: Boolean,
    lastReplyWasError: Boolean,
): List<String> {
    val out = mutableListOf<String>()
    if (planAwaitingReview) {
        out.add("Approve the plan")
        out.add("Revise the plan — make it simpler")
    } else if (planInProgress) {
        out.add("What's left on the plan?")
    }
    if (lastReplyWasError) out.add("Retry the last request")
    if (hasPendingStaged) {
        out.add("Review the staged changes")
        out.add("Explain the proposed edits")
    }
    if (isAgentMode) {
        out.add("Summarize what you changed")
    } else {
        out.add("Explain more simply")
        out.add("Show me a code example")
    }
    return out.distinct().take(3)
}

@Composable
internal fun ChatFollowUpChips(
    suggestions: List<String>,
    onPick: (String) -> Unit,
    colors: ChatPanelColors,
) {
    if (suggestions.isEmpty()) return
    Row(
        Modifier
            .padding(horizontal = 2.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        suggestions.forEach { s ->
            Text(
                s,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = colors.text,
                modifier = Modifier
                    .background(colors.surface, RoundedCornerShape(10.dp))
                    .clickable { onPick(s) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
