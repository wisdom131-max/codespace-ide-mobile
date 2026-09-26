package com.codespace.ide

import android.app.Application
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.termux.terminal.JNI
import dagger.hilt.android.HiltAndroidApp
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import com.codespace.ide.editor.FeatureToggleStore
import com.codespace.ide.editor.ProjectSettingsStore
import com.codespace.ide.editor.KeyBindingRegistry
import com.codespace.ide.editor.settings.JsonSettingsStore
import com.codespace.ide.editor.textmate.TextMateEngineHolder
import com.codespace.ide.data.NotificationStore
import com.codespace.ide.diagnostics.AppOutputLog
import com.codespace.ide.ui.screens.SettingsUsageTracker

@HiltAndroidApp
class CodeSpaceApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        // Initialize unified JSON settings store first — other stores delegate to it
        JsonSettingsStore.init(this)
        FeatureToggleStore.init(this)
        ProjectSettingsStore.init(this)
        // Custom OpenAI-compatible endpoint base URL (config, not a credential)
        com.codespace.ide.chat.CustomEndpointStore.init(this)
        // PERSIST-A: find-widget state (query + toggles) — plain prefs, cross-project.
        com.codespace.ide.editor.EditorFindState.init(this)
        com.codespace.ide.chat.ChatKeyPool.init(this)
        // R7-PLAN: per-session structured plans (plan tool + ChatPlanCard)
        com.codespace.ide.chat.ChatPlanStore.init(this)
        com.codespace.ide.chat.ChatImageAttachments.pruneOldImages(this) // R8-VISION: 7-day image hygiene
        // Initialize TextMate engine (loads bundled grammars from assets)
        TextMateEngineHolder.get(this)
        NotificationStore.init(this) // P-NOTIF-RESTRUCTURE: persisted settings + sound
        // Phase N: Register notification action handlers
        NotificationStore.registerActionHandler("view_logs") { _, _ ->
            // Opens the LSP output log — handled by ProjectShellScreen via activeOutputTarget
            AppOutputLog.log("[Notification] View Logs action triggered", "system")
        }
        NotificationStore.registerActionHandler("restart") { _, _ ->
            // LSP restart is handled via LspManager — this logs the action
            AppOutputLog.log("[Notification] Restart action triggered", "system")
        }
        SettingsUsageTracker.init(this) // P-SETTINGS-RESTRUCTURE: track setting usage for "Commonly Used" ranking
        KeyBindingRegistry.init(this) // Load persisted keybinding overrides
        super.onCreate()
        // MK-RESTRUCTURE v2: endpoint registry must be initialized (prefs +
        // legacy migration + provider sync) BEFORE anything enumerates providers.
        try { com.codespace.ide.chat.CustomEndpointStore.init(this) } catch (_: Exception) { }
        // X7 fix: Start Agent API server on app launch so the MCP status indicator
        // is green from startup, not just when a terminal session is created.
        // start() no-ops if already running, so this is safe to call here.
        com.codespace.ide.agent.AgentApiServer.start(this)
        // IG03 (P3d): tasks.json was written but NEVER restored — "re-scheduled on
        // app restart" existed only in KDoc while restoreAll() had zero callers, so
        // schedule() reported success for a persistence that did not exist beyond
        // process lifetime. One call at startup makes the claim true. Failures are
        // swallowed here the same way surrounding init is: startup must not die on
        // a corrupt tasks.json (readTasks already degrades bad JSON to an empty task set).
        try { com.codespace.ide.agent.AgentScheduler.restoreAll(this) } catch (_: Exception) { }
        // PR13 (P4a-2): restore last-known TaskRunner states; any entry persisted
        // as RUNNING died with the old process — swept to FAILED with an honest
        // interruption message. Swallowed like the surrounding init calls.
        try { com.codespace.ide.project.TaskRunner.restorePersistedState(this) } catch (_: Exception) { }
        // CRITICAL: Do NOT acquire WakeLocks or start foreground service here.
        //
        // TECNO HiOS power management kills apps that acquire WakeLocks + start FGS
        // immediately on process startup — especially on restart after being killed.
        // This was the root cause of the 16x crash loop: each restart acquired 2
        // WakeLocks + started FGS within 1 second → TECNO SIGKILL'd it → repeat.
        //
        // Termux does NOT do this — it only starts the service + WakeLock when a
        // terminal session is actually being created (from the Activity, not Application).
        // We match that pattern: service starts from TerminalPane's DisposableEffect.
        installCrashLogger()
        installNativeCrashHandler()
    }

    /**
     * Covers the crash class installCrashLogger() CANNOT see: native signal crashes
     * (SIGSEGV/SIGABRT/SIGBUS/SIGILL/SIGFPE) never reach a JVM UncaughtExceptionHandler at
     * all -- they're handled by the kernel/debuggerd directly. This is exactly the "signal
     * 11" crash class this app has hit before, so a real device crash going completely
     * unlogged (empty CrashLog on the backend after a confirmed device crash) points
     * straight at this gap. See pty_native.c's native_crash_handler for what gets written
     * (minimal, async-signal-safe only) and MainActivity.readAndUploadCrashLogs() for how
     * it gets picked up and streamed to the agent on the next successful launch.
     */
    private fun installNativeCrashHandler() {
        try {
            val dir = File(filesDir, "crash_logs").apply { mkdirs() }
            val path = File(dir, "native_crash_pending.txt").absolutePath
            JNI.installCrashHandler(path)
        } catch (e: Throwable) {
            Log.e("CodeSpaceApp", "Failed to install native crash handler: ${e.message}")
        }
    }

    /**
     * App-level PARTIAL_WAKE_LOCK — manually toggled by the user from the gear menu.
     *
     * NOT auto-acquired from onCreate() — that caused the TECNO crash loop (16 SIGKILLs
     * in 90 seconds because TECNO HiOS kills apps that acquire WakeLocks on startup).
     * Now matches Termux: the user explicitly toggles it when they need extra protection
     * (e.g. long-running terminal tasks).
     */
    private var appWakeLock: PowerManager.WakeLock? = null

    val isAppWakeLockHeld: Boolean get() = appWakeLock?.isHeld == true

    fun acquireAppWakeLock() {
        if (appWakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            appWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CodeSpaceIDE::AppWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d("CodeSpaceApp", "App WakeLock acquired (manual)")
        } catch (e: Throwable) {
            Log.e("CodeSpaceApp", "Failed to acquire app WakeLock: ${e.message}")
        }
    }

    fun releaseAppWakeLock() {
        try {
            appWakeLock?.let { if (it.isHeld) it.release() }
            appWakeLock = null
            Log.d("CodeSpaceApp", "App WakeLock released (manual)")
        } catch (e: Throwable) {
            Log.e("CodeSpaceApp", "Failed to release app WakeLock: ${e.message}")
        }
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
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stackTrace = sw.toString()

            // 1. Local file fallback (works even with zero connectivity) — MainActivity
            //    reads this on next successful launch and shows a copy/paste dialog.
            try {
                val dir = File(filesDir, "crash_logs").apply { mkdirs() }
                val header = "Thread: " + thread.name + "\nTime: " + stamp + "\n\n"
                File(dir, "crash_$stamp.txt").writeText(header + stackTrace)
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }
            } catch (_: Throwable) { /* never let logging interfere with the real crash */ }

            // 2. Stream it to the agent on a BACKGROUND thread — doing network I/O on
            //    the crashing thread (often the main thread) throws
            //    NetworkOnMainThreadException, which was silently swallowed by the
            //    catch block, meaning crash logs were NEVER uploaded. Now we spawn a
            //    daemon thread so the network call actually executes, and the crashing
            //    thread can proceed to chain the original handler immediately.
            Thread {
                try {
                    reportCrashOverNetwork(thread.name, stamp, stackTrace)
                } catch (_: Throwable) { /* best-effort only */ }
            }.start()

            // Chain to the original handler so normal Android crash behavior (dialog,
            // process kill, ANR reporting) still happens exactly as before.
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * POSTs the crash directly to the CodeSpace IDE Superagent's reportCrash backend
     * function, which stores it in the CrashLog entity. The agent can read it back with
     * read_entities the moment the user says "it crashed again" — no file transfer step.
     * Blocking call on the crashing thread is intentional: the process is about to die
     * either way, and a 4s timeout is cheap insurance against losing the only copy of a
     * crash we can't otherwise see (no ADB/logcat access to this device).
     */
    private fun reportCrashOverNetwork(threadName: String, stamp: String, stackTrace: String) {
        val url = URL(CRASH_SINK_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.setRequestProperty("Content-Type", "application/json")

        val body = JSONObject().apply {
            put("app_package", packageName)
            put("device_model", Build.MODEL)
            put("android_version", Build.VERSION.RELEASE)
            put("thread_name", threadName)
            put("stack_trace", stackTrace)
            put("app_version", BuildConfig.VERSION_NAME)
            put("version_code", BuildConfig.VERSION_CODE)
            put("git_hash", BuildConfig.GIT_HASH)
            put("device_timestamp", stamp)
            // P32: If this is the Compose concurrent change crash, flag it with state-object context
            if (stackTrace.contains("Unsupported concurrent change during composition")) {
                put("crash_type", "COMPOSE_CONCURRENT_CHANGE")
                put("active_threads", Thread.getAllStackTraces().keys.joinToString(", ") { it.name })
                // P32-CRASH-CONTEXT: Log sizes of all known global Compose state objects
                // so the exact state object involved in the next crash is directly identifiable
                try {
                    val appLog = com.codespace.ide.diagnostics.AppOutputLog
                    put("state_appoutputlog_lines_size", appLog.lines.size.toString())
                    put("state_appoutputlog_internal_size", appLog.internalLines.size.toString())
                    val notif = com.codespace.ide.data.NotificationStore
                    put("state_notificationstore_items_size", notif.items.size.toString())
                    put("state_appoutputlog_max", "500")
                    put("state_notificationstore_max", "50")
                } catch (e: Throwable) {
                    put("state_context_error", e.message ?: "unknown")
                }
            }
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
        // RG01 (P4e): the response used to be read and IGNORED -- a dead endpoint
        // 404'd silently while the pipeline claimed to be working. The crash-time
        // POST stays best-effort (it races process death by design; the RELIABLE
        // path is MainActivity's next-launch retry of the persisted local file),
        // but a failure is now at least RECORDED instead of vanishing.
        val code = conn.responseCode
        conn.disconnect()
        if (code !in 200..299) {
            Log.e("CodeSpaceApp", "reportCrash POST failed with HTTP $code (best-effort; MainActivity will retry the persisted local file)")
        }
    }

}
