package com.codespace.ide.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OUTPUT EXPORT REDACTION (advisor round 2026-10-19): the Output panel's Copy
 * and Save buttons apply the HIGH-CONFIDENCE tier (TIER_MESSAGE_TEXT — the
 * exact tier AttachmentSecretsTest scrubs message text with). These pins hold
 * the policy the export uses — what MUST be redacted, and what the tier
 * deliberately leaves alone so ordinary terminal output is never rewritten.
 */
class OutputExportRedactionTest {

    private fun exportRedact(text: String) =
        AttachmentSecrets.redact(text, AttachmentSecrets.TIER_MESSAGE_TEXT).first

    @Test fun apiKeyLine_isRedacted() {
        val out = exportRedact("[info] connecting with sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12")
        assertFalse(out.contains("sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12"))
        assertTrue(out.contains(AttachmentSecrets.REDACTED))
    }

    @Test fun awsKey_isRedacted() {
        val out = exportRedact("[terminal] AWS AKIAIOSFODNN7EXAMPLE exported")
        assertFalse(out.contains("AKIAIOSFODNN7EXAMPLE"))
    }

    @Test fun githubToken_isRedacted() {
        val out = exportRedact("[git] push with ghp_0123456789abcdef0123456789abcdef01234567")
        assertFalse(out.contains("ghp_0123456789abcdef"))
    }

    @Test fun privateKeyBlock_isRedacted() {
        val out = exportRedact("[info] -----BEGIN RSA PRIVATE KEY-----\\nMIIabc\\n-----END RSA PRIVATE KEY-----")
        assertEquals(AttachmentSecrets.REDACTED_KEY_BLOCK, out)
    }

    @Test fun normalPathsAndBuildIds_areUntouched() {
        // The tier must NOT rewrite ordinary output: paths, build ids, prose.
        val line = "[build] /sdcard/My codespace app 3/1.py ok (build 37251788137 green)"
        assertEquals(line, exportRedact(line))
    }

    @Test fun ordinaryProse_isUntouched() {
        assertEquals("hello world", exportRedact("hello world"))
    }

    @Test fun exportStaysIdempotent() {
        val once = exportRedact("sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12")
        assertEquals(once, exportRedact(once))
    }
}
