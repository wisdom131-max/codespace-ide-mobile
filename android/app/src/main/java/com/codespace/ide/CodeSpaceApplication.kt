package com.codespace.ide

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.codespace.ide.terminal.TerminalService
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class CodeSpaceApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * App-level PARTIAL_WAKE_LOCK — held for the entire app lifetime.
     *
     * WHY THIS IS NECESSARY on TECNO KL4 / Samsung OEM devices:
     *
     * Android's foreground service + WakeLock normally prevents signal 31. But TECNO's OEM
     * power manager operates at the cgroup/kernel level and targets ALL child processes of
     * apps it considers "background", including native proot/ash children.
     *
     * The problem with service-level WakeLock:
     *   - TerminalService.start() is called from Compose DisposableEffect (after first frame)
     *   - There is a window between app start and first frame where no WakeLock is held
     *   - If the service is restarted (START_STICKY after kill), there is another gap
     *   - Recompositions can briefly dispose/re-compose TerminalPane → stop()/start() gap
     *
     * Solution: hold the WakeLock from Application.onCreate() — before ANY activity or
     * service starts. The lock is never explicitly released while the app process is alive.
     * Android automatically releases all WakeLocks when the process dies.
     *
     * This matches what aggressive-OEM-compatible apps (GPS trackers, BT audio) do.
     * Termux does NOT do this (their users have cleaner OEMs), but TECNO requires it.
     */
    private var appWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        acquireAppWakeLock()
        startTerminalServiceEarly()
    }

    /**
     * Writes any uncaught exception's full stack trace to a plain-text file in
     * filesDir/crash_logs/ BEFORE letting the crash proceed normally.
     *
     * Why: this app has no ADB/logcat access available during remote debugging sessions
     * (see AGENTS.md — "signal 11" and "app closes instantly on reopen" reports). Without
     * this, a real Android-level crash (as opposed to a terminal child-process crash, which
     * already prints its own message inside the terminal) is completely invisible to us.
     *
     * MainActivity reads and surfaces the latest file from this dir on next launch (see
     * MainActivity.kt's crash-log dialog) so the user can copy/paste it back to us — no
     * root, no ADB, no file manager needed.
     */
    private fun installCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = File(filesDir, "crash_logs").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val header = "Thread: " + thread.name + "\nTime: " + stamp + "\n\n"
                File(dir, "crash_$stamp.txt").writeText(header + sw.toString())
                // Keep only the 5 most recent crash logs
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }
            } catch (_: Throwable) {
                // Never let the crash logger itself interfere with the real crash handling
            }
            // Chain to the original handler so normal Android crash behavior (dialog,
            // process kill, ANR reporting) still happens exactly as before.
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Start TerminalService from Application.onCreate() — before any Activity renders.
     * This closes the window between app start and first Compose frame where no FGS is running.
     * The service is a foreground service (low impact) and stays alive for the app lifetime.
     */
    private fun startTerminalServiceEarly() {
        try {
            val intent = Intent(this, TerminalService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d("CodeSpaceApp", "TerminalService started from Application.onCreate()")
        } catch (e: Exception) {
            Log.e("CodeSpaceApp", "Failed to start TerminalService early: ${e.message}")
        }
    }

    private fun acquireAppWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            appWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CodeSpaceIDE::AppWakeLock"
            ).apply {
                setReferenceCounted(false)
                // No timeout — held for app lifetime. Android releases it when process dies.
                acquire()
            }
            Log.d("CodeSpaceApp", "App-level WakeLock acquired — OEM signal 31 prevention active")
        } catch (e: Exception) {
            Log.e("CodeSpaceApp", "Failed to acquire app WakeLock: ${e.message}")
        }
    }
}
