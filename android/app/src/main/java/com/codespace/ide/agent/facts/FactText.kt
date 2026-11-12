package com.codespace.ide.agent.facts

/**
 * FACTS — "is this the same fact, a correction, or an argument?" without embeddings.
 *
 * The owner's constraint is explicit: no vector store and no embedding model for this. So the
 * comparison is a deterministic lexical one, chosen because it is inspectable: every decision can
 * be explained by pointing at the token sets that produced it, and every threshold is asserted by
 * a test instead of being tuned by feel.
 *
 * Three measurements per pair:
 *  - Jaccard: symmetric overlap, low when one statement is long and the other short.
 *  - Containment: overlap over the SMALLER side, which is what "the short one is saying the same
 *    thing" looks like ("uses Gradle" vs "this project uses Gradle 8 with Kotlin DSL").
 *  - Polarity: negation markers, so "uses tabs" and "does not use tabs" are not "the same fact".
 *
 * Kotlin only, no Android, no IO: the ranker and the store both call this.
 */
object FactText {

    private val NEGATIONS = setOf(
        "not", "no", "never", "dont", "don't", "doesnt", "doesn't", "cannot", "cant", "can't",
        "avoid", "without", "instead", "disallow", "forbidden", "prohibited", "never",
    )

    /** Words that carry no meaning for matching. Kept short on purpose: over-filtering hides facts. */
    private val STOPWORDS = setOf(
        "the", "a", "an", "is", "are", "was", "were", "be", "been", "being", "to", "of", "in", "on",
        "for", "with", "and", "or", "but", "it", "its", "this", "that", "these", "those", "i", "we",
        "you", "my", "our", "your", "as", "at", "by", "from", "so", "if", "then", "than", "also",
        "should", "would", "could", "must", "will", "can", "may", "do", "does", "did", "have", "has",
        "had", "use", "uses", "using", "used", "always", "prefer", "prefers", "please", "want",
    )

    /** Lowercased alphanumeric tokens with stopwords removed, in a stable order. */
    fun tokens(text: String): List<String> {
        val out = ArrayList<String>(16)
        var i = 0
        val lower = text.lowercase()
        val sb = StringBuilder()
        while (i <= lower.length) {
            val c = if (i < lower.length) lower[i] else ' '
            if (c.isLetterOrDigit() || c == '_' || c == '-' || c == '.') {
                sb.append(c)
            } else if (sb.isNotEmpty()) {
                val tok = sb.toString().trim('.', '-', '_')
                // Single CHARACTERS are noise ("a", "x"), but single DIGITS are not: "Gradle 8" and
                // "Gradle 9" are different facts, and dropping the digit made them identical.
                val meaningful = tok.length > 1 || (tok.length == 1 && tok[0].isDigit())
                if (meaningful && tok !in STOPWORDS) out.add(tok)
                sb.setLength(0)
            }
            i++
        }
        return out
    }

    fun tokenSet(text: String): Set<String> = tokens(text).toSet()

    /** 0.0 = nothing in common, 1.0 = the same token set. */
    fun similarity(a: String, b: String): Double {
        val ta = tokenSet(a)
        val tb = tokenSet(b)
        if (ta.isEmpty() || tb.isEmpty()) {
            // "2026" vs "2026" - one-token statements still compare, but only exactly.
            return if (a.trim().equals(b.trim(), ignoreCase = true)) 1.0 else 0.0
        }
        val inter = ta.count { it in tb }.toDouble()
        if (inter == 0.0) return 0.0
        val jaccard = inter / (ta.size + tb.size - inter)
        val containment = inter / minOf(ta.size, tb.size).toDouble()
        // Containment carries more weight than Jaccard, and the reason is a safety one: when one
        // statement's tokens are fully inside the other's, collapsing them loses no keyword (the
        // survivor still says everything the other said), so a missed merge costs more than a
        // slightly eager one. Jaccard still breaks the case where neither side is contained.
        return 0.35 * jaccard + 0.65 * containment
    }

    /** True when the statement carries an explicit negation. */
    fun isNegative(text: String): Boolean {
        val t = text.lowercase().replace("’", "'")
        for (w in NEGATIONS) {
            // Word-ish boundary check without regex compile per call on the hot path.
            var idx = t.indexOf(w)
            while (idx >= 0) {
                val before = if (idx == 0) ' ' else t[idx - 1]
                val after = if (idx + w.length >= t.length) ' ' else t[idx + w.length]
                val okBefore = !before.isLetterOrDigit()
                val okAfter = !after.isLetterOrDigit()
                if (okBefore && okAfter) return true
                idx = t.indexOf(w, idx + 1)
            }
        }
        return false
    }

    /** Enough shared meaning to be about the same subject, less than "the same fact". */
    const val RELATED = 0.50
    /** At or above this the second statement is treated as the same fact, newer. */
    const val SAME = 0.82

    fun related(a: String, b: String): Boolean = similarity(a, b) >= RELATED

    /** Same subject, opposite polarity, and at least one shared meaningful token. */
    fun contradicts(a: String, b: String): Boolean {
        if (isNegative(a) == isNegative(b)) return false
        if (!related(a, b)) return false
        return tokenSet(a).any { it in tokenSet(b) }
    }
}

/** What the store should do with a candidate fact. */
enum class FactDecision { NEW, DUPLICATE, CORRECTION, CONFLICT }

data class FactMatch(
    val decision: FactDecision,
    /** The existing fact this candidate matches, if any. */
    val targetId: String?,
    val score: Double,
)

/**
 * The save policy, pure and testable.
 *
 * Scope isolation is enforced HERE, before scoring: a user fact is only ever compared with other
 * user facts, and a project fact only with facts of the SAME project. That is what stops a fact
 * learned in one repository from silently rewriting a fact in another.
 */
object FactDedupe {

    fun classify(candidateContent: String, candidateScope: FactScope, candidateProjectId: String?,
                  existing: List<Fact>): FactMatch {
        var best: Fact? = null
        var bestScore = 0.0
        var bestConflict = false
        for (f in existing) {
            if (f.isRetrievable() == false) continue
            if (f.scope != candidateScope) continue
            if (candidateScope == FactScope.PROJECT && f.projectId != candidateProjectId) continue
            val score = FactText.similarity(candidateContent, f.content)
            val conflict = FactText.contradicts(candidateContent, f.content)
            // A contradiction wins over a plain similarity: it is the case a user must see.
            val rank = if (conflict) score + 1.0 else score
            if (rank > bestScore) {
                bestScore = rank
                best = f
                bestConflict = conflict
            }
        }
        val target = best ?: return FactMatch(FactDecision.NEW, null, 0.0)
        val raw = if (bestConflict) bestScore - 1.0 else bestScore
        val same = raw >= FactText.SAME
        val identical = normalizeForCompare(candidateContent) == normalizeForCompare(target.content)
        return when {
            bestConflict -> FactMatch(FactDecision.CONFLICT, target.id, raw)
            same && identical -> FactMatch(FactDecision.DUPLICATE, target.id, raw)
            same -> FactMatch(FactDecision.CORRECTION, target.id, raw)
            else -> FactMatch(FactDecision.NEW, null, raw)
        }
    }

    /** Whitespace and punctuation do not make two statements different facts. */
    fun normalizeForCompare(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}
