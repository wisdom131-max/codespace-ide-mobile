package com.codespace.ide.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps terminal sessions and background sync alive when the app
 * is backgrounded, so long-running builds/commands don't get killed on low-RAM devices.
 *
 * CRITICAL for Ubuntu extraction: Samsung's aggressive memory manager will kill a plain
 * background thread doing heavy memory work (XZ decompression). A foreground service
 * raises the process OOM priority so Android won't kill it mid-extraction.
 *
 * Call TerminalService.start(context) BEFORE starting Ubuntu extraction.
 * Call TerminalService.updateProgress(context, msg) to keep the notification fresh.
 * Call TerminalService.stop(context) after extraction + proot launch completes.
 */
class TerminalService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Terminal session active"
        startForeground(NOTIF_ID, buildNotification(text))
        return START_STICKY
    }

    private fun buildNotification(text: String = "Terminal session active"): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Terminal", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CodeSpace IDE")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val CHANNEL_ID = "terminal_channel"
        private const val NOTIF_ID   = 1001
        private const val EXTRA_TEXT = "notif_text"

        fun start(context: android.content.Context, text: String = "Terminal session active") {
            val intent = Intent(context, TerminalService::class.java).apply {
                putExtra(EXTRA_TEXT, text)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Update the notification text while the service is running (e.g. extraction progress). */
        fun updateProgress(context: android.content.Context, text: String) {
            // Re-start with new text — onStartCommand rebuilds the notification.
            val intent = Intent(context, TerminalService::class.java).apply {
                putExtra(EXTRA_TEXT, text)
            }
            context.startService(intent)  // plain startService updates a running service
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, TerminalService::class.java))
        }
    }
}
