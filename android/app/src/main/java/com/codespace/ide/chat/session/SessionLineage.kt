package com.codespace.ide.chat.session

import android.content.Context
import com.codespace.ide.chat.TranscriptFiles
import java.io.File

/**
 * P6.C - WHICH SESSION CAME FROM WHICH.
 *
 * "Continue in another session" created a new conversation and seeded it with a handoff, then forgot
 * the relationship: two sessions with no record that one is the successor of the other. After a few
 * hops the list was a pile of titles and the user had no way to read the chain.
 *
 * The lineage is kept in its own small file rather than in `SessionMeta`: the session index is a
 * strict, versioned format whose every field is validated, and adding a field there would mean a
 * format bump plus a migration for a fact that is purely presentational. A missing or damaged lineage
 * file degrades to "no lineage" and never costs anyone a conversation.
 *
 * Format: one tab-separated record per line, `id \t parent \t seq \t origin`, escaped the same way the
 * session index escapes titles.
 */
class SessionLineage(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Link(
        val sessionId: String,
        /** The session this one was continued from, or null when it is a root. */
        val parentId: String?,
        /** 1 for a root, parent's sequence + 1 otherwise: the "Session N" the user reads. */
        val seq: Int,
        /** The root conversation's title, so hop 5 still says what the chain is about. */
        val origin: String,
    )

    companion object {
        const val FILE_NAME = "session-lineage.tsv"
        const val MAX_ORIGIN = 60

        /** The lineage file for a project, beside its transcripts. */
        fun forProject(context: Context, projectId: String): SessionLineage =
            SessionLineage(
                File(File(File(context.filesDir, "chat"), TranscriptFiles.safeSegment(projectId)), FILE_NAME),
            )

        /** "Session 2 from git" - the label the list shows and the notice repeats. */
        fun label(link: Link): String = "Session " + link.seq + " from " + link.origin
    }

    fun read(): List<Link> {
        if (!file.exists()) return emptyList()
        val raw = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val out = ArrayList<Link>()
        val seen = HashSet<String>()
        for (line in raw.lineSequence()) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size != 4) continue
            val id = unescape(parts[0])
            val parent = unescape(parts[1]).ifBlank { null }
            val seq = parts[2].toIntOrNull() ?: continue
            if (id.isBlank() || !seen.add(id)) continue
            out.add(Link(id, parent, seq.coerceAtLeast(1), unescape(parts[3])))
        }
        return out
    }

    fun linkOf(sessionId: String): Link? = read().firstOrNull { it.sessionId == sessionId }

    /**
     * Record [sessionId] as the successor of [parentId].
     *
     * The sequence and the origin come from the PARENT's own link, so hop five still counts from the
     * root and still says what the root was about. A parent with no link (created before lineage
     * existed) is treated as a root: sequence 2, origin [parentTitle].
     */
    fun record(sessionId: String, parentId: String?, parentTitle: String): Link {
        val known = read()
        val parent = parentId?.let { pid -> known.firstOrNull { it.sessionId == pid } }
        val link = if (parentId == null) {
            Link(sessionId, null, 1, normalise(parentTitle))
        } else {
            Link(
                sessionId = sessionId,
                parentId = parentId,
                seq = (parent?.seq ?: 1) + 1,
                origin = parent?.origin ?: normalise(parentTitle),
            )
        }
        // Rewrite the whole file: it is a handful of lines, and a partial append is worse than a write.
        val kept = known.filter { it.sessionId != sessionId } + link
        val text = buildString {
            for (l in kept) {
                append(escape(l.sessionId)).append('\t')
                append(escape(l.parentId ?: "")).append('\t')
                append(l.seq).append('\t')
                append(escape(l.origin)).append('\n')
            }
        }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, FILE_NAME + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
        return link
    }

    private fun normalise(title: String): String {
        val one = title.replace('\n', ' ').replace('\r', ' ').trim()
        val clean = if (one.isBlank()) SessionMeta.UNTITLED else one
        return if (clean.length <= MAX_ORIGIN) clean else clean.take(MAX_ORIGIN) + "\u2026"
    }

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> { sb.append('\t'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(ch); i++ }
                }
            } else {
                sb.append(ch); i++
            }
        }
        return sb.toString()
    }
}
