package com.codespace.ide.chat.session

/**
 * M0 — THE SESSION INDEX FORMAT: encode, strict decode, and the append/remove operations that
 * keep the in-memory index consistent.
 *
 * WHY NOT JSON: this project's JVM unit tests run with `unitTests.isReturnDefaultValues = true`
 * and org.json is a stub there, so a JSON codec would only be verifiable on a device. The
 * transcript layer hit the same wall and chose a line-oriented text format for exactly this
 * reason (see chat/ChatTranscript.kt). The same format family is used here: a version header,
 * one "active" line, then one line per session, tab separated, with user text escaped.
 *
 * WHY STRICT (unlike the transcript reader, which skips bad lines): a transcript is a growing
 * log where losing one line still leaves a readable conversation. The index is a small,
 * machine-written table whose only job is to say WHICH sessions exist. A half-read table is
 * not "mostly right": rewriting it would silently drop the sessions whose lines were damaged,
 * and dropping metadata is how a session becomes invisible and then, eventually, deleted.
 * So any damage makes the whole index Unreadable, the store refuses to write over it, and the
 * caller reports it. Recovery is explicit (backup, then a rebuild from the transcripts on
 * disk) and is never silent.
 */
internal sealed interface SessionIndexRead {

    /** The index parsed completely. [note] is only set when something worth saying happened. */
    data class Ok(val index: SessionIndex, val note: String? = null) : SessionIndexRead

    /**
     * The bytes on disk are not a session index this build understands. The caller must NOT
     * treat this as an empty index and must NOT write over it.
     */
    data class Unreadable(val reason: String) : SessionIndexRead
}

/**
 * The sessions of ONE project, plus which one was active.
 *
 * Ordered oldest-first as written; presentation order is [SessionListLogic]'s job, so this
 * type never encodes a UI decision.
 */
internal data class SessionIndex(
    val sessions: List<SessionMeta> = emptyList(),
    val activeId: String? = null,
) {

    fun find(id: String): SessionMeta? = sessions.firstOrNull { it.id == id }

    fun contains(id: String): Boolean = find(id) != null

    /** Replaces by id, or appends. Appending keeps the oldest-first order the codec writes. */
    fun upsert(meta: SessionMeta): SessionIndex {
        val i = sessions.indexOfFirst { it.id == meta.id }
        return if (i < 0) copy(sessions = sessions + meta)
        else copy(sessions = sessions.toMutableList().also { it[i] = meta })
    }

    fun without(id: String): SessionIndex =
        copy(sessions = sessions.filterNot { it.id == id })

    /**
     * The active id, resolved honestly: a stored id that no longer names a real session (the
     * session was deleted on another path, or the file was edited) falls back to the newest
     * session rather than dangling.
     */
    val resolvedActiveId: String?
        get() {
            val stored = activeId
            if (stored != null && contains(stored)) return stored
            return sessions.lastOrNull()?.id
        }

    fun withActive(id: String?): SessionIndex = copy(activeId = id)
}

internal object SessionIndexCodec {

    /**
     * v2 adds an eighth column: the session's mode (item 18). A v1 file MUST still read: bumping
     * the header without accepting the old one would make every existing conversation
     * Unreadable, which is the one failure this format is built to prevent.
     */
    const val FORMAT_VERSION = "vsessions 2"
    const val FORMAT_VERSION_V1 = "vsessions 1"

    private const val TAG_SESSION = "s"

    /**
     * Writes the index. Throws IllegalArgumentException on an invalid record: by the time an
     * index reaches the encoder every record came from a validated operation, so an invalid
     * one is a programming error that must fail loudly in tests rather than reach a phone.
     */
    fun encode(index: SessionIndex): String {
        for (meta in index.sessions) {
            val problems = SessionMetaRules.problems(meta)
            require(problems.isEmpty()) {
                "refusing to encode an invalid session: " + meta.id + " " + problems
            }
        }
        val ids = index.sessions.map { it.id }
        require(ids.size == ids.toSet().size) { "duplicate session id in index" }

        val sb = StringBuilder(64 + index.sessions.size * 80)
        sb.append(FORMAT_VERSION).append('\n')
        sb.append("active\t").append(escape(index.activeId ?: "")).append('\n')
        for (m in index.sessions) {
            sb.append(TAG_SESSION).append('\t')
                .append(m.id).append('\t')
                .append(escape(m.title)).append('\t')
                .append(m.createdAtMs).append('\t')
                .append(m.updatedAtMs).append('\t')
                .append(if (m.archived) '1' else '0').append('\t')
                .append(m.lastReadAtMs).append('\t')
                .append(m.mode.token).append('\n')
        }
        return sb.toString()
    }

    /**
     * Strict reader. See the file header for why damaged bytes are Unreadable rather than
     * "empty" or "partially loaded".
     */
    fun decode(raw: String): SessionIndexRead {
        // A zero-length or whitespace-only file is a TORN WRITE, not an empty project: a real
        // empty index still carries the header, the active line and nothing else. Treating a
        // truncated file as "no sessions" is exactly the silent-empty failure this guards.
        if (raw.isBlank()) return SessionIndexRead.Unreadable("the session index file is empty")

        val lines = raw.split('\n')
        val header = lines.firstOrNull()?.trim()
        if (header != FORMAT_VERSION && header != FORMAT_VERSION_V1) {
            return SessionIndexRead.Unreadable("unrecognised session index format")
        }
        // The record width is fixed BY THE HEADER, not guessed per line. Accepting "7 or 8
        // columns" for a v2 file would quietly accept a torn v2 record with its mode column
        // missing, which is exactly the corruption this reader exists to refuse.
        val fieldsPerRecord = if (header == FORMAT_VERSION) 8 else 7

        var activeId: String? = null
        var sawActive = false
        val out = ArrayList<SessionMeta>(lines.size)
        val seen = HashSet<String>(lines.size)

        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val parts = line.split('\t')
            when {
                parts[0] == "active" -> {
                    if (sawActive) return SessionIndexRead.Unreadable("duplicate active line")
                    if (parts.size != 2) return SessionIndexRead.Unreadable("malformed active line")
                    sawActive = true
                    val id = unescape(parts[1])
                    activeId = if (id.isBlank()) null else id
                }
                parts[0] == TAG_SESSION -> {
                    val meta = parseSession(parts, fieldsPerRecord)
                        ?: return SessionIndexRead.Unreadable("malformed session record on line " + (i + 1))
                    val problems = SessionMetaRules.problems(meta)
                    if (problems.isNotEmpty()) {
                        return SessionIndexRead.Unreadable(
                            "invalid session record on line " + (i + 1) + ": " + problems.joinToString(","),
                        )
                    }
                    if (!seen.add(meta.id)) {
                        return SessionIndexRead.Unreadable("duplicate session id: " + meta.id)
                    }
                    out.add(meta)
                }
                else -> return SessionIndexRead.Unreadable("unknown record type on line " + (i + 1))
            }
        }
        if (!sawActive) return SessionIndexRead.Unreadable("missing active line")
        return SessionIndexRead.Ok(SessionIndex(out, activeId))
    }

    /**
     * Seven tab-separated fields (v1) or eight (v2, mode last); a title may contain anything once
     * escaped. A v1 record simply has no mode and takes the ASK default.
     */
    private fun parseSession(parts: List<String>, fieldsPerRecord: Int): SessionMeta? {
        if (parts.size != fieldsPerRecord) return null
        val id = parts[1]
        val title = unescape(parts[2])
        val created = parts[3].toLongOrNull() ?: return null
        val updated = parts[4].toLongOrNull() ?: return null
        val archived = when (parts[5]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val lastRead = parts[6].toLongOrNull() ?: return null
        // An unknown mode token is not a corrupt record: it is a record written by a version that
        // knew a mode this build does not, and dropping the whole index over it would lose every
        // session. Fall back to ASK and keep the conversation.
        val mode = if (fieldsPerRecord == 8) SessionMode.fromToken(parts[7]) ?: SessionMode.ASK else SessionMode.ASK
        return SessionMeta(
            id = id,
            title = title,
            createdAtMs = created,
            updatedAtMs = updated,
            archived = archived,
            lastReadAtMs = lastRead,
            mode = mode,
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
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }
}
