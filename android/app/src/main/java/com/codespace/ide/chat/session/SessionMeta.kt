package com.codespace.ide.chat.session

import kotlin.random.Random

/**
 * M0 (MULTI-SESSION, 2026-10-30) — ONE CONVERSATION'S METADATA.
 *
 * The transcript layer (chat/ChatTranscriptStore.kt) already stores one file per
 * (projectId, conversationId), so the conversations themselves need no migration. What was
 * missing is everything a session LIST needs: a title, when it started, when it last changed,
 * whether it is archived, and whether the user has looked at it since.
 *
 * Two identities are deliberately kept apart:
 *  - [id] is the conversation id. It is the transcript file stem and it NEVER changes, not
 *    on rename, not on archive. Renaming changes [title] only.
 *  - [title] is display text. It is derived once (first user message) and then user-owned.
 *
 * PROJECT ASSOCIATION is NOT a field. A session belongs to exactly one project because its
 * index file lives in that project's directory (see SessionIndexStore); carrying a redundant
 * projectId here would allow the two to disagree, and a disagreeing field is how a project-A
 * session ends up listed under project B.
 */
internal data class SessionMeta(
    val id: String,
    val title: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val archived: Boolean = false,
    /**
     * When the user last LOOKED at this session. "Read" is derived as
     * `lastReadAtMs >= updatedAtMs`, so no separate boolean can drift out of sync with the
     * timestamps it is derived from.
     */
    val lastReadAtMs: Long = 0L,
) {
    val isRead: Boolean get() = lastReadAtMs >= updatedAtMs

    companion object {
        /**
         * The pre-multi-session conversation id. It is adopted as the first session of a
         * project WITHOUT renaming anything: the id is the file stem, so "main" keeps
         * pointing at the transcript that already exists (main.txt).
         */
        const val LEGACY_ID: String = "main"

        /** Shown for a session that has no user text yet (and for an adopted empty "main"). */
        const val UNTITLED: String = "New Session"

        /** Longest id we accept; matches TranscriptFiles.safe()'s own 80-char cut. */
        const val MAX_ID: Int = 80

        /** Titles are one line of UI, not a document. */
        const val MAX_TITLE: Int = 120

        /** "About 40 characters" — the list row shows one line and ellipsizes. */
        const val DERIVED_TITLE_CHARS: Int = 40
    }
}

/** Why a metadata record cannot be trusted. Returned, never thrown, so callers can report. */
internal enum class SessionProblem {
    BLANK_ID,
    /**
     * An id that TranscriptFiles.safe() would REWRITE. Two ids that sanitise to the same
     * segment would share one transcript file, which is silent data mixing, so such ids are
     * rejected outright instead of being normalised.
     */
    ILLEGAL_ID_CHARS,
    ID_TOO_LONG,
    BLANK_TITLE,
    TITLE_TOO_LONG,
    NEGATIVE_TIME,
    UPDATED_BEFORE_CREATED,
}

/**
 * Validation as data. The store refuses to write an index containing a problem, so a bad
 * record can never reach the disk and then come back as a mystery on the next launch.
 */
internal object SessionMetaRules {

    /** True when the id is already a single safe path segment (no rewriting needed). */
    fun isSafeId(id: String): Boolean =
        id.isNotBlank() && id.length <= SessionMeta.MAX_ID &&
            id != "." && id != ".." &&
            id.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }

    fun problems(meta: SessionMeta): List<SessionProblem> {
        val out = ArrayList<SessionProblem>(2)
        if (meta.id.isBlank()) out.add(SessionProblem.BLANK_ID)
        else {
            if (!isSafeId(meta.id)) {
                if (meta.id.length > SessionMeta.MAX_ID) out.add(SessionProblem.ID_TOO_LONG)
                else out.add(SessionProblem.ILLEGAL_ID_CHARS)
            }
        }
        if (meta.title.isBlank()) out.add(SessionProblem.BLANK_TITLE)
        if (meta.title.length > SessionMeta.MAX_TITLE) out.add(SessionProblem.TITLE_TOO_LONG)
        if (meta.createdAtMs < 0L || meta.updatedAtMs < 0L || meta.lastReadAtMs < 0L) {
            out.add(SessionProblem.NEGATIVE_TIME)
        }
        if (meta.updatedAtMs < meta.createdAtMs) out.add(SessionProblem.UPDATED_BEFORE_CREATED)
        return out
    }

    fun isValid(meta: SessionMeta): Boolean = problems(meta).isEmpty()
}

/**
 * Id generation. Stable and opaque: a random suffix from a caller-supplied Random, so tests
 * are deterministic and nothing about the id depends on a title, a timestamp or a counter
 * that could collide after a restart.
 *
 * The "s_" prefix keeps a generated id visibly distinct from the legacy "main", which makes
 * a log line readable without a lookup.
 */
internal object SessionIds {

    fun newId(random: Random = Random.Default): String {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
        val sb = StringBuilder(18)
        sb.append("s_")
        repeat(16) { sb.append(alphabet[random.nextInt(alphabet.length)]) }
        return sb.toString()
    }
}

/**
 * Title derivation, kept pure so the "what does the row say" decision is testable without a
 * filesystem.
 *
 * Rules, in order: first non-blank line, whitespace collapsed, clipped to
 * [SessionMeta.DERIVED_TITLE_CHARS] with an ellipsis. No text at all means UNTITLED. A
 * derived title is only ever a STARTING value: once the user renames, nothing re-derives it.
 */
internal object SessionTitles {

    fun derive(firstUserText: String?): String {
        val raw = firstUserText ?: return SessionMeta.UNTITLED
        val oneLine = raw.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: return SessionMeta.UNTITLED
        val collapsed = collapseSpaces(oneLine)
        if (collapsed.isBlank()) return SessionMeta.UNTITLED
        return if (collapsed.length <= SessionMeta.DERIVED_TITLE_CHARS) collapsed
        else collapsed.take(SessionMeta.DERIVED_TITLE_CHARS) + "\u2026"
    }

    /** A renamed title is user text: collapse whitespace, enforce the length cap, or reject. */
    fun normalise(raw: String): String? {
        val collapsed = collapseSpaces(raw.trim())
        if (collapsed.isBlank()) return null
        return collapsed.take(SessionMeta.MAX_TITLE)
    }

    private fun collapseSpaces(s: String): String {
        val sb = StringBuilder(s.length)
        var lastWasSpace = false
        for (ch in s) {
            val isSpace = ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r'
            if (isSpace) {
                if (!lastWasSpace && sb.isNotEmpty()) sb.append(' ')
                lastWasSpace = true
            } else {
                sb.append(ch)
                lastWasSpace = false
            }
        }
        return sb.toString().trimEnd()
    }
}
