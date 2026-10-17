package com.codespace.ide.chat

import com.codespace.ide.chat.providers.OpenAiCompatibleTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 (BATCH3): per-code coverage of the pure classification core
 * [OpenAiCompatibleTransport.classifyHttpErrorCore] — the same verdicts the
 * wrapper produces for real responses. Cloudflare and 402 branches must stay
 * byte-identical to pre-P2 behavior; 401/403 vendor, 404, 429 and 5xx gain
 * plain-words verdicts that name the failure class.
 */
class HttpErrorClassifyTest {

    private fun core(code: Int, vendor: String = "API error (" + code + "): vendor says no", body: String = "", server: String = "", retry: Long? = null) =
        OpenAiCompatibleTransport.classifyHttpErrorCore(code, vendor, body, server, retry)

    // ── 401/403: Cloudflare firewall block (pre-P2 behavior, byte-identical) ──

    @Test
    fun cloudflare401IsWafBlockNotKeyRejection() {
        val he = core(401, body = "Just a moment... Cloudflare attention required", server = "cloudflare")
        assertTrue("CF 401 must set isWafBlock", he.isWafBlock)
        assertTrue(he.message.contains("Firewall block (401)"))
        assertTrue("message must say the key may be fine", he.message.contains("Your key may be fine"))
    }

    @Test
    fun cloudflare403IsWafBlockNotKeyRejection() {
        val he = core(403, body = "<html>cloudflare cf-ray: 8f2</html>", server = "Cloudflare")
        assertTrue(he.isWafBlock)
        assertTrue(he.message.contains("Firewall block (403)"))
    }

    // ── 401/403 vendor rejection (NEW plain-words verdict) ────────────────

    @Test
    fun vendor401SaysKeyRejectedWithExactCode() {
        val he = core(401)
        assertFalse("vendor 401 is NOT a WAF block", he.isWafBlock)
        assertEquals(401, he.statusCode)
        assertTrue(he.message.contains("REJECTED this API key (401)"))
        assertTrue("vendor detail rides under the verdict", he.message.contains("(API error (401): vendor says no)"))
    }

    @Test
    fun vendor403SaysKeyRejectedWithExactCode() {
        val he = core(403)
        assertFalse(he.isWafBlock)
        assertTrue(he.message.contains("REJECTED this API key (403)"))
    }

    @Test
    fun vendor401WithoutDetailStillCompilesCleanMessage() {
        val he = core(401, vendor = "")
        assertTrue(he.message.contains("REJECTED this API key (401)"))
        assertFalse("no detail suffix when vendor message blank", he.message.contains("\n("))
    }

    // ── 402: pre-P2 behavior, byte-identical ───────────────────────────────

    @Test
    fun http402VerdictIsOutOfCreditsAndAccountNotKey() {
        val he = core(402)
        assertEquals(402, he.statusCode)
        assertTrue(he.message.contains("OUT OF CREDITS"))
        assertTrue("must say credits belong to the ACCOUNT", he.message.contains("Credits belong to the provider ACCOUNT, not the key"))
        assertTrue("must close the make-a-new-key dead end", he.message.contains("creating a new key on the same account will not help"))
    }

    @Test
    fun http402KeepsVendorDetailSuffixFormat() {
        val he = core(402, vendor = "API error (402): billing issue")
        assertTrue(he.message.contains("\n(API error (402): billing issue)"))
    }

    // ── 404 (NEW verdict) ──────────────────────────────────────────────────

    @Test
    fun http404SaysModelOrEndpointNotFound() {
        val he = core(404)
        assertEquals(404, he.statusCode)
        assertTrue(he.message.contains("MODEL was NOT FOUND (404)"))
        assertTrue("points at the two real causes", he.message.contains("model ID") && he.message.contains("endpoint URL"))
    }

    // ── 429 (NEW verdict + retry propagation) ───────────────────────────────

    @Test
    fun http429SaysRateLimitedAndCarriesRetryAfterMs() {
        val he = core(429, retry = 25_000L)
        assertEquals(429, he.statusCode)
        assertEquals(Long.valueOf(25_000L), he.retryAfterMs)
        assertTrue(he.message.contains("RATE-LIMITING this key (429)"))
        assertTrue("retry wait is named in plain words", he.message.contains("Retry in 25s"))
    }

    @Test
    fun http429WithoutRetryHeaderGivesGenericWaitLine() {
        val he = core(429)
        assertNull(he.retryAfterMs)
        assertTrue(he.message.contains("Wait a moment and try again"))
    }

    // ── 5xx (NEW verdict) ───────────────────────────────────────────────────

    @Test
    fun http500SaysProviderServerErrorNotYourProblem() {
        val he = core(500)
        assertEquals(500, he.statusCode)
        assertTrue(he.message.contains("provider's SERVER errored (500)"))
        assertTrue("must clear the key and the app", he.message.contains("Nothing is wrong with your key or the app"))
    }

    @Test
    fun http503AlsoCovered() {
        val he = core(503)
        assertTrue(he.message.contains("(503)"))
        assertTrue(he.message.contains("SERVER errored"))
    }

    // ── Unknown codes: generic path unchanged ───────────────────────────────

    @Test
    fun unknownCodeKeepsVendorMessageAsIs() {
        val he = core(418, vendor = "API error (418): teapot")
        assertEquals(418, he.statusCode)
        assertEquals("API error (418): teapot", he.message)
        assertNull(he.retryAfterMs)
        assertFalse(he.isWafBlock)
    }

    // ── verdictDetail helper (shared suffix format, 402's format) ───────────

    @Test
    fun verdictDetailBlankVsPresent() {
        assertEquals("", OpenAiCompatibleTransport.verdictDetail(""))
        assertEquals("\n(x: y)", OpenAiCompatibleTransport.verdictDetail("x: y"))
    }
}
