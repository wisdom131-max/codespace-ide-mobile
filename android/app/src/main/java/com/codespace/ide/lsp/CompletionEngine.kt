package com.codespace.ide.lsp

/**
 * P41 Phase A — Completion Matching & Ranking Engine
 *
 * Sits between raw LSP/keyword/snippet responses and the CodeEditor dropdown.
 * Merges multiple completion sources into one ranked, deduplicated list.
 *
 * The fuzzy matcher implements subsequence matching with rewards for:
 * - Contiguous character runs (consecutive matches)
 * - Match-at-start (prefix bonus)
 * - camelCase hump matches (gCU matches getCurrentUser)
 * - Word-boundary matches (after _, -, space, or camelCase boundary)
 *
 * rank() combines: fuzzy score (primary) → server sortText (tiebreak) →
 * MRU recency boost → usage frequency boost.
 */

// ── Data Types ─────────────────────────────────────────────────────────────

/** Where a completion item came from — used for UI labeling and scoring. */
enum class CompletionSource {
    LSP,        // Language server (tsserver, pyright, etc.)
    AI,         // AI inline completion source (Phase E)
    SNIPPET,    // Curated snippet
    WORKSPACE,  // workspace/symbol LSP call (Phase F)
    BUFFER,     // Local keyword/type from LanguageSpecs
    PATH,       // Filesystem path completion (Phase G)
}

/**
 * Unified completion item used by CodeEditor's dropdown.
 * Every source (LSP, keyword, snippet, etc.) converts to this type before ranking.
 */
data class RankedCompletionItem(
    val label: String,
    val kind: Int,              // LSP CompletionItemKind (1-25), 0 = unknown
    val detail: String? = null,
    val documentation: String? = null,
    val insertText: String = label,
    val sortTextFromServer: String? = null,  // LSP sortText field
    val source: CompletionSource = CompletionSource.LSP,
    val score: Float = 0f,
    val isDeprecated: Boolean = false,
    val commitCharacters: List<Char> = emptyList(),
    /** LSP additionalTextEdits — for auto-import on accept (Phase D). JSON string, applied by caller. */
    val additionalTextEditsJson: String? = null,
    /** LSP textEdit (range-based replacement). JSON string, applied by caller. */
    val textEditJson: String? = null,
    /** Match indices from fuzzyScore — for highlighting matched chars (Phase C). */
    val matchIndices: List<Int> = emptyList(),
)

// ── Fuzzy Matching ──────────────────────────────────────────────────────────

/**
 * Scores how well [query] matches [candidate] as a subsequence.
 * Returns a Float score (higher = better), or -1f if no subsequence match.
 *
 * Scoring rewards:
 * - Match at start of candidate (prefix bonus)
 * - Consecutive matched characters (contiguous run bonus)
 * - camelCase / word-boundary matches (hump bonus)
 * - Shorter candidates with full match (density bonus)
 *
 * Examples:
 *   fuzzyScore("gCU", "getCurrentUser") → positive (hump match g→get, C→Current, U→User)
 *   fuzzyScore("str", "string") → high (prefix match, contiguous)
 *   fuzzyScore("abc", "xyz") → -1 (no match)
 */
fun fuzzyScore(query: String, candidate: String): Float {
    if (query.isEmpty()) return 0f
    if (candidate.isEmpty()) return -1f

    val q = query.lowercaseChar()
    val qRest = if (query.length > 1) query.substring(1) else ""
    val c = candidate

    // Fast path: exact prefix match
    if (c.lowercase().startsWith(query.lowercase())) {
        return 100f + (1f / c.length) * 50f  // prefix match + shorter-is-better
    }

    // Subsequence match with scoring
    val matchIndices = mutableListOf<Int>()
    var qi = 0
    var bestScore = -1f

    // Try matching starting from each candidate position (greedy first match)
    for (startCi in c.indices) {
        if (c[startCi].lowercaseChar() != query[0].lowercaseChar()) continue
        matchIndices.clear()
        matchIndices.add(startCi)
        qi = 1
        var i = startCi + 1

        while (qi < query.length && i < c.length) {
            if (c[i].lowercaseChar() == query[qi].lowercaseChar()) {
                matchIndices.add(i)
                qi++
            }
            i++
        }

        if (qi == query.length) {
            // Full subsequence match — score it
            val score = scoreMatch(matchIndices, c)
            if (score > bestScore) bestScore = score
        }
    }

    return bestScore
}

/**
 * Scores a match given the indices of matched characters in the candidate.
 * Internal to fuzzyScore.
 */
private fun scoreMatch(indices: List<Int>, candidate: String): Float {
    var score = 0f
    // Base: all chars matched
    score += 30f

    // Prefix bonus: match starts at index 0
    if (indices[0] == 0) score += 25f

    // Contiguous run bonus: reward consecutive indices
    var contiguousRuns = 0
    var maxRun = 1
    var currentRun = 1
    for (k in 1 until indices.size) {
        if (indices[k] == indices[k - 1] + 1) {
            contiguousRuns++
            currentRun++
            if (currentRun > maxRun) maxRun = currentRun
        } else {
            currentRun = 1
        }
    }
    score += contiguousRuns * 8f
    score += maxRun * 5f

    // camelCase hump bonus: matched char is at a word boundary
    var humpMatches = 0
    for (idx in indices) {
        if (idx == 0 || isWordBoundary(candidate, idx)) {
            humpMatches++
        }
    }
    score += humpMatches * 12f

    // Density bonus: shorter candidate = higher signal
    score += (1f / candidate.length) * 30f

    // Penalty: gaps between matches (longer gaps = lower score)
    var totalGap = 0
    for (k in 1 until indices.size) {
        val gap = indices[k] - indices[k - 1] - 1
        totalGap += gap
    }
    score -= totalGap * 2f

    return score
}

/**
 * Returns true if the character at [idx] in [s] is at a word boundary:
 * preceded by _, -, space, or a lowercase letter followed by uppercase (camelCase).
 */
private fun isWordBoundary(s: String, idx: Int): Boolean {
    if (idx == 0) return true
    val prev = s[idx - 1]
    val curr = s[idx]
    // After underscore/dash/space
    if (prev == '_' || prev == '-' || prev == ' ' || prev == '.') return true
    // camelCase boundary: prev is lowercase, curr is uppercase
    if (prev.isLowerCase() && curr.isUpperCase()) return true
    return false
}

/**
 * Returns the match indices for highlighting (Phase C).
 * Returns empty list if no match.
 */
fun fuzzyMatchIndices(query: String, candidate: String): List<Int> {
    if (query.isEmpty() || candidate.isEmpty()) return emptyList()

    // Exact prefix — highlight the prefix
    if (candidate.lowercase().startsWith(query.lowercase())) {
        return (0 until query.length).toList()
    }

    // Subsequence match — find best (first complete match)
    var qi = 0
    val indices = mutableListOf<Int>()
    for (i in candidate.indices) {
        if (qi >= query.length) break
        if (candidate[i].lowercaseChar() == query[qi].lowercaseChar()) {
            indices.add(i)
            qi++
        }
    }
    return if (qi == query.length) indices else emptyList()
}

// ── Ranking Engine ─────────────────────────────────────────────────────────

/**
 * Merges and ranks completion items from multiple sources.
 *
 * @param items Pre-collected RankedCompletionItems (without scores set)
 * @param query The current prefix being typed
 * @param mruMap Label → last-accepted epoch millis (Phase B), empty for now
 * @param usageMap Label → accept count (Phase B), empty for now
 * @return Sorted list of RankedCompletionItems with scores and match indices set, best first
 */
fun rank(
    items: List<RankedCompletionItem>,
    query: String,
    mruMap: Map<String, Long> = emptyMap(),
    usageMap: Map<String, Int> = emptyMap(),
): List<RankedCompletionItem> {
    val now = System.currentTimeMillis()
    val q = query.trim()

    return items.map { item ->
        val fuzzy = fuzzyScore(q, item.label)
        val indices = fuzzyMatchIndices(q, item.label)

        // MRU boost: more recent = higher boost (exponential decay over 7 days)
        val mruBoost = mruMap[item.label]?.let { lastUsed ->
            val ageDays = (now - lastUsed) / (1000L * 60 * 60 * 24)
            if (ageDays < 7) (7f - ageDays) * 3f else 0f
        } ?: 0f

        // Usage frequency boost: log-scaled to prevent power users from skewing
        val usageBoost = usageMap[item.label]?.let { count ->
            kotlin.math.log10((count + 1).toFloat()) * 5f
        } ?: 0f

        // Source penalty: workspace/path results are broader, score slightly lower
        val sourcePenalty = when (item.source) {
            CompletionSource.LSP -> 0f
            CompletionSource.SNIPPET -> 0f
            CompletionSource.BUFFER -> -5f
            CompletionSource.AI -> -10f
            CompletionSource.WORKSPACE -> -15f
            CompletionSource.PATH -> -20f
        }

        // sortText from server (lower string = higher priority in LSP spec)
        val sortTextScore = item.sortTextFromServer?.let { st ->
            // Convert sortText to a penalty: "a" = 0, "z" = 25, etc.
            -(st.firstOrNull()?.code?.minus(97)?.toFloat() ?: 0f)
        } ?: 0f

        val totalScore = fuzzy + mruBoost + usageBoost + sourcePenalty + sortTextScore

        item.copy(score = totalScore, matchIndices = indices)
    }
    .filter { it.score >= 0f || q.isBlank() }
    .sortedByDescending { it.score }
}

// ── Conversion Helpers ─────────────────────────────────────────────────────

/** Convert LspCompletionItem list to RankedCompletionItem list. */
fun lspToRanked(items: List<LspCompletionItem>): List<RankedCompletionItem> {
    return items.map { item ->
        RankedCompletionItem(
            label = item.label,
            kind = item.kind,
            detail = item.detail,
            insertText = item.insertText,
            source = CompletionSource.LSP,
        )
    }
}

/** Convert local keyword/type/snippet completions to RankedCompletionItem. */
fun localToRanked(
    label: String,
    kind: Int,   // LSP CompletionItemKind: 14=Snippet, 6=Method, 1=Text/Keyword
    insertText: String,
    detail: String? = null,
    source: CompletionSource = CompletionSource.BUFFER,
): RankedCompletionItem {
    return RankedCompletionItem(
        label = label,
        kind = kind,
        detail = detail,
        insertText = insertText,
        source = source,
    )
}

/** LSP CompletionItemKind constants for reference. */
object CompletionItemKind {
    const val TEXT = 1
    const val METHOD = 2
    const val FUNCTION = 3
    const val CONSTRUCTOR = 4
    const val FIELD = 5
    const val VARIABLE = 6
    const val CLASS = 7
    const val INTERFACE = 8
    const val MODULE = 9
    const val PROPERTY = 10
    const val UNIT = 11
    const val VALUE = 12
    const val ENUM = 13
    const val KEYWORD = 14
    const val SNIPPET = 15
    const val COLOR = 16
    const val FILE = 17
    const val REFERENCE = 18
    const val FOLDER = 19
    const val ENUM_MEMBER = 20
    const val CONSTANT = 21
    const val STRUCT = 22
    const val EVENT = 23
    const val OPERATOR = 24
    const val TYPE_PARAMETER = 25
}
