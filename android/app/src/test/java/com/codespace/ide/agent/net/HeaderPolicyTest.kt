package com.codespace.ide.agent.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P8: what a caller may not send, what collides, and what must never be echoed. */
class HeaderPolicyTest {

    @Test fun transportOwnedHeadersAreNeverForwarded() {
        listOf("Host", "content-length", "Connection", "Transfer-Encoding", "Upgrade", "Expect")
            .forEach { assertTrue("$it must be forbidden", HeaderPolicy.isForbidden(it)) }
    }

    @Test fun credentialHeadersAreManagedHereNotByTheCaller() {
        assertTrue(HeaderPolicy.isForbidden("Authorization"))
        assertTrue(HeaderPolicy.isForbidden("PROXY-AUTHORIZATION"))
        assertTrue(HeaderPolicy.isForbidden("Cookie"))
        assertTrue(HeaderPolicy.isForbidden("Sec-Fetch-Site"))
    }

    @Test fun ordinaryHeadersPassThrough() {
        val d = HeaderPolicy.apply(mapOf("Accept" to "application/json", "X-Request-Id" to " 42 "))
        assertEquals(mapOf("Accept" to "application/json", "X-Request-Id" to "42"), d.accepted)
        assertEquals("", d.summary())
    }

    @Test fun aCasingCollisionSendsNeitherCopy() {
        val d = HeaderPolicy.apply(mapOf("X-Trace" to "a", "x-trace" to "b"))
        assertFalse(d.accepted.keys.any { it.equals("x-trace", true) })
        assertTrue(d.rejected.any { it.contains("collides") })
    }

    @Test fun forbiddenNamesAreReportedSoTheCallerSeesWhy() {
        val d = HeaderPolicy.apply(mapOf("Authorization" to "Bearer abc", "Accept" to "*/*"))
        assertEquals(mapOf("Accept" to "*/*"), d.accepted)
        assertTrue(d.summary().contains("Authorization"))
        assertFalse("the credential must not appear in the notice", d.summary().contains("abc"))
    }

    @Test fun redactionReplacesCredentialValuesWhole() {
        val red = HeaderPolicy.redact(mapOf("Authorization" to "Bearer abc", "Accept" to "*/*"))
        assertEquals("[redacted]", red["Authorization"])
        assertEquals("*/*", red["Accept"])
    }

    @Test fun describeNeverPrintsACredentialValue() {
        val text = HeaderPolicy.describe(mapOf("x-api-key" to "live_123", "Accept" to "text/html"))
        assertFalse(text.contains("live_123"))
        assertTrue(text.contains("[redacted]"))
    }

    @Test fun blankNamesAreIgnoredRatherThanThrown() {
        val d = HeaderPolicy.apply(mapOf("   " to "x", "Accept" to "*/*"))
        assertEquals(1, d.accepted.size)
    }

    @Test fun aSameHostHttpsRedirectKeepsTheCallersHeaders() {
        assertTrue(HeaderPolicy.redirectKeepsCustomHeaders(
            "https://api.example.com/v1/items", "https://api.example.com/v1/items/2"))
    }

    @Test fun aCrossHostRedirectDropsThemSoTheyCannotLeak() {
        assertFalse(HeaderPolicy.redirectKeepsCustomHeaders(
            "https://api.example.com/v1", "https://evil.example/collect"))
        assertFalse(HeaderPolicy.redirectKeepsCustomHeaders(
            "https://api.example.com/v1", "https://api.example.com.evil.example/x"))
    }

    @Test fun aDowngradeToPlainHttpIsNeverAllowedToCarryThem() {
        assertFalse(HeaderPolicy.redirectKeepsCustomHeaders(
            "https://api.example.com/v1", "http://api.example.com/v1"))
    }

    @Test fun anUnparseableRedirectTargetKeepsNothing() {
        assertFalse(HeaderPolicy.redirectKeepsCustomHeaders("https://api.example.com/v1", "not a url"))
    }
}
