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
import android.os.Process
import androidx.core.app.NotificationCompat
import com.codespace.ide.proot.ProotInstaller
import com.codespace.ide.terminal.BusyboxInstaller
import com.codespace.ide.ui.panes.SimpleTerminalSessionClient
import com.termux.terminal.TerminalSession
import java.io.File

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

    // ── LocalBinder — allows TerminalPane to call createSession() from Service context ──
    // This is the KEY architectural fix: Termux forks all shells from inside the Service,
    // not from Activity. Android's phantom process killer sees Service as parent → not phantom.
    inner class LocalBinder : android.os.Binder() {
        val service: TerminalService get() = this@TerminalService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Terminal session active"
        // Boost service thread priority — reduces chance of OEM scheduler deprioritizing it.
        // THREAD_PRIORITY_FOREGROUND = -2 (higher than default 0, same as UI thread).
        Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
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
                CHANNEL_ID, "Termux App", NotificationManager.IMPORTANCE_LOW
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

    // ── Session factory — called via LocalBinder from TerminalPane ──
    // Fork happens HERE, inside the Service. Parent PID = Service process.
    // Android phantom process killer does NOT kill children of foreground services.
    // This is the exact same pattern Termux uses (TermuxService.executeTermuxSessionCommand).
    fun createSession(isUbuntu: Boolean = false): Pair<TerminalSession, SimpleTerminalSessionClient> {
        val client = SimpleTerminalSessionClient()
        client.appContext = applicationContext

        if (isUbuntu) {
            val (proot, args, envVars) = ProotInstaller.launchArgs(this)
            val session = TerminalSession(proot, "/", args, envVars, 4000, client)
            return Pair(session, client)
        }

        val busybox = BusyboxInstaller.shellPath(this)
        val home = File(filesDir, "home").also { it.mkdirs() }.absolutePath
        val bin  = BusyboxInstaller.binDir(this).absolutePath
        val nativeDir = applicationInfo.nativeLibraryDir
        val ldPreload  = "$nativeDir/libtermux-exec.so"
        val uid = android.os.Process.myUid()

        val envBuilder = mutableListOf(
            "HOME=$home",
            "PWD=$home",
            "PATH=$bin:/system/bin:/system/xbin",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=en_US.UTF-8",
            "SHELL=$busybox",
            "BUSYBOX=$busybox",
            "TMPDIR=${cacheDir.absolutePath}",
            "USER=vncode",
            "LOGNAME=vncode"
        )
        if (File(ldPreload).exists()) envBuilder.add("LD_PRELOAD=$ldPreload")
        for (key in listOf("ANDROID_DATA","ANDROID_ROOT","ANDROID_STORAGE","ANDROID_RUNTIME_ROOT",
                           "ANDROID_ART_ROOT","ANDROID_I18N_ROOT","ANDROID_TZDATA_ROOT",
                           "EXTERNAL_STORAGE","BOOTCLASSPATH","DEX2OATBOOTCLASSPATH")) {
            System.getenv(key)?.let { envBuilder.add("$key=$it") }
        }
        val env = envBuilder.toTypedArray()
        val session = TerminalSession(busybox, home, arrayOf("-ash"), env, 4000, client)
        return Pair(session, client)
    }

        companion object {
        private const val CHANNEL_ID      = "termux_notification_channel"
        private const val NOTIF_ID        = 1337   // 0x539 — matches Termux exactly
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
