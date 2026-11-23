package com.codespace.ide.agent.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PROJECT 2.20 (2026-11-15): the microphone, owned by VN Code.
 *
 * A short 16 kHz mono capture loop that computes each frame's level, hands it to [VadPolicy] to
 * decide whether someone is talking, and reports both to the interface. It opens only when
 * [start] is called - which the controller does only in Voice Mode, in the foreground, with
 * permission granted - and it closes in [stop] and in [close], including on the failure paths.
 *
 * Bounded by construction: one fixed frame buffer, no accumulation, and a hard ceiling on how long
 * a single capture may run so a wedged microphone cannot pin the process.
 */
class VoiceCapture(
    private val context: Context,
    private val onLevel: (energy01: Float) -> Unit,
    private val onSpeechStarted: () -> Unit,
    private val onSpeechEnded: (truncated: Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val config: VadPolicy.Config = VadPolicy.Config(),
    /**
     * Keep the raw samples, not just the levels. Any on-device recogniser needs the audio itself, and
     * this recorder is the only place that has it: the borrowed service never hands its buffer over.
     * Bounded to [MAX_KEPT_SAMPLES] so a wedged capture cannot grow without limit.
     */
    private val keepAudio: Boolean = false,
) {

    /**
     * OWN OUR AUDIO (owner request, 2026-11-23).
     *
     * Until now the app borrowed a recogniser service and could not touch the recorder, so Android's
     * echo canceller was out of reach: it attaches to an AudioRecord's session, and the service owned
     * that. With our own recorder we can attach it, which is the whole reason the interruption had to
     * rely on a guard window instead.
     *
     * VOICE_COMMUNICATION is the source Android builds for exactly this: capture the user while the
     * device is playing audio, with the playback cancelled out. It is only chosen when the device
     * reports an echo canceller, because on a device without one that source can be thin and quiet.
     */
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    /** True when the platform echo canceller is actually running on this capture. */
    var echoCancelled: Boolean = false
        private set

    private val kept = if (keepAudio) ShortArray(MAX_KEPT_SAMPLES) else null
    private var keptLen = 0

    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val detector = VadPolicy.Detector(config)

    val isRunning: Boolean get() = running.get()

    fun hasPermission(): Boolean = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    /** Starts the loop. Returns false and reports why when permission or the device refuses. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running.get()) return true
        if (!hasPermission()) {
            onError("Microphone permission has not been granted.")
            return false
        }
        val frameBytes = FRAME_SAMPLES * 2
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            onError("This device cannot open a 16 kHz microphone stream.")
            return false
        }
        val bufferSize = maxOf(minBuffer, frameBytes * 4)
        val recorder = try {
            AudioRecord(source(), SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
        } catch (t: Throwable) {
            onError("Could not open the microphone: " + (t.message ?: "unknown error"))
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            onError("The microphone refused to start.")
            return false
        }
        // Attach the platform effects to OUR session. Done here, after the recorder is known to be
        // initialised: an uninitialised AudioRecord reports session 0, and create(0) silently fails.
        val session = recorder.audioSessionId
        aec = if (AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(session)?.also { it.enabled = true } }.getOrNull()
        } else null
        echoCancelled = aec?.enabled == true
        ns = if (NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(session)?.also { it.enabled = true } }.getOrNull()
        } else null
        record = recorder
        detector.reset()
        running.set(true)
        try {
            recorder.startRecording()
        } catch (t: Throwable) {
            running.set(false)
            recorder.release()
            record = null
            onError("Could not start recording: " + (t.message ?: "unknown error"))
            return false
        }
        thread = Thread({ loop(recorder) }, "vncode-voice-capture").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun loop(recorder: AudioRecord) {
        val frame = ShortArray(FRAME_SAMPLES)
        val frameMs = (FRAME_SAMPLES * 1000) / SAMPLE_RATE
        while (running.get()) {
            val read = try {
                recorder.read(frame, 0, frame.size)
            } catch (t: Throwable) {
                if (running.get()) onError("The microphone stopped: " + (t.message ?: "unknown error"))
                break
            }
            if (read <= 0) continue
            kept?.let { buf ->
                val room = buf.size - keptLen
                if (room > 0) {
                    val n = minOf(room, read)
                    System.arraycopy(frame, 0, buf, keptLen, n)
                    keptLen += n
                }
            }
            val db = VadPolicy.rmsDb(frame, read)
            onLevel(maxOf(detector.energy01(db), VadPolicy.peak01(frame, read) * 0.6f).coerceIn(0f, 1f))
            when (detector.onFrame(db, frameMs)) {
                VadPolicy.Signal.SpeechStarted -> onSpeechStarted()
                VadPolicy.Signal.SpeechEnded -> onSpeechEnded(false)
                VadPolicy.Signal.SpeechTruncated -> onSpeechEnded(true)
                VadPolicy.Signal.None -> Unit
            }
        }
    }

    /**
     * The audio captured since the last take, as 16-bit mono at [SAMPLE_RATE]. Empty when the
     * recorder was not asked to keep audio. Taking it clears it, so a caller cannot transcribe the
     * same utterance twice by accident.
     */
    fun takeAudio(): ShortArray {
        val buf = kept ?: return ShortArray(0)
        val out = ShortArray(keptLen)
        System.arraycopy(buf, 0, out, 0, keptLen)
        keptLen = 0
        return out
    }

    /** Stops and releases the microphone. Safe to call repeatedly, and from any thread. */
    private fun source(): Int =
        if (AcousticEchoCanceler.isAvailable()) MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else MediaRecorder.AudioSource.VOICE_RECOGNITION

    private fun releaseEffects() {
        runCatching { aec?.enabled = false }
        runCatching { aec?.release() }
        aec = null
        runCatching { ns?.enabled = false }
        runCatching { ns?.release() }
        ns = null
        echoCancelled = false
    }

    fun stop() {
        running.set(false)
        keptLen = 0
        releaseEffects()
        val recorder = record ?: return
        record = null
        try {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        } catch (ignored: Throwable) {
        }
        try {
            recorder.release()
        } catch (ignored: Throwable) {
        }
        thread?.let { worker ->
            worker.interrupt()
        }
        thread = null
        detector.reset()
        onLevel(0f)
    }

    fun close() = stop()

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        private const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        /** 60 seconds at 16 kHz, mono 16-bit: enough for the longest utterance worth transcribing. */
        private const val MAX_KEPT_SAMPLES = 16_000 * 60
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
