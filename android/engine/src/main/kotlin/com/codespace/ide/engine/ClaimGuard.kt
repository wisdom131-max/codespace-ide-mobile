package com.codespace.ide.engine

/**
 * PROJECT EIGHT 8.16 (catalog 80) - "tests passed" is a CLAIM, and a claim needs evidence.
 *
 * The owner's complaint that the agent "does not know what to do" has a sibling: it sometimes
 * reports success it did not observe. A model that says "all tests pass" without a test run in
 * that turn is not lying on purpose - it is completing the pattern - but the user cannot tell the
 * difference, and that is the expensive part.
 *
 * This does not rewrite or block anything. It finds sentences that assert a result and reports
 * which of them have no matching evidence this turn, so the UI can mark them.
 */
object ClaimGuard {

    /** What a claim asserts. Matches [Evidence] ids. */
    enum class Claim(val id: String) { TESTS("tests"), BUILD("build"), FILES("files") }

    /** What actually happened this turn. Nothing is inferred; the caller records it. */
    data class Evidence(val ids: Set<String> = emptySet()) {
        fun covers(claim: Claim): Boolean = claim.id in ids
    }

    data class Unsupported(val claim: Claim, val sentence: String)

    private val SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+|\\n+")

    private val PATTERNS: List<Pair<Claim, Regex>> = listOf(
        Claim.TESTS to Regex(
            "(?i)\\b(tests?|test suite|unit tests?)\\b[^.\\n]{0,40}\\b(pass|passed|passing|green|succeed|succeeded)\\b",
        ),
        Claim.TESTS to Regex("(?i)\\ball (\\d+ )?tests? (pass|passed)\\b"),
        Claim.BUILD to Regex(
            "(?i)\\b(build|compil\\w+|apk)\\b[^.\\n]{0,40}\\b(succeed|succeeded|successful|green|works|working)\\b",
        ),
        Claim.FILES to Regex(
            "(?i)\\b(i|we) (have )?(wrote|written|created|saved|added|applied)\\b[^.\\n]{0,30}\\b(file|files|change|changes|edit|edits)\\b",
        ),
    )

    /**
     * Sentences asserting a result with no evidence behind them, in reply order.
     *
     * [reply] is the assistant's own text. A sentence is reported ONCE even when several patterns
     * match it, because the user reads sentences, not regexes.
     */
    fun unsupported(reply: String, evidence: Evidence): List<Unsupported> {
        if (reply.isBlank()) return emptyList()
        val out = ArrayList<Unsupported>()
        val seen = HashSet<String>()
        for (raw in reply.split(SENTENCE_SPLIT)) {
            val sentence = raw.trim()
            if (sentence.isEmpty() || !seen.add(sentence)) continue
            for ((claim, pattern) in PATTERNS) {
                if (!pattern.containsMatchIn(sentence)) continue
                if (!evidence.covers(claim)) out.add(Unsupported(claim, sentence))
                break
            }
        }
        return out
    }

    /** One line for the log, or "" when every claim was backed. */
    fun describe(found: List<Unsupported>): String =
        if (found.isEmpty()) ""
        else found.joinToString("; ") { it.claim.id + " claimed without evidence" }
}
