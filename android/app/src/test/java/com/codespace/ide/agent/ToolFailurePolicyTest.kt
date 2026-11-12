package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Project 4.4: different failures, different actions, and no blind retry of a side effect. */
class ToolFailurePolicyTest {

    @Test fun aTimeoutIsRetryableButAPermissionRefusalIsNot() {
        assertTrue(ToolFailurePolicy.classify("request timed out").retryable)
        val perm = ToolFailurePolicy.classify("permission denied: /data/local")
        assertFalse(perm.retryable)
        assertEquals(ToolFailurePolicy.Action.ASK_USER, perm.action)
    }

    @Test fun aMissingPrerequisiteSuggestsTheStepBeforeIt() {
        val d = ToolFailurePolicy.classify("fatal: not a git repository")
        assertEquals(ToolFailurePolicy.Kind.PREREQUISITE, d.kind)
        assertEquals(ToolFailurePolicy.Action.ALTERNATIVE, d.action)
    }

    @Test fun badArgumentsAreNotRetriedWithTheSameArguments() {
        val d = ToolFailurePolicy.classify("unknown option --nope")
        assertEquals(ToolFailurePolicy.Kind.INVALID_ARGUMENTS, d.kind)
        assertFalse(d.retryable)
        assertEquals(ToolFailurePolicy.Action.STOP, d.action)
    }

    @Test fun aMissingToolIsNotWisdomToKeepTrying() {
        assertEquals(ToolFailurePolicy.Kind.UNAVAILABLE_TOOL, ToolFailurePolicy.classify("unknown tool: deploy_everything").kind)
    }

    @Test fun approvalFailuresAskTheOwnerInsteadOfRoutingAround() {
        val d = ToolFailurePolicy.classify("this call needs approval")
        assertEquals(ToolFailurePolicy.Kind.APPROVAL_NEEDED, d.kind)
        assertEquals(ToolFailurePolicy.Action.ASK_USER, d.action)
        assertFalse(d.retryable)
    }

    @Test fun resourceLimitsPointAtANarrowerRequest() {
        val d = ToolFailurePolicy.classify("file too large to read in one go")
        assertEquals(ToolFailurePolicy.Kind.RESOURCE_LIMIT, d.kind)
        assertEquals(ToolFailurePolicy.Action.ALTERNATIVE, d.action)
    }

    @Test fun retriesStopAtTheAttemptCeiling() {
        val d = ToolFailurePolicy.classify("connection reset")
        assertTrue(ToolFailurePolicy.mayRetry(d, 0, sideEffecting = false, outcomeKnown = true))
        assertTrue(ToolFailurePolicy.mayRetry(d, 1, sideEffecting = false, outcomeKnown = true))
        assertFalse(ToolFailurePolicy.mayRetry(d, ToolFailurePolicy.MAX_ATTEMPTS, sideEffecting = false, outcomeKnown = true))
    }

    @Test fun aSideEffectWithUnknownOutcomeIsNeverRetried() {
        val d = ToolFailurePolicy.classify("timed out")
        assertFalse(ToolFailurePolicy.mayRetry(d, 0, sideEffecting = true, outcomeKnown = false))
    }

    @Test fun aSideEffectWithAKnownOutcomeMayRetryWithinBounds() {
        val d = ToolFailurePolicy.classify("timed out")
        assertTrue(ToolFailurePolicy.mayRetry(d, 0, sideEffecting = true, outcomeKnown = true))
    }

    @Test fun guidancePreservesEarlierResults() {
        val g = ToolFailurePolicy.guidance(ToolFailurePolicy.classify("timed out"), keptResults = 3)
        assertTrue(g.contains("still valid"))
        assertTrue(g.contains("3"))
    }
}
