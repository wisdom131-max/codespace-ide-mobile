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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
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

/** Rounded on three corners, squared toward the sender: reads as a message, not a panel. */
private val SenderShape = RoundedCornerShape(
    topStart = ChatTokens.RADIUS_CARD.dp,
    topEnd = ChatTokens.RADIUS_CARD.dp,
    bottomEnd = ChatTokens.RADIUS_CARD.dp,
    bottomStart = 2.dp,
)

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
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text,
            color = c.text,
            fontSize = ChatTokens.FONT_BODY.sp,
            fontFamily = FontFamily.SansSerif,
            modifier = Modifier
                .fillMaxWidth()
                // The owner's own words are the one thing in the transcript that is theirs,
                // so the bubble carries the accent instead of the neutral card grey. Same
                // radius family, asymmetric so it reads as a message and not a panel.
                .background(c.accent.copy(alpha = 0.12f), SenderShape)
                .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp),
        )
        // Same glyph as a finished reply, so copying either side of the conversation is one
        // learned gesture rather than two.
        CopyIconRow(text)
    }
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

/**
 * "Thought for Ns" plus a chevron. VS Code renders reasoning as a collapsible disclosure
 * (ThinkingDisplayMode: collapsed / collapsedPreview / fixedScrolling) and never prints the
 * model's raw reasoning. The same rule applies here: the collapsed line states the cost, the
 * expanded body lists what the assistant actually did - tool names, status, results - and says
 * plainly that the reasoning itself stays hidden.
 */
@Composable
fun ThinkingCard(seconds: Int, steps: List<String> = emptyList(), modifier: Modifier = Modifier) {
    val c = ChatTokens.LocalChatColors.current
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ChatTokens.RADIUS_CARD.dp))
            .background(c.surface)
            .clickable { expanded = !expanded }
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (expanded) "\u25BE" else "\u25B8",
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Thought for " + seconds + "s" + (if (steps.isEmpty()) "" else "  \u00b7  " + steps.size + " steps"),
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
            )
        }
        if (expanded) {
            if (steps.isEmpty()) {
                Text(
                    "Reasoning stays hidden. Nothing here is the model's internal text.",
                    color = c.textSecondary,
                    fontSize = ChatTokens.FONT_MICRO.sp,
                )
            } else {
                steps.forEach { step ->
                    Text(step, color = c.textSecondary, fontSize = ChatTokens.FONT_MICRO.sp)
                }
            }
        }
    }
}

/**
 * One AI action: chevron for the detail, x to dismiss. Collapsed by default because a long
 * run of steps is noise; the x exists because the owner asked to be able to clear a step they
 * have already read without clearing its neighbours.
 */
@Composable
fun ToolActivityRow(
    label: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    val c = ChatTokens.LocalChatColors.current
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ChatTokens.RADIUS_CARD.dp))
            .background(c.surface)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (expanded) "\u25BE" else "\u25B8",
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(end = 6.dp),
            )
            Text(
                label,
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
                modifier = Modifier
                    .weight(1f)
                    .clickable { expanded = !expanded },
            )
            // OWNER RULE (2026-11-05): an activity row collapses with its chevron and carries NO
            // dismiss x. The x lived here and could silently drop a step from the record.
        }
        if (expanded) {
            Text(detail, color = c.textSecondary, fontSize = ChatTokens.FONT_MICRO.sp)
        }
    }
}

/**
 * 2026-11-05 (owner: "install the activity grouping"): ONE collapsed row for a run of tool steps.
 *
 * The header is the count and the tool names; the chevron opens the individual steps. Chevron only,
 * no x - the owner's rule, and the reason the single-row version lost its dismiss glyph too. Corners
 * and padding follow the standing panel rule (8-12dp radius, 12dp horizontal, 10dp vertical).
 */
@Composable
fun ActivityGroupRow(
    items: List<ChatRowModel.ToolActivity>,
    modifier: Modifier = Modifier,
) {
    val c = ChatTokens.LocalChatColors.current
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ChatTokens.RADIUS_CARD.dp))
            .background(c.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (expanded) "\u25BE" else "\u25B8",
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(end = 6.dp),
            )
            Text(
                ActivityGrouping.summary(items),
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
                modifier = Modifier
                    .weight(1f)
                    .clickable { expanded = !expanded },
            )
        }
        if (expanded) {
            items.forEach { item ->
                Column(Modifier.padding(top = 6.dp)) {
                    Text(item.label, color = c.textSecondary, fontSize = ChatTokens.FONT_MICRO.sp)
                    if (item.detail.isNotBlank()) {
                        Text(item.detail, color = c.textSecondary, fontSize = ChatTokens.FONT_MICRO.sp)
                    }
                }
            }
        }
    }
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
