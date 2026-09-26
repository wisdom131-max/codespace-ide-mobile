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
import com.codespace.ide.environment.IdeEnvironment
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

    // TP14 (2026-09-26): the live-session registry moved into the class COMPANION
    // (declared at the bottom of this file) — it used to be an instance field, so
    // sessions created by the pane's own factory (used before the service binds)
    // were invisible to every service-level consumer (findLive for reattach,
    // cleanup): one concept (open sessions), two owners. Both factories now register
    // into that ONE registry; the pane registers via TerminalService.registerLiveSession.

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

    /** TP13 (2026-09-26): FGS start + notification-toggle observer, extracted so the
     *  bounded retry in onStartCommand repeats the FULL sequence, not half of it. */
    private fun startFgs(text: String) {
        startForeground(NOTIF_ID, buildNotification(text))
        // TEST-50-FIX: Observe terminal notification toggle and rebuild immediately
        serviceScope.launch {
            androidx.compose.runtime.snapshotFlow { ProjectSettingsStore.terminalNotifications.value }
                .distinctUntilChanged()
                .collect { rebuildNotification("Terminal ready") }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Terminal session active"
        // Boost service thread priority — reduces chance of OEM scheduler deprioritizing it.
        // THREAD_PRIORITY_FOREGROUND = -2 (higher than default 0, same as UI thread).
        Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
        try {
            startFgs(text)
        } catch (e: Exception) {
            // Defensive: if this throws (e.g. a transient AMS race right after process
            // restart from a killed background state), don't take the whole app down —
            // log it and continue. The service can still function; worst case it loses
            // FGS priority for this cycle instead of crashing on relaunch.
            // TP13 (2026-09-26): the degradation is no longer SILENT — one bounded retry
            // (transient AMS races often clear within half a second), then an honest
            // final-failure log. The deliberate continue stays: crashing the service on
            // a transient race is worse than one cycle without foreground priority.
            try {
                Thread.sleep(500)
                startFgs(text)
                android.util.Log.w("TerminalService", "startForeground() succeeded on bounded retry (transient AMS race cleared)")
            } catch (retry: Exception) {
                android.util.Log.e("TerminalService", "startForeground() failed TWICE — running WITHOUT foreground priority this cycle (OEM-kill protection lost): ${retry.message}", retry)
            }
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

    // TP07 (2026-09-26): killAllSessions() is DELETED — it had ZERO callers while
    // TerminalPane's comments still documented an ON_STOP kill-everything handler
    // that never existed (the abandoned HiOS workaround). A resurrected call would
    // kill every session on every minimize. Sessions persist across minimize by
    // design: the foreground service owns their lifetime.

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
            .setContentTitle("VN Code")
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
            // Gap 1+3: Use IdeEnvironment as single source of truth for env vars.
            // WORKSPACE_PATH and PROJECT_FILES are now baked into the env map directly
            // (via /usr/bin/env -i args) instead of post-hoc session.write() injection.
            val prootEnv = IdeEnvironment.forTerminal(this, projectId, workDir)
            val session = TerminalSession(prootEnv.proot, "/", prootEnv.args, prootEnv.envVars, 4000, client)
            liveSessions.add(TrackedSession(session, projectId))
            // Give ANY AI launched inside the terminal (Claude Code, llama.cpp,
            // etc.) the same 32 AgentTools the chat panel uses, via localhost:8765 — was built
            // (AgentApiServer.kt) but never actually started anywhere. Safe to call repeatedly;
            // start() no-ops if already running.
            com.codespace.ide.agent.AgentApiServer.start(applicationContext)
            // Fallback: session.write() commands as belt-and-suspenders. The env vars are
            // already baked into the /usr/bin/env -i args, but if the shell sources a profile
            // that unsets them, these re-apply. Also cd + history config for interactive use.
            IdeEnvironment.workspacePathFallbackCommands(prootEnv.workspacePath).forEach { cmd ->
                session.write(cmd)
            }
            return Pair(session, client)
        }

        // TP10 (2026-09-26): the busybox -ash branch (54 lines: BusyboxInstaller shell
        // path, full Termux env builder, login-shell session) is DELETED — it was
        // UNREACHABLE for the app's whole life: Ubuntu-only, every caller passes
        // isUbuntu=true, and the pane's inert-placeholder path uses the pane factory,
        // not this service. Shipping a dead second shell system was maintenance debt.
        // Fail closed rather than resurrect it.
        throw IllegalStateException(
            "TerminalService.createSession is Ubuntu-only — the busybox shell branch was removed (TP10)"
        )
    }

        companion object {
        // TP14 (2026-09-26): the ONE live-session registry (moved from an instance
        // field — see the comment near TrackedSession). The pane's factory registers
        // here too, so reattach/cleanup cover pane-created sessions.
        private val liveSessions = java.util.Collections.synchronizedList(
            mutableListOf<TrackedSession>()
        )

        /** TP14: pane-factory sessions register here (same registry createSession uses). */
        internal fun registerLiveSession(session: TerminalSession, projectId: String) {
            synchronized(liveSessions) { liveSessions.add(TrackedSession(session, projectId)) }
        }

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
