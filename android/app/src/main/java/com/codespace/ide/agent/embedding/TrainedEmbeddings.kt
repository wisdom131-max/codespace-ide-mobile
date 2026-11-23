package com.codespace.ide.agent.embedding

import android.content.Context
import com.codespace.ide.engine.TrainedEmbedding
import com.codespace.ide.engine.VectorIndex
import java.io.File

/**
 * PROJECT NINE 10.25 - fetch the trained model once, then hand search over to it.
 *
 * Nothing here blocks the app. Until the model is verified and loaded, search keeps using the
 * built-in hashing embedder, so a fresh install works immediately and a failed download is a
 * downgrade rather than a break.
 */
object TrainedEmbeddings {

    @Volatile private var embedder: OnnxEmbedder? = null
    @Volatile private var attempted = false
    @Volatile var progress: Int = 0
    @Volatile var lastError: String? = null

    fun modelDir(context: Context): File = File(context.filesDir, "models")

    fun isReady(): Boolean = embedder != null

    /** Called at startup. Safe to call repeatedly: it works at most once per process. */
    fun ensure(context: Context) {
        if (embedder != null) return
        synchronized(this) {
            if (embedder != null || attempted) return
            attempted = true
        }
        val app = context.applicationContext
        Thread {
            try {
                val dir = modelDir(app)
                val model = EmbeddingDownloader.fetch(TrainedEmbedding.MODEL, dir) { progress = it }
                if (model == null) {
                    lastError = "Could not download the model."
                    return@Thread
                }
                val vocab = EmbeddingDownloader.fetch(TrainedEmbedding.VOCAB, dir) { }
                if (vocab == null) {
                    lastError = "Could not download the vocabulary."
                    return@Thread
                }
                if (!TrainedEmbedding.ready(model, vocab)) {
                    lastError = "The downloaded files did not match their checksums."
                    return@Thread
                }
                val made = OnnxEmbedder(model, vocab)
                embedder = made
                VectorIndex.backend = { text -> made.embed(text) }
                progress = 100
            } catch (t: Throwable) {
                lastError = t.message ?: t::class.java.simpleName
            }
        }.apply { name = "embedding-model"; isDaemon = true }.start()
    }

    /** Plain language, for the context panel. Never claims ready when it is not. */
    fun status(context: Context): String {
        val dir = modelDir(context)
        val said = TrainedEmbedding.describe(File(dir, TrainedEmbedding.MODEL.name), File(dir, TrainedEmbedding.VOCAB.name))
        return when {
            isReady() -> "Trained model ready"
            lastError != null -> said + " (" + lastError + ")"
            progress in 1..99 -> "Downloading the trained model: " + progress + "%"
            else -> said + " - search is using the built-in matcher"
        }
    }
}
