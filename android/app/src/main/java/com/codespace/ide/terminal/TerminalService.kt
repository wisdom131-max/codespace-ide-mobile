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
import com.codespace.ide.editor.ProjectSettingsStore
import com.codespace.ide.terminal.ProotInstaller
import com.codespace.ide.terminal.BusyboxInstaller
import com.codespace.ide.ui.panes.SimpleTerminalSessionClient
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
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
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    val isWakeLockHeld: Boolean get() = wakeLock?.isHeld == true

    // ── Session leak guard (fixed 2026-07-03) ──────────────────────────────────
    // createSession() forks a real proot+bash process tree and previously had ZERO
    // tracking here. Every time the Activity/Compose tree got torn down and recreated
    // (OEM killing the Activity while this Service survives — exactly what TECNO HiOS
    // does on a plain minimize) the bootstrap effect in TerminalPane spawned ANOTHER
    // real Ubuntu proot session on top of whatever was already running, because there
    // was no way to discover/reuse an existing live session from a fresh Activity.
    // Old sessions were NEVER finished unless the user manually tapped "close tab" —
    // so orphaned proot+bash+rootfs-mount process trees stacked up across every
    // minimize/reopen cycle. On a 3GB device, this is a very plausible OOM trigger,
    // and it fires at exactly the moment of reopening — matching the reported "opens
    // then instantly closes" symptom. Track every live session here so callers can
    // reuse one instead of leaking duplicates, and so a real onDestroy() can clean
    // up anything left running.
    // Tag every tracked session with the projectId it belongs to (fix #12, 2026-07-08).
    // Previously this was a single flat, UNTAGGED list — reattaching after an Activity
    // recreation (e.g. OEM minimize-kill) rebuilt the tab list from EVERY live session
    // across EVERY project, not just the one currently open. Combined with an unkeyed
    // `remember` in ProjectShellScreen (same underlying bug, Compose side), this is what
    // caused terminal state ("even unsent keystrokes") to bleed between different
    // projects. See AGENTS.md #12 for the full root-cause writeup.
    private data class TrackedSession(val session: TerminalSession, val projectId: String)
    private val liveSessions = java.util.Collections.synchronizedList(
        mutableListOf<TrackedSession>()
    )

    /** Returns an existing, still-running Ubuntu session for THIS project, if one exists,
     *  so a freshly recreated Activity/Compose tree can REATTACH instead of forking a
     *  duplicate — scoped so it never reattaches another project's session by mistake. */
    fun findLiveUbuntuSession(projectId: String): TerminalSession? =
        getLiveUbuntuSessions(projectId).firstOrNull()

    /**
     * Returns ALL still-running sessions tracked by this Service FOR A SPECIFIC PROJECT —
     * mirrors real Termux's TermuxService.getTermuxSessions() (the Activity rebuilds its
     * ENTIRE tab list from this on every (re)connect, since Compose-`remember`-scoped tab
     * state resets on recreation) but filtered by projectId so switching projects can never
     * pull in — or accidentally kill — another project's sessions.
     */
    fun getLiveUbuntuSessions(projectId: String): List<TerminalSession> = synchronized(liveSessions) {
        liveSessions.removeAll { !it.session.isRunning }  // prune finished sessions first
        liveSessions.filter { it.projectId == projectId }.map { it.session }
    }

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
        try {
            startForeground(NOTIF_ID, buildNotification(text))
            // TEST-50-FIX: Observe terminal notification toggle and rebuild immediately
            serviceScope.launch {
                androidx.compose.runtime.snapshotFlow { ProjectSettingsStore.terminalNotifications.value }
                    .distinctUntilChanged()
                    .collect { rebuildNotification("Terminal ready") }
            }
        } catch (e: Exception) {
            // Defensive: if this throws (e.g. a transient AMS race right after process
            // restart from a killed background state), don't take the whole app down —
            // log it and continue. The service can still function; worst case it loses
            // FGS priority for this cycle instead of crashing on relaunch.
            android.util.Log.e("TerminalService", "startForeground() failed: ${e.message}", e)
        }

        when (intent?.action) {
            ACTION_WAKE_LOCK   -> actionAcquireWakeLock()
            ACTION_WAKE_UNLOCK -> actionReleaseWakeLock()
            else -> {
                // CRITICAL: Do NOT auto-acquire WakeLock here.
                //
                // TECNO HiOS power management kills apps that acquire WakeLocks immediately
                // after process restart. This was the remaining trigger for the crash loop
                // after removing the app-level WakeLock from Application.onCreate().
                //
                // The WakeLock is now purely user-toggled:
                // - Gear menu: "App WakeLock: ON/OFF"
                // - Notification action: ACTION_WAKE_LOCK / ACTION_WAKE_UNLOCK
                //
                // Termux does NOT auto-acquire either — the user explicitly enables it.
            }
        }

        return START_STICKY
    }

    /**
     * Kills ALL live terminal sessions (proot/bash process trees) and clears the tracking list.
     * Called when the Activity goes to background (ON_STOP) to free memory on 3GB devices.
     * Without this, proot processes survive across minimize/reopen cycles and cause OOM crashes
     * because TECNO HiOS kills the app when memory pressure gets too high.
     *
     * Tradeoff: terminal sessions don't persist across minimize. On 3GB devices, stability
     * is more important than session persistence. On reopen, addUbuntuTab() starts a fresh session.
     */
    fun killAllSessions() {
        synchronized(liveSessions) {
            liveSessions.forEach { try { it.session.finishIfRunning() } catch (_: Throwable) {} }
            liveSessions.clear()
        }
        com.codespace.ide.agent.AgentApiServer.stop()
        android.util.Log.d("TerminalService", "All sessions killed (minimize cleanup)")
    }

    override fun onDestroy() {
        serviceScope.cancel()
        // Genuine teardown (force-stop, real system kill, or explicit notification stop) —
        // finish any sessions still tracked here so we don't leave orphaned proot/bash
        // process trees running past the service's own lifetime.
        synchronized(liveSessions) {
            liveSessions.forEach { try { it.session.finishIfRunning() } catch (_: Exception) {} }
            liveSessions.clear()
        }
        com.codespace.ide.agent.AgentApiServer.stop()
        actionReleaseWakeLock()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // INTENTIONALLY A NO-OP as of 2026-07-03. This used to call
        // actionReleaseWakeLock() + stopSelf() here (matching a naive reading of Termux's
        // pattern), which tore down the foreground service and released the WakeLock the
        // instant the task left Recents.
        //
        // The bug: TECNO HiOS (and several other aggressive Chinese OEM skins) auto-clears
        // backgrounded apps from Recents on a plain home-press / minimize -- NOT just on an
        // explicit user swipe-to-close. That silently fired onTaskRemoved on ordinary
        // minimize, killing the service + WakeLock every time, which is the direct cause of
        // "app refuses to open / restarts broken after minimizing": the whole process (and
        // all live terminal sessions TerminalPane still holds references to) gets torn down
        // behind the user's back, and reopening is a full cold start trying to rebind to
        // sessions that no longer exist.
        //
        // Real Termux does NOT unconditionally stopSelf() here either -- persistence across
        // task removal is the whole point of a foreground service. Only a genuine
        // onDestroy() (explicit force-stop, service killed by the system, or the user
        // stopping it via the notification action) should release the WakeLock now.
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
        // P-NOTIFY: Respect In-Project Settings > Notifications > Terminal notifications
        if (!ProjectSettingsStore.terminalNotifications.value) {
            // User disabled terminal notifications — use a minimal silent notification
            // (Android requires SOME notification for foreground services, so we keep
            // it but make it invisible: no content text, lowest priority).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "VN Code", NotificationManager.IMPORTANCE_MIN
                )
                getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setShowWhen(false)
                .setSilent(true)
                .build()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "VN Code", NotificationManager.IMPORTANCE_LOW
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
    internal fun createSession(
        isUbuntu: Boolean = false,
        projectId: String = "default",
        workDir: String? = null,
    ): Pair<TerminalSession, SimpleTerminalSessionClient> {
        val client = SimpleTerminalSessionClient()
        client.appContext = applicationContext

        if (isUbuntu) {
            // P32-FIX: Ensure the dpkg LD_PRELOAD shim is in the rootfs BEFORE the terminal
            // session starts. Previously this only ran from LspManager.startServer(), so if
            // the user ran apt-get manually in the terminal before ever opening an LSP file,
            // the shim wasn't in the rootfs yet — dpkg's link() calls failed with EACCES
            // ("unable to make backup link of '...' before installing new version").
            // Calling it here means every terminal session guarantees the shim is present.
            ProotInstaller.ensureShimInstalled(this)
            val (proot, args, envVars) = ProotInstaller.launchArgs(this)
            val session = TerminalSession(proot, "/", args, envVars, 4000, client)
            liveSessions.add(TrackedSession(session, projectId))
            // Give ANY AI launched inside the terminal (Claude Code, Ollama CLI, llama.cpp,
            // etc.) the same 32 AgentTools the chat panel uses, via localhost:8765 — was built
            // (AgentApiServer.kt) but never actually started anywhere. Safe to call repeatedly;
            // start() no-ops if already running.
            com.codespace.ide.agent.AgentApiServer.start(applicationContext)
            // Auto-cd into this project's own directory (fix #12, 2026-07-08) — only for
            // real in-container paths (/root/...). Shared-storage paths (/storage/...) are
            // bind-mounted at /sdcard inside proot, not at their host path, so guessing a
            // "translated" path here would risk cd-ing somewhere wrong; leave those at the
            // default /root instead (unchanged prior behavior for non-/root workspaces).
            // Inject WORKSPACE_PATH so any AI in the terminal knows where the project files are.
            // /storage/emulated/0 is bind-mounted at /sdcard inside proot, so translate the path.
            val prootWorkspace: String? = workDir?.let {
                when {
                    it.startsWith("/storage/emulated/0") -> it.replace("/storage/emulated/0", "/sdcard")
                    it.startsWith("/sdcard") -> it
                    it.startsWith("/root") -> it
                    else -> null
                }
            }
            if (prootWorkspace != null) {
                session.write("export WORKSPACE_PATH=\"$prootWorkspace\"\n")
                session.write("export PROJECT_FILES=\"$prootWorkspace\"\n")
                // Fix Test 16/17: cd into the project workspace so files created via
                // terminal (echo > file.txt) land in the project dir the explorer shows.
                // /sdcard is bind-mounted in proot, so /sdcard/... paths work fine.
                session.write("cd \"$prootWorkspace\" 2>/dev/null && clear\n")
                // Fix Test 20: Flush bash history after each command so the shell
                // history search overlay shows commands typed in the terminal.
                session.write("export PROMPT_COMMAND='history -a'\n")
                session.write("export HISTFILE=~/.bash_history\n")
                session.write("export HISTSIZE=500\n")
                session.write("export HISTFILESIZE=500\n")
            }
            return Pair(session, client)
        }

        // ── Shell session — mirrors Termux's TermuxShellEnvironmentClient exactly ──
        // Termux: executablePath=$PREFIX/bin/bash, argv[0]="-bash" (login shell)
        // We ship bash via libbusybox.so (busybox ash) since no real bash .so exists.
        // busybox launched with argv[0]="-ash" triggers login-shell .profile sourcing.
        val busybox   = BusyboxInstaller.shellPath(this)
        val home      = File(filesDir, "home").also { it.mkdirs() }.absolutePath
        val bin       = BusyboxInstaller.binDir(this).absolutePath
        val nativeDir = applicationInfo.nativeLibraryDir
        val tmpDir    = File(cacheDir, "tmp").also { it.mkdirs() }.absolutePath

        // Termux sets LD_LIBRARY_PATH to nativeLibraryDir so .so deps resolve
        val ldLibPath = buildString {
            append(nativeDir)
            System.getenv("LD_LIBRARY_PATH")?.let { append(":$it") }
        }

        val envBuilder = mutableListOf(
            // Core identity — match Termux exactly
            "HOME=$home",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=en_US.UTF-8",
            "SHELL=$busybox",
            "USER=vncode",
            "LOGNAME=vncode",
            "TMPDIR=$tmpDir",
            "PWD=$home",
            // PATH: our bin symlinks first, then system
            "PATH=$bin:$nativeDir:/system/bin:/system/xbin",
            // LD_LIBRARY_PATH: required for native .so resolution (Termux does this)
            "LD_LIBRARY_PATH=$ldLibPath",
            // libtermux-exec intercepts exec() calls for Samsung/OEM compat
            "LD_PRELOAD=$nativeDir/libtermux-exec.so",
            // ash interactive mode: point ENV at our rc file
            "ENV=$home/.ashrc"
        )

        // Inherit Android system vars exactly as Termux does
        for (key in listOf(
            "ANDROID_DATA", "ANDROID_ROOT", "ANDROID_STORAGE",
            "ANDROID_RUNTIME_ROOT", "ANDROID_ART_ROOT",
            "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT",
            "EXTERNAL_STORAGE", "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH"
        )) {
            System.getenv(key)?.let { envBuilder.add("$key=$it") }
        }

        val env = envBuilder.toTypedArray()

        // "-ash" as argv[0] = login shell mode — busybox reads .profile on startup
        // This is equivalent to Termux passing "-bash" for bash login sessions
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
