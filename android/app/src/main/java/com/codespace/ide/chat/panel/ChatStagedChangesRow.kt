package com.codespace.ide.chat.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.PendingChangesStore
import com.codespace.ide.chat.ui.ChatTokens
import java.io.File

/**
 * Batch 2b — the REVIEW half of Edit mode, inside the chat panel.
 *
 * The in-editor AiReviewStrip already owns the detailed view (line diff, Apply, Discard)
 * and stays the ONLY apply mechanism; this row exists because a proposal for a file the
 * user has not opened would otherwise be invisible: the strip renders for the active
 * editor tab only. So the row lists the staged files, opens one on tap (which brings the
 * strip into view), and offers Apply all / Discard all through the SAME store calls
 * (applyAll/discardAll) rather than a second implementation.
 *
 * Nothing here writes to disk by itself. Discard removes the pending record only.
 */
@Composable
internal fun ChatStagedChangesRow(
    files: List<PendingChangesStore.PendingChange>,
    onOpen: (String) -> Unit,
    onApplyAll: () -> Unit,
    onDiscardAll: () -> Unit,
    note: String?,
) {
    if (files.isEmpty()) return
    val c = ChatTokens.LocalChatColors.current
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = 4.dp)
            .background(c.surface, RoundedCornerShape(10.dp))
            .border(1.dp, c.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (files.size == 1) "1 proposed change" else files.size.toString() + " proposed changes",
                color = c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Not applied",
                color = c.textSecondary, fontSize = 10.sp,
            )
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 96.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            files.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(f.path) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        File(f.path).name,
                        color = c.text, fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text("open", color = c.accent, fontSize = 10.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.background(c.accent, RoundedCornerShape(8.dp))
                    .clickable { onApplyAll() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text("Apply all", color = c.bg, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
            Row(
                Modifier.background(c.surfaceAlt, RoundedCornerShape(8.dp))
                    .border(1.dp, c.border, RoundedCornerShape(8.dp))
                    .clickable { onDiscardAll() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text("Discard all", color = c.text, fontSize = 11.sp) }
        }
        if (!note.isNullOrBlank()) {
            Text(note, color = c.textSecondary, fontSize = 10.sp)
        }
    }
}

/** Honest one-liner for the outcome of an apply batch (partial failures are reported). */
internal fun stagedApplyNote(outcomes: List<PendingChangesStore.ApplyOutcome>): String {
    if (outcomes.isEmpty()) return "Nothing to apply."
    val applied = outcomes.count { it is PendingChangesStore.ApplyOutcome.Applied }
    val failed = outcomes.size - applied
    return if (failed == 0) "Applied " + applied + " file(s)."
    else "Applied " + applied + ", " + failed + " failed (see the editor strip for the reason)."
}
