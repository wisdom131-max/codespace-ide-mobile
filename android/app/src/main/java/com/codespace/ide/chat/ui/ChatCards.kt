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
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (expanded) "\u25BE" else "\u25B8",
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
            )
            Spacer(Modifier.width(6.dp))
            // VS Code parity (owner request, 2026-11-23): their collapsed header NAMES what is
            // happening right now ("Editing files") instead of counting seconds at the user. The
            // elapsed time and the step count stay, because those are the honest part.
            Text(
                com.codespace.ide.engine.ThinkingTitles.runningTitle(steps) + "  \u00b7  " + seconds + "s" +
                    (if (steps.isEmpty()) "" else "  \u00b7  " + steps.size + " steps"),
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
                // VS Code parity: the expanded section reads as a tree, one indented line per
                // step, not as a wall of identical text. TreeChild is this file's own row helper.
                steps.forEach { step ->
                    val label = step.substringBefore(": ").trim()
                    val detail = if (step.contains(": ")) step.substringAfter(": ").trim() else ""
                    TreeChild(label, detail, c, true)
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
    ok: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val c = ChatTokens.LocalChatColors.current
    var expanded by remember { mutableStateOf(false) }
    // Area 4: the row reads through the presentation ruler, so a failure says "failed" with its
    // real reason, a capped result says "(partial)", and a long body is bounded instead of poured
    // into the transcript. The Thinking Tree still owns grouping and identity; this only styles
    // one step.
    val p = remember(label, detail, ok) {
        com.codespace.ide.chat.panel.ToolResultPresentation.present(label, ok, detail)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ChatTokens.RADIUS_CARD.dp))
            .background(c.surface)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // PROJECT SEVEN 7.9: the whole header is the control, at least 48dp tall. The chevron on
            // its own was roughly a 12dp tap target, which is unusable with a thumb and invisible to
            // TalkBack as anything but a triangle.
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = com.codespace.ide.chat.panel.CopilotAccessibility.MIN_TOUCH_DP.dp)
                .clickable { expanded = !expanded }
                .semantics(mergeDescendants = true) {
                    contentDescription = p.title + ". " +
                        (if (p.kind == com.codespace.ide.chat.panel.ToolResultPresentation.Kind.ERROR)
                            "failed" else "finished") +
                        ". " + (if (expanded) "Expanded, double tap to collapse." else "Double tap for details.")
                },
        ) {
            Text(
                if (expanded) "\u25BE" else "\u25B8",
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text(
                p.title,
                color = if (p.kind == com.codespace.ide.chat.panel.ToolResultPresentation.Kind.ERROR)
                    c.error else c.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
                // STAGE 10: one line when collapsed. A long call label used to wrap and make the
                // closed row taller than the open one on a narrow screen.
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // OWNER RULE (2026-11-05): an activity row collapses with its chevron and carries NO
            // dismiss x. The x lived here and could silently drop a step from the record.
        }
        // The summary is always visible for a failure or a partial result: the reason for a
        // stopped tool is not something the user should have to discover by expanding.
        val showSummary = p.kind != com.codespace.ide.chat.panel.ToolResultPresentation.Kind.RESULT
        if (showSummary) {
            Text(
                p.summary,
                color = if (p.kind == com.codespace.ide.chat.panel.ToolResultPresentation.Kind.ERROR)
                    c.error else c.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
                // STAGE 10: the status line is ALWAYS visible, but a collapsed row shows exactly one
                // line of it. A multi-line failure summary used to reserve its full height while the
                // tree was closed, which is what made collapsing look like it did nothing.
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp, top = 2.dp),
            )
        }
        if (expanded && p.body.isNotEmpty()) {
            Text(
                p.body.joinToString("\n"),
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
                modifier = Modifier.padding(start = 14.dp, top = 2.dp),
            )
        }
        // Never let a cut body look whole: say how much was withheld.
        if (expanded && p.bodyTruncated) {
            Text(
                "Result withheld past " + p.body.size + " lines. Narrow the call to see the rest.",
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
                modifier = Modifier.padding(start = 14.dp, top = 2.dp),
            )
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
/**
 * 2026-11-11 (owner): research sources, tappable. Compact by design: one line per source, title
 * then domain, no card chrome - it belongs to the answer above it, not to the Thinking Tree.
 * The URL is validated before it is handed to the platform handler: http and https only.
 */
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
fun ActivityGroupRow(
    id: String,
    items: List<ChatRowModel>,
    modifier: Modifier = Modifier,
) {
    val c = ChatTokens.LocalChatColors.current
    // Keyed by the task id, not by list position: a streaming update must not collapse a tree the
    // user opened. saveable so it also survives the list recycling while scrolling.
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ChatTokens.RADIUS_CARD.dp))
            // OWNER REQUEST (2026-11-23): a VS Code type card. Theirs is a bordered surface rather
            // than a filled block, so the tree reads as an object in the transcript instead of a
            // slab of colour that competes with the answer around it.
            .background(c.surfaceAlt)
            .border(1.dp, c.border, RoundedCornerShape(ChatTokens.RADIUS_CARD.dp))
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // PROJECT SEVEN 7.9: the header is the control, at least 48dp tall, and it announces what
            // the group contains instead of reading out a lone triangle.
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = com.codespace.ide.chat.panel.CopilotAccessibility.MIN_TOUCH_DP.dp)
                .clickable { expanded = !expanded }
                .semantics(mergeDescendants = true) {
                    contentDescription = com.codespace.ide.chat.panel.CopilotAccessibility.groupLabel(
                        com.codespace.ide.chat.panel.CopilotBindings.groupHeader(
                            ActivityGrouping.summary(items), items,
                        ),
                        items,
                        expanded,
                    )
                },
        ) {
            Text(
                if (expanded) "\u25BE" else "\u25B8",
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text(
                // PROJECT SEVEN 7.3: the header counts the states the rows actually carry, so a group
                // never reads "done" unless the runtime said so.
                com.codespace.ide.chat.panel.CopilotBindings.groupHeader(ActivityGrouping.summary(items), items),
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
                // One line, always: a header that wraps to three lines defeats the point of
                // collapsing the work in the first place.
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (expanded) {
            // VS Code type body: the children hang inside one inset rule, so the group reads as a
            // single task with children rather than a stack of loose lines.
            Column(
                Modifier
                    .padding(start = 4.dp, top = 4.dp)
                    .border(1.dp, c.border, RoundedCornerShape(2.dp))
                    .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
            ) {
                items.forEach { item ->
                    when (item) {
                        is ChatRowModel.ToolActivity -> TreeChild(
                            // The state word is spelled out, so status never depends on colour alone.
                            com.codespace.ide.chat.panel.CopilotBindings.childLine(item),
                            item.detail, c, false,
                        )
                        is ChatRowModel.InfoNote -> TreeChild("", item.text, c, true)
                        is ChatRowModel.AssistantStream -> TreeChild("", item.text, c, true)
                        else -> Unit
                    }
                }
            }
        }
    }
}

/** One child line of a task tree. Notes and narration are muted; steps carry their result under. */
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
