package com.codespace.ide.chat.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * THE THINKING TREE, REBUILT ON VS CODE'S CONVERSATION PROGRESS (owner decision 2026-11-24:
 * "use exactly vs codes rendering for the tree leave nothing out").
 *
 * What VS Code does, and what this mirrors:
 *
 *  1. ONE section per turn. Reasoning and tool calls share it; there is no second store rendered
 *     beside the transcript, which is why hidden work cannot leak there.
 *  2. COLLAPSED by default (`_isExpanded` starts false in ChatCollapsibleContentPart).
 *  3. LAZY: the body is composed only after the section is opened for the first time. Nothing
 *     exists to draw before that, so a fresh chat cannot show a ghost.
 *  4. The collapsed HEADER NAMES THE LIVE ACTIVITY while running, and settles to a summary of the
 *     turn when it finishes.
 *  5. OPEN/CLOSED STATE IS REMEMBERED per conversation and per turn, so a chat you left open comes
 *     back open and one you left closed comes back closed.
 *  6. NESTING: each turn is a sub-section, and each step opens to show what it actually did.
 *  7. Phone layouts in VS Code always use the collapsed preview; this app is a phone, so collapsed
 *     is the default and the expanded form is deliberate.
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
    val context = LocalContext.current
    val store = remember { ThinkingSectionState(context) }

    // One section per turn, in the order the turns happened.
    val turns = remember(tree) { tree.groupBy { it.turnId }.entries.toList() }
    val total = tree.size

    val sectionKey = "conv:$conversationKey"
    var expanded by remember(conversationKey) { mutableStateOf(store.isOpen(sectionKey)) }

    val header = when {
        running && liveLabel.isNotBlank() -> liveLabel
        running -> "Working"
        total == 0 && seconds > 0 -> "Thought for " + seconds + "s"
        total == 0 -> "Thought for " + seconds + "s"
        else -> total.toString() + (if (total == 1) " step" else " steps") +
            (tree.lastOrNull()?.label?.takeIf { it.isNotBlank() }?.let { " · " + it } ?: "")
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.surfaceAlt)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable {
                expanded = !expanded
                store.setOpen(sectionKey, expanded)
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (expanded) "▾" else "▸",
                color = c.textSecondary,
                fontSize = 11.sp,
            )
            Text(
                header,
                color = if (running) c.text else c.textSecondary,
                fontSize = 12.sp,
                fontWeight = if (running) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            if (running) {
                Text("···", color = c.accent, fontSize = 12.sp)
            } else if (seconds > 0) {
                Text(seconds.toString() + "s", color = c.textSecondary, fontSize = 11.sp)
            }
        }

        // LAZY: nothing below is composed until the section has been opened once.
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (turns.isEmpty()) {
                    Text(
                        if (running) "Working on it." else "No steps were recorded for this turn.",
                        color = c.textSecondary, fontSize = 11.sp,
                    )
                }
                turns.forEachIndexed { index, entry ->
                    TurnGroup(
                        turnLabel = "Turn " + (index + 1),
                        steps = entry.value,
                        running = running && index == turns.lastIndex,
                        openKey = sectionKey + ":turn:" + entry.key,
                        store = store,
                    )
                }
            }
        }
    }
}

@Composable
private fun TurnGroup(
    turnLabel: String,
    steps: List<TreeStep>,
    running: Boolean,
    openKey: String,
    store: ThinkingSectionState,
) {
    val c = ChatTokens.LocalChatColors.current
    var open by remember(openKey) { mutableStateOf(store.isOpen(openKey)) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open; store.setOpen(openKey, open) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (open) "▾" else "▸", color = c.textSecondary, fontSize = 10.sp)
            Text(
                turnLabel + " · " + steps.size + (if (steps.size == 1) " step" else " steps"),
                color = c.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        if (open) {
            steps.forEach { step ->
                StepLine(step, store)
            }
        }
    }
}

@Composable
private fun StepLine(step: TreeStep, store: ThinkingSectionState) {
    val c = ChatTokens.LocalChatColors.current
    val key = "step:" + step.id
    var open by remember(key) { mutableStateOf(store.isOpen(key)) }
    Column(Modifier.fillMaxWidth().padding(start = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = step.detail.isNotBlank()) {
                open = !open
                store.setOpen(key, open)
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (step.ok) "✓" else "✗",
                color = if (step.ok) c.textSecondary else c.error,
                fontSize = 11.sp,
            )
            Text(
                step.label,
                color = c.text,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            if (step.detail.isNotBlank()) {
                Text(if (open) "▾" else "▸", color = c.textSecondary, fontSize = 10.sp)
            }
        }
        if (open && step.detail.isNotBlank()) {
            Text(
                step.detail,
                color = c.textSecondary,
                fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, top = 2.dp, bottom = 2.dp),
            )
        }
    }
}

/**
 * Open/closed state, remembered across restarts. VS Code keeps this in the model for the session;
 * a phone rebuilds the list often enough that a restart would otherwise forget it, and the owner
 * asked for the tree state to persist per session.
 */
internal class ThinkingSectionState(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("thinking_sections", Context.MODE_PRIVATE)

    fun isOpen(key: String): Boolean = prefs.getBoolean(key, false)
    fun setOpen(key: String, open: Boolean) {
        prefs.edit().putBoolean(key, open).apply()
    }
}
