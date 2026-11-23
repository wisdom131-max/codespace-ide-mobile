package com.codespace.ide.agent.embedding

import android.content.Context
import com.codespace.ide.engine.TrainedEmbedding
import com.codespace.ide.engine.VectorIndex
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * PROJECT NINE 10.31 - fetch the trained model once, then hand search over to it.
 *
 * Nothing here blocks the app. Until the model is verified and loaded, search keeps using the
 * built-in hashing embedder, so a fresh install works immediately and a failed download is a
 * downgrade rather than a break.
 *
 * Order of preference, cheapest first:
 *   1. a verified copy in the model's home (public storage, which survives an uninstall)
 *   2. a verified copy anywhere else the app has kept one
 *   3. the owner's cloud backup
 *   4. a fresh download
 */
object TrainedEmbeddings {

    @Volatile private var embedder: OnnxEmbedder? = null
    @Volatile private var attempted = false
    @Volatile var progress: Int = 0
    @Volatile var lastError: String? = null
    @Volatile var homePath: String = ""

    fun modelDir(context: Context): File = ModelHome.writableHome(context)

    fun isReady(): Boolean = embedder != null

    /** A verified pair in the given folder, or null. */
    private fun verifiedIn(dir: File): Pair<File, File>? {
        val m = File(dir, TrainedEmbedding.MODEL.name)
        val v = File(dir, TrainedEmbedding.VOCAB.name)
        return if (TrainedEmbedding.ready(m, v)) m to v else null
    }

    /** Called at startup. Safe to call repeatedly: it works at most once per process. */
    fun ensure(context: Context, backendUrl: String = "", authToken: String = "") {
        if (embedder != null) return
        synchronized(this) {
            if (embedder != null || attempted) return
            attempted = true
        }
        val app = context.applicationContext
        val canBackup = backendUrl.isNotBlank() && authToken.isNotBlank()
        Thread {
            try {
                val home = ModelHome.writableHome(app)
                home.mkdirs()
                homePath = home.absolutePath

                var found = verifiedIn(home)
                if (found == null) {
                    // The copy that survives an uninstall may be in the other home already.
                    for (dir in ModelHome.searchOrder(app)) {
                        val hit = verifiedIn(dir)
                        if (hit != null) {
                            found = hit
                            break
                        }
                    }
                }

                if (found == null && canBackup) {
                    runBlocking { ModelBackup.restore(app, backendUrl, authToken, TrainedEmbedding.ID) }
                    for (dir in ModelHome.searchOrder(app)) {
                        val hit = verifiedIn(dir)
                        if (hit != null) {
                            found = hit
                            progress = 100
                            break
                        }
                    }
                }

                if (found == null) {
                    val gotModel = EmbeddingDownloader.fetch(TrainedEmbedding.MODEL, home) { progress = it }
                    if (gotModel == null) {
                        lastError = "Could not download the model."
                        return@Thread
                    }
                    val gotVocab = EmbeddingDownloader.fetch(TrainedEmbedding.VOCAB, home) { }
                    if (gotVocab == null) {
                        lastError = "Could not download the vocabulary."
                        return@Thread
                    }
                    if (!TrainedEmbedding.ready(gotModel, gotVocab)) {
                        lastError = "The downloaded files did not match their checksums."
                        return@Thread
                    }
                    found = gotModel to gotVocab
                    if (canBackup) {
                        runBlocking { ModelBackup.upload(app, backendUrl, authToken, TrainedEmbedding.ID) }
                    }
                }

                val (model, vocab) = found
                // Keep the good copy in the home that survives an uninstall. Best effort: a
                // failure here costs a future download, never the working model.
                if (model.parentFile?.absolutePath != home.absolutePath) {
                    runCatching {
                        model.copyTo(File(home, model.name), overwrite = true)
                        vocab.copyTo(File(home, vocab.name), overwrite = true)
                    }
                }

                val made = OnnxEmbedder(model, vocab)
                embedder = made
                VectorIndex.backend = { text -> made.embed(text) }
                // This model expects a question to be announced as one; documents must not be.
                VectorIndex.queryBackend = { text -> made.embed(TrainedEmbedding.QUERY_PREFIX + text) }
                progress = 100
            } catch (t: Throwable) {
                lastError = t.message ?: t::class.java.simpleName
            }
        }.apply { name = "embedding-model"; isDaemon = true }.start()
    }

    /** One line for the panel: where the model lives and whether it is in use. */
    fun describe(context: Context): String {
        val where = if (ModelHome.isPublic(context)) "shared storage" else "app storage"
        return when {
            isReady() -> "Trained model ready (" + where + ")"
            lastError != null -> "Trained model unavailable: " + lastError + ". Using built-in matching."
            progress in 1..99 -> "Downloading trained model: " + progress + "%"
            else -> "Trained model not downloaded yet. Using built-in matching."
        }
    }
}
