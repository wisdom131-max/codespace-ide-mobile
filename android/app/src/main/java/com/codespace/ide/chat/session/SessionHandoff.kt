package com.codespace.ide.chat.session

import com.codespace.ide.agent.context.ContextDigest

/**
 * P6 - CONTINUING A CONVERSATION IN ANOTHER SESSION.
 *
 * A long conversation eventually costs more to carry than to summarise. The new session gets a
 * HANDOFF, not a transcript: the decisions already made, the constraints that must not be broken, how
 * far the work got, which files and sources are involved, and what is still open. The old session is
 * untouched and remains the record.
 *
 * Two hard rules, both enforced here rather than promised:
 *  - **no credentials.** Any line carrying a key, token, password or secret is dropped, not masked,
 *    because a masked line still tells the reader where to look;
 *  - **no inherited approval.** Approvals are per-conversation by design; the handoff says so, so a
 *    new session cannot inherit a grant by being a continuation.
 *
 * Pure and bounded: text in, text out. It never reads storage and never touches the network.
 */
object SessionHandoff {

    data class Turn(val role: String, val text: String)

    data class Handoff(
        val label: String,
        val decisions: List<String>,
        val constraints: List<String>,
        val progress: List<String>,
        val files: List<String>,
        val sources: List<String>,
        val open: List<String>,
        /** Commit hashes named in the conversation (Item 35: a resume needs the exact tree). */
        val commits: List<String> = emptyList(),
        /** Test and CI statements, kept verbatim because a rounded number is a wrong number. */
        val evidence: List<String> = emptyList(),
        /** What is known not to work, so the new session does not "fix" a deferral. */
        val limitations: List<String> = emptyList(),
        /** Origin of the chain, and this session's position in it ("Session 2 from Git"). */
        val originLabel: String = "",
        val sessionNumber: Int = 1,
    ) {
        /** The first message of the linked session. Readable, and honest about what it is. */
        fun render(): String = buildString {
            append("Continuing from an earlier conversation (\"").append(label).append("\").\n")
            append("This is a handoff, not the transcript: only what still matters is carried over.\n")
            section("Decisions already made", decisions)
            section("Constraints that still apply", constraints)
            section("How far the work got", progress)
            section("Commits referenced", commits)
            section("Test and CI evidence", evidence)
            section("Files involved", files)
            section("Sources cited", sources)
            section("Still open", open)
            section("Known limitations", limitations)
            if (sessionNumber > 1 && originLabel.isNotBlank()) {
                append("\nThis is session ").append(sessionNumber).append(" of a chain that started in \"")
                append(originLabel).append("\". The earlier session remains the record.\n")
            }
            append("\nApprovals are NOT carried over: any gated action in this session needs its own ")
            append("approval. Credentials are never carried over either.")
        }

        private fun StringBuilder.section(title: String, items: List<String>) {
            if (items.isEmpty()) return
            append("\n").append(title).append(":\n")
            items.forEach { append("- ").append(it).append("\n") }
        }

        fun isEmpty(): Boolean =
            decisions.isEmpty() && constraints.isEmpty() && progress.isEmpty() &&
                files.isEmpty() && sources.isEmpty() && open.isEmpty() &&
                commits.isEmpty() && evidence.isEmpty() && limitations.isEmpty()
    }

    const val MAX_ITEMS = 8
    const val MAX_ITEM_CHARS = 240

    /**
     * A secret VALUE, not the word "token". "Never log the token" is a constraint worth carrying;
     * "token: ghp_..." is a credential that must not enter a handoff. The patterns below are shaped
     * accordingly: an assignment with a value, a known provider prefix, or a private-key block.
     */
    private val SECRET = Regex(
        "(?i)(api[_-]?key|token|secret|password|passwd|bearer)\\s*[:=]\\s*\\S+" +
            "|ghp_[a-z0-9]{8,}|glpat-[a-z0-9_-]{8,}|sk-[a-z0-9]{8,}|AKIA[0-9A-Z]{16}" +
            "|-----BEGIN [A-Z ]*PRIVATE KEY-----",
    )
    private val DECISION = Regex("(?i)\\b(we|i)('| a)?( decided| will use| chose| settled on| agreed to)\\b|^decision:")
    private val CONSTRAINT = Regex("(?i)\\b(must not|must never|never|do not|don't|cannot|can't|only if|required to)\\b")
    private val PROGRESS = Regex("(?i)\\b(done|finished|completed|landed|committed|shipped|green|passed|remaining|next step)\\b")
    private val FILE = Regex("(?:[A-Za-z0-9_.-]+/)+[A-Za-z0-9_.-]+\\.[A-Za-z0-9]{1,6}\\b")
    private val SOURCE = Regex("\\[S\\d+]|https?://[^\\s)]+")
    private val OPEN = Regex("(?i)\\b(todo|open question|unresolved|still need|blocked on|pending)\\b")

    /** True when a line may never enter a handoff. Checked before anything else. */
    fun isSecret(text: String): Boolean = SECRET.containsMatchIn(text)

    fun labelFor(turns: List<Turn>): String {
        val first = turns.firstOrNull { it.role == "user" && it.text.isNotBlank() }?.text?.trim().orEmpty()
        return when {
            first.isEmpty() -> "continued conversation"
            first.length <= 40 -> first
            else -> first.take(40).trimEnd() + "\u2026"
        }
    }

    /**
     * "Session 2 from Git": the number says where this sits in the chain, the topic says what the
     * chain is about. A chain of sessions all called "Fix the bug" is not a lineage, it is noise.
     */
    fun numberedLabel(sessionNumber: Int, originLabel: String): String {
        val n = if (sessionNumber < 1) 1 else sessionNumber
        val topic = originLabel.trim().ifBlank { "an earlier conversation" }
        return "Session " + n + " from " + topic
    }

    fun build(
        turns: List<Turn>,
        label: String? = null,
        sessionNumber: Int = 1,
        originLabel: String = "",
    ): Handoff {
        val safe = turns.filter { !isSecret(it.text) }
        fun pick(re: Regex): List<String> = safe
            .flatMap { it.text.split('\n') }
            .filter { re.containsMatchIn(it) }
            .map { it.trim().take(MAX_ITEM_CHARS) }
            .distinct()
            .take(MAX_ITEMS)
        val files = safe.flatMap { FILE.findAll(it.text).map { m -> m.value } }
            .distinct().take(MAX_ITEMS)
        val sources = safe.flatMap { SOURCE.findAll(it.text).map { m -> m.value } }
            .distinct().take(MAX_ITEMS)
        val digest = ContextDigest.digest(safe.map { ContextDigest.Turn(it.role, it.text) }, maxChars = 4_000)
        return Handoff(
            label = label ?: labelFor(turns),
            decisions = pick(DECISION),
            constraints = pick(CONSTRAINT),
            // Progress comes from the assistant's own statements, which are the only place a claim
            // about "done" ever appears.
            progress = safe.filter { it.role == "assistant" }.flatMap { it.text.split('\n') }
                .filter { PROGRESS.containsMatchIn(it) && !CONSTRAINT.containsMatchIn(it) }
                .map { it.trim().take(MAX_ITEM_CHARS) }.distinct().take(MAX_ITEMS),
            files = files,
            sources = sources,
            open = pick(OPEN),
            // Delegated to ContextDigest rather than a second set of patterns: one extraction rule,
            // one place to fix it. Only the assistant's own lines count as evidence - a user
            // quoting "5 tests" is not evidence of 5 tests.
            commits = digest.of(ContextDigest.Kind.EVIDENCE)
                .filter { it.provenance == ContextDigest.Provenance.MODEL_STATED && it.text.startsWith("commit ") }
                .map { it.text.removePrefix("commit ") }.distinct().take(MAX_ITEMS).toList(),
            evidence = digest.of(ContextDigest.Kind.EVIDENCE)
                .filter { it.provenance == ContextDigest.Provenance.MODEL_STATED }
                .map { it.text }.distinct().take(MAX_ITEMS).toList(),
            limitations = digest.of(ContextDigest.Kind.LIMITATION)
                .map { it.text }.distinct().take(MAX_ITEMS).toList(),
            originLabel = originLabel,
            sessionNumber = if (sessionNumber < 1) 1 else sessionNumber,
        )
    }

    /** What happened when a continuation was requested, for an honest notice. */
    fun describe(h: Handoff): String = when {
        h.isEmpty() -> "Nothing durable to carry over yet; start the new session with the question itself."
        else -> "Continuing in a new session with " +
            (h.decisions.size + h.constraints.size + h.files.size + h.open.size) +
            " carried point(s). Approvals start fresh there."
    }
}
