package com.codespace.ide.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B3 FIX suite (advisor round on c5d8671, 2026-10-10): pure JVM tests for the
 * sheet-notice window functions. The device failure was a 17-character edited
 * copy showing "showing 15 of 17" — the counter rendered unconditionally and
 * compared against the disk BYTE length. These tests pin the new contract:
 *  - short text: NO notice ever (a 17-character file must never show it);
 *  - text at the cap: NO notice (exactly 12,000 chars rides whole);
 *  - text over the cap: the notice states exactly what will be sent;
 *  - edits that grow or shrink the copy: the notice follows the LIVE length;
 *  - multi-attachment budget: the shared 24,000-char message cap can truncate
 *    a file BELOW the 12,000 per-file cap.
 */
class AttachmentSheetNoticesTest {

    private val CAP = ChatAttachmentInjector.SEND_CAP_PER_FILE        // 12000
    private val TOTAL = ChatAttachmentInjector.SEND_CAP_PER_MESSAGE   // 24000

    // ── counterLine: when it must NOT appear ──────────────────────────────

    @Test
    fun `short text never shows the counter`() {
        // The exact B3 case: a 17-character copy (15 original + 2 typed).
        assertNull(AttachmentSheetNotices.counterLine(17))
        assertNull(AttachmentSheetNotices.counterLine(0))
        assertNull(AttachmentSheetNotices.counterLine(1))
    }

    @Test
    fun `text at the cap shows no counter — nothing is truncated`() {
        assertNull(AttachmentSheetNotices.counterLine(CAP))
        assertNull(AttachmentSheetNotices.counterLine(CAP - 1))
    }

    @Test
    fun `text under the file cap but inside the message budget shows no counter`() {
        assertNull(AttachmentSheetNotices.counterLine(500, alreadyUsedByOthers = 1000))
    }

    // ── counterLine: when it MUST appear, with exact numbers ───────────────

    @Test
    fun `text over the cap states exactly what will be sent`() {
        val line = AttachmentSheetNotices.counterLine(CAP + 1)
        assertEquals(
            "showing $CAP of ${CAP + 1} characters — only the first $CAP " +
                "will be sent; edits past character $CAP stay in the copy but are not sent",
            line,
        )
    }

    @Test
    fun `way over the cap states the send window, not the file length`() {
        val line = AttachmentSheetNotices.counterLine(40000)
        assertEquals(
            "showing $CAP of 40000 characters — only the first $CAP " +
                "will be sent; edits past character $CAP stay in the copy but are not sent",
            line,
        )
    }

    @Test
    fun `other attachments can shrink this file's send window below the per-file cap`() {
        // 20,000 already used by others: message budget left = 4,000.
        val line = AttachmentSheetNotices.counterLine(6000, alreadyUsedByOthers = 20000)
        assertEquals(
            "showing 4000 of 6000 characters — only the first 4000 " +
                "will be sent; edits past character 4000 stay in the copy but are not sent",
            line,
        )
    }

    @Test
    fun `message budget exhausted sends nothing and says so`() {
        val line = AttachmentSheetNotices.counterLine(100, alreadyUsedByOthers = TOTAL)
        assertEquals(
            "showing 0 of 100 characters — only the first 0 " +
                "will be sent; edits past character 0 stay in the copy but are not sent",
            line,
        )
    }

    // ── sendableChars: the window math itself ─────────────────────────────

    @Test
    fun `sendableChars caps at the per-file cap`() {
        assertEquals(CAP, AttachmentSheetNotices.sendableChars(99999))
        assertEquals(5000, AttachmentSheetNotices.sendableChars(5000))
    }

    @Test
    fun `sendableChars caps at the remaining message budget`() {
        assertEquals(4000, AttachmentSheetNotices.sendableChars(6000, alreadyUsedByOthers = 20000))
        assertEquals(0, AttachmentSheetNotices.sendableChars(6000, alreadyUsedByOthers = TOTAL))
        // Over-budget others can never go negative.
        assertEquals(0, AttachmentSheetNotices.sendableChars(6000, alreadyUsedByOthers = TOTAL + 5000))
    }

    // ── edits that grow or shrink the copy: the notice follows the live length ──

    @Test
    fun `growing edit crosses the cap and the counter appears, then disappears on shrink`() {
        val original = 11000
        val typedExtra = 1500
        assertNull(AttachmentSheetNotices.counterLine(original))
        val grown = original + typedExtra
        assertEquals(
            "showing $CAP of $grown characters — only the first $CAP " +
                "will be sent; edits past character $CAP stay in the copy but are not sent",
            AttachmentSheetNotices.counterLine(grown),
        )
        val shrunk = 9000
        assertNull(AttachmentSheetNotices.counterLine(shrunk))
    }

    // ── sizeHeaderChars: chars of the viewed copy, EDITED marker ──────────

    @Test
    fun `size header counts copy chars and marks edits`() {
        assertEquals("size: 17 chars  |  EDITED", AttachmentSheetNotices.sizeHeaderChars(17, edited = true))
        assertEquals("size: 15 chars", AttachmentSheetNotices.sizeHeaderChars(15, edited = false))
    }
}
