package com.codespace.ide.agent.embedding

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer

/**
 * PROJECT NINE 10.24 - run the trained model and turn its output into one vector.
 *
 * The model returns a vector per token. A search wants one vector per passage, so the token
 * vectors are averaged and then scaled to unit length, which is what cosine similarity expects.
 */
class OnnxEmbedder(modelFile: File, vocabFile: File) : Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        modelFile.absolutePath,
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) },
    )
    private val tokenizer = WordPieceTokenizer(vocabFile)

    fun embed(text: String): FloatArray {
        val ids = tokenizer.encode(text, MAX_TOKENS)
        val n = ids.size
        val shape = longArrayOf(1L, n.toLong())
        val mask = LongArray(n) { 1L }
        val idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
        val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape)
        // try/finally as an expression: the tensors are always released, and the value is
        // produced on the one path that completes.
        val pooled: FloatArray = try {
            val inputs = HashMap<String, OnnxTensor>(2)
            inputs["input_ids"] = idsTensor
            inputs["attention_mask"] = maskTensor
            session.run(inputs).use { result -> pool(result, n) }
        } finally {
            idsTensor.close()
            maskTensor.close()
        }
        return pooled
    }

    /** Average the token vectors, then scale to unit length. */
    private fun pool(result: OrtSession.Result, tokenCount: Int): FloatArray {
        @Suppress("UNCHECKED_CAST")
        val hidden = result.get(0).value as Array<Array<FloatArray>>
        val dim = hidden[0][0].size
        val out = FloatArray(dim)
        for (t in 0 until tokenCount) {
            val row = hidden[0][t]
            for (d in 0 until dim) out[d] += row[d]
        }
        var norm = 0.0
        for (d in 0 until dim) norm += out[d].toDouble() * out[d]
        norm = Math.sqrt(norm)
        if (norm > 1e-8) {
            for (d in 0 until dim) out[d] = (out[d] / norm).toFloat()
        }
        return out
    }

    override fun close() {
        try {
            session.close()
        } catch (t: Throwable) {
            // already gone
        }
    }

    private companion object {
        const val MAX_TOKENS = 256
    }
}
