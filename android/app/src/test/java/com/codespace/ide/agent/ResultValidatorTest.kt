package com.codespace.ide.agent

import com.codespace.ide.agent.runtime.PrereqLedger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import org.json.JSONObject

/** Stage 4.5: what a result proves, and what the ledger is allowed to believe because of it. */
class ResultValidatorTest {

    @After
    fun tearDown() = PrereqLedger.clear()

    @Test
    fun aRealWriteIsValidEvidence() {
        val v = ResultValidator.validate("write_file", "Wrote 42 chars to Main.kt")
        assertEquals(ResultValidator.Outcome.VALID, v.outcome)
        assertTrue(v.satisfiesPrerequisite)
        assertEquals("", ResultValidator.note("write_file", v))
    }

    @Test
    fun anEmptySearchIsAValidAnswer() {
        val v = ResultValidator.validate("grep_search", "")
        assertEquals(ResultValidator.Outcome.EMPTY_VALID, v.outcome)
        assertTrue(v.satisfiesPrerequisite)
    }

    @Test
    fun anEmptyReadIsNotEvidence() {
        val v = ResultValidator.validate("read_file", "   ")
        assertEquals(ResultValidator.Outcome.EMPTY_UNEXPECTED, v.outcome)
        assertFalse(v.satisfiesPrerequisite)
        assertTrue(v.countsAgainstTool)
        assertTrue(ResultValidator.note("read_file", v).isNotBlank())
    }

    @Test
    fun aWriteThatDidNotAcknowledgeTheChangeIsNotProof() {
        val v = ResultValidator.validate("write_file", "ok")
        assertEquals(ResultValidator.Outcome.UNACKNOWLEDGED, v.outcome)
        assertFalse(v.satisfiesPrerequisite)
    }

    @Test
    fun aTruncatedResultIsRealButNotComplete() {
        val v = ResultValidator.validate("read_file", "line one\n(partial)")
        assertEquals(ResultValidator.Outcome.PARTIAL, v.outcome)
        assertTrue(v.establishesSomething)
        assertFalse("a partial result cannot satisfy a complete prerequisite", v.satisfiesPrerequisite)
        assertFalse(v.countsAgainstTool)
    }

    @Test
    fun malformedJsonIsRefusedRatherThanParsed() {
        val v = ResultValidator.validate("json_query", "not json at all", expectsJson = true)
        assertEquals(ResultValidator.Outcome.MALFORMED, v.outcome)
        assertFalse(v.satisfiesPrerequisite)
    }

    @Test
    fun aClearFailureIsAFailureWhateverElseTheOutputSays() {
        val v = ResultValidator.validate("read_file", "Error: no such file or directory")
        assertEquals(ResultValidator.Outcome.FAILED, v.outcome)
        assertFalse(v.satisfiesPrerequisite)
    }

    @Test
    fun aThrowingToolIsAFailure() {
        val v = ResultValidator.validate("read_file", "", threw = IllegalStateException("boom"))
        assertEquals(ResultValidator.Outcome.FAILED, v.outcome)
    }

    @Test
    fun aValidatorBugIsNotTheToolsFault() {
        val v = ResultValidator.Validation(
            ResultValidator.Outcome.VALIDATOR_FAILED, "it could not be checked", false, false)
        assertFalse(v.countsAgainstTool)
        assertFalse(v.satisfiesPrerequisite)
        assertTrue(ResultValidator.note("read_file", v).contains("unproven"))
    }

    @Test
    fun aTestRunWithoutAnyResultIsNotAcknowledged() {
        val v = ResultValidator.validate("runTests", "starting")
        assertEquals(ResultValidator.Outcome.UNACKNOWLEDGED, v.outcome)
        val good = ResultValidator.validate("runTests", "34 tests passed")
        assertEquals(ResultValidator.Outcome.VALID, good.outcome)
    }

    @Test
    fun onlyValidatedEvidenceSatisfiesAPrerequisite() {
        PrereqLedger.record("find", "grep_search", "3 hits", validation = "valid")
        assertTrue(PrereqLedger.satisfied("find"))

        PrereqLedger.record("replace", "grep_replace_all", "ok", validation = "unacknowledged")
        assertFalse("a call that did not confirm the change satisfies nothing", PrereqLedger.satisfied("replace"))

        PrereqLedger.record("structure", "read_project_structure", "cut off", validation = "partial")
        assertFalse(PrereqLedger.satisfied("structure"))

        PrereqLedger.record("read", "read_file", "text", validation = "empty valid")
        assertTrue(PrereqLedger.satisfied("read"))
    }

    @Test
    fun aSideEffectThatDidNotLandIsRefuted() {
        val dir = Files.createTempDirectory("vncode-verify").toFile()
        val target = File(dir, "Main.kt")
        val args = JSONObject().put("path", target.absolutePath).put("content", "fun main() {}")
        // The file was never written.
        val checked = SideEffectVerifier.verify("write_file", args, null)
        assertEquals(SideEffectVerifier.Result.REFUTED, checked.result)
        assertFalse(checked.ok)

        target.writeText("fun main() {}")
        val good = SideEffectVerifier.verify("write_file", args, null)
        assertEquals(SideEffectVerifier.Result.VERIFIED, good.result)

        target.writeText("something else entirely")
        val mismatched = SideEffectVerifier.verify("write_file", args, null)
        assertEquals("the file is there but its contents do not match", SideEffectVerifier.Result.REFUTED, mismatched.result)
    }

    @Test
    fun aVerificationThatCannotBeCheckedSaysNothing() {
        val args = JSONObject().put("path", "relative/thing.kt")
        val checked = SideEffectVerifier.verify("write_file", args, null)
        assertEquals(SideEffectVerifier.Result.NOT_CHECKABLE, checked.result)
        assertTrue(checked.note.isEmpty())
    }

    @Test
    fun aReadIsNeverGivenAFileVerification() {
        val args = JSONObject().put("path", "/tmp/whatever")
        assertEquals(SideEffectVerifier.Result.NOT_CHECKABLE, SideEffectVerifier.verify("read_file", args, null).result)
    }
}
