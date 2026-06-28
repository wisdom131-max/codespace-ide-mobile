package com.codespace.ide.ui.panes

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.codespace.ide.terminal.BusyboxInstaller
import com.codespace.ide.terminal.TermuxBootstrapInstaller
import com.codespace.ide.terminal.DeviceCompatibility
import com.codespace.ide.terminal.OllamaSetup
import com.codespace.ide.terminal.ProotInstaller
import android.content.ServiceConnection
import com.codespace.ide.terminal.TerminalService
import com.codespace.ide.terminal.TerminalModeManager
import com.codespace.ide.terminal.SshProfileStore
import com.codespace.ide.terminal.TextExpansionStore
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.ui.text.style.TextOverflow
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import android.net.Uri


internal class SimpleTerminalSessionClient : TerminalSessionClient {
    var onTextChanged: (() -> Unit)? = null
    var onTitleChanged: ((String?) -> Unit)? = null
    var onSessionFinished: (() -> Unit)? = null
    var onCursorStateChange: ((Boolean) -> Unit)? = null
    var appContext: Context? = null

    fun initBell(ctx: Context) { /* sound pool reserved for future beep mode */ }
    fun releaseBell() {}

    override fun onTextChanged(changedSession: TerminalSession) { onTextChanged?.invoke() }

    override fun onTitleChanged(changedSession: TerminalSession) {
        onTitleChanged?.invoke(changedSession.title)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        onSessionFinished?.invoke()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        onCursorStateChange?.invoke(state)
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text == null) return
        val ctx = appContext ?: return
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val ctx = appContext ?: return
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val pasteText = clip.getItemAt(0).coerceToText(ctx)?.toString()
            if (pasteText != null) session?.write(pasteText)
        }
    }
    override fun onBell(session: TerminalSession) {
        val ctx = appContext ?: return
        try {
            @Suppress("DEPRECATION")
            val v = ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
            if (v?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(80)
                }
            }
        } catch (_: Exception) {}
    }
    override fun onColorsChanged(session: TerminalSession) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
        // Move child process into foreground cgroup — PREVENTS phantom process killer (signal 31).
        // Android 12+ LMKD/phantom process killer kills child processes whose parent is NOT
        // a foreground service. setProcessGroup(pid, THREAD_GROUP_FOREGROUND) re-assigns
        // the child to the foreground cgroup, making Android treat it as a protected process.
        // WakeLock alone does NOT protect child processes on TECNO/Samsung Android 14.
        try {
            // Try TOP_APP first (highest priority cgroup — same as foreground UI).
            // Fallback to FOREGROUND if permission denied on some ROM builds.
            // setProcessGroup is a hidden API — call via reflection
            val m = android.os.Process::class.java.getMethod("setProcessGroup", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            m.invoke(null, pid, 5) // 5 = THREAD_GROUP_TOP_APP
            android.util.Log.d("TerminalSession", "setProcessGroup($pid, TOP_APP=5) — phantom kill protection active")
        } catch (_: Exception) {
            try {
                val m = android.os.Process::class.java.getMethod("setProcessGroup", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                m.invoke(null, pid, 1) // 1 = THREAD_GROUP_FOREGROUND
                android.util.Log.d("TerminalSession", "setProcessGroup($pid, FOREGROUND=1) — fallback protection active")
            } catch (e2: Exception) {
                android.util.Log.w("TerminalSession", "setProcessGroup failed entirely (non-fatal): ${e2.message}")
            }
        }
    }
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) { Log.e(tag, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "", e) }
}

internal class SimpleTerminalViewClient : TerminalViewClient {
    var terminalView: TerminalView? = null
    private var currentTextSize: Int = 13

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            currentTextSize = (currentTextSize + if (scale > 1f) 1 else -1).coerceIn(6, 48)
            terminalView?.setTextSize(currentTextSize)
        }
        return 1.0f
    }
    override fun onSingleTapUp(e: MotionEvent?) {
        val v = terminalView ?: return
        val emulator = v.mEmulator
        // URL detection on tap — matches Termux shouldOpenTerminalTranscriptURLOnClick
        // Uses Termux's full multi-scheme URL regex (TermuxUrlUtils)
        if (emulator != null && e != null) {
            try {
                val colRow = v.getColumnAndRow(e, true)
                if (colRow != null && colRow.size >= 2) {
                    val word = emulator.screen?.getWordAtLocation(colRow[0], colRow[1]) ?: ""
                    // Full Termux URL regex — supports http/https/ftp/git/ssh/file/sftp/etc
                    val urlRegex = Regex(
                        """((?:dav|dict|dns|file|finger|ftp(?:s?)|git|gemini|gopher|http(?:s?)|imap(?:s?)|irc(?:[6s]?)|ip[fn]s|ldap(?:s?)|pop3(?:s?)|redis(?:s?)|rsync|rtsp(?:[su]?)|sftp|smb(?:s?)|smtp(?:s?)|svn(?:(?:\+ssh)?)|tcp|telnet|tftp|udp|vnc|ws(?:s?))://)""" +
                        """((?:\S+(?::\S*)?@)?(?:(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)|(?:(?:[a-z¡-￿0-9]-*)*[a-z¡-￿0-9]+)(?:(?:\.(?:[a-z¡-￿0-9]-*)*[a-z¡-￿0-9]+)*(?:\.(?:[a-z¡-￿0-9]-*){1,}[a-z¡-￿0-9]{1,}))?|/(?:(?:[a-z¡-￿0-9]-*)*[a-z¡-￿0-9]+))(?::\d{1,5})?(?:/[a-zA-Z0-9:@%\-._~!${'$'}&()*+,;=?/]*)?(?:#[a-zA-Z0-9:@%\-._~!${'$'}&()*+,;=?/]*)?)""",
                        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
                    )
                    val urlMatch = urlRegex.find(word)
                    if (urlMatch != null) {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                            Uri.parse(urlMatch.value)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        v.context.startActivity(intent)
                        return
                    }
                }
            } catch (_: Exception) {}
        }
        v.requestFocus()
        val imm = v.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    // TYPE_NULL is the correct input type (Termux default).
    // TYPE_TEXT_VARIATION_VISIBLE_PASSWORD is only needed for Samsung stock keyboards.
    // This device is TECNO — use TYPE_NULL (shouldEnforceCharBasedInput = false).
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(e: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
    override fun onEmulatorSet() {
        terminalView?.setTerminalCursorBlinkerState(true, true)
    }
    override fun logError(tag: String?, message: String?) { Log.e(tag, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "", e) }
}

internal data class TabSession(val id: String, val name: String, val session: TerminalSession, val client: SimpleTerminalSessionClient)

internal fun createTerminalSession(context: Context, isUbuntu: Boolean = false): Pair<TerminalSession, SimpleTerminalSessionClient> {
    val client = SimpleTerminalSessionClient()
    client.appContext = context.applicationContext

    if (isUbuntu) {
        val (proot, args, envVars) = ProotInstaller.launchArgs(context)
        val session = TerminalSession(proot, "/", args, envVars, 4000, client)
        return Pair(session, client)
    }

    // Use libbusybox.so from nativeLibraryDir — always executable on Android 14 (no W^X/noexec).
    // This is the same trick Termux uses: ship the binary as a .so, Android extracts it to
    // nativeLibraryDir which is always marked executable by PackageManagerService.
    val busybox = BusyboxInstaller.shellPath(context)
    val home = File(context.filesDir, "home").also { it.mkdirs() }.absolutePath
    val bin  = BusyboxInstaller.binDir(context).absolutePath
    // Build env the same way Termux does (TermuxShellEnvironment + AndroidShellEnvironment):
    // Pass through Android system vars, set LANG/COLORTERM, add LD_PRELOAD for exec() compat.
    val nativeDir = context.applicationInfo.nativeLibraryDir
    val ldPreload  = "$nativeDir/libtermux-exec.so"
    // uid-based username for USER env var (Termux does this via getpwuid)
    val uid = android.os.Process.myUid()
    val userName = try {
        android.os.Process.myPid().let { "u0_a${uid - 10000}" }
    } catch (_: Exception) { "vncode" }

    val envBuilder = mutableListOf(
        "HOME=$home",
        "PWD=$home",
        "PATH=$bin:/system/bin:/system/xbin",
        "TERM=xterm-256color",
        "COLORTERM=truecolor",
        "LANG=en_US.UTF-8",
        "SHELL=$busybox",
        "BUSYBOX=$busybox",
        "TMPDIR=${context.cacheDir.absolutePath}",
        "USER=vncode",
        "LOGNAME=vncode"
    )
    // LD_PRELOAD: intercepts exec() calls — Termux's secret weapon for Android compat.
    if (java.io.File(ldPreload).exists()) envBuilder.add("LD_PRELOAD=$ldPreload")
    // Pass through Android system environment vars exactly as Termux does
    for (key in listOf("ANDROID_DATA","ANDROID_ROOT","ANDROID_STORAGE","ANDROID_RUNTIME_ROOT",
                       "ANDROID_ART_ROOT","ANDROID_I18N_ROOT","ANDROID_TZDATA_ROOT",
                       "EXTERNAL_STORAGE","BOOTCLASSPATH","DEX2OATBOOTCLASSPATH")) {
        System.getenv(key)?.let { envBuilder.add("$key=$it") }
    }
    val env = envBuilder.toTypedArray()
    // argv[0] = "-ash" — POSIX leading-dash login shell convention.
    // ash IS the applet name in this busybox build (not bash).
    // Termux does: processName = (isLoginShell ? "-" : "") + basename(executable)
    // Use Termux bootstrap bash if installed, otherwise fall back to busybox ash.
    // TermuxBootstrapInstaller.installIfNeeded() is called from LaunchedEffect in TerminalPane.
    return if (TermuxBootstrapInstaller.isInstalled(context)) {
        val (shell, bashEnv) = TermuxBootstrapInstaller.shellArgs(context)
        val session = TerminalSession(shell, home, arrayOf("--login"), bashEnv, 4000, client)
        Pair(session, client)
    } else {
        val session = TerminalSession(busybox, home, arrayOf("-ash"), env, 4000, client)
        Pair(session, client)
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// Shared terminal state — lifted up so TerminalPane and SplitTerminalPanel
// can both read the same sessions and active tab.
// ─────────────────────────────────────────────────────────────────────────────
internal class TerminalState(
    val tabs: androidx.compose.runtime.snapshots.SnapshotStateList<TabSession>,
    initialActiveId: String,
) {
    var activeId by androidx.compose.runtime.mutableStateOf(initialActiveId)
    var pinnedId by androidx.compose.runtime.mutableStateOf<String?>(null)  // pinned mirror session

    val active: TabSession? get() = tabs.firstOrNull { it.id == activeId }
    val pinned: TabSession? get() = tabs.firstOrNull { it.id == (pinnedId ?: activeId) }
}

@androidx.compose.runtime.Composable
internal fun rememberTerminalState(context: android.content.Context): TerminalState {
    return androidx.compose.runtime.remember {
        val terminalMode = TerminalModeManager(context)
        val deviceCompat = DeviceCompatibility(context)
        val (session, client) = createTerminalSession(context)  // service not yet bound at init time
        val defaultName = when (terminalMode.currentMode()) {
            TerminalModeManager.MODE_UBUNTU -> "ubuntu"
            TerminalModeManager.MODE_OFFLINE -> "offline"
            else -> if (deviceCompat.shouldUseOfflineOnly()) "offline" else "ollama"
        }
        val tabs = androidx.compose.runtime.mutableStateListOf(TabSession("1", defaultName, session, client))
        TerminalState(tabs, "1")
    }
}


@Composable
internal fun TerminalPane(
    initialCommand: String? = null,
    onCommandConsumed: () -> Unit = {},
    externalState: TerminalState? = null,          // if provided, uses shared state
) {
    val context      = LocalContext.current
    val deviceCompat = remember { DeviceCompatibility(context) }
    val terminalMode = remember { TerminalModeManager(context) }
    var bootstrapReady by remember { mutableStateOf(false) }
    var showMenu        by remember { mutableStateOf(false) }
    var renameTargetId  by remember { mutableStateOf<String?>(null) }
    var renameValue     by remember { mutableStateOf("") }
    var showSshManager    by remember { mutableStateOf(false) }
    var showTextExpansions by remember { mutableStateOf(false) }
    var showExtraKeys     by remember { mutableStateOf(true) }
    var isRootMode        by remember { mutableStateOf(false) }
    var showSttHint       by remember { mutableStateOf(false) }
    var zshSetupDone      by remember { mutableStateOf(false) }
    val currentView = remember { androidx.compose.runtime.mutableStateOf<com.termux.view.TerminalView?>(null) }

    // Use shared state if provided, otherwise own state
    val sharedState = externalState ?: rememberTerminalState(context)
    val tabs = sharedState.tabs

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            BusyboxInstaller.ensureOfflineShell(context)
            // Extract Termux bootstrap (bash, curl, apt) on first launch.
            // Streaming ZIP extraction — safe on 3 GB device, no full-file load.
            TermuxBootstrapInstaller.installIfNeeded(context)
        }
        bootstrapReady = true
    }

    // Keep TerminalService alive for the entire lifetime of TerminalPane.
    // Matches Termux: TermuxService runs as long as ANY terminal session is open.
    // Without this, the foreground service stops after Ubuntu setup and the ash tab
    // is left completely unprotected — OEM power manager sends signal 31 and kills it.
    // Bind to TerminalService so sessions are forked from Service context (not Activity).
    // This matches Termux's architecture: phantom process killer spares children of FGS.
    var boundService by remember { mutableStateOf<TerminalService?>(null) }
    DisposableEffect(Unit) {
        TerminalService.start(context, "Terminal session active")
        val conn = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
                boundService = (binder as TerminalService.LocalBinder).service
            }
            override fun onServiceDisconnected(name: android.content.ComponentName) {
                boundService = null
            }
        }
        context.bindService(
            android.content.Intent(context, TerminalService::class.java),
            conn,
            android.content.Context.BIND_AUTO_CREATE
        )
        onDispose {
            try { context.unbindService(conn) } catch (_: Exception) {}
        }
    }

    if (!bootstrapReady) {
        Box(Modifier.fillMaxSize().background(Color(0xFF1E1E1E)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = Color(0xFF89B4FA))
                Text("Setting up terminal...", color = Color(0xFF969696), fontSize = 13.sp)
            }
        }
        return
    }

    var activeId by remember { androidx.compose.runtime.mutableStateOf(sharedState.activeId) }
    // Keep sharedState.activeId in sync with local activeId
    LaunchedEffect(activeId) { sharedState.activeId = activeId }
    // Also sync from external state changes (split panel switching tabs)
    LaunchedEffect(sharedState.activeId) { if (sharedState.activeId != activeId) activeId = sharedState.activeId }

    val active = tabs.firstOrNull { it.id == activeId } ?: tabs.firstOrNull()

    DisposableEffect(activeId) {
        val tab = tabs.firstOrNull { it.id == activeId }
        tab?.client?.onTextChanged = { currentView.value?.post { currentView.value?.onScreenUpdated() } }
        onDispose { tab?.client?.onTextChanged = null }
    }

    fun addTab() {
        val id = System.currentTimeMillis().toString()
        val (session, client) = createTerminalSession(context)  // service not yet bound at init time
        tabs.add(TabSession(id, "ash ${tabs.size + 1}", session, client))
        activeId = id
    }

    fun renameTab(id: String, newName: String) {
        val trimmed = newName.trim().ifBlank { "ash" }
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx >= 0) tabs[idx] = tabs[idx].copy(name = trimmed)
    }

    fun writeToDisplay(session: TerminalSession, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        session.getEmulator()?.append(bytes, bytes.size)
        currentView.value?.post { currentView.value?.onScreenUpdated() }
    }

    fun addUbuntuTab() {
        val ctx = context
        // If Ubuntu is already installed and a tab exists, just switch to it — don't re-download
        val existingUbuntu = tabs.indexOfFirst { it.name == "Ubuntu" }
        if (existingUbuntu >= 0 && ProotInstaller.isInstalled(ctx)) {
            activeId = tabs[existingUbuntu].id
            return
        }
        // Create the tab immediately with a shell session so we can write progress to it
        val id = System.currentTimeMillis().toString()
        val (progressSession, progressClient) = createTerminalSession(ctx, isUbuntu = false)
        tabs.add(TabSession(id, "Ubuntu", progressSession, progressClient))
        activeId = id
        progressClient.onTextChanged = { currentView.value?.post { currentView.value?.onScreenUpdated() } }
        writeToDisplay(progressSession, "\r\n[Ubuntu] Checking installation...\r\n")
        // Start foreground service BEFORE extraction — this raises process OOM priority so
        // Samsung's memory manager won't kill us mid-extraction (plain background threads
        // have the lowest OOM score and get killed first on 3 GB devices under memory pressure).
        TerminalService.start(ctx, "Setting up Ubuntu...")
        Thread {
            try {
                // Ensure Termux proot binaries are extracted from assets
                writeToDisplay(progressSession, "[Ubuntu] Preparing proot runtime...\r\n")
                ProotInstaller.ensureBinaries(ctx)
                if (!ProotInstaller.isInstalled(ctx)) {
                    writeToDisplay(progressSession, "[Ubuntu] First-time setup: downloading Ubuntu rootfs (~250MB)...\r\n")
                    writeToDisplay(progressSession, "[Ubuntu] This may take a few minutes on mobile data.\r\n\r\n")
                    ProotInstaller.install(ctx) { msg ->
                        // Mirror progress to the foreground notification so Android sees activity
                        TerminalService.updateProgress(ctx, msg.take(60))
                        writeToDisplay(progressSession, "  $msg\r\n")
                    }
                    writeToDisplay(progressSession, "\r\n[Ubuntu] ✓ Installation complete! Launching...\r\n\r\n")
                } else {
                    writeToDisplay(progressSession, "[Ubuntu] ✓ Already installed. Launching...\r\n\r\n")
                }
                // Pre-flight: write binary info to terminal for diagnosis
                val nativeDir = ctx.applicationInfo.nativeLibraryDir
                val prootBin = java.io.File(nativeDir, "libproot.so")
                val loaderBin = java.io.File(nativeDir, "libproot-loader.so")
                val tallocBin = java.io.File(nativeDir, "libtalloc.so")
                val shmemBin  = java.io.File(nativeDir, "libandroid-shmem.so")
                writeToDisplay(progressSession, "[Ubuntu] nativeLibraryDir: $nativeDir\r\n")
                writeToDisplay(progressSession, "[Ubuntu] proot:   exists=${prootBin.exists()} canExec=${prootBin.canExecute()} size=${prootBin.length()}\r\n")
                writeToDisplay(progressSession, "[Ubuntu] loader:  exists=${loaderBin.exists()} canExec=${loaderBin.canExecute()} size=${loaderBin.length()}\r\n")
                writeToDisplay(progressSession, "[Ubuntu] talloc:  exists=${tallocBin.exists()} size=${tallocBin.length()}\r\n")
                writeToDisplay(progressSession, "[Ubuntu] shmem:   exists=${shmemBin.exists()} size=${shmemBin.length()}\r\n")
                val rootfsDir = ProotInstaller.rootfsDir(ctx)
                val bashBin = java.io.File(rootfsDir, "usr/bin/bash")
                writeToDisplay(progressSession, "[Ubuntu] rootfs:  ${rootfsDir.absolutePath}\r\n")
                writeToDisplay(progressSession, "[Ubuntu] bash:    exists=${bashBin.exists()} size=${bashBin.length()}\r\n")
                writeToDisplay(progressSession, "[Ubuntu] Launching proot...\r\n\r\n")
            } finally {
                // Do NOT stop TerminalService here — it must stay alive for the proot session.
                // TerminalService is stopped only when TerminalPane is disposed (all tabs closed).
                TerminalService.updateProgress(ctx, "Ubuntu terminal active")
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                // Replace the progress tab with real Ubuntu proot session
                val idx = tabs.indexOfFirst { it.id == id }
                progressSession.finishIfRunning()
                val (session, client) = (boundService?.createSession(isUbuntu = true) ?: createTerminalSession(ctx, isUbuntu = true))
                if (idx >= 0) {
                    tabs[idx] = TabSession(id, "Ubuntu", session, client)
                } else {
                    tabs.add(TabSession(id, "Ubuntu", session, client))
                }
                activeId = id
            }
        }.apply { isDaemon = false; name = "UbuntuSetupThread"; start() }  // non-daemon: survives app backgrounding during extraction
    }

    fun closeTab(id: String) {
        if (tabs.size <= 1) return
        val idx = tabs.indexOfFirst { it.id == id }
        tabs[idx].session.finishIfRunning()
        tabs.removeAt(idx)
        if (activeId == id) activeId = tabs.getOrNull(idx - 1)?.id ?: tabs.first().id
    }

    LaunchedEffect(initialCommand, active?.id) {
        val command = initialCommand ?: return@LaunchedEffect
        active?.session?.write(command)
        onCommandConsumed()
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
        // Tab bar
        Row(Modifier.fillMaxWidth().background(Color(0xFF252526)), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { tab ->
                    val isActive = tab.id == activeId
                    Row(
                        Modifier
                            .background(if (isActive) Color(0xFF1E1E1E) else Color(0xFF2D2D2D))
                            .clickable { activeId = tab.id }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(tab.name, color = if (isActive) Color.White else Color(0xFF969696),
                            fontSize = 13.sp, fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal)
                        Text("✎", color = Color(0xFF969696), fontSize = 11.sp,
                            modifier = Modifier.padding(start = 4.dp).clickable {
                                renameTargetId = tab.id; renameValue = tab.name
                            }.padding(2.dp))
                        if (tabs.size > 1) {
                            Icon(Icons.Default.Close, null, tint = Color(0xFF969696),
                                modifier = Modifier.padding(start = 4.dp).clickable { closeTab(tab.id) }.padding(2.dp))
                        }
                    }
                }
            }
            IconButton(onClick = { addTab() }) { Icon(Icons.Default.Add, null, tint = Color(0xFF969696)) }
            Box {
                IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, null, tint = Color(0xFF969696)) }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false },
                    offset = DpOffset(0.dp, 4.dp), modifier = Modifier.background(Color(0xFF2D2D2D))) {
                    // ── TERMINALS ──────────────────────────────────────────
                    DropdownMenuItem(
                        leadingIcon = { Text("  ", fontSize = 10.sp, color = Color(0xFF717171)) },
                        text = { Text("TERMINALS", fontSize = 10.sp, color = Color(0xFF717171), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
                        onClick = {}, enabled = false)
                    DropdownMenuItem(
                        leadingIcon = { Text("${'$'}", fontSize = 13.sp, color = Color(0xFF89B4FA)) },
                        text = { Text("New Bash Terminal", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = { showMenu = false; addTab() })
                    DropdownMenuItem(
                        leadingIcon = { Text("🐧", fontSize = 13.sp) },
                        text = { Text("Open Ubuntu Linux", color = Color(0xFF89B4FA), fontSize = 13.sp) },
                        onClick = { showMenu = false; addUbuntuTab() })
                    HorizontalDivider(color = Color(0xFF444444), modifier = Modifier.padding(vertical = 2.dp))
                    // ── AI & TOOLS ─────────────────────────────────────────────
                    DropdownMenuItem(
                        leadingIcon = { Text("  ", fontSize = 10.sp, color = Color(0xFF717171)) },
                        text = { Text("AI & TOOLS", fontSize = 10.sp, color = Color(0xFF717171), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
                        onClick = {}, enabled = false)
                    DropdownMenuItem(
                        leadingIcon = { Text("🤖", fontSize = 13.sp) },
                        text = { Text("Run Ollama AI (in Ubuntu)", color = Color(0xFF89B4FA), fontSize = 13.sp) },
                        onClick = {
                            showMenu = false
                            addUbuntuTab()
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                val ubuntuTab = tabs.lastOrNull()
                                ubuntuTab?.session?.write("ollama serve &\nclear\necho \"Ollama running on :11434 — try: ollama run llama3\"\n")
                            }, 3000)
                        })
                    DropdownMenuItem(
                        leadingIcon = { Text("📦", fontSize = 13.sp) },
                        text = { Text("Setup Offline Tools", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = { showMenu = false; BusyboxInstaller.ensureOfflineShell(context); OllamaSetup(context).installProfile(); android.widget.Toast.makeText(context, "Offline shell ready", android.widget.Toast.LENGTH_SHORT).show() })
                    DropdownMenuItem(
                        leadingIcon = { Text("🔌", fontSize = 13.sp) },
                        text = { Text("Start MCP Server (npm)", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = {
                            showMenu = false
                            active?.session?.write("npx -y @modelcontextprotocol/server-filesystem \${HOME}\n")
                            android.widget.Toast.makeText(context, "Starting MCP filesystem server…", android.widget.Toast.LENGTH_SHORT).show()
                        })
                    DropdownMenuItem(
                        leadingIcon = { Text("📜", fontSize = 13.sp) },
                        text = { Text("Make Script from History", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = {
                            showMenu = false
                            active?.session?.write("history | tail -20\n")
                            android.widget.Toast.makeText(context, "Review history above — copy commands to a .sh file", android.widget.Toast.LENGTH_LONG).show()
                        })
                    HorizontalDivider(color = Color(0xFF444444), modifier = Modifier.padding(vertical = 2.dp))
                    // ── DEFAULT MODE ───────────────────────────────────────────
                    DropdownMenuItem(
                        leadingIcon = { Text("  ", fontSize = 10.sp, color = Color(0xFF717171)) },
                        text = { Text("DEFAULT MODE", fontSize = 10.sp, color = Color(0xFF717171), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
                        onClick = {}, enabled = false)
                    DropdownMenuItem(
                        leadingIcon = { Text("⚡", fontSize = 13.sp) },
                        text = { Text("Set Default: Offline / Bash", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = { showMenu = false; terminalMode.setMode(TerminalModeManager.MODE_OLLAMA); android.widget.Toast.makeText(context, "Default: Offline / Bash", android.widget.Toast.LENGTH_SHORT).show() })
                    DropdownMenuItem(
                        leadingIcon = { Text("🐧", fontSize = 13.sp) },
                        text = { Text("Set Default: Ubuntu Mode", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = { showMenu = false; terminalMode.setMode(TerminalModeManager.MODE_UBUNTU); android.widget.Toast.makeText(context, "Default: Ubuntu", android.widget.Toast.LENGTH_SHORT).show() })
                    HorizontalDivider(color = Color(0xFF444444), modifier = Modifier.padding(vertical = 2.dp))
                    // ── MANAGE ─────────────────────────────────────────────────
                    DropdownMenuItem(
                        leadingIcon = { Text("  ", fontSize = 10.sp, color = Color(0xFF717171)) },
                        text = { Text("MANAGE", fontSize = 10.sp, color = Color(0xFF717171), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
                        onClick = {}, enabled = false)
                    DropdownMenuItem(
                        leadingIcon = { Text("🔑", fontSize = 13.sp) },
                        text = { Text("SSH Manager", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = { showMenu = false; showSshManager = true })
                    DropdownMenuItem(
                        leadingIcon = { Text("⚡", fontSize = 13.sp) },
                        text = { Text("Text Expansions", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = { showMenu = false; showTextExpansions = true })
                    DropdownMenuItem(
                        leadingIcon = { Text(if (showExtraKeys) "▲" else "▼", fontSize = 13.sp, color = Color(0xFF969696)) },
                        text = { Text(if (showExtraKeys) "Hide Extra Keys" else "Show Extra Keys", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = { showMenu = false; showExtraKeys = !showExtraKeys })
                    DropdownMenuItem(
                        leadingIcon = { Text("✕", fontSize = 13.sp, color = Color(0xFFFF6B6B)) },
                        text = { Text("Close This Tab", color = Color(0xFFFF6B6B), fontSize = 13.sp) },
                        onClick = { showMenu = false; if (tabs.size > 1) closeTab(activeId) })
                }
            }
        }

        // Rename dialog
        if (renameTargetId != null) {
            AlertDialog(
                onDismissRequest = { renameTargetId = null; renameValue = "" },
                title = { Text("Rename terminal") },
                text = {
                    OutlinedTextField(value = renameValue, onValueChange = { renameValue = it },
                        label = { Text("Terminal name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                },
                confirmButton = {
                    TextButton(onClick = { renameTargetId?.let { renameTab(it, renameValue) }; renameTargetId = null; renameValue = "" }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { renameTargetId = null; renameValue = "" }) { Text("Cancel") }
                },
            )
        }

        // ── NewTermux-style toolbar row ────────────────────────────
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF161616)).padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // STT (Speech to Text)
            val sttLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
            ) { result ->
                val data = result.data
                val results = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                if (!results.isNullOrEmpty()) {
                    active?.session?.write(results[0])
                }
            }
            Box(
                Modifier.background(Color(0xFF2A2A2A), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .clickable {
                        try {
                            val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak a terminal command…")
                            }
                            sttLauncher.launch(intent)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "STT not available on this device", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("🎤 STT", color = Color(0xFFCCCCCC), fontSize = 11.sp) }

            // Root toggle
            Box(
                Modifier.background(if (isRootMode) Color(0xFF7A1A1A) else Color(0xFF2A2A2A), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .clickable {
                        if (!isRootMode) {
                            active?.session?.write("su\n")
                            isRootMode = true
                            android.widget.Toast.makeText(context, "Root shell requested — grant in prompt", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            active?.session?.write("exit\n")
                            isRootMode = false
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) { Text(if (isRootMode) "# ROOT" else "Root", color = if (isRootMode) Color(0xFFFF6B6B) else Color(0xFFCCCCCC), fontSize = 11.sp) }

            // Zsh + OMZ setup
            Box(
                Modifier.background(if (zshSetupDone) Color(0xFF1A4A1A) else Color(0xFF2A2A2A), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .clickable {
                        val cmd = buildString {
                            append("pkg install -y zsh curl git && ")
                            append("sh -c \"\$(curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)\" -- --unattended && ")
                            append("chsh -s zsh && ")
                            append("echo \"Zsh + Oh My Zsh ready!\"\n")
                        }
                        active?.session?.write(cmd)
                        zshSetupDone = true
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) { Text(if (zshSetupDone) "✓ Zsh" else "Zsh+OMZ", color = if (zshSetupDone) Color(0xFF4EC9B0) else Color(0xFFCCCCCC), fontSize = 11.sp) }

            // Clear screen
            Box(
                Modifier.background(Color(0xFF2A2A2A), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .clickable { active?.session?.write("clear\n") }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("Clear", color = Color(0xFFCCCCCC), fontSize = 11.sp) }

            // Export terminal output to file
            Box(
                Modifier.background(Color(0xFF2A2A2A), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .clickable {
                        try {
                            val screen = active?.session?.getEmulator()?.screen
                            val text = screen?.getTranscriptText() ?: ""
                            val file = java.io.File(context.getExternalFilesDir(null), "terminal_export_${System.currentTimeMillis()}.txt")
                            file.writeText(text)
                            android.widget.Toast.makeText(context, "Saved to: ${file.name}", android.widget.Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Export failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("Export", color = Color(0xFFCCCCCC), fontSize = 11.sp) }

            Spacer(Modifier.weight(1f))
            // Pkg update shortcut
            Box(
                Modifier.background(Color(0xFF2A2A2A), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .clickable { active?.session?.write("pkg update -y && pkg upgrade -y\n") }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("Pkg↑", color = Color(0xFFCCCCCC), fontSize = 11.sp) }
        }
        HorizontalDivider(color = Color(0xFF2A2A2A))

        // Extra keys bar (ESC, TAB, arrows, Ctrl, special chars)
        if (showExtraKeys) {
            val extraKeys = listOf(
                "ESC" to "\u001B",
                "TAB" to "\t",
                "↑"  to "\u001B[A",
                "↓"  to "\u001B[B",
                "←"  to "\u001B[D",
                "→"  to "\u001B[C",
                "PGUP" to "\u001B[5~",
                "PGDN" to "\u001B[6~",
                "|"  to "|",
                "/"  to "/",
                "~"  to "~",
                "-"  to "-",
                "C-c" to "\u0003",
                "C-d" to "\u0004",
                "C-z" to "\u001A",
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                extraKeys.forEach { (label, seq) ->
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .background(Color(0xFF2D2D2D), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .clickable { active?.session?.write(seq) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = Color(0xFFCCCCCC), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // SSH Manager sheet
        if (showSshManager) {
            SshManagerSheet(
                onDismiss = { showSshManager = false },
                onConnect = { label, cmd ->
                    val id = System.currentTimeMillis().toString()
                    val (session, client) = createTerminalSession(context, isUbuntu = false)
                    tabs.add(TabSession(id, label, session, client))
                    activeId = id
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        session.write(cmd + "\n")
                    }, 300)
                }
            )
        }

        // Text Expansion manager sheet
        if (showTextExpansions) {
            TextExpansionSheet(onDismiss = { showTextExpansions = false })
        }

        // Terminal view
        if (active != null) {
            key(active.id) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        TerminalView(ctx, null).apply {
                            val viewClient = SimpleTerminalViewClient()
                            viewClient.terminalView = this
                            setTerminalViewClient(viewClient)
                            setTextSize(13)
                            setTypeface(android.graphics.Typeface.MONOSPACE)
                            isFocusable = true
                            isFocusableInTouchMode = true
                            // Keep screen on while terminal is visible — matches Termux setKeepScreenOn()
                            keepScreenOn = true
                            // PTY resize on layout change — without this, vim/nano use wrong cols/rows
                            addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
                                if ((r - l) != (or2 - ol) || (b - t) != (ob - ot)) {
                                    post { onScreenUpdated() }
                                }
                            }
                        }
                    },
                    update = { view ->
                        // ALWAYS rewire callbacks on every recomposition — Termux pattern:
                        // TermuxTerminalSessionActivityClient re-sets client on every onStart().
                        // Stale callbacks cause screen-not-updating and cursor-blink bugs.
                        active.client.onTextChanged = { view.post { view.onScreenUpdated() } }
                        active.client.onTitleChanged = { title ->
                            if (!title.isNullOrBlank()) {
                                val idx = tabs.indexOfFirst { it.id == active.id }
                                if (idx >= 0) {
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        tabs[idx] = tabs[idx].copy(name = title.take(20))
                                    }
                                }
                            }
                        }
                        active.client.onSessionFinished = {
                            val idx = tabs.indexOfFirst { it.id == active.id }
                            if (idx >= 0 && !tabs[idx].name.contains("[exited]")) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    tabs[idx] = tabs[idx].copy(name = tabs[idx].name + " [exited]")
                                }
                            }
                        }
                        active.client.onCursorStateChange = { enabled ->
                            view.setTerminalCursorBlinkerState(enabled, false)
                        }
                        currentView.value = view
                        // Only call attachSession if session actually changed — it resets scroll
                        if (view.mTermSession != active.session) {
                            view.attachSession(active.session)
                            active.client.initBell(view.context)
                            view.requestFocus()
                        }
                    }
                )
            }
        }
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// SplitTerminalPanel — replaces the AI tab
//
// Shows the SAME terminal sessions as the main TerminalPane.
// • By default mirrors whatever tab is currently active in the main pane
// • Tap 📌 to pin it — it stays on that session even if you switch in the main pane
// • Tap ◀ / ▶ arrows to manually pick which tab to show on the right
// • The pinned session is fully interactive (same PTY, real input/output)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
internal fun SplitTerminalPanel(sharedState: TerminalState) {
    val tabs     = sharedState.tabs
    var isPinned by remember { mutableStateOf(sharedState.pinnedId != null) }

    // Which session to show in the mirror
    var mirrorId by remember {
        mutableStateOf(sharedState.pinnedId ?: sharedState.activeId)
    }

    // Auto-follow active tab unless pinned
    LaunchedEffect(sharedState.activeId) {
        if (!isPinned) mirrorId = sharedState.activeId
    }

    val mirrorTab = tabs.firstOrNull { it.id == mirrorId } ?: tabs.firstOrNull()

    // Pin state persisted into sharedState
    LaunchedEffect(isPinned, mirrorId) {
        sharedState.pinnedId = if (isPinned) mirrorId else null
    }

    fun prevTab() {
        val idx = tabs.indexOfFirst { it.id == mirrorId }
        if (idx > 0) mirrorId = tabs[idx - 1].id else mirrorId = tabs.last().id
        isPinned = true
    }

    fun nextTab() {
        val idx = tabs.indexOfFirst { it.id == mirrorId }
        mirrorId = if (idx < tabs.size - 1) tabs[idx + 1].id else tabs.first().id
        isPinned = true
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {

        // Header bar
        Row(
            Modifier.fillMaxWidth().height(32.dp).background(Color(0xFF252526)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Tab navigator arrows
            Text("◀", color = if (tabs.size > 1) Color(0xFF969696) else Color(0xFF444444),
                fontSize = 13.sp, modifier = Modifier.clickable(enabled = tabs.size > 1) { prevTab() }.padding(4.dp))

            // Tab chips — tap to switch mirror target
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { tab ->
                    val isShown = tab.id == mirrorId
                    Box(
                        Modifier
                            .background(if (isShown) Color(0xFF007ACC) else Color(0xFF3A3A3A), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .clickable { mirrorId = tab.id; isPinned = true }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(tab.name,
                            color = if (isShown) Color.White else Color(0xFF969696),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(4.dp))
                }
            }

            Text("▶", color = if (tabs.size > 1) Color(0xFF969696) else Color(0xFF444444),
                fontSize = 13.sp, modifier = Modifier.clickable(enabled = tabs.size > 1) { nextTab() }.padding(4.dp))

            // Pin button
            Box(
                Modifier
                    .size(26.dp)
                    .background(if (isPinned) Color(0xFF007ACC) else Color(0xFF3A3A3A), CircleShape)
                    .clickable { isPinned = !isPinned; if (!isPinned) mirrorId = sharedState.activeId },
                contentAlignment = Alignment.Center,
            ) {
                Text("📌", fontSize = 11.sp)
            }
        }

        // Mirrored terminal — points at exactly the same TerminalSession
        if (mirrorTab != null) {
            key(mirrorTab.id) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        TerminalView(ctx, null).apply {
                            val viewClient = SimpleTerminalViewClient()
                            viewClient.terminalView = this
                            setTerminalViewClient(viewClient)
                            setTextSize(13)
                            setTypeface(android.graphics.Typeface.MONOSPACE)
                            isFocusable = true
                            isFocusableInTouchMode = true
                            keepScreenOn = true  // mirror pane also keeps screen on
                        }
                    },
                    update = { view ->
                        if (view.mTermSession != mirrorTab.session) {
                            view.attachSession(mirrorTab.session)
                            // Mirror panel: set callback directly without chaining.
                            // update{} runs on every recompose — chaining lambdas here
                            // creates an infinite chain on each recompose (memory leak + slowdown).
                            // The mirror only needs: post onScreenUpdated. Done cleanly here.
                            mirrorTab.client.onTextChanged = { view.post { view.onScreenUpdated() } }
                            view.requestFocus()
                        }
                    }
                )
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No terminal open — open a terminal tab first",
                    color = Color(0xFF969696), fontSize = 13.sp)
            }
        }
    }
}
