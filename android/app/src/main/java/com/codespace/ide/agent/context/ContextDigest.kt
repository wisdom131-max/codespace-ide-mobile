package com.codespace.ide.agent.context

/**
 * Item 35 - context compression that keeps the things a long task cannot afford to lose.
 *
 * The failure this addresses is not "the summary was too short". It is "the summary was long,
 * fluent, and wrong": a model-written digest that invents a commit hash, rounds a failing test
 * suite into "tests pass", or drops the one constraint the user stated in the third message.
 *
 * So nothing here writes prose. Every line is *extracted from a turn that exists*, and it carries
 * where it came from. If the conversation contains no commit hash, the digest has no commit
 * section - it does not have an empty one, and it certainly does not have a plausible one.
 *
 * Three properties make it usable on a phone with little memory:
 *  - single pass, no regex over the whole transcript per category (each turn is scanned once),
 *  - bounded input (the caller passes the turns it already has in memory) and bounded output
 *    ([maxChars]), so a 400-turn conversation cannot produce a 200 KB summary,
 *  - deterministic: same turns, same digest, so a diff of two digests shows what actually changed.
 */
object ContextDigest {

    /** Where a line came from. The distinction the user is entitled to see. */
    enum class Provenance {
        /** The user said it. Authoritative. */
        USER_CONFIRMED,

        /** The assistant said it. May be a mistake, a plan, or a hallucination. */
        MODEL_STATED,
    }

    /** What a line is for. Ordering decides what survives a tight budget. */
    enum class Kind(val priority: Int) {
        OBJECTIVE(0),
        CONSTRAINT(1),
        EVIDENCE(2),
        DECISION(3),
        FILE(4),
        COMPLETED(5),
        OUTSTANDING(6),
        LIMITATION(7),
    }

    data class Item(
        val kind: Kind,
        val text: String,
        val provenance: Provenance,
        val turnIndex: Int,
    )

    data class Digest(
        val items: List<Item>,
        /** How much of the input was considered, so a caller knows the digest is not the whole story. */
        val turnsConsidered: Int,
        val turnsOmitted: Int,
        val chars: Int,
    ) {
        fun of(kind: Kind): List<Item> = items.filter { it.kind == kind }

        fun render(): String = buildString {
            if (items.isEmpty()) return@buildString
            for (kind in Kind.entries) {
                val group = of(kind)
                if (group.isEmpty()) continue
                if (isNotEmpty()) append('\n')
                append(kindTitle(kind)).append(':')
                for (item in group) {
                    append("\n- ").append(item.text)
                    // A model-stated line is labelled, never presented as fact.
                    if (item.provenance == Provenance.MODEL_STATED) append(" (assistant, unconfirmed)")
                }
            }
            if (turnsOmitted > 0) append("\n(").append(turnsOmitted).append(" earlier turns summarised)")
        }

        private fun kindTitle(kind: Kind): String = when (kind) {
            Kind.OBJECTIVE -> "Objective"
            Kind.CONSTRAINT -> "Constraints"
            Kind.EVIDENCE -> "Evidence"
            Kind.DECISION -> "Decisions"
            Kind.FILE -> "Files"
            Kind.COMPLETED -> "Done"
            Kind.OUTSTANDING -> "Outstanding"
            Kind.LIMITATION -> "Limitations"
        }
    }

    // ── extraction patterns ──────────────────────────────────────────────────────────────────
    // Deliberately narrow. A pattern that fires on prose produces a summary that reads well and
    // says nothing true.

    /** A git hash in backticks or standing alone, never a hex-looking word inside a sentence. */
    private val COMMIT = Regex("`((?:[0-9a-f]{7,40}))`|\\bcommit ([0-9a-f]{7,40})\\b")
    private val CI_RUN = Regex("\\b(?:run|CI|workflow)\\s+#?(\\d{8,12})\\b")
    private val TESTS = Regex("\\b(\\d{2,5}) tests?\\b")
    private val FILE = Regex("`((?:[A-Za-z0-9_.-]+/)+[A-Za-z0-9_.-]+\\.[A-Za-z0-9]{1,6})`")
    private val CONSTRAINT = Regex(
        "\\b(?:must not|must never|never|do not|don't|without|only|always|require[sd]?)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val DECISION = Regex(
        "\\b(?:decided|decision|instead of|rather than|we will|we'll|use[sd]?|chose|chosen)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val DONE = Regex("\\b(?:implemented|fixed|landed|shipped|completed|done|merged|green)\\b", RegexOption.IGNORE_CASE)
    private val OUTSTANDING = Regex("\\b(?:remaining|next|todo|outstanding|still need|pending|follow-up)\\b", RegexOption.IGNORE_CASE)
    private val LIMIT = Regex("\\b(?:limitation|not supported|unsupported|deferred|unverified|not verified|cannot)\\b", RegexOption.IGNORE_CASE)
    private val OBJECTIVE = Regex("\\b(?:objective|goal|task|implement|build|add support)\\b", RegexOption.IGNORE_CASE)

    private const val MAX_LINE = 240
    private const val MAX_ITEMS_PER_KIND = 8

    /**
     * Build a digest.
     *
     * @param turns oldest first, as stored. [Turn.role] "user" or anything else (assistant).
     * @param maxChars hard ceiling on the rendered digest. Items are dropped lowest priority
     *   first, and a partial line is never emitted: a truncated sentence is worse than an absent
     *   one, because it reads as a complete claim that says something else.
     */
    fun digest(turns: List<Turn>, maxChars: Int = 2400): Digest {
        val budget = if (maxChars < 120) 120 else maxChars
        val candidates = ArrayList<Item>()
        var considered = 0
        for ((index, turn) in turns.withIndex()) {
            considered++
            val provenance = if (turn.role.equals("user", ignoreCase = true)) Provenance.USER_CONFIRMED else Provenance.MODEL_STATED
            collect(turn.text, index, provenance, candidates)
        }

        // A later statement of the same thing supersedes an earlier one, and a user statement
        // supersedes a model paraphrase of it. This is the one place where "newer wins" is
        // applied, and it is applied to the same *line*, not to the whole transcript.
        val deduped = dedupe(candidates)
        val ordered = deduped.sortedWith(compareBy({ it.kind.priority }, { it.turnIndex }))
        val capped = ArrayList<Item>()
        for (kind in Kind.entries) {
            val group = ordered.filter { it.kind == kind }
            // Keep the LATEST few of each kind: recent evidence is what a resume needs.
            capped += group.takeLast(MAX_ITEMS_PER_KIND)
        }

        var chars = 0
        val kept = ArrayList<Item>()
        for (item in capped.sortedBy { it.kind.priority }) {
            val cost = item.text.length + 4
            if (chars + cost > budget) continue
            kept += item
            chars += cost
        }
        return Digest(
            items = kept.sortedWith(compareBy({ it.kind.priority }, { it.turnIndex })),
            turnsConsidered = considered,
            turnsOmitted = 0,
            chars = chars,
        )
    }

    private fun collect(text: String, index: Int, provenance: Provenance, out: MutableList<Item>) {
        if (text.isBlank()) return
        val clipped = if (text.length > MAX_LINE * 4) text.take(MAX_LINE * 4) else text

        // Evidence is matched first and wins: a line carrying a hash is evidence, not chatter,
        // even when it also contains the word "implemented".
        val evidence = ArrayList<String>()
        COMMIT.findAll(clipped).forEach { evidence += "commit " + it.groupValues[1].ifBlank { it.groupValues[2] } }
        CI_RUN.findAll(clipped).forEach { evidence += "CI run " + it.groupValues[1] }
        TESTS.findAll(clipped).forEach { evidence += it.groupValues[1] + " tests" }
        // One item per fact, not one joined line: a consumer that wants only the hashes (the
        // handoff does) must not have to parse them back out of a sentence it just built.
        for (fact in evidence.distinct()) out += Item(Kind.EVIDENCE, fact, provenance, index)

        FILE.findAll(clipped).map { it.groupValues[1] }.distinct().take(3).forEach {
            out += Item(Kind.FILE, it, provenance, index)
        }

        val firstSentence = firstSentence(clipped)
        if (firstSentence.isBlank()) return
        when {
            CONSTRAINT.containsMatchIn(clipped) -> out += Item(Kind.CONSTRAINT, firstSentence, provenance, index)
            LIMIT.containsMatchIn(clipped) -> out += Item(Kind.LIMITATION, firstSentence, provenance, index)
            OUTSTANDING.containsMatchIn(clipped) -> out += Item(Kind.OUTSTANDING, firstSentence, provenance, index)
            DONE.containsMatchIn(clipped) -> out += Item(Kind.COMPLETED, firstSentence, provenance, index)
            DECISION.containsMatchIn(clipped) -> out += Item(Kind.DECISION, firstSentence, provenance, index)
            OBJECTIVE.containsMatchIn(clipped) && out.none { it.kind == Kind.OBJECTIVE } ->
                out += Item(Kind.OBJECTIVE, firstSentence, provenance, index)
        }
    }

    private fun dedupe(items: List<Item>): List<Item> {
        // Keyed on the normalised line, so "Fixed the bug." from the model and "fixed the bug"
        // from the user collapse into one item, and the user's version is the one kept.
        val byKey = LinkedHashMap<String, Item>()
        for (item in items) {
            val key = item.kind.name + "|" + item.text.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
            val existing = byKey[key]
            byKey[key] = when {
                existing == null -> item
                existing.provenance == Provenance.USER_CONFIRMED -> existing
                else -> item
            }
        }
        return byKey.values.toList()
    }

    private fun firstSentence(text: String): String {
        val trimmed = text.trim()
        val end = trimmed.indexOfFirst { it == '.' || it == '\n' }
        val raw = if (end == -1) trimmed else trimmed.substring(0, end + 1)
        return if (raw.length > MAX_LINE) raw.take(MAX_LINE - 1) + "\u2026" else raw.trim()
    }

    data class Turn(val role: String, val text: String)

    // ── retrieval ───────────────────────────────────────────────────────────────────────────

    /**
     * The one thing a resumed session actually needs: "what did we say about X".
     *
     * Ranking is term overlap on the significant words of the query, with three tie-breakers that
     * matter more than the score itself:
     *  - evidence beats prose at equal overlap (a hash is checkable, a sentence is not),
     *  - a user statement beats the model's paraphrase of it,
     *  - a later turn beats an earlier one.
     *
     * Bounded to [limit] results, so a vague query cannot pull the whole transcript into a prompt.
     */
    fun retrieve(digest: Digest, query: String, limit: Int = 5): List<Item> {
        val terms = terms(query)
        if (terms.isEmpty()) return emptyList()
        return digest.items
            .map { item -> item to score(item, terms) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<Item, Int>> { it.second }
                    .thenByDescending { if (it.first.kind == Kind.EVIDENCE) 1 else 0 }
                    .thenByDescending { if (it.first.provenance == Provenance.USER_CONFIRMED) 1 else 0 }
                    .thenByDescending { it.first.turnIndex },
            )
            .take(if (limit < 1) 1 else limit)
            .map { it.first }
    }

    private fun score(item: Item, terms: Set<String>): Int {
        val haystack = terms(item.text + " " + item.kind.name)
        if (haystack.isEmpty()) return 0
        return terms.count { haystack.contains(it) }
    }

    private fun terms(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in STOP }
            .toSet()

    private val STOP = setOf(
        "the", "and", "for", "with", "that", "this", "was", "were", "are", "did", "does", "from",
        "what", "when", "where", "which", "have", "has", "had", "not", "but", "you", "your", "our",
        "about", "into", "then", "than", "them", "they", "there", "here", "all", "any", "can",
    )
}
