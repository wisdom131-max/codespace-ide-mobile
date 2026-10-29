@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.codespace.ide.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A(a) component library: every chat state renders from ChatTokens only.
 * MANDATORY UI RULE honored in the token layer: 8-12dp rounded, 12dp
 * horizontal / 10dp vertical padding on cards.
 */

private val PanelShape = RoundedCornerShape(ChatTokens.RADIUS_PANEL.dp)
private val ChipShape = RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp)

/** One compact attachment chip's UI model (display name + human size text). */
data class AttachmentChipModel(val name: String, val sizeText: String)

/** Shared state-card frame: tinted title, optional detail, optional action. */
@Composable
private fun StateCard(
    title: String,
    detail: String,
    tint: Color,
    tintBg: Color,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = ChatTokens.LocalChatColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(tintBg, PanelShape)
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp)
    ) {
        Text(title, color = tint, fontSize = ChatTokens.FONT_SMALL.sp, fontWeight = FontWeight.SemiBold)
        if (detail.isNotEmpty()) {
            Text(
                detail,
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
                modifier = Modifier.padding(top = ChatTokens.GAP_TIGHT.dp),
            )
        }
        if (actionText != null && onAction != null) {
            Text(
                actionText,
                color = c.accent,
                fontSize = ChatTokens.FONT_SMALL.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(top = ChatTokens.GAP.dp)
                    .clickable { onAction() },
            )
        }
    }
}

/** User message bubble. */
@Composable
fun UserBubble(text: String, modifier: Modifier = Modifier) {
    val c = ChatTokens.LocalChatColors.current
    Text(
        text,
        color = c.text,
        fontSize = ChatTokens.FONT_BODY.sp,
        fontFamily = FontFamily.SansSerif,
        modifier = modifier
            .fillMaxWidth()
            .background(c.surfaceAlt, PanelShape)
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp),
    )
}

/** Streaming assistant text with an optional block cursor. */
@Composable
fun StreamingText(text: String, cursorOn: Boolean, modifier: Modifier = Modifier) {
    val c = ChatTokens.LocalChatColors.current
    Text(
        if (cursorOn) text + "▌" else text,
        color = c.text,
        fontSize = ChatTokens.FONT_BODY.sp,
        fontFamily = FontFamily.SansSerif,
        modifier = modifier,
    )
}

/** "Thinking Ns" while the provider is reasoning before first output. */
@Composable
fun ThinkingCard(seconds: Int, modifier: Modifier = Modifier) {
    val c = ChatTokens.LocalChatColors.current
    StateCard("Thinking " + seconds + "s", "", c.textSecondary, c.surface, modifier = modifier)
}

/** Plain-word error card from the engine's error taxonomy. */
@Composable
fun ErrorCard(
    verdict: String,
    detail: String = "",
    canRetry: Boolean = false,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = ChatTokens.LocalChatColors.current
    StateCard(
        verdict, detail, c.error, c.errorBg,
        if (canRetry && onRetry != null) "Retry" else null,
        if (canRetry) onRetry else null,
        modifier,
    )
}

/**
 * 429: the provider asked for a wait.
 *
 * BATCH 5: the copy used to promise "Retrying in Ns" while nothing retried. It now states
 * what the provider actually said, and offers the user the one action that exists - a
 * manual retry once they judge the wait is over.
 */
@Composable
fun RateLimitCard(
    waitSec: Int,
    attempt: Int = 0,
    maxAttempts: Int = 0,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = ChatTokens.LocalChatColors.current
    val autoRetrying = attempt > 0 && maxAttempts > 0
    val detail = when {
        autoRetrying -> "Rate limited (429). Retrying automatically in " + waitSec +
            "s (attempt " + attempt + " of " + maxAttempts + "). Stop cancels."
        waitSec > 0 -> "Rate limited (429). The provider asked for " + waitSec +
            "s before another attempt."
        else -> "Rate limited (429). Try again in a moment."
    }
    StateCard(
        "Provider asked to wait", detail, c.warning, c.warningBg,
        // An automatic retry needs no button; a manual one does.
        if (onRetry != null && !autoRetrying) "Retry" else null,
        if (!autoRetrying) onRetry else null,
        modifier,
    )
}

/** 402: out of credits, plain verdict, no retry offered. */
@Composable
fun NoCreditsCard(modifier: Modifier = Modifier) {
    val c = ChatTokens.LocalChatColors.current
    StateCard(
        "Out of credits",
        "This provider account needs a top-up before the model can answer.",
        c.error, c.errorBg, modifier = modifier,
    )
}

/** Offline: network unreachable, retry offered. */
@Composable
fun OfflineCard(onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val c = ChatTokens.LocalChatColors.current
    StateCard(
        "You are offline",
        "No network connection. Check Wi-Fi or mobile data, then retry.",
        c.warning, c.warningBg,
        if (onRetry != null) "Retry" else null, onRetry, modifier,
    )
}

/** Over-budget refusal with the owner-ruled explicit "Send anyway?" bypass. */
@Composable
fun OverBudgetCard(
    estimate: String,
    onSendAnyway: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = ChatTokens.LocalChatColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(c.surface, PanelShape)
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp)
    ) {
        Text(
            "This send is over budget",
            color = c.warning,
            fontSize = ChatTokens.FONT_SMALL.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            estimate,
            color = c.textSecondary,
            fontSize = ChatTokens.FONT_MICRO.sp,
            modifier = Modifier.padding(top = ChatTokens.GAP_TIGHT.dp),
        )
        Row(
            modifier = Modifier.padding(top = ChatTokens.GAP.dp),
            horizontalArrangement = Arrangement.spacedBy(ChatTokens.GAP.dp),
        ) {
            Text(
                "Send anyway",
                color = c.accent,
                fontSize = ChatTokens.FONT_SMALL.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(enabled = onSendAnyway != null) { onSendAnyway?.invoke() },
            )
            Text(
                "Cancel",
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_SMALL.sp,
                modifier = Modifier.clickable(enabled = onCancel != null) { onCancel?.invoke() },
            )
        }
    }
}

/** WP6: honest visible system note - muted, same family as the trim row. */
@Composable
fun InfoNoteRow(text: String, modifier: Modifier = Modifier) {
    val c = ChatTokens.LocalChatColors.current
    Text(
        text,
        color = c.textSecondary,
        fontSize = ChatTokens.FONT_MICRO.sp,
        modifier = modifier
            .fillMaxWidth()
            .background(c.surfaceAlt, ChipShape)
    )
}

/** Thin banner row: history was trimmed by the history budget. */
@Composable
fun TrimmedHistoryRow(droppedCount: Int, modifier: Modifier = Modifier) {
    val c = ChatTokens.LocalChatColors.current
    Text(
        "Trimmed history — " + droppedCount + " older message(s) not sent",
        color = c.textSecondary,
        fontSize = ChatTokens.FONT_MICRO.sp,
        modifier = modifier
            .fillMaxWidth()
            .background(c.surfaceAlt, ChipShape)
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.GAP_TIGHT.dp),
    )
}

/** One compact attachment chip; tapping removes it before sending. */
@Composable
fun AttachmentChip(chip: AttachmentChipModel, onRemove: (AttachmentChipModel) -> Unit, modifier: Modifier = Modifier) {
    val c = ChatTokens.LocalChatColors.current
    Text(
        chip.name + " · " + chip.sizeText,
        color = c.textSecondary,
        fontSize = ChatTokens.FONT_MICRO.sp,
        modifier = modifier
            .background(c.surfaceAlt, ChipShape)
            .clickable { onRemove(chip) }
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.GAP_TIGHT.dp),
    )
}

/** Compact chip row; FlowRow wraps so long names never clip off-screen. */
@Composable
fun AttachmentChipRow(chips: List<AttachmentChipModel>, onRemove: (AttachmentChipModel) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ChatTokens.GAP_TIGHT.dp),
    ) {
        chips.forEach { AttachmentChip(it, onRemove) }
    }
}
