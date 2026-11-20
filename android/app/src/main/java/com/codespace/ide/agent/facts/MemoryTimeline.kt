package com.codespace.ide.agent.facts

import com.codespace.ide.util.AtomicJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * STAGE 9 — the memory timeline.
 *
 * One bounded document, `memory-timeline.json`, next to facts.json. It is SEPARATE on purpose:
 * a timeline that cannot be written must never be able to damage the facts it describes, and a
 * facts write that succeeded must not be rolled back because an event append failed.
 *
 * Two times are stored per event and they are not interchangeable:
 *  - [TimelineEvent.atMs] is when the APP performed the operation (a real event time).
 *  - [TimelineEvent.subjectTimeMs] + [TimelineEvent.timeSource] are when the information itself
 *    is about, taken from the fact's Stage 4 provenance and never invented here.
 *
 * Nothing in this file fabricates history: an operation that happened before tracking existed
 * simply has no event, and a missing date stays 0.
 */
enum class TimelineEventType {
    CREATED,
    ACCEPTED,
    UPDATED,
    SUPERSEDED,
    DELETED,
    REJECTED,
    RECONSIDERED,
}

data class TimelineEvent(
    val id: String,
    val type: TimelineEventType,
    /** Fact id, or review-candidate id for the review events. */
    val subjectId: String,
    /** "fact" or "candidate". */
    val subjectKind: String,
    /** When the app performed this operation. */
    val atMs: Long,
    val scope: FactScope = FactScope.USER,
    val projectId: String? = null,
    val previousText: String? = null,
    val newText: String? = null,
    val sourceConversationId: String? = null,
    val sourceMessageId: String? = null,
    /** When the information is about; 0 when unknown. Never a stand-in for [atMs]. */
    val subjectTimeMs: Long = 0L,
    val timeSource: TimeSource = TimeSource.UNKNOWN,
    /** The fact that replaced this one, when reliably known. */
    val replacementId: String? = null,
    val reason: String? = null,
) {
    /** A verified date is only claimed when the fact itself claims one. */
    fun hasVerifiedTime(): Boolean =
        timeSource == TimeSource.TRANSCRIPT_VERIFIED && subjectTimeMs > 0L
}

object MemoryTimeline {

    /** Bounded: the oldest events fall off first, and no fact is ever touched by pruning. */
    const val MAX_EVENTS = 400

    const val FILE_NAME = "memory-timeline.json"

    fun fileIn(d: File): File = File(d, FILE_NAME)

    /** Newest first. A malformed document reads as empty rather than as invented history. */
    fun loadFrom(d: File): List<TimelineEvent> {
        val f = fileIn(d)
        if (!f.isFile) return emptyList()
        return try {
            parse(f.readText())
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun parse(raw: String): List<TimelineEvent> {
        val arr = try { JSONArray(raw) } catch (_: Throwable) { return emptyList() }
        val out = ArrayList<TimelineEvent>(arr.length())
        val seen = HashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val e = fromJson(o) ?: continue
            if (!seen.add(e.id)) continue
            out.add(e)
        }
        return out.sortedByDescending { it.atMs }
    }

    fun toJson(events: List<TimelineEvent>): String {
        val arr = JSONArray()
        for (e in events) arr.put(jsonOf(e))
        return arr.toString(2)
    }

    /**
     * Append one event. Idempotent: an event with the same id is never stored twice, so a retry,
     * a restoration or a repeated review action cannot duplicate history.
     */
    fun record(d: File, event: TimelineEvent): Boolean {
        val existing = loadFrom(d)
        if (existing.any { it.id == event.id }) return true
        val next = (existing + event).sortedByDescending { it.atMs }.take(MAX_EVENTS)
        return AtomicJson.write(fileIn(d), toJson(next))
    }

    /** Timeline for one scope, newest first, bounded. */
    fun events(d: File, projectId: String?, limit: Int = 50): List<TimelineEvent> =
        loadFrom(d)
            .filter { it.scope == FactScope.USER || it.projectId == projectId }
            .take(limit.coerceIn(1, MAX_EVENTS))

    /** Stable identity for an event: same operation on the same subject at the same instant. */
    fun idFor(type: TimelineEventType, subjectId: String, atMs: Long): String =
        type.name + ":" + subjectId + ":" + atMs

    // ---------------------------------------------------------------- convenience appends

    fun factEvent(
        d: File,
        type: TimelineEventType,
        fact: Fact,
        atMs: Long,
        previousText: String? = null,
        replacementId: String? = null,
    ): Boolean = record(
        d,
        TimelineEvent(
            id = idFor(type, fact.id, atMs),
            type = type,
            subjectId = fact.id,
            subjectKind = "fact",
            atMs = atMs,
            scope = fact.scope,
            projectId = fact.projectId,
            previousText = previousText,
            newText = fact.content,
            sourceConversationId = fact.sourceConversationId,
            sourceMessageId = fact.sourceMessageId,
            // NOT fact.factTime(): that helper falls back to createdAt, which is when the app
            // saved the fact, not when the information is from. A timeline must never present a
            // save time as a conversation date.
            subjectTimeMs = when {
                fact.sourceConversationAt > 0L -> fact.sourceConversationAt
                fact.sourceMessageAt > 0L -> fact.sourceMessageAt
                else -> 0L
            },
            timeSource = fact.timeSource,
            replacementId = replacementId,
        ),
    )

    fun reviewEvent(
        d: File,
        type: TimelineEventType,
        candidate: ReviewCandidate,
        atMs: Long,
        reason: String? = null,
        replacementId: String? = null,
    ): Boolean = record(
        d,
        TimelineEvent(
            id = idFor(type, candidate.id, atMs),
            type = type,
            subjectId = candidate.id,
            subjectKind = "candidate",
            atMs = atMs,
            scope = candidate.scope,
            projectId = candidate.projectId,
            newText = candidate.text,
            sourceConversationId = candidate.sourceConversationId,
            sourceMessageId = candidate.sourceMessageId,
            subjectTimeMs = candidate.subjectTimeMs(),
            timeSource = candidate.timeSource,
            replacementId = replacementId,
            reason = reason,
        ),
    )

    // ---------------------------------------------------------------- json

    private fun jsonOf(e: TimelineEvent): JSONObject {
        val o = JSONObject()
        o.put("id", e.id)
        o.put("type", e.type.name)
        o.put("subjectId", e.subjectId)
        o.put("subjectKind", e.subjectKind)
        o.put("atMs", e.atMs)
        o.put("scope", e.scope.id)
        if (!e.projectId.isNullOrBlank()) o.put("projectId", e.projectId)
        if (!e.previousText.isNullOrBlank()) o.put("previousText", e.previousText)
        if (!e.newText.isNullOrBlank()) o.put("newText", e.newText)
        if (!e.sourceConversationId.isNullOrBlank()) o.put("sourceConversationId", e.sourceConversationId)
        if (!e.sourceMessageId.isNullOrBlank()) o.put("sourceMessageId", e.sourceMessageId)
        if (e.subjectTimeMs > 0L) o.put("subjectTimeMs", e.subjectTimeMs)
        o.put("timeSource", e.timeSource.name)
        if (!e.replacementId.isNullOrBlank()) o.put("replacementId", e.replacementId)
        if (!e.reason.isNullOrBlank()) o.put("reason", e.reason)
        return o
    }

    private fun fromJson(o: JSONObject): TimelineEvent? {
        val id = o.optString("id", "").trim()
        if (id.isEmpty()) return null
        val type = TimelineEventType.entries.firstOrNull {
            it.name.equals(o.optString("type", "").trim(), true)
        } ?: return null
        val subject = o.optString("subjectId", "").trim()
        if (subject.isEmpty()) return null
        return TimelineEvent(
            id = id,
            type = type,
            subjectId = subject,
            subjectKind = o.optString("subjectKind", "fact").ifBlank { "fact" },
            atMs = o.optLong("atMs", 0L),
            scope = FactScope.parse(o.optString("scope", "")) ?: FactScope.USER,
            projectId = o.optString("projectId", "").ifBlank { null },
            previousText = o.optString("previousText", "").ifBlank { null },
            newText = o.optString("newText", "").ifBlank { null },
            sourceConversationId = o.optString("sourceConversationId", "").ifBlank { null },
            sourceMessageId = o.optString("sourceMessageId", "").ifBlank { null },
            subjectTimeMs = o.optLong("subjectTimeMs", 0L),
            timeSource = TimeSource.entries.firstOrNull {
                it.name.equals(o.optString("timeSource", "").trim(), true)
            } ?: TimeSource.UNKNOWN,
            replacementId = o.optString("replacementId", "").ifBlank { null },
            reason = o.optString("reason", "").ifBlank { null },
        )
    }
}
