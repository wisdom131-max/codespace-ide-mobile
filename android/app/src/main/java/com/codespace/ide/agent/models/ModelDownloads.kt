package com.codespace.ide.agent.models

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.codespace.ide.agent.embedding.EmbeddingDownloader
import com.codespace.ide.agent.embedding.ModelHome
import com.codespace.ide.agent.embedding.TrainedEmbeddings
import com.codespace.ide.agent.voice.SherpaModels
import com.codespace.ide.engine.TrainedEmbedding
import java.io.File

/**
 * OWNER REQUEST (2026-11-23): "I need the model to be able to be automatically downloadable and
 * share the same download screen as the embedding does. I need both the embedding and the model to
 * be able to auto healing while downloading fails mid turn."
 *
 * So there is ONE download path, not two. Both models go through EmbeddingDownloader.fetch, which is
 * the code that already resumes a half-finished file with a Range request, refuses a file whose
 * checksum is wrong, and retries three times before giving up. The speech model was previously on its
 * own separate downloader with no resume and no retry; that is gone.
 *
 * Both models live in the same folder - ModelHome's writable home, the public CodespaceIDE/models
 * copy that survives an uninstall - with speech in an "asr" subfolder so the two never collide.
 *
 * Auto-healing works in three layers, because a download can fail at three different moments:
 *  1. mid-file, network drops: the .part file stays, the next attempt resumes from its offset;
 *  2. mid-run, a file keeps failing: three attempts per file, then the run stops with the partials
 *     kept, so nothing already downloaded is thrown away;
 *  3. mid-turn, the app is killed or the owner leaves: nothing is lost, because the next call to
 *     ensureAll - opening the status screen or tapping the microphone - picks up exactly where the
 *     last run stopped.
 */
object ModelDownloads {

    /** Speech model progress, read by the panel and by the settings row. */
    val speechStatus = mutableStateOf("")
    val speechPercent = mutableStateOf(0)

    @Volatile private var speechRunning = false
    @Volatile private var embeddingRunning = false
    @Volatile private var autoStarted = false
    private val lock = Any()

    /** The same home the embedding model uses, so a reinstall finds both. */
    fun speechDir(context: Context): File =
        File(ModelHome.writableHome(context), "asr").apply { mkdirs() }

    fun isSpeechReady(context: Context): Boolean {
        val dir = speechDir(context)
        return SherpaModels.DEFAULT.files.all { f ->
            val file = File(dir, f.name)
            file.isFile && file.length() == f.bytes
        }
    }

    /**
     * One line per model, for the panel that already shows the embedding status. Two models, one
     * screen, and each line says exactly what is happening: downloading with a percentage, ready, or
     * stopped with the reason.
     */
    fun statusLine(context: Context): String {
        val embedding = runCatching { TrainedEmbeddings.describe(context) }.getOrDefault("Embedding model unavailable.")
        val speech = when {
            speechStatus.value.isNotBlank() -> speechStatus.value
            isSpeechReady(context) -> "Ready (offline)"
            else -> "Not downloaded yet. Tap the microphone or Download now to fetch it."
        }
        return "Embedding: " + embedding + "\nSpeech: " + speech
    }

    /** Both models, started once per process when the status screen is first read. */
    fun autoStart(context: Context) {
        if (autoStarted) return
        autoStarted = true
        ensureAll(context)
    }

    fun ensureAll(context: Context) {
        ensureEmbedding(context)
        ensureSpeech(context)
    }

    fun ensureEmbedding(context: Context) {
        synchronized(lock) {
            if (embeddingRunning) return
            embeddingRunning = true
        }
        Thread({
            runCatching { TrainedEmbeddings.ensure(context) }
            synchronized(lock) { embeddingRunning = false }
        }, "vncode-embedding-download").start()
    }

    /**
     * Fetches the speech model, resuming whatever a previous attempt left behind. Safe to call on
     * every use: it returns immediately when the model is ready or a run is already going.
     */
    fun ensureSpeech(context: Context) {
        if (isSpeechReady(context)) {
            speechStatus.value = "Ready (offline)"
            speechPercent.value = 100
            return
        }
        synchronized(lock) {
            if (speechRunning) return
            speechRunning = true
        }
        Thread({
            val dir = speechDir(context)
            val files = SherpaModels.DEFAULT.files
            val total = files.sumOf { it.bytes }
            var doneBefore = 0L
            var failedFile: String? = null
            for (f in files) {
                val asset = TrainedEmbedding.Asset(
                    name = f.name,
                    url = f.url,
                    bytes = f.bytes,
                    sha256 = f.sha256,
                )
                val base = doneBefore
                val got = runCatching {
                    EmbeddingDownloader.fetch(asset, dir) { percent ->
                        val soFar = base + (f.bytes * percent / 100)
                        val overall = ((soFar * 100) / total).toInt().coerceIn(0, 100)
                        speechPercent.value = overall
                        speechStatus.value = "Downloading " + overall + "% \u00b7 " +
                            (soFar / 1_048_576) + "MB of " + (total / 1_048_576) + "MB"
                    }
                }.getOrNull()
                if (got == null) {
                    failedFile = f.name
                    break
                }
                doneBefore += f.bytes
                speechPercent.value = ((doneBefore * 100) / total).toInt().coerceIn(0, 100)
            }
            synchronized(lock) { speechRunning = false }
            val problem = failedFile
            if (problem != null) {
                // Deliberately NOT clearing progress: whatever arrived is kept, and the next call
                // resumes from it instead of starting the 45MB again.
                speechStatus.value = "Stopped at " + speechPercent.value + "% (" + problem +
                    "). It resumes automatically next time."
            } else if (isSpeechReady(context)) {
                speechPercent.value = 100
                speechStatus.value = "Ready (offline)"
            } else {
                speechStatus.value = "Downloaded but could not be verified. It will retry."
            }
        }, "vncode-speech-download").start()
    }
}
