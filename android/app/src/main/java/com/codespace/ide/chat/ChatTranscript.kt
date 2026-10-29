package com.codespace.ide.chat

import com.codespace.ide.chat.ui.ChatRowModel

/**
 * BATCH 4 — CONVERSATION PERSISTENCE (owner mission part 4).
 *
 * What exists in the app today: nothing. Sessions live in a HashMap in
 * ChatSessionManager and every restart starts from zero rows, so a conversation that
 * mattered is gone the moment the process dies. This file is the storage MODEL - the
 * part that decides what is worth keeping and how it survives a round trip - and it is
 * deliberately free of Android and Compose so it can be unit-tested on the JVM.
 *
 * TWO JUDGEMENT CALLS, both stated rather than implied:
 *
 * 1. NOT EVERY ROW IS HISTORY. Thinking, Failed, RateLimited, NoCredits, Offline,
 *    OverBudget and TrimmedHistory describe a MOMENT (this request is retrying, the
 *    network is down). Restoring them tomorrow would show a live-looking "Offline" card
 *    for a connection that has long since come back. They are actions, not transcript,
 *    so they are not stored. User text, assistant text and honest system NOTES (the
 *    "wrote src/a.kt" kind) are.
 *
 * 2. ATTACHMENTS ARE NAMED, NEVER CARRIED. The attachment bytes were already promised
 *    never to be persisted (WP8 redaction rule) and that promise holds here: a restore
 *    shows "Attached: photo.png" so the message still reads correctly, and re-sending is
 *    the user's decision, not something this layer silently fakes.
 *
 * AN INCOMPLETE REPLY IS KEPT AND SAID OUT LOUD. If the app closed mid-stream the partial
 * text is real content the user may still want, so it is stored with complete=false and
 * restored with a note that names what happened instead of pretending the reply finished.
 */
/**
 * One persisted message. PUBLIC because the public [com.codespace.ide.chat.TranscriptSink]
 * contract passes lists of it; the mapping and the store stay internal.
 */
data class StoredMessage(
    /** "user" | "assistant" | "note". Unknown roles are skipped on read, never crashed on. */
    val role: String,
    val text: String,
    /** false only for an assistant reply that was still streaming when it was saved. */
    val complete: Boolean = true,
    /** Wall-clock at save time; kept for debugging and future ordering, never shown. */
    val atMs: Long = 0L,
)

/** Result of decoding a stored transcript: messages plus how much was thrown away. */
internal data class DecodedTranscript(
    val messages: List<StoredMessage>,
    /** Lines that could not be parsed (torn write, hand edit, future format). */
    val skippedLines: Int = 0,
    /** True when the file was readable but not in a format this build understands. */
    val unreadable: Boolean = false,
)

internal object ChatTranscript {

    const val FORMAT_VERSION = "v1"

    /** Bounded storage: a runaway conversation must not become a slow file. */
    const val MAX_MESSAGES = 300
    const val MAX_TEXT_CHARS = 40_000

    const val INTERRUPTED_NOTE =
        "That reply was cut off (the app closed while it was still streaming). " +
            "Ask again if you need the rest."

    private const val TRUNC_MARKER = "\n[... truncated when saved: "

    // ---------------------------------------------------------------- write side

    /**
     * Rows -> storable messages, PRESERVING ORDER.
     *
     * [streaming] is passed rather than inferred because only the session knows whether a
     * reply is still arriving: the last assistant message is the only one that can be
     * incomplete, and marking it is what makes an interrupted reply honest on restore.
     */
    fun rowsToMessages(
        rows: List<ChatRowModel>,
        streaming: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): List<StoredMessage> {
        val mapped = ArrayList<StoredMessage>(rows.size)
        for (row in rows) {
            when (row) {
                is ChatRowModel.User -> mapped.add(StoredMessage("user", row.text, true, nowMs))
                is ChatRowModel.AssistantStream -> mapped.add(StoredMessage("assistant", row.text, true, nowMs))
                is ChatRowModel.InfoNote -> mapped.add(StoredMessage("note", row.text, true, nowMs))
                is ChatRowModel.Attachments -> {
                    // Names only — the bytes are never persisted (WP8 redaction rule).
                    val names = row.chips.joinToString(", ") { it.name }
                    if (names.isNotBlank()) mapped.add(StoredMessage("note", "Attached: " + names, true, nowMs))
                }
                // Transient surfaces: an action or a moment, not transcript. See header.
                is ChatRowModel.Thinking, is ChatRowModel.Failed, is ChatRowModel.RateLimited,
                ChatRowModel.NoCredits, ChatRowModel.Offline, is ChatRowModel.OverBudget,
                is ChatRowModel.TrimmedHistory -> Unit
            }
        }
        if (streaming) {
            val lastAssistant = mapped.indexOfLast { it.role == "assistant" }
            if (lastAssistant >= 0) mapped[lastAssistant] = mapped[lastAssistant].copy(complete = false)
        }
        return cap(mapped)
    }

    /** Newest kept; oldest dropped. Per-message text capped, and the cap SAYS so. */
    fun cap(messages: List<StoredMessage>): List<StoredMessage> {
        val kept = if (messages.size > MAX_MESSAGES) messages.takeLast(MAX_MESSAGES) else messages
        return kept.map { m ->
            if (m.text.length <= MAX_TEXT_CHARS) m
            else m.copy(
                text = m.text.take(MAX_TEXT_CHARS) + TRUNC_MARKER +
                    (m.text.length - MAX_TEXT_CHARS) + " chars]",
            )
        }
    }

    // ---------------------------------------------------------------- read side

    /** Messages -> rows. An incomplete reply comes back WITH its explanation. */
    fun messagesToRows(messages: List<StoredMessage>): List<ChatRowModel> {
        val rows = ArrayList<ChatRowModel>(messages.size)
        for (m in messages) {
            when (m.role) {
                "user" -> if (m.text.isNotBlank()) rows.add(ChatRowModel.User(m.text))
                "assistant" -> {
                    if (m.text.isNotBlank()) {
                        // cursorOn=false: nothing is streaming on a restored row, and a
                        // blinking cursor on old text would be a lie.
                        rows.add(ChatRowModel.AssistantStream(m.text, cursorOn = false))
                    }
                    if (!m.complete) rows.add(ChatRowModel.InfoNote(INTERRUPTED_NOTE))
                }
                "note" -> if (m.text.isNotBlank()) rows.add(ChatRowModel.InfoNote(m.text))
                // Unknown role (a newer build wrote it): skip, do not lose the rest.
                else -> Unit
            }
        }
        return rows
    }

    // ---------------------------------------------------------------- format

    /**
     * One line per message: `role \t complete \t atMs \t escapedText`.
     *
     * Deliberately NOT JSON: org.json is a stub in this project's JVM unit tests (it
     * throws "not mocked"), and a format the tests cannot read is a format that is only
     * verified on a device. It is also line-oriented on purpose — a torn write can damage
     * one line, and the reader skips that line instead of losing the whole conversation.
     */
    fun encode(messages: List<StoredMessage>): String {
        val sb = StringBuilder(64 + messages.size * 48)
        sb.append(FORMAT_VERSION).append('\n')
        for (m in messages) {
            sb.append(m.role).append('\t')
                .append(if (m.complete) '1' else '0').append('\t')
                .append(m.atMs).append('\t')
                .append(escape(m.text)).append('\n')
        }
        return sb.toString()
    }

    /** Tolerant reader: unknown header / blank file -> empty; bad lines -> skipped. */
    fun decode(raw: String): DecodedTranscript {
        if (raw.isBlank()) return DecodedTranscript(emptyList())
        val lines = raw.split('\n')
        val header = lines.firstOrNull()?.trim()
        if (header != FORMAT_VERSION) {
            return DecodedTranscript(emptyList(), skippedLines = lines.size, unreadable = true)
        }
        val out = ArrayList<StoredMessage>(lines.size)
        var skipped = 0
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val parts = splitFields(line)
            if (parts == null) { skipped++; continue }
            val role = parts[0]
            if (role != "user" && role != "assistant" && role != "note") { skipped++; continue }
            out.add(
                StoredMessage(
                    role = role,
                    text = parts[3],
                    complete = parts[1] != "0",
                    atMs = parts[2].toLongOrNull() ?: 0L,
                ),
            )
        }
        return DecodedTranscript(out, skippedLines = skipped)
    }

    /** Exactly four fields, with the TEXT allowed to contain nothing unescaped. */
    private fun splitFields(line: String): List<String>? {
        val a = line.indexOf('\t')
        if (a < 0) return null
        val b = line.indexOf('\t', a + 1)
        if (b < 0) return null
        val c = line.indexOf('\t', b + 1)
        if (c < 0) return null
        return listOf(
            unescape(line.substring(0, a)),
            line.substring(a + 1, b),
            line.substring(b + 1, c),
            unescape(line.substring(c + 1)),
        )
    }

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun unescape(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(n); i += 2 }
                }
            } else {
                sb.append(ch); i++
            }
        }
        return sb.toString()
    }
}

/**
 * The guards that keep a restore from eating a newer conversation.
 *
 * Both are pure so the race is decided by an assertion rather than by hoping the timing
 * on a device works out: a save may not happen before the restore attempt has finished,
 * and a restore may only be applied to an EMPTY transcript (if the user already typed
 * something, the file on disk is stale by definition and applying it would interleave two
 * conversations).
 */
internal object RestorePolicy {

    fun shouldApplyRestore(restoreFinished: Boolean, currentRowCount: Int, storedCount: Int): Boolean =
        !restoreFinished && currentRowCount == 0 && storedCount > 0

    fun mayPersist(restoreFinished: Boolean): Boolean = restoreFinished
}
