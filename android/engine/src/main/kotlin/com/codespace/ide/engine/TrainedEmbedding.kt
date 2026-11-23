package com.codespace.ide.engine

import java.io.File
import java.security.MessageDigest

/**
 * PROJECT NINE 10.20 (catalog 45, trained model) - the real embedding model, and the rules for
 * trusting it.
 *
 * The hashing embedder matches word shapes and cannot know that "authentication" and "login" are
 * the same idea. This describes the trained model that can, and - more importantly - the rules for
 * deciding whether the file on disk is the file we meant to download.
 *
 * Every number here was read from the real artifact, not estimated: the byte count from the
 * server's content-length, the digest from hashing the downloaded file. A made-up checksum would
 * fail verification forever, which is worse than having no check at all.
 */
object TrainedEmbedding {

    data class Asset(val name: String, val url: String, val bytes: Long, val sha256: String)

    /** all-MiniLM-L6-v2, quantised. 384 dimensions, trained for sentence similarity. */
    val MODEL = Asset(
        name = "model_quantized.onnx",
        url = "https://huggingface.co/Xenova/bge-base-en-v1.5/resolve/main/onnx/model_quantized.onnx",
        bytes = 110_083_337L,
        sha256 = "c9729cc84cbd0e9fecc759505d2be65916c9fe05222d7ea26c65fcb3382af38d",
    )

    val VOCAB = Asset(
        name = "vocab.txt",
        url = "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/vocab.txt",
        bytes = 231_508L,
        sha256 = "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3",
    )

    /** Output width of this model. The vector store indexes on it, so it is stated once. */
    const val DIM = 768

    /** Stable name, used to label the backup archive so a restore can find it again. */
    const val ID = "bge-base-en-v1.5"

    /**
     * This model was trained to be told that a passage is a QUESTION. The instruction goes on
     * queries only; putting it on documents as well measurably hurts the ranking.
     */
    const val QUERY_PREFIX = "Represent this sentence for searching relevant passages: "

    enum class State {
        /** Nothing on disk, or an empty file. */
        ABSENT,
        /** Fewer bytes than expected: a download that stopped. Resumable. */
        PARTIAL,
        /** The right size and the right digest. Usable. */
        READY,
        /** Wrong size, or the right size with the wrong digest. Must be discarded. */
        CORRUPT,
    }

    /**
     * The digest is only computed when the size already matches, so the common case of a partial
     * file costs a length check rather than a hash of 22MB on the main thread.
     */
    fun stateOf(asset: Asset, file: File): State {
        if (!file.exists() || !file.isFile) return State.ABSENT
        val len = file.length()
        if (len == 0L) return State.ABSENT
        if (len < asset.bytes) return State.PARTIAL
        if (len > asset.bytes) return State.CORRUPT
        return if (sha256(file) == asset.sha256) State.READY else State.CORRUPT
    }

    /**
     * Where a resume should start. A file longer than expected is not resumed from its end: that
     * would append to something already wrong.
     */
    fun resumeOffset(asset: Asset, file: File): Long {
        if (!file.exists() || !file.isFile) return 0L
        val len = file.length()
        return if (len in 1..(asset.bytes - 1)) len else 0L
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val out = digest.digest()
        val hex = StringBuilder(out.size * 2)
        for (b in out) hex.append(String.format("%02x", b))
        return hex.toString()
    }

    /** Both files verified. Until this is true the hashing embedder stays in charge. */
    fun ready(model: File, vocab: File): Boolean =
        stateOf(MODEL, model) == State.READY && stateOf(VOCAB, vocab) == State.READY

    /** Plain-language state for the UI, so the panel never says "ready" when it is not. */
    fun describe(model: File, vocab: File): String {
        val m = stateOf(MODEL, model)
        val v = stateOf(VOCAB, vocab)
        return when {
            m == State.READY && v == State.READY -> "Trained model ready"
            m == State.ABSENT && v == State.ABSENT -> "Trained model not downloaded"
            m == State.PARTIAL || v == State.PARTIAL -> "Download incomplete"
            else -> "Download damaged, will re-fetch"
        }
    }
}
