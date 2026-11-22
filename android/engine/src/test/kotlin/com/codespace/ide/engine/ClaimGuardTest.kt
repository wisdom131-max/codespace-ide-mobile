package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimGuardTest {

    @Test
    fun aBackedClaimIsNotReported() {
        val reply = "I ran the suite. All tests pass."
        val found = ClaimGuard.unsupported(reply, ClaimGuard.Evidence(setOf("tests")))
        assertTrue(found.isEmpty())
    }

    @Test
    fun anUnbackedTestClaimIsReported() {
        val reply = "All tests pass."
        val found = ClaimGuard.unsupported(reply, ClaimGuard.Evidence())
        assertEquals(1, found.size)
        assertEquals(ClaimGuard.Claim.TESTS, found.single().claim)
    }

    @Test
    fun anUnbackedBuildClaimIsReported() {
        val found = ClaimGuard.unsupported("The build succeeded.", ClaimGuard.Evidence())
        assertEquals(ClaimGuard.Claim.BUILD, found.single().claim)
    }

    @Test
    fun aWrittenFileClaimNeedsFileEvidence() {
        val found = ClaimGuard.unsupported("I wrote the file already.", ClaimGuard.Evidence())
        assertEquals(ClaimGuard.Claim.FILES, found.single().claim)
    }

    @Test
    fun oneSentenceIsReportedOnce() {
        val found = ClaimGuard.unsupported("Tests pass and the build succeeded.", ClaimGuard.Evidence())
        assertEquals("one sentence, one finding", 1, found.size)
    }

    @Test
    fun ordinaryProseIsQuiet() {
        assertTrue(ClaimGuard.unsupported("I will now read the file.", ClaimGuard.Evidence()).isEmpty())
    }

    @Test
    fun describeIsEmptyWhenEverythingIsBacked() {
        assertEquals("", ClaimGuard.describe(emptyList()))
    }
}
