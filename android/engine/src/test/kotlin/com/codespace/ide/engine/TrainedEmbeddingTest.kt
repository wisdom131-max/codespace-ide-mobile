package com.codespace.ide.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CATALOG 45: the rules for trusting the model file. Every case here is one that would otherwise
 * fail silently on a phone - a half download, a damaged file, a file from an older model version.
 */
class TrainedEmbeddingTest {

    private val tmp: File = File.createTempFile("trained", ".dir").also { it.delete(); it.mkdirs() }

    @After fun clean() { tmp.deleteRecursively() }

    private fun asset(bytes: Long, digest: String) = TrainedEmbedding.Asset("x", "u", bytes, digest)

    private fun fileWith(text: String): File =
        File(tmp, "f").also { it.writeText(text) }

    @Test fun missingFileIsAbsent() {
        assertEquals(TrainedEmbedding.State.ABSENT, TrainedEmbedding.stateOf(asset(10, "x"), File(tmp, "nope")))
    }

    @Test fun emptyFileIsAbsentNotPartial() {
        val f = fileWith("")
        assertEquals(TrainedEmbedding.State.ABSENT, TrainedEmbedding.stateOf(asset(10, "x"), f))
    }

    @Test fun shortFileIsPartialAndResumesFromItsEnd() {
        val f = fileWith("12345")
        assertEquals(TrainedEmbedding.State.PARTIAL, TrainedEmbedding.stateOf(asset(10, "x"), f))
        assertEquals(5L, TrainedEmbedding.resumeOffset(asset(10, "x"), f))
    }

    @Test fun longerFileIsCorruptAndNeverResumed() {
        val f = fileWith("123456789012")
        assertEquals(TrainedEmbedding.State.CORRUPT, TrainedEmbedding.stateOf(asset(10, "x"), f))
        assertEquals(0L, TrainedEmbedding.resumeOffset(asset(10, "x"), f))
    }

    @Test fun rightSizeWrongDigestIsCorrupt() {
        val f = fileWith("1234567890")
        assertEquals(TrainedEmbedding.State.CORRUPT, TrainedEmbedding.stateOf(asset(10, "wrong"), f))
    }

    @Test fun rightSizeRightDigestIsReady() {
        val f = fileWith("1234567890")
        val digest = TrainedEmbedding.sha256(f)
        assertEquals(TrainedEmbedding.State.READY, TrainedEmbedding.stateOf(asset(10, digest), f))
    }

    @Test fun readyNeedsBothFiles() {
        val f = fileWith("1234567890")
        val digest = TrainedEmbedding.sha256(f)
        // A local asset pair rather than the real one, so this stays fast and offline.
        assertTrue(digest.length == 64)
        assertFalse(TrainedEmbedding.ready(File(tmp, "no-model"), File(tmp, "no-vocab")))
    }

    @Test fun describeNeverClaimsReadyWhenAbsent() {
        val said = TrainedEmbedding.describe(File(tmp, "no-model"), File(tmp, "no-vocab"))
        assertFalse(said.contains("ready", ignoreCase = true))
    }

    @Test fun hashingEmbedderStaysInChargeWithNoBackend() {
        VectorIndex.backend = null
        val a = VectorIndex.embed("fact retrieval index")
        val b = VectorIndex.embed("fact retrieval index")
        assertEquals(1.0, VectorIndex.cosine(a, b), 1e-9)
    }

    @Test fun trainedBackendTakesOverWhenSet() {
        val sentinel = FloatArray(TrainedEmbedding.DIM) { 1.0f }
        VectorIndex.backend = { sentinel }
        val out = VectorIndex.embed("anything")
        assertEquals(TrainedEmbedding.DIM, out.size)
        assertEquals(1.0f, out[0], 0.0f)
        VectorIndex.backend = null
    }

    @Test
    fun aQueryGetsItsOwnPathAndDocumentsDoNot() {
        val oldDoc = VectorIndex.backend
        val oldQuery = VectorIndex.queryBackend
        try {
            VectorIndex.backend = { _ -> floatArrayOf(1f, 0f) }
            VectorIndex.queryBackend = { t -> if (t.startsWith(TrainedEmbedding.QUERY_PREFIX)) floatArrayOf(0f, 1f) else floatArrayOf(1f, 0f) }
            val q = VectorIndex.embedQuery(TrainedEmbedding.QUERY_PREFIX + "login")
            val d = VectorIndex.embed("authentication service")
            assertEquals(1f, q[1], 0f)
            assertEquals(1f, d[0], 0f)
        } finally {
            VectorIndex.backend = oldDoc
            VectorIndex.queryBackend = oldQuery
        }
    }
}
