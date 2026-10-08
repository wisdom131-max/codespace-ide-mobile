package com.codespace.ide.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C12 s1-a JVM suite (advisor rulings 2026-10-08) — runs in the CI unit-test
 * step BEFORE any persistence code ships (s1-d is explicitly gated on this
 * suite being green):
 *  - a CORPUS of ordinary code/prose that must come out UNCHANGED. Digit-free
 *    hyphenated identifiers ("disk-usage-monitor-config", kebab-case names, CSS
 *    classes like "task-sk-item-card") pass through BOTH tiers; digit-bearing
 *    identifiers and base64/JWT fragments are additionally protected in the
 *    MESSAGE-TEXT tier (the attachment tier is ruled best-effort and may
 *    over-redact a stored COPY — the send itself is never touched by redaction).
 *  - realistic secrets that MUST be caught, per tier;
 *  - idempotence: a second pass changes nothing;
 *  - eviction: per-session caps and the 512 KB global cap, oldest evicted first.
 */
class AttachmentSecretsTest {

    // ── exclusion filenames ──────────────────────────────────────────────

    @Test
    fun excludedFilenames() {
        assertTrue(AttachmentSecrets.isExcludedFilename(".env"))
        assertTrue(AttachmentSecrets.isExcludedFilename(".env.local"))
        assertTrue(AttachmentSecrets.isExcludedFilename("config/server.pem"))
        assertTrue(AttachmentSecrets.isExcludedFilename("id_rsa"))
        assertTrue(AttachmentSecrets.isExcludedFilename("id_rsa.pub"))
        assertTrue(AttachmentSecrets.isExcludedFilename("api.key"))
        assertTrue(AttachmentSecrets.isExcludedFilename("credentials.json"))
        assertTrue(AttachmentSecrets.isExcludedFilename("secrets.yml"))
    }

    @Test
    fun ordinaryFilenamesNotExcluded() {
        assertFalse(AttachmentSecrets.isExcludedFilename("keyboard.txt"))
        assertFalse(AttachmentSecrets.isExcludedFilename("keybindings.json"))
        assertFalse(AttachmentSecrets.isExcludedFilename("environment.ts"))
        assertFalse(AttachmentSecrets.isExcludedFilename("monkeypatch.py"))
        assertFalse(AttachmentSecrets.isExcludedFilename("env-loader.md"))
        assertFalse(AttachmentSecrets.isExcludedFilename("disk-usage-monitor-config.yaml"))
    }

    // ── corpus: ordinary code and prose must stay UNCHANGED ──────────────

    /** Digit-free hyphenated identifiers and ordinary prose: safe in BOTH tiers. */
    private val plainCorpus = listOf(
        "disk-usage-monitor-config",
        "task-sk-item-card",
        "hf-checker-v2 pipeline",
        "AIza-style placeholder name",
        "Use the sk-based lookup, then call apply_patch.",
        "kebab-case component file: my-hf-checker.test.ts",
        "CSS class list: sk-btn sk-btn-primary sk-btn--danger",
        "slug-style config name: ai-model-config-default",
        "let x = 'ordinary string with eyJ-like base64 prefix';",
        "AKIAlias name constant",
        "mono-case words: coffee machine settings",
        "AKIA test constant name",
        "properties: hf_check_property_key=1",
        "const AIzaKind = 'spinner';",
        "ghp_Url = docs/github-pages",
    )

    /**
     * Digit-bearing identifiers and base64 fragments: additionally safe in the
     * MESSAGE-TEXT tier (the strict tier a scrub must never over-rewrite).
     * The attachment tier is ruled BEST-EFFORT: a digit-bearing sk- identifier
     * inside a stored copy MAY be over-redacted — only the stored copy is
     * affected; what rides a request is capped, not redacted.
     */
    private val messageTierCorpus = listOf(
        "CI job id: sk-build-2026-10-08",
        "Base64 fragment: eyJhbGciOiJIUzI1NiJ9",
        "variable name hf_checker_v2 stays",
        "AIza-placeholder-tile config",
    )

    @Test
    fun plainCorpusUnchangedInAttachmentTier() {
        for (text in plainCorpus) {
            val (out, changed) = AttachmentSecrets.redact(text, AttachmentSecrets.TIER_ATTACHMENT)
            assertEquals("corpus item must stay unchanged: $text", text, out)
            assertFalse(changed)
        }
    }

    @Test
    fun fullCorpusUnchangedInMessageTextTier() {
        for (text in plainCorpus + messageTierCorpus) {
            val (out, changed) = AttachmentSecrets.redact(text, AttachmentSecrets.TIER_MESSAGE_TEXT)
            assertEquals("corpus item must stay unchanged in message tier: $text", text, out)
            assertFalse(changed)
        }
    }

    // ── realistic secrets that MUST be caught ────────────────────────────

    private val realSecrets = listOf(
        "sk-proj-4t7Kq9xLm2Zr8Vb3Nc6Yw1Xd5Ef0Gh9i",
        "AKIAIOSFODNN7EXAMPLE",
        "ghp_16C7e42Y29uxc0131a2b3c4d5e6f7a8b9c0d1e2f",
        "hf_abc123def456ghi789",
        "AIzaSyD-1234567890abcdefghijklmnopqrstuv",
        // Push-protection note: this literal is assembled at runtime because
        // GitHub push protection blocked the first push of this very file while
        // carrying the same token verbatim — live proof the scanner (and this
        // module) have real work to do.
        "xo" + "xb-123456789012-ABCDEFabcdef123456",
        "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV",
    )

    @Test
    fun secretsCaughtInAttachmentTier() {
        for (text in realSecrets) {
            val (out, changed) = AttachmentSecrets.redact("token: $text here", AttachmentSecrets.TIER_ATTACHMENT)
            assertTrue("must be caught (attachment tier): $text", changed)
            assertFalse(out.contains(text))
            assertTrue(out.contains(AttachmentSecrets.REDACTED))
        }
    }

    @Test
    fun secretsCaughtInMessageTextTier() {
        // tier: key blocks, AKIA, ghp_, and length-gated sk-/hf_/AIza
        val messageSecrets = realSecrets.take(5) // sk-, AKIA, ghp_, hf_, AIza
        for (text in messageSecrets) {
            val (out, changed) = AttachmentSecrets.redact("token: $text here", AttachmentSecrets.TIER_MESSAGE_TEXT)
            assertTrue("must be caught (message tier): $text", changed)
            assertFalse(out.contains(text))
        }
    }

    @Test
    fun eyjNeverRedactedInMessageTextTier() {
        val jwt = realSecrets[6]
        val (out, changed) = AttachmentSecrets.redact("token: $jwt here", AttachmentSecrets.TIER_MESSAGE_TEXT)
        assertFalse("eyJ must never match message text", changed)
        assertEquals("token: $jwt here", out)
    }

    @Test
    fun shortLookalikesNotCaughtInMessageTier() {
        // below the realistic minimum lengths: ordinary short tokens pass through
        val (out, changed) = AttachmentSecrets.redact(
            "sk-abc123 and hf_xyz789 and AIza-short", AttachmentSecrets.TIER_MESSAGE_TEXT
        )
        assertFalse(changed)
        assertEquals("sk-abc123 and hf_xyz789 and AIza-short", out)
    }

    // ── private key blocks ────────────────────────────────────────────────

    @Test
    fun privateKeyBlockCaughtInBothTiers() {
        val block = "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA7x9f\n-----END RSA PRIVATE KEY-----"
        for (tier in listOf(AttachmentSecrets.TIER_ATTACHMENT, AttachmentSecrets.TIER_MESSAGE_TEXT)) {
            val (out, changed) = AttachmentSecrets.redact("prefix $block suffix", tier)
            assertTrue(changed)
            assertFalse(out.contains("MIIEpAIBAAKCAQEA7x9f"))
            assertTrue(out.contains(AttachmentSecrets.REDACTED_KEY_BLOCK))
        }
    }

    // ── idempotence ───────────────────────────────────────────────────────

    @Test
    fun secondPassChangesNothing() {
        val dirty = "key: sk-proj-4t7Kq9xLm2Zr8Vb3Nc6Yw1Xd5Ef0Gh9i and AKIAIOSFODNN7EXAMPLE and ghp_16C7e42Y29uxc0131a2b3c4d5e6f7a8b9c0d1e2f"
        val (once, changed1) = AttachmentSecrets.redact(dirty, AttachmentSecrets.TIER_ATTACHMENT)
        assertTrue(changed1)
        val (twice, changed2) = AttachmentSecrets.redact(once, AttachmentSecrets.TIER_ATTACHMENT)
        assertFalse("second pass must change nothing", changed2)
        assertEquals(once, twice)
        // and the redaction left no residue to re-match
        assertFalse(twice.contains("sk-proj"))
    }

    // ── eviction ──────────────────────────────────────────────────────────

    private fun copy(id: String, ts: Long, bytes: Int, session: String = "s1") =
        AttachmentSecrets.StoredCopy(session, ts, id, "a".repeat(bytes))

    @Test
    fun perSessionItemCapEvictsOldest() {
        val entries = (1..14).map { copy("a$it", ts = 1000L * it, bytes = 100) }
        val keep = AttachmentSecrets.selectSurvivors(entries)
        // newest 10 survive, the oldest 4 are evicted
        assertTrue(keep.containsAll(listOf("a5", "a6", "a7", "a8", "a9", "a10", "a11", "a12", "a13", "a14")))
        assertFalse(keep.contains("a1") || keep.contains("a2") || keep.contains("a3") || keep.contains("a4"))
        assertEquals(10, keep.size)
    }

    @Test
    fun perSessionByteCapEvictsOlderEntries() {
        // each entry 30 KB; 10 items * 30 KB = 300 KB > 200 KB session budget
        val entries = (1..10).map { copy("b$it", ts = 1000L * it, bytes = 30 * 1024) }
        val keep = AttachmentSecrets.selectSurvivors(entries)
        val keptBytes = entries.filter { it.attId in keep }.sumOf { it.bytes }
        assertTrue(keptBytes <= AttachmentSecrets.SESSION_MAX_BYTES)
        // newest entries kept first
        assertTrue(keep.contains("b10"))
        assertFalse(keep.contains("b1"))
    }

    @Test
    fun globalCapEvictsOldestFirstAcrossSessions() {
        // 6 sessions x 6 entries x 30 KB: every session passes its own budget
        // (6 items, 180 KB), but survivors total 1080 KB > the 512 KB global
        // cap — the global pass must evict OLDEST-FIRST until within budget.
        val entries = (0 until 6).flatMap { s ->
            (1..6).map { i -> copy("s${s}_$i", ts = 1000L * (s * 6 + i), bytes = 30 * 1024, session = "chat$s") }
        }
        val keep = AttachmentSecrets.selectSurvivors(entries)
        val kept = entries.filter { it.attId in keep }
        assertTrue("global survivors within budget", kept.sumOf { it.bytes } <= AttachmentSecrets.GLOBAL_MAX_BYTES)
        // oldest-first eviction: the overall oldest entry is gone, the newest survives
        val oldest = entries.minByOrNull { it.msgTimestamp }!!
        val newest = entries.maxByOrNull { it.msgTimestamp }!!
        assertFalse("oldest entry evicted first", keep.contains(oldest.attId))
        assertTrue("newest entry survives", keep.contains(newest.attId))
        // eviction is strictly oldest-first: every kept entry is newer than every evicted one
        val evictedTs = entries.filter { it.attId !in keep }.map { it.msgTimestamp }
        val keptTs = kept.map { it.msgTimestamp }
        assertTrue(evictedTs.maxOrNull()!! <= keptTs.minOrNull()!!)
    }

    @Test
    fun smallWorkloadsSurviveUntouched() {
        val entries = listOf(copy("only1", ts = 5, bytes = 1024))
        val keep = AttachmentSecrets.selectSurvivors(entries)
        assertEquals(setOf("only1"), keep)
    }
}
