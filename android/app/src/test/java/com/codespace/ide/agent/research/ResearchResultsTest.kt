package com.codespace.ide.agent.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PROJECT 1.11: another project reads research as claims, not prose.
 *
 * The buckets are the point: a consumer must be able to tell a fact two sources agree on from a
 * claim only one source made, because those two deserve different confidence downstream.
 */
class ResearchResultsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun claim(subject: String, value: String, sourceId: String) =
        ContradictionScan.Claim(subject, value, sourceId, "quote for " + subject)

    private fun brief(question: String) = ResearchSynthesis.Brief(
        question = question,
        corroborated = listOf(claim("min sdk", "26", "S1"), claim("min sdk", "26", "S2")),
        singleSource = listOf(claim("compile sdk", "34", "S1")),
        disputed = listOf(ContradictionScan.Dispute("target sdk", listOf(
            claim("target sdk", "28", "S1"), claim("target sdk", "34", "S3")))),
        unresolved = listOf("which emulator image"),
        followUps = listOf("does targetSdk 28 still ship"),
        stop = ResearchSynthesis.StopReason.SUFFICIENT,
        recovery = emptyList(),
        render = "rendered text",
        continued = false,
    )

    private fun store(): ResearchResults {
        val f = tmp.newFile("r.json")
        f.delete()
        return ResearchResults.forTest(f)
    }

    @Test
    fun theThreeBucketsSurviveARoundTrip() {
        val s = store()
        s.save("android sdk levels", 1000L, brief("android sdk levels"))
        val fresh = ResearchResults.forTest(java.io.File(tmp.root, "r.json")).latest()!!
        assertEquals(2, fresh.corroborated.size)
        assertEquals(1, fresh.unsupported.size)
        assertEquals(2, fresh.disputed.size)
        assertEquals("S3", fresh.disputed[1].sourceId)
        assertEquals("which emulator image", fresh.unresolved.single())
    }

    @Test
    fun theSameQuestionReplacesItsResult() {
        val s = store()
        s.save("android sdk levels", 1L, brief("android sdk levels"))
        s.save("Android SDK levels", 2L, brief("Android SDK levels"))
        assertEquals(1, s.all().size)
        assertEquals(2L, s.latest()!!.atMs)
    }

    @Test
    fun aRelatedQuestionFindsItsResult() {
        val s = store()
        s.save("android sdk levels for the build", 1L, brief("android sdk levels for the build"))
        s.save("kotlin coroutine dispatch", 2L, brief("kotlin coroutine dispatch"))
        val hit = s.forQuestion("what android sdk levels does the build use")!!
        assertTrue(hit.question.contains("android sdk levels"))
    }

    @Test
    fun anEmptyStoreIsNullNotAGuess() {
        assertNull(store().latest())
        assertNull(store().forQuestion("anything at all"))
    }

    @Test
    fun aCorruptFileReadsAsEmpty() {
        val f = tmp.newFile("bad.json")
        f.writeText("[{]")
        assertEquals(0, ResearchResults.forTest(f).all().size)
    }
}
