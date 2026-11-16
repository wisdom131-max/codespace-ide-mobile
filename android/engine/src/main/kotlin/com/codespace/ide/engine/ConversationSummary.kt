package com.codespace.ide.engine

/**
 * PROJECT SIX, STAGE 6.2 — evidence-based conversation summarisation and bounded retention.
 *
 * The design constraint that shapes everything here: a summary must never be able to invent a fact.
 * So this is NOT an abstractive summariser and it does not call a model to write prose about the
 * conversation. Every line is an EXCERPT of a message or of a tool result that actually exists, with
 * the ids it came from. A reader can therefore check any line against the original, and nothing that
 * was never said can appear in a summary.
 *
 * That trade is deliberate: extracting is weaker than summarising, and it is the only version of this
 * feature that cannot fabricate. What extraction cannot do is compress forty turns into three
 * sentences. It can keep the forty turns bounded, labelled, and attributable.
 *
 * Classification uses LITERAL markers only (a result starting `FAILED`, a line starting `TODO:`), and
 * anything unrecognised is still retained, as [Kind.OTHER]. A label is evidence, never a guess, so an
 * unlabellable message is a message with no label rather than a message with a made-up one.
 */
enum class SummaryKind {
    /** The user said something the work must obey ("must", "never", "do not" as a literal instruction). */
    INSTRUCTION,
    /** A decision was recorded ("decided", "we will", "DECISION:"). */
    DECISION,
    /** A concrete fact: file, commit, test count, path. */
    FACT,
    /** Work that is not finished ("TODO:", "next:", "remaining", "not started"). */
    OPEN_TASK,
    /** Something the conversation said it does not know ("unknown", "unverified", "assume"). */
    UNCERTAINTY,
    /** An operation that failed or was refused. Never presented as success. */
    FAILURE,
    /** A tool call and its outcome, carried as DATA. It is never re-executed. */
    TOOL_RESULT,
    /** Retained because it was said, with no label the evidence supports. */
    OTHER,
}

data class SummaryLine(
    /** Decides `render` order among equals: retention must not shuffle what was said. */
    val seq: Int = 0,
    /** Verbatim excerpt, cut with a stated marker rather than paraphrased. */
    val text: String,
    /** Message ids this line was taken from. Empty is impossible: a line always came from somewhere. */
    val sourceIds: List<String>,
    val role: String,
    val kind: SummaryKind = SummaryKind.OTHER,
    /** PROJECT SIX 6.3A: how sure the conversation was, read from its own wording. */
    val status: ClaimStatus = ClaimStatus.UNKNOWN,
    /** PROJECT SIX 6.3A: original or generated, so restored text can never pass as the user's. */
    val origin: LineOrigin = LineOrigin.MESSAGE,
)

data class ConversationSummary(
    val lines: List<SummaryLine>,
    val coveredIds: List<String>,
    /** Revision, so a reader can tell a refreshed summary from the one they already had. */
    val revision: Int = 1,
) {
    /**
     * The text a provider receives. The header is not decoration: a model that mistakes an excerpt
     * list for the conversation will answer about the summary instead of the work, and a reader who
     * mistakes it for verbatim history will believe a paraphrase was said.
     */
    fun render(maxChars: Int = MAX_RENDER_CHARS): String {
        if (lines.isEmpty()) return ""
        val head = "GENERATED SUMMARY of earlier turns (excerpts, not verbatim; the original session " +
            "remains the record).\n"
        val body = StringBuilder()
        for (line in lines) {
            val sure = when (line.status) {
                ClaimStatus.ASSUMED -> " [assumed]"
                ClaimStatus.UNRESOLVED -> " [unresolved]"
                else -> ""
            }
            val row = "- [" + line.kind.name + "] " + line.text + sure + "\n"
            if (head.length + body.length + row.length > maxChars) {
                body.append("- [").append(SummaryKind.OTHER.name)
                    .append("] further earlier turns omitted from this summary to keep it bounded.\n")
                break
            }
            body.append(row)
        }
        return head + body
    }

    companion object {
        const val MAX_RENDER_CHARS = 6_000
        val EMPTY = ConversationSummary(emptyList(), emptyList())
    }
}

object Summariser {

    /** Per line, so one huge message cannot crowd out nine others. The cut is stated. */
    const val MAX_LINE_CHARS = 240

    /** Lines kept per summary. Oldest lines fall off first when a summary is refreshed. */
    const val MAX_LINES = 24

    private val instructionMarkers = listOf("must ", "never ", "do not ", "don't ", "always ", "required")
    private val decisionMarkers = listOf("decided", "decision:", "we will", "agreed", "chose ", "rule:")
    private val openMarkers = listOf("todo:", "next:", "remaining", "not started", "still open", "unresolved")
    private val uncertaintyMarkers = listOf("unknown", "unverified", "not verified", "assume", "hypothesis")
    private val failureMarkers = listOf("failed", "refused", "not run", "unsure", "error:", "cannot ")

    /**
     * Builds a summary from messages that are ABOUT TO LEAVE the window. Only dropped messages are
     * summarised: summarising what still travels would duplicate it and waste the budget twice.
     */
    fun summarise(
        dropped: List<ChatMessage>,
        exchanges: List<ToolExchange> = emptyList(),
        maxLines: Int = MAX_LINES,
    ): ConversationSummary {
        val lines = ArrayList<SummaryLine>()
        val covered = ArrayList<String>()
        var seq = 0
        for (m in dropped) {
            if (m.text.isBlank()) continue
            covered.add(m.id)
            for (piece in m.text.split("\n")) {
                if (piece.isBlank()) continue
                val kind = classify(piece, m.role)
                lines.add(
                    SummaryLine(
                        seq = seq++,
                        text = excerpt(piece.trim()),
                        sourceIds = listOf(m.id),
                        role = m.role.name.lowercase(),
                        kind = kind,
                        status = ClaimStatus.of(piece),
                        origin = LineOrigin.MESSAGE,
                    )
                )
            }
        }
        // Tool outcomes are carried as DATA with their statuses intact. This is the whole reason a
        // new session cannot silently forget that a write was uncertain or a call was refused.
        for (x in exchanges) {
            lines.add(
                SummaryLine(
                    seq = seq++,
                    text = excerpt(x.name + " -> " + x.status.name + (if (x.complete) "" else " (incomplete)") +
                        ": " + x.output.replace("\n", " ")),
                    sourceIds = listOf(x.callId),
                    role = "tool",
                    kind = if (x.status == ExchangeStatus.OK) SummaryKind.TOOL_RESULT else SummaryKind.FAILURE,
                    // An exchange states its own outcome; a refusal is resolved, an uncertain call is not.
                    status = when (x.status) {
                        ExchangeStatus.OK -> ClaimStatus.CONFIRMED
                        ExchangeStatus.UNCERTAIN -> ClaimStatus.UNRESOLVED
                        else -> ClaimStatus.CONFIRMED
                    },
                    origin = LineOrigin.TOOL_EXCHANGE,
                )
            )
        }
        // Priority: instructions, failures and open work outlive idle chatter when the bound bites.
        val ordered = lines.sortedWith(compareBy({ priority(it.kind) }, { it.seq }))
        val kept = if (ordered.size <= maxLines) ordered else ordered.take(maxLines)
        return ConversationSummary(kept, covered)
    }

    /**
     * Labels come from literal evidence in the text, checked in an order that keeps the safety-relevant
     * ones (failure, refusal, uncertainty) ahead of the descriptive ones. A line with no evidence keeps
     * [SummaryKind.OTHER] rather than being promoted into a claim.
     */
    fun classify(piece: String, role: ChatRole): SummaryKind {
        val t = piece.trim().lowercase()
        if (role == ChatRole.ASSISTANT && (t.startsWith("failed") || t.startsWith("refused") ||
                t.startsWith("not run") || t.startsWith("unsure") || t.startsWith("cancelled")))
            return SummaryKind.FAILURE
        if (failureMarkers.any { t.contains(it) }) return SummaryKind.FAILURE
        if (uncertaintyMarkers.any { t.contains(it) }) return SummaryKind.UNCERTAINTY
        if (openMarkers.any { t.contains(it) }) return SummaryKind.OPEN_TASK
        if (role == ChatRole.USER && instructionMarkers.any { t.contains(it) }) return SummaryKind.INSTRUCTION
        if (decisionMarkers.any { t.contains(it) }) return SummaryKind.DECISION
        if (looksFactual(piece)) return SummaryKind.FACT
        return SummaryKind.OTHER
    }

    /** A fact is something checkable in the text: a path, a hash, a counted test run. */
    private fun looksFactual(piece: String): Boolean {
        if (Regex("[0-9a-f]{7,40}").containsMatchIn(piece)) return true
        if (Regex("[\\w./-]+\\.(kt|java|md|json|gradle)").containsMatchIn(piece)) return true
        return Regex("\\b\\d{2,}\\b").containsMatchIn(piece)
    }

    private fun priority(kind: SummaryKind): Int = when (kind) {
        SummaryKind.FAILURE -> 0
        SummaryKind.INSTRUCTION -> 1
        SummaryKind.UNCERTAINTY -> 2
        SummaryKind.OPEN_TASK -> 3
        SummaryKind.DECISION -> 4
        SummaryKind.TOOL_RESULT -> 5
        SummaryKind.FACT -> 6
        SummaryKind.OTHER -> 7
    }

    fun excerpt(text: String, maxChars: Int = MAX_LINE_CHARS): String =
        if (text.length <= maxChars) text
        else text.take(maxChars) + " [cut " + (text.length - maxChars) + " chars]"
}

/**
 * Bounded retention across sends and across repeated summarisation.
 *
 * Two failure modes this exists to stop:
 *  - the same dropped turns being summarised again and again, so the summary grows duplicates and the
 *    window fills with copies of the same sentence;
 *  - an unbounded history of summaries, which is the original problem wearing a different hat.
 *
 * So: a covered message id is never summarised twice, and the retained summary is capped. When the cap
 * is reached the OLDEST LINES are dropped and that is stated in the rendered text - the summary says it
 * is partial rather than pretending to be complete.
 */
class SummaryLedger(
    private val maxLines: Int = Summariser.MAX_LINES,
) {
    private var covered = LinkedHashSet<String>()
    private var lines = ArrayList<SummaryLine>()
    private var revision = 0
    private var droppedForBound = 0
    private var superseded = 0

    val current: ConversationSummary get() = ConversationSummary(lines.toList(), covered.toList(), revision)

    /** True when these messages are already represented and must not be summarised again. */
    fun alreadyCovered(messages: List<ChatMessage>): Boolean =
        messages.isNotEmpty() && messages.all { it.id in covered }

    fun absorb(summary: ConversationSummary): ConversationSummary {
        revision += 1
        covered.addAll(summary.coveredIds)
        val next = ArrayList(lines)
        for (fresh in summary.lines) {
            if (next.any { it.text == fresh.text && it.kind == fresh.kind }) continue
            // PROJECT SIX 6.3B: a NEWER statement of the same subject replaces the older one instead of
            // sitting beside it. Without this the summary accumulates "decision: X" and "decision: Y"
            // with no way to tell which one the work now follows. Only the same subject and the same
            // kind are superseded: an earlier FAILURE is evidence and is never overwritten by a later
            // success, it is kept so the record shows the failure happened.
            if (fresh.kind in SUPERSEDABLE) {
                val words = subjectWords(fresh)
                if (words.isNotEmpty()) {
                    val old = next.indexOfFirst {
                        it.kind == fresh.kind && similar(subjectWords(it), words)
                    }
                    if (old >= 0) {
                        next.removeAt(old)
                        superseded += 1
                    }
                }
            }
            next.add(fresh)
        }
        lines = ArrayList(next.sortedBy { it.seq })
        if (lines.size > maxLines) {
            val excess = lines.size - maxLines
            droppedForBound += excess
            lines = ArrayList(lines.drop(excess))
        }
        return current
    }

    fun render(maxChars: Int = ConversationSummary.MAX_RENDER_CHARS): String {
        val base = current.render(maxChars)
        if (base.isEmpty()) return ""
        var out = base
        if (superseded > 0) {
            out += "- [" + SummaryKind.OTHER.name + "] " + superseded +
                " older statement(s) of the same subject were replaced by a newer one.\n"
        }
        if (droppedForBound > 0) {
            out += "- [" + SummaryKind.OTHER.name + "] " + droppedForBound +
                " older labelled line(s) fell off the retention bound.\n"
        }
        return out
    }

    fun coveredIds(): Set<String> = covered.toSet()

    /** How many older statements a newer one replaced. Read by tests and by the retained file. */
    fun supersededCount(): Int = superseded

    companion object {
        /** Kinds where a later statement about the same subject is a correction, not an addition. */
        private val SUPERSEDABLE = setOf(
            SummaryKind.DECISION, SummaryKind.INSTRUCTION, SummaryKind.FACT, SummaryKind.OPEN_TASK,
        )

        private val STOP = setOf(
            "the", "and", "for", "that", "with", "this", "from", "into", "have", "must", "never",
            "will", "does", "not", "are", "was", "were", "but", "can", "its", "it's", "than", "then",
        )

        /**
         * The distinctive words a line is about, order-insensitive.
         *
         * Two sentences about the same thing ("keep the branch stage-5.3 unmerged" and "keep the branch
         * stage-5.3 on hold") share most of these; two unrelated ones do not. Deliberately crude and
         * deliberately conservative: it is a duplicate detector, not an understanding of meaning, so it
         * prefers keeping both lines over deleting one that might still matter.
         */
        fun subjectWords(line: SummaryLine): Set<String> =
            line.text.lowercase()
                .split(Regex("[^a-z0-9_.-]+"))
                .filter { it.length >= 4 && it !in STOP }
                .toSet()

        /**
         * Same subject = at least three distinctive words in common AND 60% of the SMALLER vocabulary
         * shared. Containment rather than union: a correction usually adds words ("on hold instead"),
         * so a union test would refuse exactly the corrections this exists to catch.
         */
        fun similar(a: Set<String>, b: Set<String>): Boolean {
            if (a.isEmpty() || b.isEmpty()) return false
            val shared = a.count { it in b }
            val smaller = maxOf(a.size, b.size)
            return shared >= 3 && shared.toDouble() / smaller >= 0.6
        }

        /** Kept for callers that want a printable subject. */
        fun subjectKey(line: SummaryLine): String = subjectWords(line).sorted().joinToString(" ")
    }
}

/**
 * PROJECT SIX 6.2B — continuing a conversation in another session.
 *
 * A continuation carries three things: the summary, the exchange list as DATA, and a label saying
 * which hop this is ("Session 2 from git"). The type makes the safety rule structural: there is no
 * executor here and no tool name, only inert records, so creating a continuation cannot run anything.
 * Historical calls are context, not work to redo - replaying a write because a session boundary moved
 * is exactly the duplicate side effect the rest of this codebase spends its effort preventing.
 */
data class Continuation(
    val label: String,
    val sessionNumber: Int,
    val originLabel: String,
    val summary: ConversationSummary,
    val exchanges: List<ToolExchange>,
) {
    fun renderSummary(): String = summary.render()

    /** Exchanges as context lines, each stating its status so nothing reads as a fresh call. */
    fun renderExchanges(): List<String> = exchanges.map { x ->
        "- " + x.name + " [" + x.status.name + (if (x.complete) "" else ", incomplete") + "]: " +
            x.output.replace("\n", " ").take(200)
    }

    companion object {
        /** "Session 2 from git" - the hop number first, the chain's origin named after it. */
        fun labelFor(sessionNumber: Int, originLabel: String): String =
            "Session " + sessionNumber + (if (originLabel.isBlank()) "" else " from " + originLabel)
    }
}
