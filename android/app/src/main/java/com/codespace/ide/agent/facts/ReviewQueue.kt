package com.codespace.ide.agent.facts

import com.codespace.ide.util.AtomicJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * STAGE 8 — the review queue for automatically extracted memory candidates.
 *
 * WHY THIS EXISTS: Stage 7 could save what it detected. Detection is a regex, and a regex is not
 * permission. A candidate the user never approved must not become a fact, so every automatic
 * candidate now lands here as PENDING and only an explicit accept reaches [FactsStore].
 *
 * ONE STORE, NOT TWO: rejected candidates stay in this same list with their reason and timestamp.
 * That IS the rejection ledger - a separate file would be a second source of truth that could
 * disagree with this one about whether a candidate was rejected.
 */
enum class ReviewStatus(val id: String) {
    PENDING("pending"),
    ACCEPTED("accepted"),
    REJECTED("rejected"),
    ;

    companion object {
        fun parse(raw: String?): ReviewStatus =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase() } ?: PENDING
    }
}

data class ReviewCandidate(
    val id: String,
    val text: String,
    val category: FactCategory = FactCategory.GENERAL,
    val scope: FactScope = FactScope.USER,
    val projectId: String? = null,
    /** Why it was suggested: the Stage 7 [MemoryExtraction.Kind] name. */
    val rationale: String = "",
    val sourceConversationId: String? = null,
    /** Null when the originating message could not be identified. Never guessed. */
    val sourceMessageId: String? = null,
    val sourceConversationAt: Long = 0L,
    val sourceMessageAt: Long = 0L,
    val timeSource: TimeSource = TimeSource.UNKNOWN,
    val createdAt: Long = 0L,
    val reviewedAt: Long = 0L,
    val status: ReviewStatus = ReviewStatus.PENDING,
    /** The fact this became, once accepted. */
    val factId: String? = null,
    /** Only when the user typed one. Never required. */
    val reason: String? = null,
) {
    fun belongsTo(projectId: String?): Boolean =
        scope == FactScope.USER || (projectId != null && this.projectId == projectId)
}

object ReviewQueue {

    /** Bounded like the fact store: a phone must not grow an unbounded review list. */
    const val MAX = 200

    /** A rejection is remembered for 90 days, then the candidate may be suggested again. */
    const val REJECT_RETENTION_DAYS = 90

    private const val FILE = "memory-review.json"

    data class Enqueued(val added: Int, val duplicates: Int, val rejectedBefore: Int, val full: Boolean = false)

    sealed class Outcome {
        data class Accepted(val fact: Fact, val decision: FactDecision) : Outcome()
        /** The write did not land. Nothing changed on disk. */
        data object WriteFailed : Outcome()
        /** Already reviewed, or no such candidate. Repeat actions are no-ops, not errors. */
        data object AlreadyResolved : Outcome()
        data object NotFound : Outcome()
        data object Rejected : Outcome()
    }

    fun fileIn(d: File): File = File(d, FILE)

    // ------------------------------------------------------------------ read

    fun loadFrom(d: File): List<ReviewCandidate> {
        val f = fileIn(d)
        if (!f.isFile) return emptyList()
        return try {
            parse(f.readText())
        } catch (_: Throwable) {
            // Corrupt queue: report empty, never "everything was accepted". An empty queue cannot
            // accept anything, so corruption cannot promote a candidate into memory.
            emptyList()
        }
    }

    fun parse(raw: String): List<ReviewCandidate> {
        val arr = try { JSONArray(raw) } catch (_: Throwable) { return emptyList() }
        val out = ArrayList<ReviewCandidate>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val text = o.optString("text", "")
            if (id.isBlank() || text.isBlank()) continue
            out.add(
                ReviewCandidate(
                    id = id,
                    text = text,
                    category = FactCategory.parse(o.optString("category", null)),
                    scope = FactScope.parse(o.optString("scope", null)) ?: FactScope.USER,
                    projectId = o.optString("projectId", "").ifBlank { null },
                    rationale = o.optString("rationale", ""),
                    sourceConversationId = o.optString("sourceConversationId", "").ifBlank { null },
                    sourceMessageId = o.optString("sourceMessageId", "").ifBlank { null },
                    sourceConversationAt = o.optLong("sourceConversationAt", 0L),
                    sourceMessageAt = o.optLong("sourceMessageAt", 0L),
                    timeSource = TimeSource.entries.firstOrNull { it.name.equals(o.optString("timeSource", "").trim(), true) } ?: TimeSource.UNKNOWN,
                    createdAt = o.optLong("createdAt", 0L),
                    reviewedAt = o.optLong("reviewedAt", 0L),
                    status = ReviewStatus.parse(o.optString("status", null)),
                    factId = o.optString("factId", "").ifBlank { null },
                    reason = o.optString("reason", "").ifBlank { null },
                )
            )
        }
        return out
    }

    private fun toJson(list: List<ReviewCandidate>): String {
        val arr = JSONArray()
        for (c in list) {
            val o = JSONObject()
            o.put("id", c.id)
            o.put("text", c.text)
            o.put("category", c.category.id)
            o.put("scope", c.scope.id)
            if (c.projectId != null) o.put("projectId", c.projectId)
            if (c.rationale.isNotBlank()) o.put("rationale", c.rationale)
            if (c.sourceConversationId != null) o.put("sourceConversationId", c.sourceConversationId)
            if (c.sourceMessageId != null) o.put("sourceMessageId", c.sourceMessageId)
            if (c.sourceConversationAt > 0L) o.put("sourceConversationAt", c.sourceConversationAt)
            if (c.sourceMessageAt > 0L) o.put("sourceMessageAt", c.sourceMessageAt)
            o.put("timeSource", c.timeSource.name)
            o.put("createdAt", c.createdAt)
            if (c.reviewedAt > 0L) o.put("reviewedAt", c.reviewedAt)
            o.put("status", c.status.id)
            if (c.factId != null) o.put("factId", c.factId)
            if (c.reason != null) o.put("reason", c.reason)
            arr.put(o)
        }
        return arr.toString(2)
    }

    /** The whole queue is one atomic document: a half-written review must never be readable. */
    fun writeTo(d: File, list: List<ReviewCandidate>): Boolean = AtomicJson.write(fileIn(d), toJson(list))

    // ------------------------------------------------------------------ matching

    /** Normalised identity: wording variations that mean the same thing collide here. */
    fun keyOf(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * "Substantially equivalent" uses the same similarity the dedupe uses, so a rejected candidate
     * cannot come back as a reworded suggestion, and two genuinely different statements stay apart.
     */
    fun sameStatement(a: String, b: String): Boolean {
        val ka = keyOf(a)
        val kb = keyOf(b)
        if (ka == kb) return true
        if (ka.length < 8 || kb.length < 8) return false
        return FactText.similarity(ka, kb) >= 0.82
    }

    // ------------------------------------------------------------------ write

    /** Drops rejections older than the retention window. Accepted and pending rows are kept. */
    fun pruneRejections(list: List<ReviewCandidate>, now: Long): List<ReviewCandidate> {
        val horizon = now - REJECT_RETENTION_DAYS * 86_400_000L
        return list.filter { it.status != ReviewStatus.REJECTED || it.reviewedAt <= 0L || it.reviewedAt >= horizon }
    }

    /**
     * Add detected candidates. A statement already pending, accepted, or rejected (including a
     * reworded equivalent) is not added again - that is what stops an unwanted suggestion looping.
     */
    fun enqueue(
        d: File,
        candidates: List<MemoryExtraction.Candidate>,
        provenance: FactProvenance = FactProvenance.NONE,
        scope: FactScope = FactScope.USER,
        projectId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Enqueued {
        if (candidates.isEmpty()) return Enqueued(0, 0, 0)
        // A project candidate with no project open becomes a user candidate, exactly as the fact
        // store does, so the queue never holds a project row that belongs to no project.
        val effectiveScope = if (scope == FactScope.PROJECT && projectId.isNullOrBlank()) FactScope.USER else scope
        val all = loadFrom(d)
        val kept = pruneRejections(all, now).toMutableList()
        var added = 0
        var dup = 0
        var knownRejects = 0
        for (c in candidates) {
            val existing = kept.firstOrNull { sameStatement(it.text, c.text) && it.scope == effectiveScope }
            if (existing != null) {
                if (existing.status == ReviewStatus.REJECTED) knownRejects++ else dup++
                continue
            }
            if (kept.size >= MAX) return Enqueued(added, dup, knownRejects, full = true)
            kept.add(
                ReviewCandidate(
                    id = "r_" + java.util.UUID.randomUUID().toString().take(8),
                    text = c.text,
                    category = c.category,
                    scope = effectiveScope,
                    projectId = if (effectiveScope == FactScope.PROJECT) projectId else null,
                    rationale = c.kind.name,
                    sourceConversationId = provenance.sourceConversationId,
                    sourceMessageId = provenance.sourceMessageId,
                    sourceConversationAt = provenance.sourceConversationAt,
                    sourceMessageAt = provenance.sourceMessageAt,
                    timeSource = provenance.timeSource,
                    createdAt = now,
                )
            )
            added++
        }
        if (!writeTo(d, kept)) return Enqueued(0, dup, knownRejects)
        return Enqueued(added, dup, knownRejects)
    }

    /**
     * Accept: the ONLY path from candidate to fact. Idempotent - accepting twice saves once, and a
     * failed write leaves the candidate PENDING rather than claiming success.
     */
    fun accept(d: File, id: String, now: Long = System.currentTimeMillis()): Outcome {
        val all = loadFrom(d)
        val c = all.firstOrNull { it.id == id } ?: return Outcome.NotFound
        if (c.status != ReviewStatus.PENDING) return Outcome.AlreadyResolved
        val saved = try {
            FactsStore.saveTo(
                d = d,
                content = c.text,
                scope = if (c.scope == FactScope.PROJECT && c.projectId.isNullOrBlank()) FactScope.USER else c.scope,
                projectId = c.projectId,
                category = c.category,
                // Approval changes the STATUS, never the provenance: this was inferred by the app,
                // and saying otherwise would be a lie about who decided it.
                source = FactSource.INFERRED,
                confidence = FactConfidence.INFERRED,
                now = now,
                provenance = FactProvenance(
                    sourceConversationId = c.sourceConversationId,
                    conversationStartedAt = c.sourceConversationAt,
                    sourceConversationAt = c.sourceConversationAt,
                    sourceMessageId = c.sourceMessageId,
                    sourceMessageAt = c.sourceMessageAt,
                    timeSource = c.timeSource,
                ),
            )
        } catch (_: Throwable) {
            return Outcome.WriteFailed
        }
        val fact = when (saved) {
            is FactsStore.SaveResult.Saved -> saved.fact
            is FactsStore.SaveResult.Refused,
            FactsStore.SaveResult.Full,
            FactsStore.SaveResult.WriteFailed -> return Outcome.WriteFailed
        }
        val updated = all.map {
            if (it.id == id) it.copy(status = ReviewStatus.ACCEPTED, reviewedAt = now, factId = fact.id) else it
        }
        if (!writeTo(d, updated)) return Outcome.WriteFailed
        MemoryEvents.publish()
        return Outcome.Accepted(fact, (saved as FactsStore.SaveResult.Saved).decision)
    }

    /** Reject: never saves. The reason is optional; the record is what stops the repeat. */
    fun reject(
        d: File,
        id: String,
        reason: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Outcome {
        val all = loadFrom(d)
        val c = all.firstOrNull { it.id == id } ?: return Outcome.NotFound
        if (c.status != ReviewStatus.PENDING) return Outcome.AlreadyResolved
        val updated = all.map {
            if (it.id == id) it.copy(
                status = ReviewStatus.REJECTED,
                reviewedAt = now,
                reason = reason?.trim()?.take(200)?.ifBlank { null },
            ) else it
        }
        if (!writeTo(d, updated)) return Outcome.WriteFailed
        MemoryEvents.publish()
        return Outcome.Rejected
    }

    /** Explicit reconsideration: a rejection is never permanent. */
    fun reconsider(d: File, id: String, now: Long = System.currentTimeMillis()): Boolean {
        val all = loadFrom(d)
        val c = all.firstOrNull { it.id == id } ?: return false
        if (c.status != ReviewStatus.REJECTED) return false
        val updated = all.map {
            if (it.id == id) it.copy(status = ReviewStatus.PENDING, reviewedAt = 0L, reason = null) else it
        }
        if (!writeTo(d, updated)) return false
        MemoryEvents.publish()
        return true
    }

    fun pending(list: List<ReviewCandidate>): List<ReviewCandidate> =
        list.filter { it.status == ReviewStatus.PENDING }.sortedByDescending { it.createdAt }

    fun rejected(list: List<ReviewCandidate>): List<ReviewCandidate> =
        list.filter { it.status == ReviewStatus.REJECTED }.sortedByDescending { it.reviewedAt }

    fun accepted(list: List<ReviewCandidate>): List<ReviewCandidate> =
        list.filter { it.status == ReviewStatus.ACCEPTED }.sortedByDescending { it.reviewedAt }
}
