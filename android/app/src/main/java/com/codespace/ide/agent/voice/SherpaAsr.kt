package com.codespace.ide.agent.voice

import android.content.Context
import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.security.MessageDigest

/**
 * OWN OUR AUDIO, FINISHED (owner decision, 2026-11-23): the official prebuilt sherpa-onnx library is
 * vendored at app/libs/sherpa-onnx-2.0.3-static.aar, pinned by checksum, with the model pinned file by
 * file. This is the engine on top of it.
 *
 * The API here was read out of the vendored AAR's own bytecode, not from a demo, after builds 4080 and
 * 4082 failed on my guesses. What the class file actually says:
 *  - OnlineRecognizer has ONE constructor: (AssetManager, OnlineRecognizerConfig). There is no
 *    config-only constructor, so the asset manager is passed even though the model is read from an
 *    absolute path.
 *  - acceptWaveform is NOT on the recogniser. It is on the STREAM: acceptWaveform(float[], int).
 *  - getResult returns OnlineRecognizerResult, whose text is a property, not a String.
 *  - EndpointRule(boolean, float, float) and EndpointConfig(rule1, rule2, rule3).
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

    private const val REPO =
        "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/main"

    /**
     * Streaming zipformer, English, 20M parameters, int8. Chosen over the bigger variants because the
     * phone has to decode while the owner is still talking: 45MB of model, not 150. Every hash below
     * was computed from the real downloaded file.
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

/** Where the model lives. Downloading is ModelDownloads' job - one path for both models. */
object SherpaModelStore {

    fun dir(context: Context): File = com.codespace.ide.agent.models.ModelDownloads.speechDir(context)

    /**
     * Presence and size, not integrity: this runs on the microphone tap and hashing 45MB there would
     * make the mic feel broken. Integrity is verified once, at download time.
     */
    fun isInstalled(context: Context, model: SherpaModel = SherpaModels.DEFAULT): Boolean {
        val d = dir(context)
        return model.files.all { f ->
            val file = File(d, f.name)
            file.isFile && file.length() == f.bytes
        }
    }

    /** What is missing or damaged, in words a person can act on. */
    fun status(context: Context, model: SherpaModel = SherpaModels.DEFAULT): String {
        val d = dir(context)
        val bad = model.files.filter { f ->
            val file = File(d, f.name)
            !file.isFile || file.length() != f.bytes || sha256(file) != f.sha256
        }
        if (bad.isEmpty()) return "Ready (offline)."
        return "Needs " + (model.totalBytes / 1_048_576) + "MB (" + bad.size + " of " +
            model.files.size + " files missing or damaged)."
    }

    fun sha256(file: File): String = runCatching {
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
 * The streaming recogniser. One instance per panel, reset between turns, released with the panel: a
 * native recogniser that is never released holds memory and a decoder thread.
 */
class SherpaAsr(context: Context, model: SherpaModel = SherpaModels.DEFAULT) {

    private val assets: AssetManager = context.applicationContext.assets
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
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
                    // short phrase, a longer one ends a normal turn, a long utterance is force-cut.
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(true, 1.2f, 0f),
                        rule2 = EndpointRule(true, 2.4f, 0f),
                        rule3 = EndpointRule(false, 0f, 30f),
                    ),
                    enableEndpoint = true,
                    decodingMethod = "greedy_search",
                )
                val r = OnlineRecognizer(assets, config)
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
        val r = recognizer ?: return
        val s = stream ?: return
        runCatching { r.reset(s) }
    }

    /** Feed one block of 16-bit mono audio; decode whatever is decodable so far. */
    fun accept(samples: ShortArray) {
        val r = recognizer ?: return
        val s = stream ?: return
        if (samples.isEmpty()) return
        runCatching {
            // The stream takes FloatArray in [-1, 1] plus the sample rate. Dividing by 32768 is the
            // exact inverse of the scale our recorder wrote.
            val floats = FloatArray(samples.size) { i -> samples[i] / 32768f }
            s.acceptWaveform(floats, SAMPLE_RATE)
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
