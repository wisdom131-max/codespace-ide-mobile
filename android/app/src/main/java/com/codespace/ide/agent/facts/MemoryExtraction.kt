package com.codespace.ide.agent.facts

import java.io.File

/**
 * STAGE 7 — automatic memory extraction.
 *
 * Deliberately NOT a summariser. It only promotes a message that is shaped like a durable statement
 * the user made: an explicit preference, a decision, a standing rule, or a statement about the
 * project. Everything else is left alone, because a memory that stores chatter is worse than no
 * memory at all - it costs prompt space and it is wrong.
 *
 * Nothing here can invent provenance. A candidate carries the [FactProvenance] it was given, and a
 * fact is stored with `FactSource.INFERRED` so it never masquerades as an explicit user statement.
 */
object MemoryExtraction {

    /** How many candidates one message may contribute. Bounded so a long message cannot flood memory. */
    const val MAX_PER_MESSAGE = 3

    /** Below this, a message is almost always conversational rather than a statement. */
    const val MIN_CHARS = 12

    const val MAX_CHARS = 240

    enum class Kind { PREFERENCE, DECISION, RULE, PROJECT_CONTEXT }

    data class Candidate(val text: String, val kind: Kind, val category: FactCategory)

    data class Result(
        val accepted: List<Fact> = emptyList(),
        val duplicates: Int = 0,
        val rejected: Int = 0,
        val skippedReason: String? = null,
    )

    private val TRANSIENT = setOf(
        "thanks", "thank you", "ok", "okay", "sure", "yes", "no", "cool", "nice", "great",
        "continue", "go on", "next", "stop", "wait", "hello", "hi", "hey", "lol", "hmm",
    )

    private val PREFERENCE = Regex(
        """\b(i|we)\s+(prefer|like|want|always use|never use|hate|don't like|do not like)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val DECISION = Regex(
        """\b(we|i)\s+(decided|agreed|chose|will use|are going with|settled on)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val RULE = Regex(
        """\b(always|never|must|should always|should never|make sure to|from now on)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val PROJECT = Regex(
        """\b(the project|this project|the app|the build|the repo|our stack|the codebase)\s+(uses|is|targets|runs|builds)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Candidate detection. Pure and cheap: no model call, no disk, no network, so it can run on the
     * message path without adding latency to chat.
     */
    fun candidates(text: String, category: FactCategory? = null): List<Candidate> {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.length < MIN_CHARS) return emptyList()
        if (clean.length > MAX_CHARS * 4) return emptyList()
        if (clean.lowercase().trim('.', '!', ' ') in TRANSIENT) return emptyList()
        // A question is a request, not a statement of durable fact.
        if (clean.endsWith("?")) return emptyList()

        val out = ArrayList<Candidate>(2)
        for (sentence in clean.split(Regex("(?<=[.!;])\\s+"))) {
            val s = sentence.trim().trim('.', '!', ';')
            if (s.length < MIN_CHARS) continue
            val kind = when {
                DECISION.containsMatchIn(s) -> Kind.DECISION
                PREFERENCE.containsMatchIn(s) -> Kind.PREFERENCE
                PROJECT.containsMatchIn(s) -> Kind.PROJECT_CONTEXT
                RULE.containsMatchIn(s) -> Kind.RULE
                else -> null
            } ?: continue
            val body = s.take(MAX_CHARS)
            // Guard runs here as well as at the store, so a secret never even becomes a candidate.
            if (FactGuard.rejectionReason(body, FactSource.INFERRED) != null) continue
            out.add(Candidate(body, kind, category ?: FactCategory.infer(body)))
            if (out.size >= MAX_PER_MESSAGE) break
        }
        return out
    }

    /**
     * Store what was detected. Every write goes through [FactsStore.saveTo], so de-duplication,
     * contradiction handling and supersession are the SAME code the explicit path uses - there is
     * no second memory system here.
     */
    fun capture(
        d: File,
        text: String,
        provenance: FactProvenance,
        scope: FactScope = FactScope.USER,
        projectId: String? = null,
        category: FactCategory? = null,
        now: Long = System.currentTimeMillis(),
    ): Result {
        val found = candidates(text, category)
        if (found.isEmpty()) return Result(skippedReason = "no durable statement")
        var duplicates = 0
        var rejected = 0
        val accepted = ArrayList<Fact>(found.size)
        for (c in found) {
            val r = try {
                FactsStore.saveTo(
                    d = d,
                    content = c.text,
                    scope = if (scope == FactScope.PROJECT && projectId.isNullOrBlank()) FactScope.USER else scope,
                    projectId = projectId,
                    category = c.category,
                    // INFERRED, never EXPLICIT_USER: the user said the words, the app decided they
                    // were durable. That difference must stay visible in the fact itself.
                    source = FactSource.INFERRED,
                    confidence = FactConfidence.INFERRED,
                    now = now,
                    provenance = provenance,
                )
            } catch (_: Throwable) {
                // A memory failure must never surface as a chat failure.
                return Result(accepted, duplicates, rejected + 1, "store error")
            }
            when (r) {
                is FactsStore.SaveResult.Saved ->
                    if (r.decision == FactDecision.DUPLICATE) duplicates++ else accepted.add(r.fact)
                is FactsStore.SaveResult.Refused -> rejected++
                FactsStore.SaveResult.Full, FactsStore.SaveResult.WriteFailed -> {
                    return Result(accepted, duplicates, rejected, "store unavailable")
                }
            }
        }
        return Result(accepted, duplicates, rejected)
    }
}
