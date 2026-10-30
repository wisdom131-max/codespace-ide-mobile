package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 404 verdict must be useful without being a leak: it names the PATH that was requested
 * (so a wrong base URL and a missing model stop sharing one sentence) and never the query.
 */
class ChatErrorVerdictTest {

    @Test
    fun a404NamesTheRequestedPathWithoutTheQuery() {
        val err = ErrorTaxonomy.classify(
            status = 404,
            headers = emptyMap(),
            body = "{\"error\":\"model not found\"}",
            endpointPath = "https://gw.example/v1/chat/completions",
        )
        assertTrue(err is ChatError.ModelNotFound)
        assertTrue("the path is named", err.userVerdict.contains("https://gw.example/v1/chat/completions"))
        assertTrue("it stays honest about both causes", err.userVerdict.contains("base URL"))
        assertFalse("a key must never appear", err.userVerdict.contains("sk-"))
    }

    @Test
    fun a404WithoutAPathStillSaysWhichModel() {
        val err = ErrorTaxonomy.classify(404)
        assertTrue(err.userVerdict.contains("Model not found"))
        assertFalse("no invented certainty about the path", err.userVerdict.contains("base URL"))
    }

    @Test
    fun theOtherStatusesStillMapAsBefore() {
        assertTrue(ErrorTaxonomy.classify(401) is ChatError.Auth)
        assertTrue(ErrorTaxonomy.classify(403) is ChatError.Auth)
        assertTrue(ErrorTaxonomy.classify(429) is ChatError.RateLimited)
        assertTrue(ErrorTaxonomy.classify(402) is ChatError.OutOfCredits)
        assertTrue(ErrorTaxonomy.classify(500) is ChatError.Server)
        assertTrue(ErrorTaxonomy.classify(400) is ChatError.BadRequest)
    }

    @Test
    fun classifyKeepsItsOldCallShape() {
        // Three-argument call sites (the pre-existing ones) must still compile and behave.
        val err = ErrorTaxonomy.classify(404, emptyMap(), "")
        assertEquals(ChatError.ModelNotFound::class.java, err.javaClass)
    }
}
