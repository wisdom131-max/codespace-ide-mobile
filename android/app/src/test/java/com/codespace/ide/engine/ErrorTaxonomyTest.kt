package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Advisor Stage 2 #8: the wait the provider actually asked for must reach the card.
 *
 * RFC 7231 allows delta-seconds and HTTP-date; only delta-seconds was read, so every date-form
 * Retry-After became "no wait given" and the card asked for an unnamed wait.
 */
class ErrorTaxonomyTest {

    private val now = 1_800_000_000_000L // fixed instant, so the date form is deterministic

    private fun parse(value: String): Long =
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            .parse(value)!!.time

    @Test
    fun deltaSecondsIsRead() {
        assertEquals(30, ErrorTaxonomy.retryAfterSec(mapOf("Retry-After" to "30"), now))
        assertEquals(30, ErrorTaxonomy.retryAfterSec(mapOf("retry-after" to " 30 "), now))
    }

    @Test
    fun anHttpDateBecomesASecondsWait() {
        val in90 = "Wed, 21 Oct 2026 07:28:00 GMT"
        val target = parse(in90)
        val header = mapOf("Retry-After" to in90)
        assertEquals(90, ErrorTaxonomy.retryAfterSec(header, target - 90_000L))
    }

    @Test
    fun aDateInThePastIsZeroNotMissing() {
        val past = mapOf("Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT")
        val later = parse("Wed, 21 Oct 2026 07:29:00 GMT")
        assertEquals("waiting a negative time means no wait", 0, ErrorTaxonomy.retryAfterSec(past, later))
    }

    @Test
    fun noHeaderIsNoWait() {
        assertNull(ErrorTaxonomy.retryAfterSec(emptyMap(), now))
        assertNull(ErrorTaxonomy.retryAfterSec(mapOf("Retry-After" to "soon"), now))
    }

    @Test
    fun fourTwoNineAndFourTwoTwoStayDifferentErrors() {
        val busy = ErrorTaxonomy.classify(429, mapOf("Retry-After" to "45"))
        assertTrue(busy is ChatError.RateLimited)
        assertEquals(45, (busy as ChatError.RateLimited).retryAfterSec)

        val broke = ErrorTaxonomy.classify(402, body = """{"error":"insufficient quota"}""")
        assertTrue("a 402 is not a 429", broke is ChatError.OutOfCredits)
        assertTrue(
            "the 402 verdict never borrows the rate-limit sentence",
            !broke.userVerdict.contains("rate-limited", ignoreCase = true),
        )
    }

    @Test
    fun theNoHeaderVerdictStillNamesAWaitInPlainWords() {
        val busy = ErrorTaxonomy.classify(429)
        assertTrue(busy.userVerdict.contains("Try again in a few seconds"))
        val named = ErrorTaxonomy.classify(429, mapOf("Retry-After" to "20"))
        assertTrue(
            "the number carries a unit; the old copy said 'Wait 20'",
            named.userVerdict.contains("20 seconds"),
        )
    }
}
