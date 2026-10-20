package com.codespace.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.codespace.ide.chat.AttachmentIntake
import com.codespace.ide.chat.ChatAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * C12 Stage 3 U2/U3 (advisor GO 2026-10-20): OFF-MAIN extraction effects —
 * one small file per the 64KB rule (the panel body has zero headroom).
 *
 * For every pending FILE attachment whose verdict says DOCUMENT (and, from U3,
 * ARCHIVE) and that has no extractedDoc yet, ONE cancellable IO job runs
 * [AttachmentIntake.extractBlocking] under a 5 s timeout. The per-attachment
 * LaunchedEffect cancels itself the moment the attachment leaves the pending
 * list (send/remove) — removal drops the key, the coroutine dies, no orphan
 * work, no main-thread I/O ever.
 *
 * Timeout is honest: the attachment gets a refusal-style ExtractedDoc that
 * the sheet and the send block both display ("timed out — nothing sent").
 * Post-send chips are never re-extracted (readOnly lists carry what they have).
 */
@Composable
fun AttachmentExtractionEffects(
    attachments: List<ChatAttachment>,
    onExtracted: (ChatAttachment) -> Unit,
) {
    // Pending document/archive attachments: extract once each. The verdict is
    // re-derived here (bounded 8-byte read) so the effect owns its gating.
    val pending = remember(attachments) {
        attachments.filter { att ->
            att.kind == ChatAttachment.Kind.FILE &&
                att.extractedDoc == null &&
                verdictNeedsExtraction(att)
        }
    }
    for (att in pending) {
        LaunchedEffect(att.path) {
            val result = withTimeoutOrNull(AttachmentIntake.EXTRACT_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    AttachmentIntake.extractBlocking(att)
                }
            } ?: AttachmentIntake.ExtractedDoc(
                refusal = "Extraction timed out after ${AttachmentIntake.EXTRACT_TIMEOUT_MS / 1000} s — nothing extracted."
            )
            onExtracted(att.copy(extractedDoc = result))
        }
    }
}

private fun verdictNeedsExtraction(att: ChatAttachment): Boolean {
    val verdict = try {
        AttachmentIntake.verdictOf(att)
    } catch (_: Exception) { return false }
    return verdict.extraction == com.codespace.ide.chat.AttachmentExtraction.DOCUMENT ||
        verdict.extraction == com.codespace.ide.chat.AttachmentExtraction.ARCHIVE
}
