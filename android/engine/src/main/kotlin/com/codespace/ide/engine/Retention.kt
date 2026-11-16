package com.codespace.ide.engine

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * PROJECT SIX 6.3 — retention, provenance and recovery.
 *
 * 6.2 made a summary that cannot invent a fact. 6.3 answers the two questions that leaves open:
 * "how sure is this line?" and "where did it come from, once the summary has outlived the session
 * that produced it?" A summary without provenance is indistinguishable from a user's own words after
 * a restart, which is exactly the confusion this file exists to prevent.
 */

/** How much the conversation actually committed to, as far as its own wording shows. */
enum class ClaimStatus {
    /** Stated with evidence in the text: verified, confirmed, green, a real path or run. */
    CONFIRMED,
    /** Explicitly a guess: assume, presumed, hypothesis. */
    ASSUMED,
    /** Explicitly not settled: unknown, unresolved, blocked, unsure, pending. */
    UNRESOLVED,
    /** No evidence either way. The honest default, never promoted to CONFIRMED by assumption. */
    UNKNOWN,
    ;

    companion object {
        private val assumed = listOf("assume", "assumed", "presumably", "hypothesis", "suspected", "probably")
        private val unresolved = listOf(
            "unknown", "unresolved", "unsure", "not verified", "unverified", "blocked",
            "still open", "pending", "cannot tell", "needs evidence",
        )
        private val confirmed = listOf("verified", "confirmed", "green", "passed", "succeeded", "reproduced")

        /**
         * Order matters: an explicit "unverified" must win over a nearby "verified", which is why the
         * negative markers are checked first. Certainty is never inferred from the absence of doubt.
         */
        fun of(text: String): ClaimStatus {
            val t = text.lowercase()
            if (unresolved.any { t.contains(it) }) return UNRESOLVED
            if (assumed.any { t.contains(it) }) return ASSUMED
            if (confirmed.any { t.contains(it) }) return CONFIRMED
            return UNKNOWN
        }
    }
}

/** Where a retained line came from, so generated text can never pass as the original. */
enum class LineOrigin { MESSAGE, TOOL_EXCHANGE, CARRIED_SUMMARY }

/**
 * On-disk retention for summaries, one file per session, atomic, bounded and keyed by session.
 *
 * Three rules the storage has to hold:
 *  1. **A session reads only its own file.** A summary from another conversation would be leakage, so
 *     the file states the session it belongs to and a mismatch is treated as corrupt, not as data.
 *  2. **A torn write must not destroy a good summary.** Writes go to a temporary file and are renamed
 *     into place, so a process death mid-write leaves either the old file or the new one.
 *  3. **Nothing credential-shaped is written.** Excerpts are redacted before they touch the disk.
 *
 * A file that cannot be parsed is reported as [Load.Corrupt] with a reason. Recovery from there is the
 * caller's job, and it is honest recovery: rebuild from the messages the session still has.
 */
class SummaryStore(
    private val dir: File?,
    private val maxSessions: Int = MAX_SESSIONS,
) {
    sealed class Load {
        object Empty : Load()
        data class Ok(val summary: ConversationSummary) : Load()
        data class Corrupt(val reason: String) : Load()
    }

    private fun fileFor(sessionId: String): File? {
        val d = dir ?: return null
        val safe = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
        val tag = Integer.toHexString(sessionId.hashCode())
        return File(d, safe + "-" + tag + ".summary")
    }

    fun load(sessionId: String): Load {
        val f = fileFor(sessionId) ?: return Load.Empty
        if (!f.exists() || f.length() == 0L) return Load.Empty
        val text = try {
            f.readText()
        } catch (e: Exception) {
            return Load.Corrupt("unreadable: " + (e.message ?: e.javaClass.simpleName))
        }
        val rows = text.split("\n").filter { it.isNotBlank() }
        val head = rows.firstOrNull() ?: return Load.Corrupt("empty file")
        val parts = head.split("|")
        if (parts.size < 3) return Load.Corrupt("unknown header")
        if (parts[0] != FORMAT) return Load.Corrupt("format " + parts[0] + " is not " + FORMAT)
        if (parts[1] != sessionId) return Load.Corrupt("belongs to another session")
        val revision = parts[2].toIntOrNull() ?: return Load.Corrupt("bad revision")
        val lines = ArrayList<SummaryLine>()
        val covered = ArrayList<String>()
        for (row in rows.drop(1)) {
            val f2 = row.split("|")
            if (f2.size < 8) return Load.Corrupt("malformed line")
            val seq = f2[0].toIntOrNull() ?: return Load.Corrupt("bad sequence")
            val kind = runCatching { SummaryKind.valueOf(f2[1]) }.getOrNull()
                ?: return Load.Corrupt("unknown kind " + f2[1])
            val status = runCatching { ClaimStatus.valueOf(f2[2]) }.getOrDefault(ClaimStatus.UNKNOWN)
            val origin = runCatching { LineOrigin.valueOf(f2[3]) }.getOrDefault(LineOrigin.MESSAGE)
            val role = f2[4]
            val ids = f2[5].split(",").filter { it.isNotBlank() }
            if (ids.isEmpty()) return Load.Corrupt("a retained line lost its provenance")
            val body = try {
                URLDecoder.decode(f2[6], "UTF-8")
            } catch (e: Exception) {
                return Load.Corrupt("undecodable text")
            }
            if (f2[7] == "1") covered.addAll(ids)
            lines.add(SummaryLine(seq, body, ids, role, kind, status, origin))
        }
        return Load.Ok(ConversationSummary(lines, covered, revision))
    }

    /** True when the file was written. False means "retention is not available", never "it worked". */
    fun save(sessionId: String, summary: ConversationSummary, coveredIds: Collection<String>): Boolean {
        val f = fileFor(sessionId) ?: return false
        return try {
            f.parentFile?.mkdirs()
            val covered = coveredIds.toSet()
            val sb = StringBuilder()
            sb.append(FORMAT).append('|').append(sessionId).append('|').append(summary.revision).append('\n')
            for (line in summary.lines) {
                val carried = if (line.sourceIds.all { it in covered }) "0" else "0"
                sb.append(line.seq).append('|')
                    .append(line.kind.name).append('|')
                    .append(line.status.name).append('|')
                    .append(line.origin.name).append('|')
                    .append(line.role).append('|')
                    .append(line.sourceIds.joinToString(",")).append('|')
                    .append(URLEncoder.encode(redact(line.text), "UTF-8")).append('|')
                    .append(carried).append('\n')
            }
            val tmp = File(f.absolutePath + ".tmp")
            tmp.writeText(sb.toString())
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) { tmp.delete(); return false }
            }
            prune()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun delete(sessionId: String): Boolean = fileFor(sessionId)?.delete() ?: false

    /** Bounded storage: the oldest files go first once the cap is reached. */
    private fun prune() {
        val d = dir ?: return
        val files = d.listFiles { f -> f.isFile && f.name.endsWith(".summary") } ?: return
        if (files.size <= maxSessions) return
        files.sortedBy { it.lastModified() }.take(files.size - maxSessions).forEach { it.delete() }
    }

    /** Credentials never reach the disk, even as an excerpt of something the user pasted. */
    fun redact(text: String): String {
        var out = text
        for (pattern in SECRETS) out = pattern.replace(out, "[redacted]")
        return out
    }

    companion object {
        const val FORMAT = "vncode-summary-1"
        const val MAX_SESSIONS = 20
        private val SECRETS = listOf(
            Regex("ghp_[A-Za-z0-9]{20,}"),
            Regex("github_pat_[A-Za-z0-9_]{20,}"),
            Regex("sk-[A-Za-z0-9-]{12,}"),
            Regex("sk-ant-[A-Za-z0-9-]{12,}"),
            Regex("AIza[A-Za-z0-9_-]{20,}"),
            Regex("(?i)\\bBearer\\s+[A-Za-z0-9._-]{12,}"),
            Regex("(?i)\\b(api[_-]?key|token)\\s*[=:]\\s*[A-Za-z0-9._-]{12,}"),
        )
    }
}

/**
 * PROJECT SIX 6.3C — historical retrieval.
 *
 * The summary is a lossy index. When the owner asks a question whose answer was in a turn that has
 * left the window, the honest move is to fetch the ORIGINAL wording, not to answer from an excerpt of
 * an excerpt. So this scores the older messages against the question and quotes the best ones back
 * verbatim, labelled as retrieved.
 *
 * What it deliberately cannot do: run a tool. The input is [ChatMessage] and the output is text, so
 * there is no executor to reach and no approval to inherit. Retrieval is reading, and reading is free
 * of side effects.
 */
data class RetrievedLine(
    val text: String,
    val sourceIds: List<String>,
    val seq: Int,
    val score: Int,
)

data class Retrieved(val lines: List<RetrievedLine>, val chars: Int) {
    fun isEmpty(): Boolean = lines.isEmpty()

    /** Verbatim, labelled as retrieved and distinct from a generated summary. */
    fun render(): String {
        if (lines.isEmpty()) return ""
        val sb = StringBuilder("RETRIEVED FROM EARLIER TURNS (verbatim excerpts, quoted because the question ")
            .append("matched them; these turns are no longer in the window).\n")
        for (l in lines) sb.append("- [RETRIEVED] ").append(l.text).append('\n')
        return sb.toString()
    }

    companion object {
        val EMPTY = Retrieved(emptyList(), 0)
    }
}

object HistoryRetriever {
    const val MAX_LINES = 3
    const val MAX_LINE_CHARS = 400

    private val stop = setOf(
        "the", "and", "for", "that", "with", "this", "from", "into", "have", "what", "when", "which",
        "your", "about", "there", "their", "them", "then", "than", "were", "will", "would", "should",
        "does", "did", "was", "are", "you", "our", "its", "it's", "but", "not", "can", "could",
    )

    fun terms(text: String): Set<String> =
        text.lowercase().split(Regex("[^a-z0-9_.-]+"))
            .filter { it.length >= 4 && it !in stop }
            .toSet()

    /**
     * @param candidates older messages, newest first is NOT assumed: ordering comes from `seq` so the
     *        result can be quoted in the order it was said.
     * @param excludeIds ids already travelling in the window or already quoted.
     * @param alreadyQuoted summary text, used to avoid quoting what the summary already says.
     */
    fun retrieve(
        query: String,
        candidates: List<ChatMessage>,
        excludeIds: Set<String>,
        budgetChars: Int,
        alreadyQuoted: String = "",
        maxLines: Int = MAX_LINES,
    ): Retrieved {
        if (budgetChars <= 0 || candidates.isEmpty()) return Retrieved.EMPTY
        val wanted = terms(query)
        if (wanted.isEmpty()) return Retrieved.EMPTY
        val scored = ArrayList<RetrievedLine>()
        var i = 0
        for (m in candidates) {
            val seq = i++
            if (m.id in excludeIds) continue
            if (m.role == ChatRole.SYSTEM) continue
            val text = m.text.trim()
            if (text.length < 24) continue
            val hit = terms(text).count { it in wanted }
            if (hit == 0) continue
            val body = if (text.length <= MAX_LINE_CHARS) text.replace("\n", " ")
            else text.replace("\n", " ").take(MAX_LINE_CHARS) + " [cut " + (text.length - MAX_LINE_CHARS) + " chars]"
            if (alreadyQuoted.isNotEmpty() && alreadyQuoted.contains(body.take(120))) continue
            scored.add(RetrievedLine(body, listOf(m.id), seq, hit))
        }
        val best = scored.sortedWith(compareByDescending<RetrievedLine> { it.score }.thenBy { it.seq })
            .take(maxLines)
            .sortedBy { it.seq }
        val kept = ArrayList<RetrievedLine>()
        var used = 0
        for (l in best) {
            val cost = l.text.length + 20
            if (used + cost > budgetChars) continue
            used += cost
            kept.add(l)
        }
        return if (kept.isEmpty()) Retrieved.EMPTY else Retrieved(kept, used)
    }
}
