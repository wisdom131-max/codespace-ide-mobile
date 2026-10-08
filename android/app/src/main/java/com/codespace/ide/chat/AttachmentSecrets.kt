package com.codespace.ide.chat

/**
 * C12 s1-a (owner + advisor rulings 2026-10-08): secrets handling for chat
 * attachment persistence. Pure Kotlin, JVM-tested (AttachmentSecretsTest runs
 * in the CI unit-test step BEFORE any persistence ships — s1-d waits on it).
 *
 * Scope:
 *  - EXCLUSION: secret-looking FILENAMES are never persisted at all (marker only).
 *  - REDACTION, TWO TIERS (advisor, scrub scope):
 *      TIER_ATTACHMENT — attachment copies + selection snippets: the FULL set.
 *      TIER_MESSAGE_TEXT — user/assistant message text: HIGH-CONFIDENCE only
 *        (key blocks, AKIA, ghp_, and length-gated sk-/hf_/AIza; NEVER eyJ).
 *        A scrub must never rewrite ordinary code or prose.
 *  - PATTERN SHAPE (advisor correction, both tiers): a match requires a WORD
 *    BOUNDARY before the prefix (no mid-identifier hits) and a KEY-LIKE BODY
 *    after it — mixed letters AND digits. Plain hyphenated words never match.
 *  - EVICTION, TWO TIERS: per session (~10 attachments / ~200 KB) plus a
 *    GLOBAL cap (512 KB, advisor-set — everything loads at app start), oldest
 *    evicted first. Evicted copies persist as a why-marker, not content.
 *
 * Idempotence: replacements echo NO prefix, so a second pass changes nothing.
 */
object AttachmentSecrets {

    const val TIER_ATTACHMENT = 0
    const val TIER_MESSAGE_TEXT = 1

    const val SESSION_MAX_ITEMS = 10
    const val SESSION_MAX_BYTES = 200 * 1024
    const val GLOBAL_MAX_BYTES = 512 * 1024

    const val EXCLUDED_MARKER = "[content not stored: secret-looking file]"
    const val EVICTED_MARKER = "[content evicted to save space - attach again to view]"
    const val REDACTED = "[REDACTED]"
    const val REDACTED_KEY_BLOCK = "[REDACTED-KEY-BLOCK]"

    private val EXCLUDED_PATTERNS = listOf(
        Regex("(^|/)\\.env(\\..+)?$"),
        Regex("\\.pem$"),
        Regex("(^|/)id_rsa(\\..*)?$"),
        Regex("\\.key$"),
        Regex("(^|/)credentials.*$"),
        Regex("(^|/)secrets.*$"),
    )

    /** True when the file NAME alone means its content must never be persisted. */
    fun isExcludedFilename(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        for (re in EXCLUDED_PATTERNS) if (re.containsMatchIn(n)) return true
        return false
    }

    /**
     * One token pattern, advisor pattern-shape ruling (both tiers): a WORD
     * BOUNDARY before the prefix (negative lookbehind over [A-Za-z0-9_-], so
     * "task-sk-item-card" and other hyphenated identifiers never match), then a
     * KEY-LIKE BODY of at least [minBody] chars that mixes letters AND digits
     * (two lookaheads over the body class) — plain hyphenated words have no
     * digits and can never satisfy both.
     */
    private fun tokenRegex(prefix: String, minBody: Int): Regex = Regex(
        "(?<![A-Za-z0-9_-])" + Regex.escape(prefix) +
            "(?=[A-Za-z0-9_-]*[A-Za-z])(?=[A-Za-z0-9_-]*[0-9])" +
            "[A-Za-z0-9_-]{$minBody,}"
    )

    // FULL tier — attachment copies + selection snippets (best-effort, as ruled).
    private val FULL_PATTERNS = listOf(
        tokenRegex("sk-", 8),
        tokenRegex("AKIA", 8),
        tokenRegex("hf_", 8),
        tokenRegex("AIza", 10),
        tokenRegex("xox", 8),
        tokenRegex("eyJ", 10),
        tokenRegex("ghp_", 8),
    )

    // HIGH-CONFIDENCE tier — message text only. AKIA/ghp_ are fixed distinctive
    // prefixes (no length debate); sk-/hf_/AIza carry realistic minimum lengths
    // (advisor proposals: sk- >= 20, hf_ >= 17, AIza >= 30 past the prefix).
    // eyJ is NEVER applied to message text.
    private val MESSAGE_PATTERNS = listOf(
        tokenRegex("sk-", 20),
        tokenRegex("AKIA", 8),
        tokenRegex("hf_", 17),
        tokenRegex("AIza", 30),
        tokenRegex("ghp_", 8),
    )

    private val KEY_BLOCK = Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----")

    /**
     * Redact [text] at [tier] (TIER_ATTACHMENT or TIER_MESSAGE_TEXT) and return
     * (redactedText, changed). Best-effort; never throws; idempotent (the
     * replacements echo no prefix so nothing re-matches).
     */
    fun redact(text: String, tier: Int): Pair<String, Boolean> {
        if (text.isEmpty()) return text to false
        var out = text
        var changed = false
        val blockMatch = KEY_BLOCK.find(out)
        if (blockMatch != null) {
            out = KEY_BLOCK.replace(out, REDACTED_KEY_BLOCK)
            changed = true
        }
        val patterns = if (tier == TIER_ATTACHMENT) FULL_PATTERNS else MESSAGE_PATTERNS
        for (re in patterns) {
            val m = re.find(out)
            if (m != null) {
                out = re.replace(out, REDACTED)
                changed = true
            }
        }
        return out to changed
    }

    /** One stored copy up for persistence, with its eviction coordinates. */
    data class StoredCopy(
        val sessionKey: String,
        val msgTimestamp: Long,   // message time — eviction ordering
        val attId: String,       // stable id of the attachment entry
        val content: String,     // the copy that rode the send (already redacted)
    ) {
        val bytes: Int get() = content.length
    }

    /**
     * Select which stored copies SURVIVE persistence. Everything else must be
     * persisted as the eviction marker. Two tiers (advisor item 5 + cap ruling):
     *   1. PER SESSION: newest first, up to SESSION_MAX_ITEMS items and
     *      SESSION_MAX_BYTES bytes — older entries in a full session are evicted.
     *   2. GLOBAL: survivors from (1) pooled across sessions; if they exceed
     *      GLOBAL_MAX_BYTES, evict OLDEST-FIRST until within budget.
     * Pure function — the caller maps survivors back to storage.
     */
    fun selectSurvivors(all: List<StoredCopy>): Set<String> {
        // 1) per-session pass
        val survivors = mutableSetOf<String>()
        for ((_, group) in all.groupBy { it.sessionKey }) {
            var budget = SESSION_MAX_BYTES
            var items = 0
            for (c in group.sortedByDescending { it.msgTimestamp }) {
                if (items >= SESSION_MAX_ITEMS || c.bytes > budget) continue
                survivors.add(c.attId)
                budget -= c.bytes
                items++
            }
        }
        // 2) global pass — evict oldest first until within the global budget
        var total = all.filter { it.attId in survivors }.sumOf { it.bytes }
        if (total > GLOBAL_MAX_BYTES) {
            val byOldest = all.filter { it.attId in survivors }.sortedBy { it.msgTimestamp }
            for (c in byOldest) {
                if (total <= GLOBAL_MAX_BYTES) break
                survivors.remove(c.attId)
                total -= c.bytes
            }
        }
        return survivors
    }
}
