package com.codespace.ide.agent.research

/**
 * PROJECT FOUR, STAGE 4.7 (2026-11-16): a claim is only as good as the source behind it.
 *
 * [ContradictionScan] already notices that two claims disagree. What it could not say was whether a
 * claim is actually in the source it cites, whether two "sources" are the same article republished,
 * or whether a claim has no source at all. This adds that reading without replacing the scan: it takes
 * the claims the scan produced and the ledger of sources that were really fetched, and judges each
 * claim against them.
 *
 * Never invents a source, a quotation or a corroboration. A claim whose quoted text is not in its
 * source is unsupported, not confirmed; two copies of one article are one source, not two.
 */
object ClaimReconciliation {

    enum class Support {
        /** Quoted text is in its source, and no independent source disagrees. */
        SUPPORTED,
        /** Two or more genuinely independent sources say the same thing. */
        INDEPENDENTLY_SUPPORTED,
        /** Sources disagree with each other. No winner is chosen. */
        CONFLICTING,
        /** The cited source does not contain the quoted text. */
        UNSUPPORTED,
        /** The claim cites nothing, or cites a source that was never fetched. */
        UNVERIFIED,
        /** There is not enough material to judge anything. */
        INSUFFICIENT,
    }

    data class Verdict(
        val subject: String,
        val value: String,
        val sourceId: String,
        val support: Support,
        val independentSources: List<String>,
        val duplicates: List<String>,
        val note: String,
    ) {
        val confirmed: Boolean
            get() = support == Support.SUPPORTED || support == Support.INDEPENDENTLY_SUPPORTED
    }

    data class Report(val verdicts: List<Verdict>, val notes: List<String>) {
        val confirmed: List<Verdict> get() = verdicts.filter { it.confirmed }
        val disputed: List<Verdict> get() = verdicts.filter { it.support == Support.CONFLICTING }
    }

    fun reconcile(
        claims: List<ContradictionScan.Claim>,
        sources: List<SourceLedger.Source>,
    ): Report {
        if (sources.isEmpty()) {
            return Report(
                claims.map {
                    Verdict(it.subject, it.value, it.sourceId, Support.INSUFFICIENT, emptyList(), emptyList(),
                        "no sources were fetched, so nothing can be judged")
                },
                listOf("nothing was fetched, so no claim is confirmed"),
            )
        }
        val byId = sources.associateBy { it.id }
        val identity = identityOf(sources)
        val notes = ArrayList<String>()

        val verdicts = claims.map { claim ->
            val source = byId[claim.sourceId]
            if (source == null) {
                return@map Verdict(
                    claim.subject, claim.value, claim.sourceId, Support.UNVERIFIED, emptyList(), emptyList(),
                    if (claim.sourceId.isBlank()) "the claim cites no source" else "the cited source was never fetched",
                )
            }
            val quote = claim.quote.trim()
            val body = (source.excerpt + " " + source.title).lowercase()
            if (quote.isNotEmpty() && quote.length >= 12 && !body.contains(quote.lowercase().take(80))) {
                return@map Verdict(
                    claim.subject, claim.value, claim.sourceId, Support.UNSUPPORTED, emptyList(), emptyList(),
                    "the quoted text does not appear in " + (source.domain.ifBlank { source.url }),
                )
            }
            // Sources that share an identity are one source: a syndicated copy is not corroboration.
            val key = keyOf(claim.subject)
            val sameKey = claims.filter { keyOf(it.subject) == key }
            // The claim's own source counts once; others count only when they are a different
            // publication, so a syndicated copy cannot look like a second witness.
            val independent = sameKey.mapNotNull { c -> byId[c.sourceId] }
                .distinctBy { it.id }
                .filter { it.id == source.id || independentOf(it, source, identity) }
                .map { it.id }
            val disagreeing = sameKey.filter { other ->
                keyOf(other.value) != keyOf(claim.value) && other.sourceId in independent
            }
            when {
                disagreeing.isNotEmpty() -> Verdict(
                    claim.subject, claim.value, claim.sourceId, Support.CONFLICTING, independent, emptyList(),
                    "sources disagree on " + claim.subject + "; none is being chosen",
                )
                independent.size >= 2 -> Verdict(
                    claim.subject, claim.value, claim.sourceId, Support.INDEPENDENTLY_SUPPORTED,
                    independent, emptyList(),
                    independent.size.toString() + " independent sources agree",
                )
                else -> Verdict(
                    claim.subject, claim.value, claim.sourceId, Support.SUPPORTED, listOf(claim.sourceId), emptyList(),
                    "one source supports this; it is not corroborated",
                )
            }
        }

        val collapsed = identity.values.groupBy { it }.filterValues { it.size > 1 }
        if (collapsed.isNotEmpty()) {
            notes.add(collapsed.size.toString() + " source(s) share an identity with another and were not counted twice")
        }
        if (verdicts.none { it.confirmed } && verdicts.isNotEmpty()) {
            notes.add("no claim is supported by its own source")
        }
        return Report(verdicts, notes)
    }

    /** What makes two sources the same publication, not two witnesses. */
    private fun identityOf(sources: List<SourceLedger.Source>): Map<String, String> =
        sources.associate { it.id to UrlTools.normalize(it.url).ifBlank { it.domain.ifBlank { it.id } } }

    private fun independentOf(
        other: SourceLedger.Source,
        reference: SourceLedger.Source,
        identity: Map<String, String>,
    ): Boolean {
        if (other.id == reference.id) return false
        val a = identity[other.id]
        val b = identity[reference.id]
        return a == null || b == null || a != b
    }

    private fun keyOf(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(" ").filter { it.length > 2 }.joinToString(" ")

    fun render(report: Report): String {
        if (report.verdicts.isEmpty()) return ""
        val lines = report.verdicts.map { v ->
            "- " + v.subject + ": " + v.support.name.lowercase().replace('_', ' ') + " (" + v.note + ")"
        }
        return lines.joinToString("\n")
    }
}
