package com.codespace.ide.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.AttachmentSnapshot
import com.codespace.ide.chat.ChatAttachment
import com.codespace.ide.ui.screens.ChatPanelColors

/**
 * C12 s1-b (owner-approved 2026-10-08): the pre-send AttachmentSheet — view and
 * edit the ATTACH-TIME copy. Opened by tapping an attachment chip (chip body =
 * one large tap target; the x stays a separate trailing button, advisor item 6).
 *
 * Notices (advisor items 1 + 2): the change notice compares the first-8 KB hash
 * now vs attach ("no change detected" / "file changed since attaching — the
 * edited copy still rides the send"); the send-portion notice states EXACTLY
 * what rides when the caps will truncate — "Only the first N characters will be
 * sent; the rest is not included". Edits beyond the cap stay possible (the copy
 * stays whole) but the sheet keeps the flag line visible.
 *
 * The UNEDITED attachment keeps a FRESH DISK READ at send time (s1-e); the
 * in-memory copy rides the send ONLY when the user EDITED it.
 *
 * IMAGE/AUDIO: info row only (sent as a part) — no editor (s1-c adds the binary
 * read-only notice for binary files).
 *
 * Editor content lives in the CALLER's state so the snapshot/EDITED flag stays
 * the single source of truth; this composable only renders and reports actions.
 *
 * UI rules: rounded corners 8-12dp, panel padding 12dp horizontal / 10dp vertical.
 */
@Composable
internal fun AttachmentSheet(
    attachment: ChatAttachment,
    onEdit: (String) -> Unit,
    onReset: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    colors: ChatPanelColors,
) {
    val snap: AttachmentSnapshot? = attachment.snapshot
    val isFileKind = attachment.kind == ChatAttachment.Kind.FILE
    val isSelection = attachment.kind == ChatAttachment.Kind.SELECTION
    val isMedia = attachment.kind == ChatAttachment.Kind.IMAGE || attachment.kind == ChatAttachment.Kind.AUDIO

    // The shown copy: the edited override when edited, else the attach-time copy.
    val shown = snap?.editedContent ?: snap?.content ?: ""

    // Full length of the underlying basis: the file for FILE kind, the copy itself
    // for SELECTION (no file basis).
    val fullLen = remember(attachment) {
        if (isFileKind) {
            try { java.io.File(attachment.path).length().toInt() } catch (_: Exception) { shown.length }
        } else shown.length
    }

    // Change notice: first-8 KB hash NOW vs at attach (VIEW basis only — never a
    // send input). One bounded 8 KB read per sheet open; binary/truncated files
    // have no text basis to compare, so the notice is skipped for them.
    val changedSinceAttach = remember(attachment) {
        if (isFileKind && snap != null && !snap.isBinary && !snap.truncated && snap.attachHash8k.isNotEmpty()) {
            try {
                val all = java.io.File(attachment.path).readBytes()
                val win = if (all.size > AttachmentSnapshot.HASH_WINDOW_BYTES) all.copyOf(AttachmentSnapshot.HASH_WINDOW_BYTES) else all
                AttachmentSnapshot.hash8k(win) != snap.attachHash8k
            } catch (_: Exception) { false }
        } else false
    }

    val kindLabel = when (attachment.kind) {
        ChatAttachment.Kind.FILE -> "file"
        ChatAttachment.Kind.SELECTION -> "selection"
        ChatAttachment.Kind.IMAGE -> "image"
        ChatAttachment.Kind.AUDIO -> "audio"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(attachment.relPath, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 2)
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // header: kind, size, edit state
                Text(
                    "kind: " + kindLabel + "  |  size: " + (if (fullLen > 0) fullLen.toString() else "n/a") + " chars" +
                        (if (snap != null && snap.edited) "  |  EDITED" else ""),
                    fontSize = 10.sp, color = colors.textSecondary,
                )
                // change notice (advisor ruling wording)
                if (isFileKind && snap != null && !snap.isBinary) {
                    if (snap.truncated) {
                        Text(
                            "attached as truncated copy — only the first " +
                                (AttachmentSnapshot.LOAD_CAP_BYTES / 1024) + " KB is loaded (partial copy, cannot be saved back)",
                            fontSize = 10.sp, color = colors.textSecondary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    } else if (changedSinceAttach) {
                        Text(
                            "file changed since attaching — the edited copy still rides the send",
                            fontSize = 10.sp, color = colors.accent,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    } else {
                        Text(
                            "no change detected",
                            fontSize = 10.sp, color = colors.textSecondary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                // "showing X of N characters" counter over the capped view
                Text(
                    "showing " + shown.length + " of " + fullLen + " characters",
                    fontSize = 10.sp, color = colors.textSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                // send-portion notice (advisor item 2): exactly what rides the send
                val sendCap = 12000
                if (!isMedia && shown.length > sendCap) {
                    Text(
                        "Only the first " + sendCap + " characters will be sent; the rest is not included. " +
                            "Edits past character " + sendCap + " stay in the copy but are not sent.",
                        fontSize = 10.sp, color = colors.accent,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                // editor / info row
                if (isMedia) {
                    Text(
                        if (attachment.kind == ChatAttachment.Kind.IMAGE)
                            "Sent as an image part with this message."
                        else
                            "Sent as an audio part with this message.",
                        fontSize = 11.sp, color = colors.text,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    )
                } else if (snap != null && snap.isBinary) {
                    // C12 s1-c: binary — editor disabled, read-only notice (ruling wording).
                    Text(
                        "Attached as binary \u2014 content cannot be edited or shown in the sheet.",
                        fontSize = 11.sp, color = colors.text,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                } else {
                    OutlinedTextField(
                        value = shown,
                        onValueChange = onEdit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .heightIn(max = 280.dp),
                        placeholder = { Text(if (isSelection) "Edit the selection snippet" else "Edit the copy that rides the send", fontSize = 10.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        ),
                        shape = RoundedCornerShape(10.dp),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.surface,
                        ),
                    )
                    Text(
                        if (isSelection) "Edit applies to this snippet only — the file on disk is untouched."
                        else "Edit applies to the copy that rides the send — the file on disk is untouched.",
                        fontSize = 10.sp, color = colors.textSecondary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (snap != null && snap.edited) {
                    TextButton(onClick = onReset) { Text("Reset to attached version", fontSize = 11.sp) }
                }
                TextButton(onClick = {
                    onRemove()
                    onDismiss()
                }) { Text("Remove attachment", fontSize = 11.sp, color = colors.accent) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", fontSize = 11.sp) }
        },
    )
}
