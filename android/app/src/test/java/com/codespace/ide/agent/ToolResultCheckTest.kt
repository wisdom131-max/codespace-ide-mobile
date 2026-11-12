package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Project 4.3: a returned tool is not a successful tool, and empty is not always a failure. */
class ToolResultCheckTest {

    @Test fun anErrorSentenceInsideASuccessfulReturnIsSuspicious() {
        val c = ToolResultCheck.check("run_command", "bash: gradlew: command not found")
        assertEquals(ToolResultCheck.Verdict.SUSPICIOUS_SUCCESS, c.verdict)
        assertTrue(c.needsVerify)
        assertTrue(ToolResultCheck.note("run_command", c).contains("Verify"))
    }

    @Test fun aThrownErrorIsAFailure() {
        val c = ToolResultCheck.check("read_file", "", IllegalStateException("boom"))
        assertEquals(ToolResultCheck.Verdict.FAILED, c.verdict)
        assertFalse(ToolResultCheck.note("read_file", c).isBlank())
    }

    @Test fun noSearchMatchesIsALegitimateEmptyResult() {
        val c = ToolResultCheck.check("grep_search", "")
        assertEquals(ToolResultCheck.Verdict.LEGITIMATELY_EMPTY, c.verdict)
        assertTrue(c.successful)
        assertEquals("", ToolResultCheck.note("grep_search", c))
    }

    @Test fun anEmptyReadIsNotALegitimateEmptyResult() {
        val c = ToolResultCheck.check("read_file", "")
        assertEquals(ToolResultCheck.Verdict.SUSPICIOUS_SUCCESS, c.verdict)
        assertTrue(c.needsVerify)
    }

    @Test fun aTruncatedResultIsIncompleteRatherThanOK() {
        val c = ToolResultCheck.check("read_file", "some content \u2026 [1200 chars omitted]")
        assertEquals(ToolResultCheck.Verdict.INCOMPLETE, c.verdict)
        assertTrue(ToolResultCheck.note("read_file", c).contains("truncated"))
    }

    @Test fun jsonThatIsNotJsonIsMalformed() {
        val c = ToolResultCheck.check("http_request", "not json at all", expectsJson = true)
        assertEquals(ToolResultCheck.Verdict.MALFORMED, c.verdict)
    }

    @Test fun jsonObjectsAndArraysAreAccepted() {
        assertEquals(ToolResultCheck.Verdict.OK, ToolResultCheck.check("http_request", "{\"a\":1}", expectsJson = true).verdict)
        assertEquals(ToolResultCheck.Verdict.OK, ToolResultCheck.check("http_request", "[1,2]", expectsJson = true).verdict)
    }

    @Test fun aMissingFileInTheOutputIsNoticed() {
        val c = ToolResultCheck.check("read_file", "cat: src/Main.kt: No such file or directory")
        assertEquals(ToolResultCheck.Verdict.SUSPICIOUS_SUCCESS, c.verdict)
    }

    @Test fun realOutputWithNoMarkersIsOK() {
        val c = ToolResultCheck.check("read_file", "class Main { fun run() {} }")
        assertEquals(ToolResultCheck.Verdict.OK, c.verdict)
        assertTrue(c.successful)
    }

    @Test fun theRealTruncationMarkerFromTheDispatchPathIsRecognised() {
        // The marker AgentTools actually emits (truncMark) must trip the check, or the wiring is
        // decorative. Text copied from the shipped constant, not invented here.
        val marker = "body\n[... TRUNCATED \u2014 showing 4000 of 90000 chars. Ask for a specific range/section to see the rest.]"
        assertTrue(ToolResultCheck.isTruncated(marker))
        assertEquals(ToolResultCheck.Verdict.INCOMPLETE, ToolResultCheck.check("read_file", marker).verdict)
    }

    @Test fun aDiffThatIsEmptyForGitStatusIsLegitimate() {
        assertEquals(ToolResultCheck.Verdict.LEGITIMATELY_EMPTY, ToolResultCheck.check("git_status", "").verdict)
    }
}
