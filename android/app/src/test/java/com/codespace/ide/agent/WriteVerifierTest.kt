package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3.C: an attempted write is not a successful write. The turn must not say "done" when the file does
 * not hold what was asked for.
 */
class WriteVerifierTest {

    private fun verify(tool: String, args: Map<String, String>, actual: String?) =
        WriteVerifier.verify(tool, args) { actual }

    @Test fun aWriteThatLandedIsVerifiedAndSaysNothing() {
        val r = verify("write_file", mapOf("path" to "/p/a.txt", "content" to "hello"), "hello")
        assertTrue(r is WriteVerifier.Result.Verified)
        assertNull("a verified write is not news", WriteVerifier.line(r))
    }

    @Test fun aWriteThatDidNotLandIsReportedAsAttemptedNotConfirmed() {
        val r = verify("write_file", mapOf("path" to "/p/a.txt", "content" to "hello"), "something else")
        assertTrue(r is WriteVerifier.Result.Differs)
        val line = WriteVerifier.line(r)!!
        assertTrue(line.contains("Attempted, not confirmed"))
        assertTrue(line.contains("/p/a.txt"))
    }

    @Test fun aMissingFileIsNotTreatedAsSuccess() {
        val r = verify("create_file", mapOf("path" to "/p/gone.txt", "content" to "x"), null)
        assertTrue(r is WriteVerifier.Result.Missing)
        assertTrue(WriteVerifier.line(r)!!.contains("not there"))
    }

    @Test fun aPartialEditThatAppliedIsVerified() {
        val r = verify(
            "replace_string_in_file",
            mapOf("path" to "/p/a.kt", "new_string" to "val x = 2"),
            "fun main() { val x = 2 }",
        )
        assertTrue(r is WriteVerifier.Result.Verified)
    }

    @Test fun aPartialEditThatDidNotApplyIsReported() {
        val r = verify(
            "replace_string_in_file",
            mapOf("path" to "/p/a.kt", "new_string" to "val x = 2"),
            "fun main() { val x = 1 }",
        )
        assertTrue(r is WriteVerifier.Result.Differs)
    }

    @Test fun aWriteWithoutRecordedContentIsNotClaimedEitherWay() {
        val r = verify("apply_patch", mapOf("path" to "/p/a.kt"), "anything")
        assertTrue(r is WriteVerifier.Result.Uncheckable)
        assertNull("silence, not a false claim", WriteVerifier.line(r))
    }

    @Test fun aReadToolIsNotApplicable() {
        val r = verify("read_file", mapOf("path" to "/p/a.txt"), "hello")
        assertEquals(WriteVerifier.Result.NotApplicable, r)
    }

    @Test fun aLongBodyIsComparedByFingerprint() {
        val long = "z".repeat(ArgFingerprint.KEEP + 500)
        assertTrue(verify("write_file", mapOf("path" to "/p/big.txt", "content" to long), long)
            is WriteVerifier.Result.Verified)
        assertTrue(verify("write_file", mapOf("path" to "/p/big.txt", "content" to long), long + "!")
            is WriteVerifier.Result.Differs)
    }

    @Test fun everyVerifiableToolIsARealWriteTool() {
        assertTrue(WriteVerifier.VERIFIABLE.contains("write_file"))
        assertTrue(WriteVerifier.VERIFIABLE.contains("replace_string_in_file"))
        assertFalse(WriteVerifier.VERIFIABLE.contains("read_file"))
        WriteVerifier.VERIFIABLE.forEach {
            assertTrue("$it must be classified as side-effecting", ToolEffectIndex.effectOf(it) != com.codespace.ide.chat.panel.ToolEffect.READ_ONLY)
        }
    }
}
