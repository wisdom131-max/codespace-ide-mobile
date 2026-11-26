@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.codespace.ide.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
data class AttachmentChipModel(val name: String, val sizeText: String, val index: Int = -1)

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

@Composable
fun SourcesRow(
    items: List<com.codespace.ide.agent.research.ResearchSources.Item>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val c = ChatTokens.LocalChatColors.current
    val opener = androidx.compose.ui.platform.LocalUriHandler.current
    // Sanitising here as well as in the model: this composable is the last gate before an Intent.
    val safe = remember(items) { com.codespace.ide.agent.research.ResearchSources.sanitise(items) }
    if (safe.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(c.surfaceAlt)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = ResearchPresentation.header(safe.size),
            style = MaterialTheme.typography.labelSmall,
            color = c.textSecondary,
        )
        val bounded = ResearchPresentation.bound(safe)
        bounded.shown.forEach { s ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .clickable {
                        if (com.codespace.ide.agent.research.ResearchSources.isOpenable(s.url)) {
                            runCatching { opener.openUri(s.url) }
                        }
                    }
                    .padding(vertical = 4.dp)
                    // PROJECT SEVEN 7.9A: at a large font scale the single-line title ellipsises, which
                    // is correct for the layout and wrong for the reader. The full title and domain are
                    // provided to TalkBack, so nothing important is lost and the row looks identical.
                    .semantics(mergeDescendants = true) {
                        contentDescription = ResearchPresentation.label(s)
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = s.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = s.domain,
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ResearchPresentation.overflowLine(bounded.hidden)?.let { line ->
            Text(line, color = c.textSecondary, fontSize = ChatTokens.FONT_MICRO.sp)
        }
    }
}

@Composable
private fun TreeChild(label: String, detail: String, c: com.codespace.ide.chat.ui.ChatColors, muted: Boolean) {
    Column(Modifier.padding(start = 14.dp, top = 6.dp)) {
        if (label.isNotBlank()) {
            Text(label, color = c.textSecondary, fontSize = ChatTokens.FONT_MICRO.sp)
        }
        if (detail.isNotBlank()) {
            Text(
                detail,
                color = if (muted) c.textSecondary else c.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
            )
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

/**
 * One compact attachment chip with an explicit remove control.
 *
 * Owner item 5: "attachment chips have no individual remove control". Correct - the whole chip used
 * to be one tap target whose only hint was a doc comment, so removal was invisible AND the chip
 * itself was a destructive button. Removal now lives on a visible X with a 40dp touch target
 * (the MANDATORY UI RULE's minimum tappable size) while the text stays inert, so tapping a file name
 * to read it can never delete it. Multiple attachments removed one at a time: the X removes the row
 * it belongs to, by index, so two files with the same name cannot vanish together.
 */
@Composable
fun AttachmentChip(chip: AttachmentChipModel, onRemove: (AttachmentChipModel) -> Unit, modifier: Modifier = Modifier) {
    val c = ChatTokens.LocalChatColors.current
    Row(
        modifier = modifier
            .background(c.surfaceAlt, ChipShape)
            .padding(start = ChatTokens.PAD_HORIZONTAL.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            chip.name + " · " + chip.sizeText,
            color = c.textSecondary,
            fontSize = ChatTokens.FONT_MICRO.sp,
            modifier = Modifier.padding(vertical = ChatTokens.GAP_TIGHT.dp),
        )
        // 40dp of touch target around a small glyph, per the mandatory touch-size rule. The
        // onClickLabel is what a screen reader announces; the glyph alone is decoration.
        Box(
            Modifier
                .size(40.dp)
                .clickable(onClickLabel = "Remove " + chip.name) { onRemove(chip) },
            contentAlignment = Alignment.Center,
        ) {
            Text("\u00D7", color = c.textSecondary, fontSize = 14.sp)
        }
    }
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
