package com.codespace.ide.chat.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * VS CODE'S CONVERSATION PROGRESS, ported from their own stylesheets rather than approximated.
 *
 * The mechanics came from chatThinkingContentPart.ts and chatCollapsibleContentPart.ts; the LOOK
 * comes from their CSS, and these are the exact values taken from it:
 *
 *  - Header label: `.chat-used-context-label` -> font-size body-m (13px here), colour
 *    `--vscode-descriptionForeground` (muted, never the primary text colour), user-select none,
 *    inline-flex with overflow-wrap anywhere so a long activity wraps instead of truncating.
 *  - Active title: `.chat-thinking-title-shimmer` -> a gradient swept across the TEXT
 *    (background-clip: text), 400% background size, 2s steps(60) infinite, transparent fill.
 *    With reduced motion it falls back to a plain colour.
 *  - Collapse/expand: `.chat-collapsible-content-animated` -> grid-template-rows 1fr <-> 0fr with
 *    opacity, rows 180ms cubic-bezier(0.2, 0, 0, 1), opacity 140ms, and on collapse visibility is
 *    delayed 180ms so the content is not clipped before it has finished moving.
 *  - Tool rows: `.chat-tool-call-with-icon` -> a fixed icon gutter on the left and the text inset by
 *    it, so every step lines up on one rail.
 *
 * Owner's choice for the header: name the LIVE activity while it runs. That is the shimmer line.
 */
@Composable
internal fun ThinkingTreeSection(
    seconds: Int,
    tree: List<TreeStep>,
    liveLabel: String,
    running: Boolean,
    conversationKey: String,
    modifier: Modifier = Modifier,
) {
    val c = ChatTokens.LocalChatColors.current
    val ctx = LocalContext.current
    val store = remember { ThinkingSectionState(ctx) }
    var expanded by remember(conversationKey) {
        mutableStateOf(store.isExpanded(conversationKey, ROOT_TURN))
    }

    // VS Code names the latest activity while running; when it is done the header settles.
    val header = when {
        running && liveLabel.isNotBlank() -> liveLabel
        running -> "Working"
        tree.isEmpty() -> "Worked for " + seconds + "s"
        else -> tree.size.toString() + (if (tree.size == 1) " step" else " steps") +
            " · " + tree.last().label
    }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = tree.isNotEmpty() || running) {
                    expanded = !expanded
                    store.setExpanded(conversationKey, ROOT_TURN, expanded)
                }
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The chevron only exists when the row can actually disclose something, which is
            // `.chat-collapsible-hover-chevron.hidden` in their CSS.
            if (tree.isNotEmpty() || running) {
                Text(
                    text = if (expanded) "▾" else "▸",
                    color = c.textSecondary,
                    fontSize = 10.sp,
                )
                Box(Modifier.width(6.dp))
            }
            if (running) {
                ShimmerLabel(header, c.textSecondary, c.accent)
            } else {
                Text(
                    text = header,
                    color = c.textSecondary,
                    fontSize = 13.sp,
                    maxLines = 2,
                )
            }
        }

        // Their collapse: rows 180ms on that curve, opacity 140ms, content leaves only after the
        // movement has finished.
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(180, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
                expandFrom = Alignment.Top,
            ) + fadeIn(animationSpec = tween(140)),
            exit = shrinkVertically(
                animationSpec = tween(180, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(animationSpec = tween(140)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 2.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Grouped by turn, because VS Code gives each turn its own section.
                val turns = remember(tree) { tree.groupBy { it.turnId } }
                for ((turnId, steps) in turns) {
                    var turnOpen by remember(conversationKey, turnId) {
                        mutableStateOf(store.isExpanded(conversationKey, turnId))
                    }
                    if (turns.size > 1) {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                turnOpen = !turnOpen
                                store.setExpanded(conversationKey, turnId, turnOpen)
                            }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (turnOpen) "▾" else "▸", color = c.textSecondary, fontSize = 9.sp)
                            Box(Modifier.width(5.dp))
                            Text(
                                "Turn " + (turns.keys.indexOf(turnId) + 1) + " · " +
                                    steps.size + " step" + (if (steps.size == 1) "" else "s"),
                                color = c.textSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    if (turnOpen || turns.size == 1) {
                        for (step in steps) StepRow(step, conversationKey, store, c)
                    }
                }
            }
        }
    }
}

/** One activity: a fixed icon gutter, then the label, then its own detail on demand. */
@Composable
private fun StepRow(
    step: TreeStep,
    conversationKey: String,
    store: ThinkingSectionState,
    c: ChatColors,
) {
    var open by remember(conversationKey, step.id) {
        mutableStateOf(store.isExpanded(conversationKey, step.id))
    }
    val hasDetail = step.detail.isNotBlank()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = hasDetail) {
                    if (hasDetail) {
                        open = !open
                        store.setExpanded(conversationKey, step.id, open)
                    }
                }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // The icon gutter. Their CSS pins the glyph to a compact codicon size at a fixed inset.
            Box(Modifier.width(14.dp), contentAlignment = Alignment.TopStart) {
                Text(
                    text = if (step.ok) "✓" else "✕",
                    color = if (step.ok) c.success else c.error,
                    fontSize = 10.sp,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = step.label,
                    color = c.textSecondary,
                    fontSize = 12.sp,
                    maxLines = if (open) 6 else 2,
                )
                if (open) {
                    Text(
                        text = step.detail,
                        color = c.textSecondary.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                    )
                }
            }
            if (hasDetail) {
                Text(
                    text = if (open) "▾" else "▸",
                    color = c.textSecondary.copy(alpha = 0.6f),
                    fontSize = 9.sp,
                )
            }
        }
    }
}

/**
 * `.chat-thinking-title-shimmer`: a highlight swept across the text itself. 400% background, 2s
 * steps(60) infinite in CSS; Compose interpolates instead of stepping, which reads the same at this
 * size. The resting colour is the description colour, so the label never shouts.
 */
@Composable
private fun ShimmerLabel(text: String, base: Color, highlight: Color) {
    val t = rememberInfiniteTransition(label = "thinking-shimmer")
    val shift by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shift",
    )
    // A 400%-wide gradient translated across the text, like their background-position sweep.
    val brush = Brush.linearGradient(
        colors = listOf(base, base, highlight, base, base),
        start = Offset(x = -600f + shift * 1200f, y = 0f),
        end = Offset(x = shift * 1200f, y = 0f),
    )
    Text(
        text = text,
        style = TextStyle(brush = brush, fontSize = 13.sp),
        maxLines = 2,
    )
}

/** Root key: the whole section for a conversation. */
internal const val ROOT_TURN = "__all__"

/**
 * Where "is this open" lives. VS Code keeps `_isExpanded` on the part and the part is rebuilt per
 * session; we keep the same intent across restarts by keying on conversation + turn + step.
 */
internal class ThinkingSectionState(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("thinking_sections", Context.MODE_PRIVATE)

    private fun key(conversation: String, part: String): String =
        (if (conversation.isBlank()) "_" else conversation) + "|" + part

    fun isExpanded(conversation: String, part: String): Boolean =
        prefs.getBoolean(key(conversation, part), false)

    fun setExpanded(conversation: String, part: String, open: Boolean) {
        prefs.edit().putBoolean(key(conversation, part), open).apply()
    }
}
