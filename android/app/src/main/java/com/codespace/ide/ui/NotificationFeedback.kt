package com.codespace.ide.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * BATCH7-P2 (advisor 2, 2026-10-18, device-evidence): the notification sound was
 * created, played AND released on the MAIN thread per notification:
 *
 *     val toneGen = ToneGenerator(STREAM_NOTIFICATION, 80)
 *     toneGen.startTone(TONE_PROP_BEEP, 100)
 *     toneGen.release()
 *
 * ToneGenerator.release() is a NATIVE call — the owner's watchdog shows
 * "STALL 22289ms" with main blocked inside android.media.ToneGenerator.release
 * <- ProjectShellScreen.showNotification <- ExplorerPane file-open tap, TWICE
 * (12:12:23 and 12:13:27). Rules implemented here:
 *  - NEVER create, play or release a ToneGenerator on the main thread;
 *  - ONE shared instance, created lazily on the background feedback thread
 *    (release-per-call is gone entirely — the instance lives for the process);
 *  - every step catches Throwable (audio HAL misbehavior must never reach UI);
 *  - tones are RATE-LIMITED (min interval) so a burst of notifications cannot
 *    machine-gun the audio HAL.
 * Vibration rides the same background thread (binder call, cheap, but it has no
 * business on main during a tap either).
 */
object NotificationFeedback {

    /** Minimum spacing between pop tones — bursts collapse into one. */
    const val MIN_TONE_INTERVAL_MS = 750L

    /** The one feedback thread: lazy init, play, vibrate and (future) release all live here. */
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ui-feedback").apply { isDaemon = true }
    }

    @Volatile private var toneGen: ToneGenerator? = null
    private var lastToneAtMs = 0L

    /**
     * Haptic + audio feedback for a notification. Safe to call from the main
     * thread: everything happens on the feedback thread.
     * [silent] = vibration only (Do Not Disturb) — same split the old code had.
     */
    fun playFeedback(context: Context, silent: Boolean) {
        val appContext = context.applicationContext
        executor.execute {
            try {
                val vibrator = appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(
                        if (silent) 80L else 100L,
                        VibrationEffect.DEFAULT_AMPLITUDE,
                    )
                )
            } catch (_: Throwable) { /* haptic failures are never fatal */ }
            if (silent) return@execute
            // Rate limit — measured on the single feedback thread, so no locking needed.
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastToneAtMs < MIN_TONE_INTERVAL_MS) return@execute
            lastToneAtMs = now
            try {
                val tg = toneGen ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).also { toneGen = it }
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
            } catch (_: Throwable) {
                // Audio HAL trouble: drop the shared instance; the next tone retries lazily.
                try { toneGen?.release() } catch (_: Throwable) { /* already dead */ }
                toneGen = null
            }
        }
    }

    /**
     * Off-main release, for completeness/lifecycle — NOT called per notification.
     * If audio ever needs to be torn down (e.g. on a low-memory trim), call this.
     */
    fun release() {
        executor.execute {
            try { toneGen?.release() } catch (_: Throwable) { /* already dead */ }
            toneGen = null
        }
    }
}
