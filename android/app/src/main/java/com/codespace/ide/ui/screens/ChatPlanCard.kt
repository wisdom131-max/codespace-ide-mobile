package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.ChatPlanStore

/**
 * R7-PLAN (VS Code plan-review part parity): the structured plan card.
 *
 * Rides at the transcript end while the session has a plan. Doubles as the
 * todo list: the agent re-calls the plan tool with updated statuses as it
 * works, so the card shows live progress (done/in-progress/pending).
 *
 * Footer states:
 *  - !approved  -> "Approve" (auto-sends the approval message, agent executes)
 *                  + "Revise" (fills the input with a revision template)
 *  - approved && all steps done -> "Clear plan"
 */
@Composable
internal fun ChatPlanCard(
    plan: ChatPlanStore.Plan,
    onApprove: () -> Unit,
    onRevise: () -> Unit,
    onClear: () -> Unit,
    colors: ChatPanelColors,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.assistantBubble,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {

            // ── Header: title + progress ───────────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        if (plan.approved) "Plan — in progress" else "Plan — awaiting review",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
                    Text("${plan.steps.size} steps · ${plan.doneCount} done",
                        fontSize = 10.sp, color = colors.textSecondary)
                }
                Text(
                    if (plan.approved) "${plan.doneCount}/${plan.steps.size}" else "review",
                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = colors.accent)
            }

            Spacer(Modifier.height(8.dp))

            // ── Steps ──────────────────────────────────────────────────────
            plan.steps.forEachIndexed { i, step ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    StatusGlyph(step.status, colors)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(step.title, fontSize = 12.sp,
                            color = if (step.status == "completed") colors.textSecondary else colors.text,
                            fontWeight = FontWeight.Medium)
                        if (step.detail.isNotBlank()) {
                            Text(step.detail, fontSize = 10.sp, color = colors.textSecondary)
                        }
                    }
                    Text("${i + 1}", fontSize = 9.sp, color = colors.textSecondary)
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Footer ─────────────────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!plan.approved) {
                    PlanAction("Approve", colors.accent, Color.White, onApprove)
                    PlanAction("Revise", colors.surface, colors.text, onRevise)
                } else if (plan.allDone) {
                    PlanAction("Clear plan", colors.surface, colors.textSecondary, onClear)
                }
            }
        }
    }
}

@Composable
private fun StatusGlyph(status: String, colors: ChatPanelColors) {
    val glyph = when (status) {
        "completed"   -> "✓" to Color(0xFF4ADE80)
        "in_progress" -> "●" to colors.accent
        else          -> "○" to colors.textSecondary
    }
    Box(
        Modifier.size(16.dp).background(Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph.first, fontSize = 11.sp, color = glyph.second)
    }
}

@Composable
private fun PlanAction(label: String, bg: Color, textColor: Color, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = textColor,
        modifier = Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
