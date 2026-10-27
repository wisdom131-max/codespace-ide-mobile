package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Taxonomy contract tests: plain-word verdicts, 402/429 split, retryable flags. */
class ErrorTaxonomyTest {

    @Test fun `429 carries Retry-After seconds`() {
        val e = ErrorTaxonomy.classify(429, mapOf("retry-after" to "30"))
        assertTrue(e is ChatError.RateLimited)
        assertEquals(30, (e as ChatError.RateLimited).retryAfterSec)
        assertTrue(e.retryable)
    }

    @Test fun `402 is dedicated out-of-credits, never 429 wording`() {
        val e = ErrorTaxonomy.classify(402, body = """{"error":"insufficient credits"}""")
        assertTrue(e is ChatError.OutOfCredits)
        assertTrue(!e.userVerdict.contains("busy"))
        assertTrue(!e.retryable)
    }

    @Test fun `401 maps to auth`() {
        assertTrue(ErrorTaxonomy.classify(401) is ChatError.Auth)
    }

    @Test fun `404 maps to model-not-found with id hint`() {
        val e = ErrorTaxonomy.classify(404, body = """{"error":"model 'gemini-x' not found"}""")
        assertTrue(e is ChatError.ModelNotFound)
        assertTrue((e as ChatError.ModelNotFound).modelId.contains("gemini-x"))
    }

    @Test fun `500 maps to retryable server`() {
        val e = ErrorTaxonomy.classify(503)
        assertTrue(e is ChatError.Server && e.retryable)
    }

    @Test fun `brief truncates long bodies without raw newlines`() {
        val e = ErrorTaxonomy.network("x\ny".repeat(80))
        assertTrue(!e.userVerdict.contains("\n"))
        assertTrue(e.userVerdict.endsWith("...") || e.userVerdict.length <= 120)
    }
}
