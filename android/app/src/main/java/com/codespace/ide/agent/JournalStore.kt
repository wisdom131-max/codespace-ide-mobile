package com.codespace.ide.agent

import java.io.File
import java.util.Base64

/**
 * Item 19 - PERSISTENT UNDO HISTORY, on disk.
 *
 * What the research established first (see docs/vscode-undo-redo-research-2026-10-31.md):
 * VS Code's undo stack is a list of closed-over undo/redo CALLBACKS, which cannot be written
 * to disk at all - so "persist the stack" is not a matter of copying their design. What CAN be
 * persisted is the thing VN Code already stores per change set: the path plus the exact before
 * and after images. On restart the same set can be re-verified against the disk and then
 * reversed, which is exactly as safe as it was in memory, because [AgentWriteJournal.undoEntry]
 * refuses to touch a file that no longer matches `after`.
 *
 * Rules this file exists to keep:
 *  - A session's history is written to a file named after that session, inside its project's
 *    directory, so one session can never load another's sets.
 *  - Damage is not an error the app can act on: a malformed file loads as EMPTY history rather
 *    than as a half-understood stack, because a partially parsed undo stack is a way to revert
 *    the wrong file.
 *  - An entry too large to hold safely is NOT silently dropped from a set (that would make undo
 *    restore only part of an operation). The whole set is left memory-only and says so.
 */
internal object JournalStore {

    const val VERSION = "vjournal 1"

    /** Per-image cap. Two images per entry, and MAX_SETS sets: a few MB in the worst case. */
    const val MAX_IMAGE_CHARS = 262_144

    /** Why a set could not be written; shown to the user instead of pretending it saved. */
    const val TOO_LARGE = "not saved: a file in this change is too large to keep for restart"

    fun file(root: File, projectId: String, sessionId: String): File {
        val safeProject = safeSegment(projectId)
        val safeSession = safeSegment(sessionId)
        return File(File(root, safeProject), safeSession + ".journal")
    }

    fun safeSegment(value: String): String {
        val sb = StringBuilder(value.length)
        for (ch in value) {
            val ok = (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '_' || ch == '-'
            sb.append(if (ok) ch else '_')
        }
        val out = sb.toString().trim('_')
        return if (out.isEmpty()) "x" else out.take(64)
    }

    private fun enc(text: String?): String =
        if (text == null) "-" else "b" + Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun dec(token: String): String? {
        if (token == "-") return null
        if (!token.startsWith("b")) throw IllegalArgumentException("not a field")
        return String(Base64.getDecoder().decode(token.drop(1)), Charsets.UTF_8)
    }

    /**
     * Encodes the sets, or returns null when this history must stay memory-only. Nothing is
     * approximated: a set that cannot be represented exactly is refused whole.
     */
    fun encode(sets: List<AgentWriteJournal.ChangeSet>): String? {
        for (set in sets) {
            for (entry in set.entries) {
                val before = entry.before?.length ?: 0
                if (before > MAX_IMAGE_CHARS || entry.after.length > MAX_IMAGE_CHARS) return null
            }
        }
        val sb = StringBuilder(256)
        sb.append(VERSION).append('\n')
        for (set in sets) {
            sb.append("set\t").append(set.id).append('\t')
                .append(if (set.settled) '1' else '0').append('\t')
                .append(enc(set.outcome)).append('\t')
                .append(enc(set.note)).append('\t')
                .append(enc(set.sessionId)).append('\n')
            for (entry in set.entries) {
                sb.append("e\t").append(entry.kind.name).append('\t')
                    .append(enc(entry.path)).append('\t')
                    .append(enc(entry.before)).append('\t')
                    .append(enc(entry.after)).append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * Tolerant reader: anything unexpected yields an EMPTY history. A half-read stack is not a
     * smaller stack, it is a different one, and this is the last place that must be clever.
     */
    fun decode(raw: String): List<AgentWriteJournal.ChangeSet> = try {
        val lines = raw.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.firstOrNull()?.trim() != VERSION) emptyList()
        else {
            val out = ArrayList<AgentWriteJournal.ChangeSet>()
            var id = 0L
            var settled = false
            var outcome: String? = null
            var note: String? = null
            var sessionId = ""
            var entries = ArrayList<AgentWriteJournal.Entry>()
            fun flush() {
                if (id != 0L) {
                    out.add(
                        AgentWriteJournal.ChangeSet(
                            id = id,
                            entries = entries,
                            settled = settled,
                            outcome = outcome,
                            note = note,
                            sessionId = sessionId,
                        ),
                    )
                }
            }
            for (line in lines.drop(1)) {
                val p = line.split('\t')
                when (p[0]) {
                    "set" -> {
                        flush()
                        id = p[1].toLong()
                        settled = p[2] == "1"
                        outcome = dec(p[3])
                        note = dec(p[4])
                        sessionId = dec(p[5]).orEmpty()
                        entries = ArrayList()
                    }
                    "e" -> {
                        val kind = AgentWriteJournal.Kind.valueOf(p[1])
                        val path = dec(p[2]) ?: throw IllegalArgumentException("entry without path")
                        entries.add(
                            AgentWriteJournal.Entry(path = path, kind = kind, before = dec(p[3]), after = dec(p[4]).orEmpty()),
                        )
                    }
                    else -> throw IllegalArgumentException("unknown record")
                }
            }
            flush()
            out
        }
    } catch (t: Throwable) {
        emptyList()
    }

    /** Returns null on success, or a short reason. Never leaves a half-written file behind. */
    fun save(root: File, projectId: String, sessionId: String, sets: List<AgentWriteJournal.ChangeSet>): String? {
        val target = file(root, projectId, sessionId)
        if (sets.isEmpty()) {
            delete(root, projectId, sessionId)
            return null
        }
        val body = encode(sets) ?: return TOO_LARGE
        return try {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(body)
            if (target.exists() && !target.delete()) return "could not replace the saved history"
            if (!temp.renameTo(target)) return "could not finish writing the saved history"
            null
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }

    fun load(root: File, projectId: String, sessionId: String): List<AgentWriteJournal.ChangeSet> {
        val f = file(root, projectId, sessionId)
        if (!f.exists()) return emptyList()
        return try {
            decode(f.readText())
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun delete(root: File, projectId: String, sessionId: String) {
        try {
            file(root, projectId, sessionId).delete()
        } catch (t: Throwable) {
            // A history file that cannot be deleted is dead weight, never a correctness problem:
            // undo still verifies every target against the disk before touching it.
        }
    }
}
