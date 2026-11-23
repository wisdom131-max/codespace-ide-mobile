package com.codespace.ide.agent.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * OWN OUR AUDIO, FINISHED (owner decision, 2026-11-23): "Vendor the official prebuilt library from
 * the sherpa-onnx releases, pinned by checksum. One file in the repo, no build tools needed."
 *
 * The library is vendored at app/libs/sherpa-onnx-2.0.3-static.aar (official release asset, 39MB,
 * static-linked onnxruntime, sha256 7744a3a8...b977b4, four ABIs, no ai.onnxruntime classes so it
 * cannot collide with the embedder's onnxruntime-android). This file is the engine on top of it: the
 * model it needs, the verified download, and the streaming recogniser.
 *
 * Everything here is verifiable: the model files come from a named HuggingFace repo, each pinned by
 * its real sha256 and byte size, computed from the actual files - not a guess.
 */
data class SherpaModelFile(
    val name: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
)

data class SherpaModel(
    val id: String,
    val label: String,
    val languages: String,
    val files: List<SherpaModelFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }
}

object SherpaModels {

    private const val REPO = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/main"

    /**
     * Streaming zipformer, English, 20M parameters, int8. Chosen over the bigger variants because the
     * phone has to decode while the owner is still talking: 45MB of model, not 150.
     */
    val ENGLISH_20M = SherpaModel(
        id = "zipformer-en-20M-int8",
        label = "English (streaming, 20M)",
        languages = "en",
        files = listOf(
            SherpaModelFile(
                name = "encoder-epoch-99-avg-1.int8.onnx",
                url = "$REPO/encoder-epoch-99-avg-1.int8.onnx",
                sha256 = "3810755ce7c3ab26b42a8bcf39d191308fa27fb0f53358823ba46141d03b7eb3",
                bytes = 42_845_182L,
            ),
            SherpaModelFile(
                name = "decoder-epoch-99-avg-1.onnx",
                url = "$REPO/decoder-epoch-99-avg-1.onnx",
                sha256 = "45a7f940ecfb53d89fa270ad11b88b961e53a317203eb24b1c8e95ed208b0f30",
                bytes = 2_092_272L,
            ),
            SherpaModelFile(
                name = "joiner-epoch-99-avg-1.int8.onnx",
                url = "$REPO/joiner-epoch-99-avg-1.int8.onnx",
                sha256 = "e085d73b593cf9b0707f370dbd656d58327d3fe36d80d849202ef81df02cb01e",
                bytes = 259_572L,
            ),
            SherpaModelFile(
                name = "tokens.txt",
                url = "$REPO/tokens.txt",
                sha256 = "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb",
                bytes = 5_048L,
            ),
        ),
    )

    val ALL = listOf(ENGLISH_20M)
    val DEFAULT = ENGLISH_20M
}

/** Where the model lives, and how it gets there: verified, resumable, and honest about failure. */
object SherpaModelStore {

    /**
     * The same shared location the embedding model uses, so a model survives an uninstall, with the
     * app's own folder as the fallback when shared storage is not writable.
     */
    fun dir(context: Context): File =
        com.codespace.ide.agent.models.ModelDownloads.speechDir(context)

    /**
     * Presence, not integrity: this runs on the microphone tap, and hashing 45MB there would make the
     * mic feel broken. Integrity is checked once, at download time, by EmbeddingDownloader - which
     * refuses a file whose checksum is wrong and keeps the partial for a resumed retry.
     */
    fun isInstalled(context: Context, model: SherpaModel = SherpaModels.DEFAULT): Boolean {
        val d = dir(context)
        return model.files.all { f ->
            val file = File(d, f.name)
            file.isFile && file.length() == f.bytes
        }
    }

    /** What is missing or wrong, in words a person can act on. */
    fun status(context: Context, model: SherpaModel = SherpaModels.DEFAULT): String {
        val d = dir(context)
        val bad = model.files.filter { f ->
            val file = File(d, f.name)
            !file.isFile || file.length() != f.bytes || sha256(file) != f.sha256
        }
        if (bad.isEmpty()) return "On-device speech is ready (offline)."
        return "On-device speech needs " + (model.totalBytes / 1_048_576) + "MB downloaded (" +
            bad.size + " of " + model.files.size + " files missing or damaged)."
    }

    /**
     * Downloads whatever is missing, verifying every file against its pinned hash. A file that
     * arrives damaged is deleted and reported, never installed: a half-model is worse than none.
     */
    fun ensureDownloaded(
        context: Context,
        model: SherpaModel = SherpaModels.DEFAULT,
        onProgress: (doneBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<Unit> {
        val d = dir(context)
        var done = 0L
        for (f in model.files) {
            val target = File(d, f.name)
            if (target.isFile && target.length() == f.bytes && sha256(target) == f.sha256) {
                done += f.bytes
                onProgress(done, model.totalBytes)
                continue
            }
            val tmp = File(d, f.name + ".part")
            runCatching {
                tmp.delete()
                val conn = (URL(f.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                }
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
            }.onFailure { t ->
                tmp.delete()
                return Result.failure(Exception("Could not download " + f.name + ": " + (t.message ?: "network error")))
            }
            if (tmp.length() != f.bytes || sha256(tmp) != f.sha256) {
                tmp.delete()
                return Result.failure(Exception(f.name + " arrived damaged (checksum mismatch); it was deleted."))
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) return Result.failure(Exception("Could not save " + f.name + "."))
            done += f.bytes
            onProgress(done, model.totalBytes)
        }
        return Result.success(Unit)
    }

    private fun sha256(file: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { b -> "%02x".format(b) }
    }.getOrDefault("")
}

/**
 * The streaming recogniser itself. One instance per session, reset between turns, released when the
 * panel goes away: a native recogniser that is never released holds memory and a decoder thread.
 */
class SherpaAsr(context: Context, model: SherpaModel = SherpaModels.DEFAULT) {

    private var recognizer: OnlineRecognizer? = null
    private var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
    private var failed: String? = null

    /** Why it could not start, when it could not: never a silent failure. */
    val error: String? get() = failed

    init {
        runCatching {
            if (!SherpaModelStore.isInstalled(context, model)) {
                failed = "The on-device model is not installed yet."
            } else {
                val d = SherpaModelStore.dir(context)
                val config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = File(d, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
                            decoder = File(d, "decoder-epoch-99-avg-1.onnx").absolutePath,
                            joiner = File(d, "joiner-epoch-99-avg-1.int8.onnx").absolutePath,
                        ),
                        tokens = File(d, "tokens.txt").absolutePath,
                        numThreads = 2,
                        modelType = "zipformer",
                    ),
                    // Three endpoint rules, the way sherpa-onnx itself does it: a short silence ends a
                    // short phrase, a longer one ends a normal turn, and a long utterance is cut.
                    endpointConfig = EndpointConfig(
                        rule1 = com.k2fsa.sherpa.onnx.EndpointRule(
                            mustContainNonsilence = true,
                            minTrailingSilence = 1.2f,
                            minUtteranceLength = 0f,
                        ),
                        rule2 = com.k2fsa.sherpa.onnx.EndpointRule(
                            mustContainNonsilence = true,
                            minTrailingSilence = 2.4f,
                            minUtteranceLength = 0f,
                        ),
                        rule3 = com.k2fsa.sherpa.onnx.EndpointRule(
                            mustContainNonsilence = false,
                            minTrailingSilence = 0f,
                            minUtteranceLength = 30f,
                        ),
                    ),
                    decodingMethod = "greedy_search",
                    enableEndpoint = true,
                )
                val r = OnlineRecognizer(config = config)
                recognizer = r
                stream = r.createStream()
            }
        }.onFailure { t ->
            failed = "The on-device engine could not start: " + (t.message ?: "unknown error")
            release()
        }
    }

    val isReady: Boolean get() = recognizer != null && stream != null

    fun reset() {
        stream?.let { s ->
            recognizer?.reset(s)
        }
    }

    /** Feed one block of 16-bit mono audio; decode whatever is decodable so far. */
    fun accept(samples: ShortArray) {
        val r = recognizer ?: return
        val s = stream ?: return
        if (samples.isEmpty()) return
        runCatching {
            // BUILD 4080: "Unresolved reference: acceptWaveform" for a ShortArray. sherpa-onnx 2.x
            // takes FloatArray in [-1, 1], so the 16-bit samples our recorder keeps are converted
            // here. Dividing by 32768 is the exact inverse of the recorder's scale.
            val floats = FloatArray(samples.size) { i -> samples[i] / 32768f }
            r.acceptWaveform(s, floats)
            while (r.isReady(s)) r.decode(s)
        }
    }

    /** The words so far. */
    fun text(): String {
        val r = recognizer ?: return ""
        val s = stream ?: return ""
        return runCatching { r.getResult(s).text.trim() }.getOrDefault("")
    }

    fun release() {
        runCatching { stream?.release() }
        stream = null
        runCatching { recognizer?.release() }
        recognizer = null
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
