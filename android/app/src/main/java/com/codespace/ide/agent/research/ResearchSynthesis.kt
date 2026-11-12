package com.codespace.ide.agent.research

/**
 * PROJECT 1.4 / 1.5 - THE HONEST BRIEF.
 *
 * This does not write the prose answer; it assembles everything the answer must respect, so the
 * model cannot round a partial investigation up to a finished one:
 *
 *  - citations belong to CLAIMS, not to a paragraph, so every statement is traceable to [S1], [S2];
 *  - corroborated, single-source, disputed and unresolved are separate buckets, because "two
 *    sources agree" and "one blog said so" are different kinds of knowledge;
 *  - a dispute carries both sides forward, so the answer cannot quietly pick one;
 *  - a gap is only worth another round when it is a term the question asked about, a round is
 *    still allowed, and the budget is not gone - otherwise the brief says plainly what is missing;
 *  - a research run that lost a fetch says so, keeps what it already had, and never reports
 *    SUFFICIENT just because it stopped.
 *
 * Provider-independent: it takes evidence and outcomes as plain data, never a client or a network.
 */
object ResearchSynthesis {

    /** Why research stopped. Only [SUFFICIENT] may be presented as a complete answer. */
    enum class StopReason(val phrase: String) {
        SUFFICIENT("enough evidence was read to answer"),
        MAX_ROUNDS("the round limit was reached"),
        BUDGET_EXHAUSTED("the search budget was exhausted"),
        FAILURES("source failures ended the run"),
        PARTIAL("the run ended with evidence still missing"),
    }

    /** Recovery statuses, distinct so a blocked page never reads like "nothing exists". */
    enum class RecoveryKind(val phrase: String) {
        NO_RESULTS("the search returned nothing for these phrasings"),
        SOURCE_UNAVAILABLE("a source could not be reached"),
        FETCH_FAILED("a page could not be fetched"),
        BLOCKED("a source refused automated access"),
        PARSE_FAILED("a page returned content that could not be read"),
        TIMEOUT("a request timed out"),
        RATE_LIMITED("a provider rate-limited the request"),
        BUDGET_EXHAUSTED("the search budget ran out"),
    }

    data class RecoveryNote(val kind: RecoveryKind, val subject: String, val retriable: Boolean)

    data class Evidence(
        val sourceId: String,
        val url: String,
        val title: String,
        val text: String,
        val publishedAt: String = "",
    )

    data class Brief(
        val question: String,
        val corroborated: List<ContradictionScan.Claim>,
        val singleSource: List<ContradictionScan.Claim>,
        val disputed: List<ContradictionScan.Dispute>,
        val unresolved: List<String>,
        val followUps: List<String>,
        val stop: StopReason,
        val recovery: List<RecoveryNote>,
        val render: String,
        val continued: Boolean,
    )

    /** One round's worth of limits. Mirrors the planner's ceiling rather than inventing a second one. */
    data class Budget(val round: Int, val searchesUsed: Int, val searchBudget: Int, val maxRounds: Int = ResearchPlanner.MAX_ROUNDS)

    private const val MAX_TEXT_PER_SOURCE = 4_000

    /**
     * Assemble the brief. [followUps] come only from [ResearchPlanner.gapQueries], which derives them
     * from question terms with no support in what was read - never from repeating a search that
     * already returned results this task.
     */
    fun build(
        question: String,
        evidence: List<Evidence>,
        recovery: List<RecoveryNote> = emptyList(),
        budget: Budget = Budget(0, 0, 6),
        sufficient: Boolean = false,
    ): Brief {
        val bounded = evidence.take(24).map { it.copy(text = it.text.take(MAX_TEXT_PER_SOURCE)) }
        val scan = ContradictionScan.report(bounded.map { it.sourceId to (it.title + ". " + it.text) })

        val hosts = bounded.map { UrlTools.domain(it.url) }.filter { it.isNotBlank() }.distinct()
        val reachedSufficiency = sufficient || hosts.size >= 2 && scan.disputed.isEmpty() && scan.confirmed.isNotEmpty()
        val gaps = ResearchPlanner.gapQueries(question, bounded.map { ResearchPlanner.Evidence(it.url, it.text) })
        val canContinue = !reachedSufficiency && gaps.isNotEmpty() &&
            ResearchPlanner.allowAnotherRound(budget.round, budget.searchesUsed, budget.searchBudget) &&
            budget.maxRounds > budget.round

        val stop = when {
            reachedSufficiency -> StopReason.SUFFICIENT
            canContinue -> StopReason.PARTIAL
            budget.round >= budget.maxRounds -> StopReason.MAX_ROUNDS
            budget.searchesUsed >= budget.searchBudget -> StopReason.BUDGET_EXHAUSTED
            recovery.any { !it.retriable } -> StopReason.FAILURES
            else -> StopReason.PARTIAL
        }

        // Follow-ups only exist when another round is genuinely allowed; otherwise the brief says
        // what is missing and stops, rather than inviting a search that cannot run.
        val usable = if (canContinue) gaps else emptyList()

        val unresolved = buildList {
            addAll(gaps)
            if (scan.disputed.isNotEmpty()) add("sources disagree on " + scan.disputed.joinToString(", ") { it.subject })
            recovery.forEach { add(it.kind.phrase + ": " + it.subject) }
        }.distinct().take(8)

        val brief = Brief(
            question = question,
            corroborated = scan.confirmed,
            singleSource = scan.unconfirmed,
            disputed = scan.disputed,
            unresolved = unresolved,
            followUps = usable,
            stop = stop,
            recovery = recovery.take(8),
            render = "",
            continued = usable.isNotEmpty(),
        )
        return brief.copy(render = render(brief, hosts.size))
    }

    private fun render(b: Brief, hostCount: Int): String {
        val sb = StringBuilder()
        sb.append("Research brief for: ").append(b.question.take(200)).append('\n')
        if (b.disputed.isNotEmpty()) {
            sb.append("Disputed (both sides are real sources; present both, choose neither):\n")
            b.disputed.take(4).forEach { d ->
                sb.append("  ").append(d.subject).append('\n')
                d.claims.groupBy { it.value }.forEach { (value, group) ->
                    val ids = group.map { it.sourceId }.distinct().joinToString(" ")
                    sb.append("    ").append(value).append(" ").append(ids).append('\n')
                }
            }
        }
        if (b.corroborated.isNotEmpty()) {
            sb.append("Corroborated (cite every source you rely on): ")
            sb.append(b.corroborated.take(6).joinToString("; ") { it.subject + " = " + it.value + " " + it.sourceId })
            sb.append('\n')
        }
        if (b.singleSource.isNotEmpty()) {
            sb.append("Single-source (say it is unconfirmed, not settled): ")
            sb.append(b.singleSource.take(6).joinToString("; ") { it.subject + " = " + it.value + " " + it.sourceId })
            sb.append('\n')
        }
        if (hostCount == 0) {
            sb.append("Nothing was read, so no part of the answer is sourced.\n")
        } else if (hostCount == 1) {
            sb.append("Only one host was read: this is a single account, not confirmation.\n")
        }
        if (b.recovery.isNotEmpty()) {
            sb.append("What did not work: ")
            sb.append(b.recovery.joinToString("; ") { it.kind.phrase + " (" + it.subject.take(60) + ")" })
            sb.append('\n')
        }
        if (b.unresolved.isNotEmpty()) {
            sb.append("Still unresolved: ").append(b.unresolved.take(5).joinToString("; ")).append('\n')
        }
        sb.append("Status: ").append(b.stop.phrase)
        if (b.stop != StopReason.SUFFICIENT) {
            sb.append(". Evidence above is partial: state what is missing instead of presenting a " +
                "confident answer, and do not describe the research as complete.")
        }
        if (b.followUps.isNotEmpty()) {
            sb.append("\nNext searches worth running: ").append(b.followUps.joinToString(" | "))
        }
        return sb.toString()
    }

    /** One user-facing line, used when a run ends without an answer. */
    fun statusLine(brief: Brief): String = brief.stop.phrase + when {
        brief.disputed.isNotEmpty() -> " with " + brief.disputed.size + " disputed claim(s)"
        brief.unresolved.isNotEmpty() -> ", " + brief.unresolved.size + " unresolved point(s)"
        else -> ""
    }
}
