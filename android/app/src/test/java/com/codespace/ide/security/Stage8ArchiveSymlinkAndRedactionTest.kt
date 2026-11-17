package com.codespace.ide.security

import com.codespace.ide.agent.ArchiveVerifier
import com.codespace.ide.chat.AttachmentSecrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT EIGHT checkpoint 2 — the two shapes checkpoint 1 disclosed as unexamined: an archive link's
 * TARGET, and credentials that are recognisable by structure rather than by an issuer prefix.
 *
 * Every secret here is synthetic and obviously fake. Nothing is printed on failure: the assertions
 * report only whether the value survived, never the value.
 */
class Stage8ArchiveSymlinkAndRedactionTest {

    // ---------------------------------------------------------------- archive links

    private fun listing(vararg lines: String): String = lines.joinToString("\n")

    @Test
    fun `a safe relative link is accepted`() {
        val out = listing(
            "bin/tool 10",
            "bin/alias -> tool",
            "lib/libfoo.so 4096",
        )
        assertEquals(ArchiveVerifier.Result.VERIFIED, ArchiveVerifier.verifyListing(out).result)
    }

    @Test
    fun `a link climbing out of the destination is refused`() {
        val out = listing("safe.txt 12", "escape -> ../outside/secret")
        val verdict = ArchiveVerifier.verifyListing(out)
        assertEquals(ArchiveVerifier.Result.REFUTED, verdict.result)
        assertTrue("the refusal must say why", verdict.note.contains("link"))
    }

    @Test
    fun `an absolute link target is refused`() {
        val out = listing("safe.txt 12", "passwd -> /data/data/com.codespace.ide/files/keys")
        assertEquals(ArchiveVerifier.Result.REFUTED, ArchiveVerifier.verifyListing(out).result)
    }

    @Test
    fun `a drive-rooted link target is refused`() {
        val out = listing("safe.txt 12", "win -> C:/Windows/System32/drivers/etc/hosts")
        assertEquals(ArchiveVerifier.Result.REFUTED, ArchiveVerifier.verifyListing(out).result)
    }

    @Test
    fun `a network-rooted link target is refused`() {
        val out = listing("safe.txt 12", "unc -> \\\\server\\share\\payload")
        assertEquals(ArchiveVerifier.Result.REFUTED, ArchiveVerifier.verifyListing(out).result)
    }

    @Test
    fun `a nested traversal inside a link target is refused`() {
        val out = listing("a/b -> ../../../../etc/shadow")
        assertEquals(ArchiveVerifier.Result.REFUTED, ArchiveVerifier.verifyListing(out).result)
    }

    @Test
    fun `link metadata that cannot be read is refused rather than assumed safe`() {
        val out = listing("safe.txt 12", "broken ->")
        assertEquals(ArchiveVerifier.Result.REFUTED, ArchiveVerifier.verifyListing(out).result)
    }

    @Test
    fun `ordinary files with an arrow in the PATH are not mistaken for links`() {
        // No link is present: the arrow is glued to the name, so it is not a ` name -> target ` line.
        val out = listing("docs/a->b.txt 20", "docs/plain.txt 30")
        assertNull(ArchiveVerifier.unsafeLink(out))
    }

    @Test
    fun `a link whose own name escapes is refused`() {
        val out = listing("../evil -> fine")
        assertTrue(ArchiveVerifier.unsafeLink(out) != null)
    }

    // ---------------------------------------------------------------- redaction

    private fun message(text: String): String = AttachmentSecrets.redact(text, AttachmentSecrets.TIER_MESSAGE_TEXT).first

    private fun attachment(text: String): String = AttachmentSecrets.redact(text, AttachmentSecrets.TIER_ATTACHMENT).first

    @Test
    fun `a bearer value never survives, whatever follows the word`() {
        val secret = "Zx9Qw3ErTy7UiOp1AsDfGh2"
        val out = message("request failed: Authorization: Bearer " + secret + " (retry later)")
        assertFalse("the token survived redaction", out.contains(secret))
        assertTrue("the diagnostic should still say what failed", out.contains("request failed"))
        assertTrue("the header name should survive", out.lowercase().contains("authorization"))
    }

    @Test
    fun `a basic pair never survives`() {
        val secret = "dXNlcjpwYXNzd29yZDEyMzQ1Ng=="
        val out = attachment("Authorization: Basic " + secret)
        assertFalse(out.contains(secret))
        assertTrue(out.lowercase().contains("basic"))
    }

    @Test
    fun `a credential query parameter is redacted and the parameter name kept`() {
        val secret = "tok_live_9f8e7d6c5b4a3210"
        val out = message("GET https://api.example.com/v1/models?api_key=" + secret + "&limit=20")
        assertFalse(out.contains(secret))
        assertTrue(out.contains("api_key"))
        assertTrue("unrelated parameters must survive", out.contains("limit=20"))
    }

    @Test
    fun `a nested json credential is redacted and neighbouring fields survive`() {
        val secret = "sk-syntheticvalue0123456789abcdefXY"
        val out = attachment("""{"provider":{"api_key":"$secret","model":"gemini-2.5-pro"}}""")
        assertFalse(out.contains(secret))
        assertTrue("the model name is not a credential and must survive", out.contains("gemini-2.5-pro"))
    }

    @Test
    fun `a credential in a tool error is redacted while the tool name survives`() {
        val secret = "AIzaSySYNTHETICONLYVALUE0123456789abcd"
        val out = message("mcp_github failed: 401 with header x-api-key: " + secret)
        assertFalse(out.contains(secret))
        assertTrue(out.contains("mcp_github"))
        assertTrue(out.contains("401"))
    }

    @Test
    fun `redaction is idempotent and never throws on malformed input`() {
        val once = message("Authorization: Bearer abcdefgh12345678")
        val twice = message(once)
        assertEquals(once, twice)
        assertEquals("", message(""))
        message("{\"api_key\":")
        message("Bearer")
    }

    @Test
    fun `ordinary code and identifiers are left alone`() {
        val code = "val token = computeRefreshExpiry(); fun basicAuthEnabled(): Boolean = false"
        assertEquals(code, message(code))
    }
}
