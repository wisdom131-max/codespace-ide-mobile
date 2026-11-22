package com.codespace.ide.engine

import kotlin.math.min
import kotlin.math.sqrt

/**
 * PROJECT EIGHT 8.60 (catalog 45/46/51) - the retrieval half that used to be excluded as "too
 * heavy for Android".
 *
 * That rule is gone, so this exists. What it deliberately does NOT do is ship a model: a 300MB
 * embedder cannot be committed to a repo, and a feature that waits on a download is a feature that
 * is off. So the vector maths, the stored form and the ranking are real, testable, and available
 * now; the embedder is a seam a trained model can replace later without touching any caller.
 *
 * [HashingEmbedder] is the working default. It is not semantic in the way a trained model is: it
 * scores the words and word-shapes that a query and a document share. For ranking code that is
 * genuinely useful - it bridges "retrieve" and "retrieval" and "FactRetrieval" - and it is honest
 * about being a stand-in rather than pretending to understand meaning.
 */
object VectorIndex {

    /** Tokens, so "FactRetrieval" and "fact retrieval" are built from the same parts. */
    private val TOKEN = Regex("[A-Za-z0-9_]+")

    /** Default width. Small enough to store cheaply, wide enough that collisions stay rare. */
    const val DEFAULT_DIM: Int = 256

    data class Hit(val id: String, val score: Double)

    /**
     * Deterministic, dependency-free embedding.
     *
     * Three signals, weighted by how much they mean: the word itself, the character trigrams
     * inside it (so near-miss spellings and inflections land close), and the pair of adjacent
     * words (so "fact retrieval" is not the same vector as "retrieval fact"). The result is
     * L2-normalised, so [cosine] is a plain dot product and every vector has the same length.
     */
    fun embed(text: String, dim: Int = DEFAULT_DIM): FloatArray {
        val width = dim.coerceAtLeast(8)
        val v = FloatArray(width)
        if (text.isBlank()) return v
        val tokens = TOKEN.findAll(text.lowercase()).map { it.value }.toList()
        for (t in tokens) {
            bump(v, t, 1.0f)
            if (t.length > 3) {
                for (i in 0..t.length - 3) bump(v, t.substring(i, i + 3), 0.5f)
            }
        }
        for (i in 0 until tokens.size - 1) {
            bump(v, tokens[i] + "_" + tokens[i + 1], 0.35f)
        }
        return normalise(v)
    }

    private fun bump(v: FloatArray, s: String, w: Float) {
        val h = s.hashCode()
        val i = ((h % v.size) + v.size) % v.size
        v[i] += w
    }

    private fun normalise(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x.toDouble()
        val n = sqrt(sum)
        if (n == 0.0) return v
        for (i in v.indices) v[i] = (v[i] / n).toFloat()
        return v
    }

    /**
     * Zero-length or empty vectors score 0.0 rather than NaN. A NaN that reaches a sort silently
     * produces an arbitrary order, which is the kind of defect that looks like a ranking bug.
     */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        val n = min(a.size, b.size)
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until n) {
            dot += a[i].toDouble() * b[i].toDouble()
            na += a[i].toDouble() * a[i].toDouble()
            nb += b[i].toDouble() * b[i].toDouble()
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (sqrt(na) * sqrt(nb))
    }

    /** Highest scoring first. Zero and negative scores are dropped: they are not evidence. */
    fun topK(query: FloatArray, items: List<Pair<String, FloatArray>>, k: Int): List<Hit> =
        items.asSequence()
            .map { Hit(it.first, cosine(query, it.second)) }
            .filter { it.score > 0.0 }
            .sortedByDescending { it.score }
            .take(k.coerceAtLeast(1))
            .toList()

    /** Little-endian float32, the form a BLOB column stores without a second encoding. */
    fun encode(v: FloatArray): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(v.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (x in v) buf.putFloat(x)
        return buf.array()
    }

    /** A blob whose length is not a multiple of four is reported as empty, never misread. */
    fun decode(b: ByteArray): FloatArray {
        if (b.isEmpty() || b.size % 4 != 0) return FloatArray(0)
        val buf = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(b.size / 4)
        for (i in out.indices) out[i] = buf.float
        return out
    }
}
