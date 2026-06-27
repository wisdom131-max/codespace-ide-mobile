package com.codespace.ide.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps terminal sessions alive when backgrounded.
 *
 * Matches Termux's TermuxService approach exactly (verified from source June 27, 2026):
 *
 * 1. startForeground() — raises OOM priority, prevents Android from killing the process
 *    as an "empty" background process.
 *
 * 2. Optional WakeLock + WifiLock (user-toggled, same as Termux):
 *    - PowerManager.PARTIAL_WAKE_LOCK — keeps CPU running when screen is off.
 *      Without this, on TECNO/Infinix/Samsung OEM devices the kernel suspends the CPU
 *      and the OEM power manager sends SIGRTMIN (signal 31) to kill terminal subprocesses.
 *    - WifiManager.WIFI_MODE_FULL_HIGH_PERF — keeps wifi radio active at full performance.
 *      Prevents wifi from going into power-save mode which drops the SSH/network connections.
 *    - Termux always acquires/releases them AS A PAIR. We do the same.
 *    - Notification is REBUILT when lock state changes — text shows "Wake Lock held" vs default.
 *    - Triggered via notification ACTION button ("Acquire WakeLock" / "Release WakeLock"),
 *      exactly as Termux does it via ACTION_WAKE_LOCK / ACTION_WAKE_UNLOCK intents.
 *
 * 3. START_STICKY — service restarts if killed, matches Termux behavior for session persistence.
 *
 * Why both locks matter on TECNO KL4 (Android 14):
 *   - Foreground service alone is NOT enough — OEM power managers bypass it.
 *   - WakeLock prevents CPU suspension → prevents signal 31 kills.
 *   - Battery is already set to Unrestricted (user confirmed) — this is the code-side complement.
 */
class TerminalService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    val isWakeLockHeld: Boolean get() = wakeLock?.isHeld == true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Terminal session active"
        startForeground(NOTIF_ID, buildNotification(text))

        when (intent?.action) {
            ACTION_WAKE_LOCK   -> actionAcquireWakeLock()
            ACTION_WAKE_UNLOCK -> actionReleaseWakeLock()
            else -> {
                // Auto-acquire WakeLock on every start — foreground service alone is NOT enough
                // on Samsung/TECNO OEM devices. Without WakeLock, OEM power manager sends
                // SIGRTMIN (signal 31) and kills terminal processes immediately.
                // This is the ONLY reliable fix for "signal 31 on all tabs" on this device.
                actionAcquireWakeLock()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        actionReleaseWakeLock()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        actionReleaseWakeLock()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    // ── WakeLock + WifiLock — always acquired/released as a pair (Termux pattern) ──

    private fun actionAcquireWakeLock() {
        if (wakeLock?.isHeld == true) return

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CodeSpaceIDE::TerminalWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "CodeSpaceIDE::TerminalWifiLock"
        ).apply { acquire() }

        // Rebuild notification to show lock state — matches Termux
        rebuildNotification("Terminal active · Wake Lock held")
    }

    private fun actionReleaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        if (wifiLock?.isHeld == true) wifiLock?.release()
        wifiLock = null
        rebuildNotification("Terminal session active")
    }

    // ── Notification ──

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Terminal", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val wakeLockHeld = wakeLock?.isHeld == true

        // Action button: toggle wake lock — matches Termux notification button
        val toggleAction = if (wakeLockHeld) {
            val pi = PendingIntent.getService(
                this, 0,
                Intent(this, TerminalService::class.java).setAction(ACTION_WAKE_UNLOCK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action(android.R.drawable.ic_lock_idle_lock, "Release WakeLock", pi)
        } else {
            val pi = PendingIntent.getService(
                this, 1,
                Intent(this, TerminalService::class.java).setAction(ACTION_WAKE_LOCK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action(android.R.drawable.ic_lock_lock, "Acquire WakeLock", pi)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CodeSpace IDE")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .addAction(toggleAction)
            .build()
    }

    private fun rebuildNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID      = "terminal_channel"
        private const val NOTIF_ID        = 1001
        private const val EXTRA_TEXT      = "notif_text"
        const val ACTION_WAKE_LOCK        = "com.codespace.ide.terminal.ACTION_WAKE_LOCK"
        const val ACTION_WAKE_UNLOCK      = "com.codespace.ide.terminal.ACTION_WAKE_UNLOCK"

        fun start(context: Context, text: String = "Terminal session active") {
            val intent = Intent(context, TerminalService::class.java).apply {
                putExtra(EXTRA_TEXT, text)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateProgress(context: Context, text: String) {
            val intent = Intent(context, TerminalService::class.java).apply {
                putExtra(EXTRA_TEXT, text)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TerminalService::class.java))
        }
    }
}
