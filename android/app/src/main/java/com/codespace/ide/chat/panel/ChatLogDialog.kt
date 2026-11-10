package com.codespace.ide.chat.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.codespace.ide.chat.ui.ChatTokens
import com.codespace.ide.engine.ChatLog
import com.codespace.ide.engine.ChatLogEntry

/**
 * THE CHAT LOG SCREEN. What each send actually was, newest first, with one Copy that produces a
 * report safe to paste in public (redaction lives in ChatLogSanitizer, not here).
 *
 * Rows show the DECISION, not a wall of text: what was sent, how big it was, what came back. The
 * provider's own words are behind the row's own detail line so a long body cannot swallow the list.
 */
internal object ChatLogView {

    const val EMPTY = "No sends recorded yet. This session's requests and their answers appear here."

    /** One line per entry: the fact that answers "was it even sent, and how big was it". */
    fun rowLine(e: ChatLogEntry): String {
        val size = "~" + e.estTokens + "t"
        val status = e.httpStatus?.let { "HTTP $it" } ?: "-"
        val drop = if (e.historyDropped > 0) " · ${e.historyDropped} dropped" else ""
        val retry = if (e.retriedCeilingField) " · retried" else ""
        return e.outcome + " · " + status + " · " + size + " · " + e.mode +
            " · " + e.modelId.substringAfterLast('/') + drop + retry
    }

    /** The single line of provider text a row may show, already sanitized by the log. */
    fun detailLine(e: ChatLogEntry): String {
        val text = e.providerMessage.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return e.endpointPath
        return if (text.length <= 220) text else text.take(219) + "\u2026"
    }

    /** The text the Copy button puts on the clipboard. */
    fun copyText(): String = ChatLog.report()
}

@Composable
internal fun ChatLogDialog(onDismiss: () -> Unit) {
    val c = ChatTokens.LocalChatColors.current
    var bump by remember { mutableStateOf(0) }
    val entries = remember(bump) { ChatLog.recent(100) }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "Chat log",
                    color = c.text,
                    fontSize = ChatTokens.FONT_BODY.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (copied) "Copied" else "Copy report",
                    color = c.accent,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                    modifier = Modifier.clickable {
                        clipboard.setText(AnnotatedString(ChatLogView.copyText()))
                        copied = true
                    },
                )
            }
            Spacer(Modifier.heightIn(min = 8.dp))
            if (entries.isEmpty()) {
                Text(ChatLogView.EMPTY, color = c.textSecondary, fontSize = ChatTokens.FONT_SMALL.sp)
            } else {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                ) {
                    entries.forEach { e ->
                        Text(ChatLogView.rowLine(e), color = c.text, fontSize = ChatTokens.FONT_SMALL.sp)
                        Text(
                            ChatLogView.detailLine(e),
                            color = c.textSecondary,
                            fontSize = ChatTokens.FONT_MICRO.sp,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.heightIn(min = 8.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "Clear",
                    color = c.warning,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                    modifier = Modifier.clickable { ChatLog.clear(); bump++ },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Close",
                    color = c.accent,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                    modifier = Modifier.clickable { onDismiss() },
                )
            }
        }
    }
}
