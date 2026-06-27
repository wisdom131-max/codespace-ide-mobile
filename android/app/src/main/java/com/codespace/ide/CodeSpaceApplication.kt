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
        acquireAppWakeLock()
        startTerminalServiceEarly()
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
