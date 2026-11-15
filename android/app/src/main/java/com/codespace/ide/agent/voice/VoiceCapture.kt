package com.codespace.ide.agent.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
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
) {

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
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
        } catch (t: Throwable) {
            onError("Could not open the microphone: " + (t.message ?: "unknown error"))
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            onError("The microphone refused to start.")
            return false
        }
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

    /** Stops and releases the microphone. Safe to call repeatedly, and from any thread. */
    fun stop() {
        running.set(false)
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
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
