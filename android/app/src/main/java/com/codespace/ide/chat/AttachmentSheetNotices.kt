package com.codespace.ide.chat

/**
 * B3 FIX (2026-10-10, advisor round on c5d8671): the sheet's "showing X of N
 * characters" line previously rendered UNCONDITIONALLY and compared the shown
 * copy against the ON-DISK FILE BYTE LENGTH — so a 17-character edited copy
 * showed "15 of 17" nonsense (bytes vs chars, stale copy vs disk), and even a
 * tiny 15-of-15 file always carried a truncation-looking counter.
 *
 * These are the PURE functions behind the sheet notices now:
 *  - sendableChars(): how many characters of THIS attachment actually ride the
 *    send, given the injector's real caps — SEND_CAP_PER_FILE per file and the
 *    shared SEND_CAP_PER_MESSAGE budget across ALL attachments of the message.
 *  - counterLine(): the one visible notice; NULL when nothing is truncated
 *    (a 17-character file must NEVER show it), otherwise an exact statement of
 *    what will be sent.
 *  - sizeHeaderChars(): the sheet header in CHARS of the copy being viewed —
 *    never the disk byte length (UTF-8 multibyte files made the old header lie).
 *
 * Pure Kotlin only (no Android imports) — JVM-tested: short text, text at the
 * cap, text over the cap, edits that grow or shrink the copy, and multi-
 * attachment budget interplay.
 */
object AttachmentSheetNotices {

    /**
     * How many characters of this attachment ride the send. [alreadyUsedByOthers]
     * = characters the message's OTHER attachments will consume (their sendable
     * chars, in attach order before this one) — the per-message budget is shared.
     */
    fun sendableChars(copyChars: Int, alreadyUsedByOthers: Int = 0): Int {
        val messageBudget = (ChatAttachmentInjector.SEND_CAP_PER_MESSAGE - alreadyUsedByOthers).coerceAtLeast(0)
        return copyChars.coerceAtMost(minOf(ChatAttachmentInjector.SEND_CAP_PER_FILE, messageBudget))
    }

    /**
     * The single truncation notice; null when the full copy rides the send.
     * Only the FIRST [sendable] characters are sent; edits past that stay in
     * the copy (still editable) but are not included.
     */
    fun counterLine(copyChars: Int, alreadyUsedByOthers: Int = 0): String? {
        if (copyChars <= 0) return null
        val sendable = sendableChars(copyChars, alreadyUsedByOthers)
        if (sendable >= copyChars) return null
        return "showing $sendable of $copyChars characters — only the first $sendable " +
            "will be sent; edits past character $sendable stay in the copy but are not sent"
    }

    /** Sheet header line: chars of the copy being viewed (+ EDITED marker). */
    fun sizeHeaderChars(copyChars: Int, edited: Boolean): String =
        "size: $copyChars chars" + (if (edited) "  |  EDITED" else "")
}
