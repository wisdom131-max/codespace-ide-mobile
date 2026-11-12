package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit 2026-11-12: the ledger kept whole argument values, so a written file body sat in memory and
 * in the persisted ledger. Long values are fingerprinted; reconciliation still has to work.
 */
class ArgFingerprintTest {

    private val body = "x".repeat(ArgFingerprint.KEEP + 1)

    @Test fun shortValuesStayReadable() {
        val out = ArgFingerprint.stamp(mapOf("path" to "/root/a.txt", "command" to "ls -la"))
        assertEquals("/root/a.txt", out["path"])
        assertEquals("ls -la", out["command"])
    }

    @Test fun aLongBodyBecomesAFingerprintAndNothingElseChanges() {
        val out = ArgFingerprint.stamp(mapOf("path" to "/root/a.txt", "content" to body))
        assertEquals("/root/a.txt", out["path"])
        assertTrue("content must not be stored whole", out["content"]!!.length < 80)
        assertTrue(out["content"]!!.startsWith("sha256:"))
    }

    @Test fun aFingerprintStillProvesTheWriteLanded() {
        val stamped = ArgFingerprint.stamp(mapOf("content" to body))["content"]
        assertTrue(ArgFingerprint.matches(stamped, body))
    }

    @Test fun aFingerprintRejectsDifferentContent() {
        val stamped = ArgFingerprint.stamp(mapOf("content" to body))["content"]
        assertFalse(ArgFingerprint.matches(stamped, body + " "))
    }

    @Test fun shortValuesStillCompareExactly() {
        assertTrue(ArgFingerprint.matches("hello", "hello"))
        assertFalse(ArgFingerprint.matches("hello", "hello!"))
    }

    @Test fun absenceIsNeverEvidence() {
        assertFalse(ArgFingerprint.matches(null, "anything"))
        assertFalse(ArgFingerprint.matches("anything", null))
    }

    @Test fun theMapItselfIsBounded() {
        val many = (1..40).associate { "k$it" to "v" }
        assertEquals(ArgFingerprint.MAX_KEYS, ArgFingerprint.stamp(many).size)
    }

    @Test fun theValueAtTheBoundaryIsKeptWhole() {
        val exact = "y".repeat(ArgFingerprint.KEEP)
        assertEquals(exact, ArgFingerprint.stamp(mapOf("content" to exact))["content"])
    }
}
