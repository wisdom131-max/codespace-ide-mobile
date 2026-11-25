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
 * 1. NOT EVERY ROW IS HISTORY. Thinking, Failed, NoCredits, Offline and OverBudget
 *    describe a MOMENT (this request is retrying, the network is down). Restoring them tomorrow would show a live-looking "Offline" card
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
/** STAGE 8 (Checkpoint 0.5): the four reply states a record may carry. */
internal const val STATE_STREAMING = "STREAMING"
internal const val STATE_DONE = "DONE"
internal const val STATE_INTERRUPTED = "INTERRUPTED"
internal const val STATE_FAILED = "FAILED"

/**
 * STAGE 8: what a stored reply really was.
 *
 * [state] outranks [complete], because "finished" and "ended well" are different questions. Every
 * disagreement resolves to the SAFER answer: a reply is never upgraded to complete on the strength
 * of a flag that a crash, a cancellation or a cut-off could also have written.
 */
internal fun restoredState(state: String, complete: Boolean): String = when (state) {
    STATE_DONE -> if (complete) STATE_DONE else STATE_INTERRUPTED
    STATE_FAILED, STATE_INTERRUPTED -> state
    // STAGE 8 fix: a record that says STREAMING was written while the reply was still arriving,
    // so the process ended mid-answer. Restoring it as STREAMING would put a live-looking reply
    // back on screen with nothing driving it; the approved rule is INTERRUPTED.
    STATE_STREAMING -> STATE_INTERRUPTED
    "" -> if (complete) STATE_DONE else STATE_INTERRUPTED
    // A value this build does not know: treat as interrupted, never as success.
    else -> STATE_INTERRUPTED
}

data class StoredMessage(
    /** "user" | "assistant" | "note". Unknown roles are skipped on read, never crashed on. */
    val role: String,
    val text: String,
    /** false only for an assistant reply that was still streaming when it was saved. */
    val complete: Boolean = true,
    /** Wall-clock at save time; kept for debugging and future ordering, never shown. */
    val atMs: Long = 0L,
    /**
     * STAGE 3: stable identity for this message inside its conversation.
     *
     * Assigned once when the message is first persisted and preserved afterwards, so an edit to
     * the text does not move it. Empty means "no id yet" (a v1/v2 line, or a row built in memory
     * that has not been saved); the store mints one on the first write.
     */
    val id: String = "",
    /**
     * STAGE 8 (Checkpoint 0.5): how this reply ended, as its own column.
     *
     * One of DONE, INTERRUPTED, FAILED, STREAMING, or empty on a record written before the column
     * existed. It outranks [complete], because a flag that only says "finished" cannot tell a
     * finished answer from one that was cut off, cancelled or failed.
     */
    val state: String = "",
    /** STAGE 8: which logical reply this message belongs to. Empty on older records. */
    val replyId: String = "",
    /** STAGE 8: which user ask this message answers. Empty on older records, never invented. */
    val turnId: String = "",
    /**
     * STAGE 9: which kind of notice this was, by name, so a reopened conversation keeps its
     * categories. Empty on older records, which read back as APP_NOTICE - the same meaning
     * those records had when they were written.
     */
    val noteKind: String = "",
    /**
     * STAGE 3: when persistence FIRST observed this message - an app event, not an edit time.
     * It is never later than the true send time and is never rewritten by a later save.
     * 0 means unknown (a legacy line): callers must say "not recorded" rather than guess.
     */
    val sentAtMs: Long = 0L,
    /**
     * STAGE 3: true when [id] was reconstructed for a legacy line. Such an id identifies the
     * message for de-duplication, but it was NOT in an original transcript record, so nothing may
     * present it as transcript-verified history.
     */
    val syntheticId: Boolean = false,
    /**
     * Area 6: the authoritative finish verdict for this assistant turn
     * ([com.codespace.ide.engine.FinishReason] name), or null for a conversation saved before it
     * was recorded. Null restores as UNKNOWN: no Continue offered, nothing called complete.
     */
    val finish: String? = null,
)

/** Result of decoding a stored transcript: messages plus how much was thrown away. */
internal data class DecodedTranscript(
    val messages: List<StoredMessage>,
    /** Lines that could not be parsed (torn write, hand edit, future format). */
    val skippedLines: Int = 0,
    /** True when the file was readable but not in a format this build understands. */
    val unreadable: Boolean = false,
    /** STAGE 3: the format that was actually read ("v1", "v2", "v3"). */
    val version: String = "",
    /** STAGE 3: header metadata. Null for v1/v2, which carried none. */
    val meta: TranscriptMeta? = null,
    /** STAGE 3: persisted tool steps. Empty for v1/v2 and for any file without them. */
    val steps: List<StoredStep> = emptyList(),
    /** STAGE 3: records dropped because their id was already seen (a duplicated write). */
    val duplicateRecords: Int = 0,
)

/**
 * STAGE 3 - conversation-level metadata.
 *
 * [startedAtMs] is 0 when unknown, and unknown is never filled in from a message time: "we do not
 * know when this conversation began" is a fact the UI can show, a guess is not.
 */
internal data class TranscriptMeta(
    val conversationId: String? = null,
    val startedAtMs: Long = 0L,
)

/**
 * STAGE 3 - one persisted tool activity step.
 *
 * The record type and its codec exist now so a transcript can carry steps without a later format
 * change. Stage 10 wires the Thinking Tree to produce them; nothing writes them yet.
 */
internal data class StoredStep(
    val callId: String,
    val turnId: String? = null,
    val atMs: Long = 0L,
    val ok: Boolean = true,
    val label: String = "",
    val detail: String = "",
)

internal object ChatTranscript {

    const val FORMAT_VERSION = "v2"

    /** STAGE 3: the format this build WRITES. v1 and v2 are read, never written. */
    const val CURRENT_FORMAT_VERSION = "v3"

    /** STAGE 3: every reconstructed legacy id starts with this, so it is never mistaken for one. */
    const val SYNTHETIC_PREFIX = "legacy-"

    /** v1 had no finish field. Read, never written. */
    internal const val LEGACY_FORMAT_VERSION = "v1"

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
        /** The last assistant turn's verdict, written onto that message only. */
        lastFinish: String? = null,
    ): List<StoredMessage> {
        val mapped = ArrayList<StoredMessage>(rows.size)
        for (row in rows) {
            when (row) {
                is ChatRowModel.User -> mapped.add(StoredMessage("user", row.text, true, nowMs))
                is ChatRowModel.AssistantStream -> mapped.add(
                    StoredMessage(
                        role = "assistant", text = row.text, complete = true, atMs = nowMs,
                        replyId = row.replyId,
                        // STAGE 8: the row knows only that it is streaming. A finished row's
                        // verdict is filled in below from the turn's authoritative finish reason.
                        state = if (row.cursorOn) STATE_STREAMING else STATE_DONE,
                    )
                )
                is ChatRowModel.InfoNote -> mapped.add(
                    StoredMessage("note", row.text, true, nowMs, noteKind = row.kind.name),
                )
                // Sources are rebuilt from the answer's own "Sources:" block on restore; writing
                // them as messages as well would show every source twice after a reopen.
                is ChatRowModel.Sources -> Unit
                is ChatRowModel.Attachments -> {
                    // Names only — the bytes are never persisted (WP8 redaction rule).
                    val names = row.chips.joinToString(", ") { it.name }
                    if (names.isNotBlank()) mapped.add(StoredMessage("note", "Attached: " + names, true, nowMs))
                }
                // Transient surfaces: an action or a moment, not transcript. See header.
                is ChatRowModel.ToolActivity, is ChatRowModel.Thinking, is ChatRowModel.Failed,
                ChatRowModel.NoCredits, ChatRowModel.Offline, is ChatRowModel.OverBudget -> Unit
            }
        }
        val lastAssistant = mapped.indexOfLast { it.role == "assistant" }
        if (lastAssistant >= 0) {
            var row = mapped[lastAssistant]
            if (lastFinish != null) row = row.copy(finish = lastFinish)
            if (streaming) row = row.copy(complete = false)
            // STAGE 8: state, complete and finish are derived HERE, in one place, so a saved line
            // can never claim DONE and unfinished at the same time.
            // lastFinish is the finish reason's NAME (a String), not the enum, so completeness is
            // read from the row's own complete flag here; the finish name is still stored beside it
            // and restoredState() applies the precedence when the line is read back.
            row = if (row.complete) row.copy(state = STATE_DONE)
            else row.copy(state = STATE_STREAMING)
            mapped[lastAssistant] = row
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
                        // STAGE 8: the row keeps its identity, so a restored continuation still
                        // extends the same answer instead of appending a second one.
                        rows.add(
                            ChatRowModel.AssistantStream(
                                m.text, cursorOn = false, replyId = m.replyId,
                                // STAGE 8: the stored verdict decides, not the fact that text
                                // exists. A reply saved mid-stream comes back marked as cut off.
                                interrupted = restoredState(m.state, m.complete) != STATE_DONE,
                            )
                        )
                        // Sources survive a reopen by being read back out of the answer text, so a
                        // conversation written before this row existed restores them too.
                        val src = com.codespace.ide.agent.research.ResearchSources.parse(m.text)
                        if (src.isNotEmpty()) rows.add(ChatRowModel.Sources(src))
                    }
                }
                "note" -> if (m.text.isNotBlank()) {
                    // STAGE 9: an unknown or absent category is an APP_NOTICE, never a work
                    // step, so a legacy line cannot smuggle anything into the tree.
                    val kind = ChatRowModel.NoticeKind.entries.firstOrNull { it.name == m.noteKind }
                        ?: ChatRowModel.NoticeKind.APP_NOTICE
                    rows.add(ChatRowModel.InfoNote(m.text, kind))
                }
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
    fun encode(
        messages: List<StoredMessage>,
        meta: TranscriptMeta? = null,
        steps: List<StoredStep> = emptyList(),
    ): String {
        val sb = StringBuilder(64 + messages.size * 56)
        sb.append(CURRENT_FORMAT_VERSION).append('\n')
        // Header records start with '#'. They are optional, so a v3 file written without metadata
        // is still valid and still reads back.
        if (meta?.conversationId != null) {
            sb.append("#c\t").append(escape(meta.conversationId)).append('\n')
        }
        if (meta != null && meta.startedAtMs > 0L) {
            sb.append("#s\t").append(meta.startedAtMs).append('\n')
        }
        for (m in messages) {
            sb.append("m\t")
                .append(escape(m.id)).append('\t')
                .append(m.role).append('\t')
                .append(if (m.complete) '1' else '0').append('\t')
                .append(m.sentAtMs).append('\t')
                .append(m.atMs).append('\t')
                .append(m.finish ?: "-").append('\t')
                .append(escape(m.text)).append('\t')
                // STAGE 8: trailing columns. A reader that predates them stops at the text, which
                // is why they are appended and not inserted: no version bump, no migration.
                .append(m.state.ifBlank { "-" }).append('\t')
                .append(escape(m.replyId)).append('\t')
                .append(escape(m.turnId)).append('\t')
                // STAGE 9: appended, never inserted, so a reader that predates it still reads
                // the line and old files need no migration.
                .append(escape(m.noteKind)).append('\n')
        }
        for (st in steps) {
            sb.append("s\t")
                .append(escape(st.callId)).append('\t')
                .append(escape(st.turnId ?: "-")).append('\t')
                .append(st.atMs).append('\t')
                .append(if (st.ok) '1' else '0').append('\t')
                .append(escape(st.label)).append('\t')
                .append(escape(st.detail)).append('\n')
        }
        return sb.toString()
    }

    /**
     * Tolerant reader: unknown header / blank file -> empty; bad lines -> skipped.
     *
     * STAGE 3: three formats are read. v1 and v2 keep their original paths untouched, so an old
     * conversation restores exactly as it did before. A v3 file is read record by record: an
     * unknown record type, a malformed record or a duplicated id costs THAT record and nothing
     * else, which is the whole point of a line-oriented format.
     */
    fun decode(raw: String): DecodedTranscript {
        if (raw.isBlank()) return DecodedTranscript(emptyList())
        val lines = raw.split('\n')
        val header = lines.firstOrNull()?.trim()
        return when (header) {
            CURRENT_FORMAT_VERSION -> decodeV3(lines)
            FORMAT_VERSION, LEGACY_FORMAT_VERSION -> decodeLegacy(lines, header == LEGACY_FORMAT_VERSION)
            else -> DecodedTranscript(emptyList(), skippedLines = lines.size, unreadable = true)
        }
    }

    /** v3: header records, then message and step records, all prefixed by their type. */
    private fun decodeV3(lines: List<String>): DecodedTranscript {
        var meta = TranscriptMeta()
        val out = ArrayList<StoredMessage>(lines.size)
        val steps = ArrayList<StoredStep>()
        val seen = HashSet<String>()
        var skipped = 0
        var duplicates = 0
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val fields = line.split('\t')
            when (fields[0]) {
                "#c" -> if (fields.size >= 2) meta = meta.copy(conversationId = unescape(fields[1]))
                "#s" -> if (fields.size >= 2) {
                    val ms = fields[1].toLongOrNull()
                    if (ms != null) meta = meta.copy(startedAtMs = ms) else skipped++
                }
                "m" -> {
                    // id, role, complete, sentAt, savedAt, finish, text
                    if (fields.size < 8) { skipped++; continue }
                    val id = unescape(fields[1])
                    val role = fields[2]
                    if (role != "user" && role != "assistant" && role != "note") { skipped++; continue }
                    if (id.isNotBlank() && !seen.add(id)) { duplicates++; continue }
                    out.add(
                        StoredMessage(
                            role = role,
                            text = unescape(fields[7]),
                            complete = fields[3] != "0",
                            atMs = fields[5].toLongOrNull() ?: 0L,
                            id = id,
                            sentAtMs = fields[4].toLongOrNull() ?: 0L,
                            finish = fields[6].takeIf { it != "-" },
                            // STAGE 8: absent on older records; the reader never guesses.
                            state = if (fields.size >= 11) fields[8].takeIf { it != "-" } ?: "" else "",
                            replyId = if (fields.size >= 11) unescape(fields[9]) else "",
                            turnId = if (fields.size >= 11) unescape(fields[10]) else "",
                            noteKind = if (fields.size >= 12) unescape(fields[11]) else "",
                            // The prefix IS the flag: a legacy id is recognisable from the id
                            // itself, so a round trip through disk cannot launder it into a
                            // transcript-verified identity.
                            syntheticId = id.startsWith(SYNTHETIC_PREFIX),
                        ),
                    )
                }
                "s" -> {
                    // callId, turnId, atMs, ok, label, detail
                    if (fields.size < 7) { skipped++; continue }
                    val callId = unescape(fields[1])
                    if (callId.isBlank() || !seen.add("step:" + callId)) { duplicates++; continue }
                    steps.add(
                        StoredStep(
                            callId = callId,
                            turnId = unescape(fields[2]).takeIf { it != "-" },
                            atMs = fields[3].toLongOrNull() ?: 0L,
                            ok = fields[4] != "0",
                            label = unescape(fields[5]),
                            detail = unescape(fields[6]),
                        ),
                    )
                }
                else -> skipped++
            }
        }
        val ordered = assignSyntheticIds(out, meta.conversationId)
        return DecodedTranscript(
            messages = ordered,
            skippedLines = skipped,
            version = CURRENT_FORMAT_VERSION,
            meta = meta,
            steps = steps,
            duplicateRecords = duplicates,
        )
    }

    /** v1/v2: the original four/five-field readers, unchanged in behaviour. */
    private fun decodeLegacy(lines: List<String>, legacy: Boolean): DecodedTranscript {
        val out = ArrayList<StoredMessage>(lines.size)
        var skipped = 0
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val parts = if (legacy) legacyFields(line) else splitFields(line)
            if (parts == null) { skipped++; continue }
            val role = parts[0]
            if (role != "user" && role != "assistant" && role != "note") { skipped++; continue }
            out.add(
                StoredMessage(
                    role = role,
                    text = parts[3],
                    complete = parts[1] != "0",
                    atMs = parts[2].toLongOrNull() ?: 0L,
                    finish = if (legacy) null else parts[4].takeIf { it != "-" },
                ),
            )
        }
        // A legacy line had no id and no send time. Both are marked, not invented.
        return DecodedTranscript(
            messages = assignSyntheticIds(out, null),
            skippedLines = skipped,
            version = if (legacy) LEGACY_FORMAT_VERSION else FORMAT_VERSION,
        )
    }

    /**
     * STAGE 3: give an id-less message a DETERMINISTIC id and say so.
     *
     * Deterministic, not random: the same legacy file must produce the same ids on every load, or
     * de-duplication and any fact linked to a message would drift. [StoredMessage.syntheticId]
     * carries the honesty flag, and [StoredMessage.sentAtMs] stays 0 because the original record
     * never held a send time.
     */
    fun assignSyntheticIds(messages: List<StoredMessage>, conversationId: String?): List<StoredMessage> {
        if (messages.none { it.id.isBlank() }) return messages
        val salt = conversationId ?: ""
        return messages.mapIndexed { idx, m ->
            if (m.id.isNotBlank()) m
            else m.copy(id = syntheticId(salt, idx, m.role), syntheticId = true)
        }
    }

    /** Deterministic legacy id: stable for the same conversation, index and role. */
    fun syntheticId(salt: String, index: Int, role: String): String {
        val h = (salt + "|" + index + "|" + role).hashCode()
        return SYNTHETIC_PREFIX + Integer.toHexString(h) + "-" + index
    }

    /**
     * STAGE 3: carry identity forward across a save.
     *
     * A save rewrites the whole file from the live row list, which has no ids of its own. The
     * message at position i therefore inherits the id and the first-observed time of the message
     * that was at position i in the previous file WHEN the role matches; anything else is a new
     * message and gets a fresh id and a first-observed time of now. Editing text does not move a
     * message, appending does not renumber the ones before it.
     */
    fun carryIdentity(
        messages: List<StoredMessage>,
        previous: List<StoredMessage>,
        conversationId: String,
        nowMs: Long,
    ): List<StoredMessage> {
        if (previous.isEmpty()) {
            return messages.mapIndexed { idx, m ->
                m.copy(id = m.id.ifBlank { newId(conversationId, idx) }, sentAtMs = m.sentAtMs.takeIf { it > 0L } ?: nowMs, syntheticId = false)
            }
        }
        val matched = arrayOfNulls<StoredMessage>(messages.size)
        val used = BooleanArray(previous.size)

        // PASS 1 - identity at the same position, unchanged content. The common save case.
        for (i in messages.indices) {
            val p = previous.getOrNull(i) ?: continue
            if (!used[i] && p.role == messages[i].role && p.text == messages[i].text) {
                matched[i] = p
                used[i] = true
            }
        }
        // PASS 2 - unchanged content found elsewhere. This is what makes DELETION, PRUNING and
        // REORDERING safe: a message keeps its id because its content moved, not because its index
        // stayed. Ordering of the scan keeps duplicates matched in their original order.
        for (i in messages.indices) {
            if (matched[i] != null) continue
            for (j in previous.indices) {
                if (used[j]) continue
                if (previous[j].role == messages[i].role && previous[j].text == messages[i].text) {
                    matched[i] = previous[j]
                    used[j] = true
                    break
                }
            }
        }
        // PASS 3 - in-place EDIT: same role, same position, content changed. Only allowed when the
        // role's population did not change, because otherwise the unmatched previous message may be
        // one that was DELETED, and handing its id to an edited neighbour would silently point a
        // fact at the wrong message. Never invent an association to keep an id alive.
        for (i in messages.indices) {
            if (matched[i] != null) continue
            val role = messages[i].role
            if (messages.count { it.role == role } != previous.count { it.role == role }) continue
            val p = previous.getOrNull(i) ?: continue
            if (!used[i] && p.role == role) {
                matched[i] = p
                used[i] = true
            }
        }
        return messages.mapIndexed { idx, m ->
            val prev = matched[idx]
            val keepId = m.id.ifBlank { prev?.id?.takeIf { it.isNotBlank() } ?: "" }
            val sent = when {
                m.sentAtMs > 0L -> m.sentAtMs
                prev != null -> prev.sentAtMs
                else -> nowMs
            }
            val inheritedSynthetic = prev != null && prev.syntheticId && keepId == prev.id
            m.copy(
                id = keepId.ifBlank { newId(conversationId, idx) },
                sentAtMs = sent,
                syntheticId = inheritedSynthetic,
            )
        }
    }

    private fun newId(conversationId: String, index: Int): String =
        "m" + Integer.toHexString((conversationId + "|" + index + "|" + System.nanoTime()).hashCode()) +
            "-" + index

    /**
     * v2: five fields, finish before the text so the escaped text stays last. v1 lines still parse
     * through [legacyFields]: an old conversation keeps its messages and simply has no verdict.
     */
    private fun splitFields(line: String): List<String>? {
        val a = line.indexOf('\t')
        if (a < 0) return null
        val b = line.indexOf('\t', a + 1)
        if (b < 0) return null
        val c = line.indexOf('\t', b + 1)
        if (c < 0) return null
        // A four-field line under a v2 header is a v1 line (torn write, hand edit, a file written
        // by an older build into a new session). Read it as v1 rather than skipping the message:
        // the TEXT is the user's, the verdict is the only thing missing.
        val d = line.indexOf('\t', c + 1)
        if (d < 0) return legacyFields(line)
        return listOf(
            unescape(line.substring(0, a)),
            line.substring(a + 1, b),
            line.substring(b + 1, c),
            unescape(line.substring(d + 1)),
            line.substring(c + 1, d),
        )
    }

    /** Exactly four fields, with the TEXT allowed to contain nothing unescaped. */
    private fun legacyFields(line: String): List<String>? {
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
            "-",
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
