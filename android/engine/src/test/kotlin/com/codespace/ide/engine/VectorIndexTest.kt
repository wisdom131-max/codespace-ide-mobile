package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorIndexTest {

    @Test
    fun identicalTextScoresOne() {
        val a = VectorIndex.embed("fact retrieval index")
        val b = VectorIndex.embed("fact retrieval index")
        assertEquals(1.0, VectorIndex.cosine(a, b), 1e-9)
    }

    @Test
    fun emptyTextScoresZeroAndNeverNaN() {
        val z = VectorIndex.embed("")
        val v = VectorIndex.embed("something at all")
        assertEquals(0.0, VectorIndex.cosine(z, v), 0.0)
        assertTrue("a zero vector must not produce NaN", !VectorIndex.cosine(z, z).isNaN())
    }

    @Test
    fun inflectionBeatsUnrelatedPhrase() {
        val q = VectorIndex.embed("retrieval")
        val near = VectorIndex.cosine(q, VectorIndex.embed("retrieve"))
        val far = VectorIndex.cosine(q, VectorIndex.embed("gradle assemble debug apk"))
        assertTrue("inflection should outrank an unrelated phrase ($near vs $far)", near > far)
    }

    @Test
    fun topKOrdersAndDropsNonEvidence() {
        val q = VectorIndex.embed("fact retrieval")
        val items = listOf(
            "a" to VectorIndex.embed("fact retrieval index for the project"),
            "b" to VectorIndex.embed("unrelated gradle task"),
        )
        val hits = VectorIndex.topK(q, items, 5)
        assertEquals("a", hits.first().id)
        assertTrue("zero scores are not evidence", hits.none { it.score <= 0.0 })
    }

    @Test
    fun encodeDecodeRoundTripsExactly() {
        val v = VectorIndex.embed("round trip vector")
        val back = VectorIndex.decode(VectorIndex.encode(v))
        assertEquals(v.size, back.size)
        for (i in v.indices) assertEquals(v[i], back[i], 0.0f)
    }

    @Test
    fun badBlobIsEmptyRatherThanGarbage() {
        assertEquals(0, VectorIndex.decode(byteArrayOf(1, 2, 3)).size)
        assertEquals(0, VectorIndex.decode(ByteArray(0)).size)
    }
}
