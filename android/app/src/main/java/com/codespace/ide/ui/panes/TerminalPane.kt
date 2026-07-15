package com.codespace.ide.ui.panes

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import com.codespace.ide.terminal.ProotInstaller
import com.codespace.ide.terminal.TerminalSessionStore
import com.codespace.ide.terminal.BackupManager
import com.codespace.ide.terminal.McpShellProfile
import android.content.ServiceConnection
import com.codespace.ide.terminal.TerminalService
import com.codespace.ide.terminal.SshProfileStore
import com.codespace.ide.terminal.TextExpansionStore
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
    // Shared font size — injected from TerminalPane state so all tabs + rotation stay in sync
    var currentTextSize: Int = 13
    var onFontSizeChanged: ((Int) -> Unit)? = null

    // Termux-exact constants from TermuxPreferenceConstants / TerminalView:
    // MIN_FONTSIZE=6, MAX_FONTSIZE=56, DEFAULT_FONTSIZE=13
    companion object {
        const val MIN_FONTSIZE     = 6
        const val MAX_FONTSIZE     = 56
        const val DEFAULT_FONTSIZE = 13
    }

    override fun onScale(scale: Float): Float {
        // Exact Termux TermuxTerminalViewClient.onScale() logic:
        // 'scale' here is TerminalView.mScaleFactor — the ACCUMULATED scale factor.
        // TerminalView does: mScaleFactor *= rawPinch; mScaleFactor = mClient.onScale(mScaleFactor)
        // We MUST return 1.0f to reset mScaleFactor back to neutral each call.
        // Without the 1.0f return, scale accumulates exponentially and font jumps wildly.
        //
        // Termux formula: newSize = (currentSize * scale + 0.5).toInt().coerceIn(MIN, MAX)
        // Threshold 0.9 / 1.1: ignore micro-wobbles from a two-finger touch that isn't a real pinch.
        if (scale < 0.9f || scale > 1.1f) {
            val newSize = (currentTextSize * scale + 0.5f).toInt().coerceIn(MIN_FONTSIZE, MAX_FONTSIZE)
            if (newSize != currentTextSize) {
                currentTextSize = newSize
                terminalView?.setTextSize(currentTextSize)
                onFontSizeChanged?.invoke(currentTextSize)
            }
        }
        return 1.0f   // ALWAYS reset mScaleFactor — prevents exponential accumulation
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
    // Key state tracking — Termux pattern for hardware keyboard modifier awareness
    private var ctrlKeyDown  = false
    private var altKeyDown   = false
    private var shiftKeyDown = false

    // Callbacks wired from TerminalPane so shortcuts can act on tab state
    var onNewTab:      (() -> Unit)? = null
    var onCloseTab:    (() -> Unit)? = null
    var onPrevTab:     (() -> Unit)? = null
    var onNextTab:     (() -> Unit)? = null
    var onClearScreen: (() -> Unit)? = null

    override fun readControlKey(): Boolean = ctrlKeyDown
    override fun readAltKey():     Boolean = altKeyDown
    override fun readShiftKey():   Boolean = shiftKeyDown
    override fun readFnKey():      Boolean = false

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
        val isCtrl  = e?.isCtrlPressed  == true
        val isAlt   = e?.isAltPressed   == true
        val isShift = e?.isShiftPressed == true
        // Track modifier state for readControlKey()/readAltKey()
        if (keyCode == KeyEvent.KEYCODE_CTRL_LEFT  || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT)  { ctrlKeyDown = true;  return false }
        if (keyCode == KeyEvent.KEYCODE_ALT_LEFT   || keyCode == KeyEvent.KEYCODE_ALT_RIGHT)   { altKeyDown  = true;  return false }
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) { shiftKeyDown = true; return false }
        // ── Termux-style Ctrl+Alt shortcuts ──
        // Ctrl+Alt+N = new tab, Ctrl+Alt+W = close tab
        // Ctrl+Alt+← / P = prev tab, Ctrl+Alt+→ / N = next tab
        // Ctrl+Alt+L = clear screen
        if (isCtrl && isAlt) {
            when (keyCode) {
                KeyEvent.KEYCODE_N                  -> { onNewTab?.invoke();      return true }
                KeyEvent.KEYCODE_W                  -> { onCloseTab?.invoke();    return true }
                KeyEvent.KEYCODE_P,
                KeyEvent.KEYCODE_DPAD_LEFT          -> { onPrevTab?.invoke();     return true }
                KeyEvent.KEYCODE_DPAD_RIGHT         -> { onNextTab?.invoke();     return true }
                KeyEvent.KEYCODE_L                  -> { onClearScreen?.invoke(); return true }
            }
        }
        // Ctrl+L = clear screen (common convention, non-destructive)
        if (isCtrl && keyCode == KeyEvent.KEYCODE_L && !isAlt) {
            session?.write("")  // form feed = clear
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_CTRL_LEFT  || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT)  { ctrlKeyDown = false;  return false }
        if (keyCode == KeyEvent.KEYCODE_ALT_LEFT   || keyCode == KeyEvent.KEYCODE_ALT_RIGHT)   { altKeyDown  = false;  return false }
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) { shiftKeyDown = false; return false }
        return false
    }

    override fun onLongPress(e: MotionEvent?): Boolean = false
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

// ── Built-in color schemes — matching Termux's bundled themes ──────────────────
internal object TerminalSchemes {
    data class Scheme(val name: String, val fg: Int, val bg: Int, val cursor: Int, val colors: IntArray)

    // Helper: apply a scheme to the static COLOR_SCHEME singleton that all TerminalColors instances reset from
    fun apply(scheme: Scheme) {
        val cs = com.termux.terminal.TerminalColors.COLOR_SCHEME
        cs.mDefaultColors[com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND] = scheme.fg
        cs.mDefaultColors[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND] = scheme.bg
        cs.mDefaultColors[com.termux.terminal.TextStyle.COLOR_INDEX_CURSOR]     = scheme.cursor
        for (i in scheme.colors.indices.take(16)) cs.mDefaultColors[i] = scheme.colors[i]
    }

    val DARK = Scheme("Dark",
        fg     = 0xFFE5E5E5.toInt(),
        bg     = 0xFF000000.toInt(),
        cursor = 0xFFFFFFFF.toInt(),
        colors = intArrayOf(
            0xFF000000.toInt(), 0xFFCD0000.toInt(), 0xFF00CD00.toInt(), 0xFFCDCD00.toInt(),
            0xFF6495ED.toInt(), 0xFFCD00CD.toInt(), 0xFF00CDCD.toInt(), 0xFFE5E5E5.toInt(),
            0xFF7F7F7F.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
            0xFF5C5CFF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt()
        ))

    val DRACULA = Scheme("Dracula",
        fg     = 0xFFF8F8F2.toInt(),
        bg     = 0xFF282A36.toInt(),
        cursor = 0xFFBBBBBB.toInt(),
        colors = intArrayOf(
            0xFF21222C.toInt(), 0xFFFF5555.toInt(), 0xFF50FA7B.toInt(), 0xFFF1FA8C.toInt(),
            0xFFBD93F9.toInt(), 0xFFFF79C6.toInt(), 0xFF8BE9FD.toInt(), 0xFFF8F8F2.toInt(),
            0xFF6272A4.toInt(), 0xFFFF6E6E.toInt(), 0xFF69FF94.toInt(), 0xFFFFFFA5.toInt(),
            0xFFD6ACFF.toInt(), 0xFFFF92DF.toInt(), 0xFFA4FFFF.toInt(), 0xFFFFFFFF.toInt()
        ))

    val SOLARIZED_DARK = Scheme("Solarized Dark",
        fg     = 0xFF839496.toInt(),
        bg     = 0xFF002B36.toInt(),
        cursor = 0xFF839496.toInt(),
        colors = intArrayOf(
            0xFF073642.toInt(), 0xFFDC322F.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
            0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFF839496.toInt(),
            0xFF002B36.toInt(), 0xFFCB4B16.toInt(), 0xFF586E75.toInt(), 0xFF657B83.toInt(),
            0xFF839496.toInt(), 0xFF6C71C4.toInt(), 0xFF93A1A1.toInt(), 0xFFFDF6E3.toInt()
        ))

    val MONOKAI = Scheme("Monokai",
        fg     = 0xFFF8F8F2.toInt(),
        bg     = 0xFF1B1D1E.toInt(),
        cursor = 0xFFF92672.toInt(),
        colors = intArrayOf(
            0xFF1B1D1E.toInt(), 0xFFF92672.toInt(), 0xFFA6E22E.toInt(), 0xFFFD971F.toInt(),
            0xFF66D9EF.toInt(), 0xFF9E6EFE.toInt(), 0xFF529B2F.toInt(), 0xFFF8F8F2.toInt(),
            0xFF75715E.toInt(), 0xFFF92672.toInt(), 0xFFA6E22E.toInt(), 0xFFF4BF75.toInt(),
            0xFF66D9EF.toInt(), 0xFFAE81FF.toInt(), 0xFFA1EFE4.toInt(), 0xFFF9F8F5.toInt()
        ))

    val GRUVBOX = Scheme("Gruvbox",
        fg     = 0xFFEBDBB2.toInt(),
        bg     = 0xFF282828.toInt(),
        cursor = 0xFFEBDBB2.toInt(),
        colors = intArrayOf(
            0xFF282828.toInt(), 0xFFCC241D.toInt(), 0xFF98971A.toInt(), 0xFFD79921.toInt(),
            0xFF458588.toInt(), 0xFFB16286.toInt(), 0xFF689D6A.toInt(), 0xFFA89984.toInt(),
            0xFF928374.toInt(), 0xFFFB4934.toInt(), 0xFFB8BB26.toInt(), 0xFFFABD2F.toInt(),
            0xFF83A598.toInt(), 0xFFD3869B.toInt(), 0xFF8EC07C.toInt(), 0xFFEBDBB2.toInt()
        ))

    val ALL = listOf(DARK, DRACULA, SOLARIZED_DARK, MONOKAI, GRUVBOX)
}

internal fun createTerminalSession(context: Context, isUbuntu: Boolean = false, workDir: String? = null): Pair<TerminalSession, SimpleTerminalSessionClient> {
    val client = SimpleTerminalSessionClient()
    client.appContext = context.applicationContext

    if (isUbuntu) {
        val (proot, args, envVars) = ProotInstaller.launchArgs(context)
        val session = TerminalSession(proot, "/", args, envVars, 4000, client)
        // Inject WORKSPACE_PATH even for fallback sessions (service not yet bound)
        val prootWorkspace = workDir?.let {
            when {
                it.startsWith("/storage/emulated/0") -> it.replace("/storage/emulated/0", "/sdcard")
                it.startsWith("/sdcard") || it.startsWith("/root") -> it
                else -> null
            }
        }
        if (prootWorkspace != null) {
            session.write("export WORKSPACE_PATH=\"$prootWorkspace\"\n")
            session.write("export PROJECT_FILES=\"$prootWorkspace\"\n")
        }
        return Pair(session, client)
    }

    // Non-Ubuntu path is ONLY ever used as an inert placeholder — e.g. to hold a PTY open
    // and display progress text (via writeToDisplay's direct emulator buffer append) while
    // the real Ubuntu proot session installs/launches in the background. It is never
    // exposed to the user as a selectable shell. We deliberately removed the old
    // busybox/ash and Termux-bootstrap/bash dual-shell system (see AGENTS.md) — Ubuntu proot
    // is now the only terminal environment the app ships. Use the OS's own /system/bin/sh
    // so no bundled binary is needed at all for this placeholder.
    val home = File(context.filesDir, "home").also { it.mkdirs() }.absolutePath
    val env = arrayOf(
        "HOME=$home",
        "PWD=$home",
        "TERM=xterm-256color",
        "PATH=/system/bin:/system/xbin"
    )
    val session = TerminalSession("/system/bin/sh", home, arrayOf("sh"), env, 4000, client)
    return Pair(session, client)
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
    // Guards the one-time Ubuntu bootstrap so it only runs once even if this state is
    // shared across multiple TerminalPane composables (split panels).
    var ubuntuBootstrapStarted by androidx.compose.runtime.mutableStateOf(false)

    val active: TabSession? get() = tabs.firstOrNull { it.id == activeId }
    val pinned: TabSession? get() = tabs.firstOrNull { it.id == (pinnedId ?: activeId) }
}

@androidx.compose.runtime.Composable
internal fun rememberTerminalState(context: android.content.Context): TerminalState {
    return androidx.compose.runtime.remember {
        // App ships Ubuntu proot as the only terminal environment. The initial tab starts
        // as an inert placeholder (see createTerminalSession) and gets upgraded to the real
        // Ubuntu proot session by the bootstrap LaunchedEffect in TerminalPane below.
        val (session, client) = createTerminalSession(context)  // service not yet bound at init time
        val tabs = androidx.compose.runtime.mutableStateListOf(TabSession("1", "Ubuntu", session, client))
        TerminalState(tabs, "1")
    }
}



// ─────────────────────────────────────────────────────────────────────────────
// Ollama/Claude launch flow — rebuilt 2026-07-07 for persistence (item #12).
// Old behavior: every tap did a full re-install/re-pull/re-tab. New behavior:
// one-time setup (tracked via SharedPreferences "ollama_prefs"), then a single
// lightweight "Launch Coding Agent" that reuses the server + tab.
// ─────────────────────────────────────────────────────────────────────────────
internal data class OllamaModelOption(
    val id: String,
    val label: String,
    val ramNote: String,
    val isCloud: Boolean,
    val warn: Boolean,
)

internal val OLLAMA_MODELS = listOf(
    OllamaModelOption("qwen2.5-coder:1.5b", "Qwen 2.5 Coder 1.5B", "~1GB RAM — best for coding, recommended", isCloud = false, warn = false),
    OllamaModelOption("llama3.2:1b", "Llama 3.2 1B", "~0.8GB RAM — general chat", isCloud = false, warn = false),
    OllamaModelOption("tinyllama", "TinyLlama", "~0.6GB RAM — lightest fallback", isCloud = false, warn = false),
    OllamaModelOption("nemotron-3-super:cloud", "Nemotron 3 Super (Cloud)", "Cloud inference — needs free ollama.com sign-in", isCloud = true, warn = false),
    OllamaModelOption("qwen2.5-coder:7b", "Qwen 2.5 Coder 7B", "~5GB RAM — needs 8GB+ phone", isCloud = false, warn = true),
)

// Tries every install method in turn, stops at the first that works. Each command is kept
// on a single line (no backslash continuations) to avoid the quote-corruption bug documented
// in item #13 (a stray "&& \"" landing at a line-continuation boundary).
internal fun ollamaInstallScript(): String =
    "echo -e \"\\033[1;34m[Install]\\033[0m Installing Ollama (trying every known method)...\"\n" +
    "if ollama --version &>/dev/null 2>&1; then\n" +
    "  echo -e \"\\033[1;32m  Already installed: \$(ollama --version 2>/dev/null | head -1)\\033[0m\"\n" +
    "else\n" +
    "  command -v curl &>/dev/null || { echo -e \"\\033[1;33m  curl missing \u2014 installing...\\033[0m\"; apt install -y curl 2>&1 | tail -3; }\n" +
    "  command -v wget &>/dev/null || { echo -e \"\\033[1;33m  wget missing \u2014 installing...\\033[0m\"; apt install -y wget 2>&1 | tail -3; }\n" +
    "  command -v tar &>/dev/null  || { echo -e \"\\033[1;33m  tar missing \u2014 installing...\\033[0m\";  apt install -y tar 2>&1 | tail -3; }\n" +
    "  echo -e \"\\033[1;36m  Method 1/5: official install script...\\033[0m\"\n" +
    "  curl -fsSL --retry 5 --retry-delay 3 https://ollama.com/install.sh 2>/dev/null | sh 2>&1 | tail -8\n" +
    "  if ! ollama --version &>/dev/null 2>&1; then echo -e \"\\033[1;33m  Method 2/5: direct arm64 binary via curl...\\033[0m\"; curl -L -C - --retry 5 --retry-delay 3 https://github.com/ollama/ollama/releases/latest/download/ollama-linux-arm64.tgz -o /tmp/ollama.tgz 2>&1 | tail -5 && tar -xzf /tmp/ollama.tgz -C /usr/local/bin/ ollama 2>/dev/null && chmod +x /usr/local/bin/ollama && rm -f /tmp/ollama.tgz; fi\n" +
    "  if ! ollama --version &>/dev/null 2>&1; then echo -e \"\\033[1;33m  Method 3/5: direct arm64 binary via wget...\\033[0m\"; wget -q -c --tries=5 --waitretry=3 https://github.com/ollama/ollama/releases/latest/download/ollama-linux-arm64.tgz -O /tmp/ollama.tgz 2>&1 | tail -5 && tar -xzf /tmp/ollama.tgz -C /usr/local/bin/ ollama 2>/dev/null && chmod +x /usr/local/bin/ollama && rm -f /tmp/ollama.tgz; fi\n" +
    "  if ! ollama --version &>/dev/null 2>&1; then echo -e \"\\033[1;33m  Method 4/5: raw binary asset (no tarball)...\\033[0m\"; curl -L -C - --retry 5 --retry-delay 3 https://github.com/ollama/ollama/releases/latest/download/ollama-linux-arm64 -o /usr/local/bin/ollama 2>&1 | tail -5 && chmod +x /usr/local/bin/ollama; fi\n" +
    "  if ! ollama --version &>/dev/null 2>&1; then echo -e \"\\033[1;33m  Method 5/5: mirror proxy (for restricted networks)...\\033[0m\"; curl -L -C - --retry 5 --retry-delay 3 https://ghproxy.com/https://github.com/ollama/ollama/releases/latest/download/ollama-linux-arm64.tgz -o /tmp/ollama.tgz 2>&1 | tail -5 && tar -xzf /tmp/ollama.tgz -C /usr/local/bin/ ollama 2>/dev/null && chmod +x /usr/local/bin/ollama && rm -f /tmp/ollama.tgz; fi\n" +
    "  if ollama --version &>/dev/null 2>&1; then echo -e \"\\033[1;32m  Ollama installed!\\033[0m\"; else echo -e \"\\033[1;31m  All 5 methods failed — check your connection and try again.\\033[0m\"; fi\n" +
    "  rm -f /tmp/ollama.tgz /tmp/ollama.tar /tmp/ollama.tar.zst 2>/dev/null\n" +
    "fi\n"

// ─────────────────────────────────────────────────────────────────────────────
// Voice / TTS models — added 2026-07-08 in response to the debug doc's TTS section.
// Piper = fast, free, on-device, but flat/robotic (no pacing, no non-verbal sounds).
// Bark-small = heavier generative model, actually supports [sighs]/[laughs]/[coughs]-style
// non-verbal tags and real emotional pacing, but is CPU-only here (no GPU in proot) and may
// be slow or memory-heavy on this device — offered as an explicit, clearly-labeled trade-off.
// All downloads use curl -C - / wget -c (resume) + --retry so a dropped connection just
// picks back up instead of restarting from zero — this is now the standard pattern for any
// downloadable asset in the app, not just voices.
// ─────────────────────────────────────────────────────────────────────────────
internal data class VoiceModelOption(
    val id: String,
    val label: String,
    val note: String,
    val sizeNote: String,
    val engine: String, // "piper" or "bark"
)

internal val VOICE_MODELS = listOf(
    VoiceModelOption("en_US-lessac-medium", "Lessac (Medium)", "Fast, clear, robotic — good default", "~60MB", "piper"),
    VoiceModelOption("en_US-lessac-high", "Lessac (High)", "Fast, crisper pronunciation — still no emotion", "~120MB", "piper"),
    VoiceModelOption("en_US-amy-medium", "Amy (Medium)", "Alternate fast voice, female", "~60MB", "piper"),
    VoiceModelOption("bark-small", "Bark (Emotional)", "\u26A0\uFE0F Real pacing + sighs/laughs/coughs, but slow & CPU-only — may be heavy on this device", "~1.7GB", "bark"),
)

private fun piperVoiceRepoPath(id: String): String = when (id) {
    "en_US-lessac-medium" -> "en/en_US/lessac/medium/en_US-lessac-medium"
    "en_US-lessac-high"   -> "en/en_US/lessac/high/en_US-lessac-high"
    "en_US-amy-medium"    -> "en/en_US/amy/medium/en_US-amy-medium"
    else -> "en/en_US/lessac/medium/en_US-lessac-medium"
}

internal fun voiceInstallScript(m: VoiceModelOption): String = when (m.engine) {
    "piper" -> {
        val repoPath = piperVoiceRepoPath(m.id)
        "echo -e \"\u001b[1;34m[Voice]\u001b[0m Setting up Piper voice: ${m.label}...\"\n" +
        "command -v pip3 &>/dev/null || { echo -e \"\u001b[1;33m  python3-pip missing \u2014 installing...\u001b[0m\"; apt install -y python3-pip 2>&1 | tail -3; }\n" +
        "pip3 show piper-tts &>/dev/null || pip3 install piper-tts --break-system-packages 2>&1 | tail -8\n" +
        "mkdir -p ~/remotion-project/audio && cd ~/remotion-project/audio\n" +
        "echo -e \"\u001b[1;36m  Downloading voice model (resumable)...\u001b[0m\"\n" +
        "curl -C - --retry 5 --retry-delay 3 -L -o ${m.id}.onnx https://huggingface.co/rhasspy/piper-voices/resolve/main/$repoPath.onnx 2>&1 | tail -5\n" +
        "curl -C - --retry 5 --retry-delay 3 -L -o ${m.id}.onnx.json https://huggingface.co/rhasspy/piper-voices/resolve/main/$repoPath.onnx.json 2>&1 | tail -5\n" +
        "echo -e \"\u001b[1;32m  Done. Test with: piper --model ${m.id}.onnx --output_file test.wav <<< 'hello there'\u001b[0m\"\n"
    }
    "bark" -> {
        "echo -e \"\u001b[1;34m[Voice]\u001b[0m Setting up Bark (this is heavy \u2014 ~1.7GB + torch/transformers, may take a while)...\"\n" +
        "command -v pip3 &>/dev/null || { echo -e \"\u001b[1;33m  python3-pip missing \u2014 installing...\u001b[0m\"; apt install -y python3-pip 2>&1 | tail -3; }\n" +
        "pip3 show torch &>/dev/null || pip3 install --break-system-packages torch --index-url https://download.pytorch.org/whl/cpu 2>&1 | tail -8\n" +
        "pip3 show transformers &>/dev/null || pip3 install --break-system-packages transformers scipy accelerate 2>&1 | tail -8\n" +
        // huggingface_hub (used internally by transformers' from_pretrained) already resumes
        // partially-downloaded files automatically on retry — just re-run on failure.
        "echo -e \"\u001b[1;36m  Downloading + caching bark-small (auto-resumes on retry if it drops)...\u001b[0m\"\n" +
        "python3 -c \"from transformers import AutoProcessor, BarkModel; AutoProcessor.from_pretrained('suno/bark-small'); BarkModel.from_pretrained('suno/bark-small')\" 2>&1 | tail -15\n" +
        "echo -e \"\u001b[1;32m  Bark-small ready. This is CPU-only here \u2014 expect generation to be noticeably slower than Piper.\u001b[0m\"\n"
    }
    else -> "echo 'Unknown voice model'\n"
}

// Hard guard: never start a second `ollama serve` on this device.
internal fun ollamaServerGuardScript(): String =
    "echo -e \"\\033[1;34m[Server]\\033[0m Checking Ollama server...\"\n" +
    "if pgrep -f \"ollama serve\" >/dev/null 2>&1; then\n" +
    "  echo -e \"\\033[1;32m  Already running on :11434 — reusing.\\033[0m\"\n" +
    "else\n" +
    "  nohup ollama serve >/tmp/ollama.log 2>&1 &\n" +
    "  sleep 2\n" +
    "  echo -e \"\\033[1;32m  Server started on :11434\\033[0m\"\n" +
    "fi\n"

internal fun ollamaClaudeGuardScript(): String =
    "if ! command -v claude &>/dev/null; then\n" +
    "  echo -e \"\\033[1;34m[Claude]\\033[0m Installing Claude Code...\"\n" +
    "  npm install -g @anthropic-ai/claude-code 2>&1 | tail -3\n" +
    // 2026-07-08 debug session found the npm install alone is not enough: the native binary's
    // postinstall doesn't always run (--ignore-scripts, some npm/pnpm configs), leaving `claude`
    // installed but non-functional with no error at install time. Fix: always run the postinstall
    // manually, then verify with `claude --version` instead of trusting npm's exit code.
    "  node /usr/local/lib/node_modules/@anthropic-ai/claude-code/install.cjs 2>&1 | tail -5\n" +
    "  if command -v claude &>/dev/null && claude --version &>/dev/null 2>&1; then\n" +
    "    echo -e \"\\033[1;32m  Claude Code installed: \$(claude --version 2>/dev/null | head -1)\\033[0m\"\n" +
    "  else\n" +
    "    echo -e \"\\033[1;31m  Claude Code install may be incomplete — try running 'claude --version' manually.\\033[0m\"\n" +
    "  fi\n" +
    "else\n" +
    "  echo -e \"\\033[1;32m  Claude Code already installed: \$(claude --version 2>/dev/null | head -1)\\033[0m\"\n" +
    "fi\n"

internal fun ollamaEnvScript(model: String): String =
    "grep -q ANTHROPIC_BASE_URL ~/.bashrc 2>/dev/null || {\n" +
    "  echo \"export ANTHROPIC_BASE_URL=http://localhost:11434\" >> ~/.bashrc\n" +
    "  echo \"export ANTHROPIC_AUTH_TOKEN=ollama\" >> ~/.bashrc\n" +
    "  echo \"export ANTHROPIC_MODEL=$model\" >> ~/.bashrc\n" +
    "  source ~/.bashrc\n" +
    "}\n"

// Guarded pull — only pulls if not already present (no more unconditional re-pull every run).
internal fun ollamaPullGuardScript(model: String): String =
    "echo -e \"\\033[1;34m[Model]\\033[0m Checking $model...\"\n" +
    "if ollama list 2>/dev/null | grep -q \"$model\"; then\n" +
    "  echo -e \"\\033[1;32m  Already pulled\\033[0m\"\n" +
    "else\n" +
    "  ollama pull $model\n" +
    "fi\n"

// First-ever run: full setup, then launches straight into Claude Code.
internal fun ollamaFullSetupScript(model: OllamaModelOption): String =
    ollamaInstallScript() +
    ollamaServerGuardScript() +
    (if (model.isCloud)
        "echo -e \"\\033[1;34m[Sign-in]\\033[0m Cloud model needs a free ollama.com account...\"\n" +
        "ollama signin\n"
    else "") +
    ollamaPullGuardScript(model.id) +
    ollamaClaudeGuardScript() +
    ollamaEnvScript(model.id) +
    "clear\n" +
    "echo -e \"\\033[1;32mSetup complete! Launching Claude Code...\\033[0m\"\n" +
    "claude --model ${model.id}\n"

// Every run after: no install, no pull, no new tab (reused by caller) — just make sure the
// server is up and jump straight to Claude Code.
internal fun ollamaLaunchScript(model: String): String =
    ollamaServerGuardScript() +
    "clear\n" +
    "echo -e \"\\033[1;32mLaunching Claude Code ($model)...\\033[0m\"\n" +
    "claude --model $model\n"


// Guarded Node.js + ffmpeg + @remotion/cli install, idempotent project scaffold (no
// interactive create-video prompts — hand-written minimal project instead), and a
// chunked-render helper script for long videos. Mirrors the Ollama install pattern:
// safe to re-run — every step checks "already have this?" before doing anything.
// Wisdom's chunked-render requirement (item raised 2026-07-07): rendering a full
// 30min+ video in one process risks OOM on this device, so render_chunked.sh renders
// in small --frames=start-end segments and stitches them with `ffmpeg -c copy` (no
// re-encode) — resumable, and keeps peak RAM bounded regardless of total video length.
internal fun remotionSetupScript(): String = """
echo -e "\033[1;34m[1/5]\033[0m Checking Node.js (need 18+)..."
NODE_OK=0
if command -v node &>/dev/null; then
  NODE_MAJOR=${'$'}(node -v | sed 's/v//' | cut -d. -f1)
  if [ "${'$'}NODE_MAJOR" -ge 18 ] 2>/dev/null; then NODE_OK=1; fi
fi
if [ "${'$'}NODE_OK" -eq 0 ]; then
  echo -e "\033[1;36m  Installing Node.js via apt...\033[0m"
  apt install -y nodejs npm 2>&1 | tail -5
  NODE_MAJOR=${'$'}(node -v 2>/dev/null | sed 's/v//' | cut -d. -f1)
  if [ -z "${'$'}NODE_MAJOR" ] || [ "${'$'}NODE_MAJOR" -lt 18 ] 2>/dev/null; then
    echo -e "\033[1;33m  apt Node too old/missing — trying NodeSource (Node 20)...\033[0m"
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - 2>&1 | tail -10
    apt install -y nodejs 2>&1 | tail -5
  fi
  echo -e "\033[1;32m  Node: ${'$'}(node -v 2>/dev/null)\033[0m"
else
  echo -e "\033[1;32m  Already have Node ${'$'}(node -v)\033[0m"
fi

echo -e "\033[1;34m[2/5]\033[0m Checking ffmpeg + Chrome headless-shell deps..."
if ! command -v ffmpeg &>/dev/null; then
  apt install -y ffmpeg 2>&1 | tail -5
else
  echo -e "\033[1;32m  ffmpeg already installed\033[0m"
fi
# 2026-07-08 debug session found ffmpeg alone is not enough: Remotion's bundled headless
# Chrome fails to launch at render time with "libnspr4.so: cannot open shared object file"
# even though ffmpeg itself works fine — these libs aren't pulled in by ffmpeg's own deps.
if ! dpkg -s libnspr4 &>/dev/null 2>&1 || ! dpkg -s libnss3 &>/dev/null 2>&1; then
  echo -e "\033[1;36m  Installing Chrome headless-shell runtime libs (needed for rendering)...\033[0m"
  apt install -y libnspr4 libnss3 libatk1.0-0t64 libatk-bridge2.0-0t64 \
    libcups2t64 libxcomposite1 libxdamage1 libxfixes3 libxrandr2 \
    libgbm1 libpango-1.0-0 libcairo2 libasound2t64 2>&1 | tail -8
else
  echo -e "\033[1;32m  Chrome headless-shell libs already installed\033[0m"
fi

echo -e "\033[1;34m[3/5]\033[0m Checking @remotion/cli..."
if ! npm list -g @remotion/cli &>/dev/null; then
  npm install -g @remotion/cli 2>&1 | tail -5
else
  echo -e "\033[1;32m  Already installed\033[0m"
fi

echo -e "\033[1;34m[4/5]\033[0m Checking Remotion project (~/remotion-project)..."
if [ ! -d ~/remotion-project ]; then
  echo -e "\033[1;36m  Scaffolding a minimal project (no interactive prompts)...\033[0m"
  mkdir -p ~/remotion-project/src ~/remotion-project/out/chunks
  cat > ~/remotion-project/package.json <<'PKGEOF'
{
  "name": "remotion-project",
  "version": "1.0.0",
  "private": true,
  "scripts": {
    "start": "remotion studio",
    "render": "remotion render"
  },
  "dependencies": {
    "@remotion/cli": "4.0.0",
    "remotion": "4.0.0",
    "react": "18.2.0",
    "react-dom": "18.2.0"
  },
  "devDependencies": {
    "typescript": "5.4.0"
  }
}
PKGEOF
  cat > ~/remotion-project/tsconfig.json <<'TSCEOF'
{
  "compilerOptions": {
    "target": "ES2018",
    "module": "commonjs",
    "jsx": "react",
    "esModuleInterop": true,
    "strict": false,
    "skipLibCheck": true
  }
}
TSCEOF
  cat > ~/remotion-project/src/Root.tsx <<'ROOTEOF'
import { Composition } from 'remotion';
import { MyVideo } from './MyVideo';

export const RemotionRoot = () => {
  return (
    <Composition
      id="MyVideo"
      component={MyVideo}
      durationInFrames={150}
      fps={30}
      width={1280}
      height={720}
    />
  );
};
ROOTEOF
  cat > ~/remotion-project/src/MyVideo.tsx <<'VIDEOEOF'
import { AbsoluteFill, useCurrentFrame, interpolate } from 'remotion';

export const MyVideo = () => {
  const frame = useCurrentFrame();
  const opacity = interpolate(frame, [0, 30], [0, 1], { extrapolateRight: 'clamp' });
  return (
    <AbsoluteFill style={{ backgroundColor: 'white', justifyContent: 'center', alignItems: 'center' }}>
      <div style={{ fontSize: 80, opacity }}>Hello Remotion — edit src/MyVideo.tsx</div>
    </AbsoluteFill>
  );
};
VIDEOEOF
  cat > ~/remotion-project/src/index.ts <<'IDXEOF'
import { registerRoot } from 'remotion';
import { RemotionRoot } from './Root';

registerRoot(RemotionRoot);
IDXEOF
  cat > ~/remotion-project/remotion.config.ts <<'CFGEOF'
import { Config } from '@remotion/cli/config';

Config.setVideoImageFormat('jpeg');
Config.setOverwriteOutput(true);
CFGEOF
  cat > ~/remotion-project/render_chunked.sh <<'RCEOF'
#!/bin/bash
# Chunked Remotion render — avoids OOM crashes on long (e.g. 30min+) videos by
# rendering in small frame-range segments, then stitching with ffmpeg concat
# (stream copy, no re-encode, so the merge itself is fast and near-zero RAM).
# Resumable: re-running skips chunks that already have an output file, same
# philosophy as the rootfs chunked-extraction fix (crash mid-way loses only
# the current chunk, not the whole render).
#
# Usage: ./render_chunked.sh <compositionId> <totalFrames> [chunkFrames] [fps]
# Example (30 min @ 30fps = 54000 frames, 5s chunks = 150 frames):
#   ./render_chunked.sh MyVideo 54000 150 30

COMP=${'$'}{1:?Usage: render_chunked.sh <compositionId> <totalFrames> [chunkFrames] [fps]}
TOTAL=${'$'}{2:?total frame count required}
CHUNK=${'$'}{3:-150}
FPS=${'$'}{4:-30}

mkdir -p out/chunks
START=0
INDEX=0

echo "=== Chunked render: ${'$'}COMP, ${'$'}TOTAL frames, ${'$'}CHUNK frames/chunk, ${'$'}{FPS}fps ==="

while [ ${'$'}START -lt ${'$'}TOTAL ]; do
  END=${'$'}((START + CHUNK - 1))
  if [ ${'$'}END -ge ${'$'}TOTAL ]; then END=${'$'}((TOTAL - 1)); fi
  OUTFILE=${'$'}(printf "out/chunks/chunk_%04d.mp4" ${'$'}INDEX)

  if [ -f "${'$'}OUTFILE" ]; then
    echo "[chunk ${'$'}INDEX] already rendered — skipping (resume)"
  else
    echo "[chunk ${'$'}INDEX] frames ${'$'}START-${'$'}END -> ${'$'}OUTFILE"
    npx remotion render "${'$'}COMP" --frames=${'$'}START-${'$'}END "${'$'}OUTFILE"
    # Brief pause between chunks lets the device settle (same GC-breathing-room
    # pattern used during rootfs extraction) before starting the next render.
    sleep 2
  fi

  START=${'$'}((END + 1))
  INDEX=${'$'}((INDEX + 1))
done

echo "=== Merging ${'$'}INDEX chunks (stream copy, keeps continuous flow/audio in sync) ==="
rm -f out/filelist.txt
for f in out/chunks/chunk_*.mp4; do
  echo "file '${'$'}f'" >> out/filelist.txt
done
ffmpeg -y -f concat -safe 0 -i out/filelist.txt -c copy out/final_output.mp4

if [ -f out/final_output.mp4 ]; then
  echo "=== Done: out/final_output.mp4 ==="
else
  echo "=== Merge failed — check ffmpeg output above ==="
fi
RCEOF
  chmod +x ~/remotion-project/render_chunked.sh
  cd ~/remotion-project && npm install 2>&1 | tail -10
  echo -e "\033[1;32m  Project scaffolded at ~/remotion-project\033[0m"
else
  echo -e "\033[1;32m  Already scaffolded\033[0m"
fi

echo -e "\033[1;34m[5/5]\033[0m Launching Remotion Studio..."
cd ~/remotion-project
echo -e "\033[1;32mSetup complete!\033[0m"
echo -e "\033[1;33m  Studio:         npx remotion studio\033[0m"
echo -e "\033[1;33m  Chunked render: ./render_chunked.sh MyVideo <totalFrames> [chunkFrames] [fps]\033[0m"
echo -e "\033[1;33m  Example (30min @30fps, 5s chunks): ./render_chunked.sh MyVideo 54000 150 30\033[0m"
npx remotion studio
""".trimIndent()

// Every run after first-time setup: no reinstall, no rescaffold — just guard the dev
// server (checks if Remotion Studio is already up on :3000 before starting another) and
// jump straight back into the existing project.
internal fun remotionRelaunchScript(): String =
    "echo -e \"\\033[1;34m[Remotion]\\033[0m Checking Remotion Studio...\"\n" +
    "if pgrep -f \"remotion studio\" >/dev/null 2>&1; then\n" +
    "  echo -e \"\\033[1;32m  Already running on :3000 — reusing.\\033[0m\"\n" +
    "else\n" +
    "  cd ~/remotion-project && npx remotion studio\n" +
    "fi\n"
// Scoped by projectId to match ExplorerPane's / SourceControlPane's per-project workspace
// isolation — reads the SAME saved path so a freshly created Ubuntu session for this
// project can auto-cd into it (fix #12, 2026-07-08).
private fun loadWorkspacePath(context: android.content.Context, projectId: String): String? =
    context.getSharedPreferences("workspace_prefs", android.content.Context.MODE_PRIVATE)
        .getString("workspace_path_$projectId", null)

@Composable
internal fun TerminalPane(
    initialCommand: String? = null,
    onCommandConsumed: () -> Unit = {},
    externalState: TerminalState? = null,          // if provided, uses shared state
    projectId: String = "default",                 // fix #12: scopes session tracking/reattach
) {
    val context      = LocalContext.current
    val scope        = androidx.compose.runtime.rememberCoroutineScope()

    // ── Moved up: needed by the lifecycle observer (ON_STOP handler) below.
    // Use shared state if provided, otherwise own state
    val sharedState = externalState ?: rememberTerminalState(context)
    val tabs = sharedState.tabs
    // Service binding — declared early so the ON_STOP lifecycle handler can call killAllSessions()
    var boundService by remember { mutableStateOf<TerminalService?>(null) }

    // ── Activity visibility tracker — mirrors Termux's mActivity.isVisible() check in
    //    onTextChanged. Without this, terminal output triggers wasted onScreenUpdated()
    //    posts even when the app is minimized, burning CPU on a 3GB device and attracting
    //    OEM power manager kills. Confirmed from decompiled TermuxTerminalSessionClient.smali
    //    line 113: "if (!mActivity.isVisible()) return;" before any redraw.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var isActivityVisible by remember { mutableStateOf(true) }
    androidx.lifecycle.LifecycleEventObserver { _, event ->
        when (event) {
            androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> isActivityVisible = false
            androidx.lifecycle.Lifecycle.Event.ON_RESUME -> isActivityVisible = true
            else -> {}
        }
    }.also { observer ->
        DisposableEffect(lifecycleOwner) {
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }
    // ── Font size state — persisted via SharedPreferences (Termux KEY_FONTSIZE pattern) ──
    // MIN=6, MAX=56, DEFAULT=13 — survives rotation, tab switches, process restarts
    val prefs = remember { context.getSharedPreferences("terminal_prefs", android.content.Context.MODE_PRIVATE) }
    var terminalFontSize by remember { mutableStateOf(prefs.getInt("KEY_FONTSIZE", SimpleTerminalViewClient.DEFAULT_FONTSIZE).coerceIn(SimpleTerminalViewClient.MIN_FONTSIZE, SimpleTerminalViewClient.MAX_FONTSIZE)) }
    // Observe screen configuration for rotation — triggers updateSize() on all TerminalViews
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    @Suppress("UNUSED_VARIABLE")
    val screenWidthDp  = configuration.screenWidthDp   // read to subscribe to config changes
    @Suppress("UNUSED_VARIABLE")
    val screenHeightDp = configuration.screenHeightDp
    // FIXED 2026-07-03: "rotating added a new tab I never tapped." The tab-strip "+" icon
    // sits exactly where a finger resting on the glass during a physical rotate gesture
    // ends up once the layout reflows for the new orientation -- Android can dispatch that
    // as a genuine ghost-tap on whatever is now under the finger. Track the last orientation
    // flip and have addTab() ignore any call landing within the same short window.
    var lastOrientationChangeAt by remember { mutableStateOf(0L) }
    LaunchedEffect(configuration.orientation) { lastOrientationChangeAt = System.currentTimeMillis() }
    var bootstrapReady by remember { mutableStateOf(false) }
    var showTapToStart by remember { mutableStateOf(false) }
    var autoStartCountdownDone by remember { mutableStateOf(false) }
    var showMenu        by remember { mutableStateOf(false) }
    var renameTargetId  by remember { mutableStateOf<String?>(null) }
    var renameValue     by remember { mutableStateOf("") }
    var showSshManager    by remember { mutableStateOf(false) }
    var showTextExpansions by remember { mutableStateOf(false) }
    var showExtraKeys     by remember { mutableStateOf(false) }
    var showQuickActions by remember { mutableStateOf(true) }
    var isRootMode        by remember { mutableStateOf(false) }
    var acEnabled         by remember { mutableStateOf(false) }
    var showCustomCmds    by remember { mutableStateOf(false) }
    var showHistorySearch  by remember { mutableStateOf(false) }
    var showSttHint       by remember { mutableStateOf(false) }
    var zshSetupDone      by remember { mutableStateOf(false) }
    var showSchemeMenu    by remember { mutableStateOf(false) }
    var activeScheme      by remember { mutableStateOf(TerminalSchemes.DARK) }
    // Ollama/Claude persistent state — see item #12 rebuild (2026-07-07)
    val ollamaPrefs = remember { context.getSharedPreferences("ollama_prefs", android.content.Context.MODE_PRIVATE) }
    var showOllamaModelPicker by remember { mutableStateOf(false) }
    var showVoiceModelPicker by remember { mutableStateOf(false) }
    var ollamaMultiInstance by remember { mutableStateOf(ollamaPrefs.getBoolean("multi_instance", false)) }
    // Remotion persistent state — same one-time-setup pattern as Ollama (2026-07-07)
    val remotionPrefs = remember { context.getSharedPreferences("remotion_prefs", android.content.Context.MODE_PRIVATE) }
    val currentView = remember { androidx.compose.runtime.mutableStateOf<com.termux.view.TerminalView?>(null) }

    LaunchedEffect(Unit) {
        // Ubuntu proot install/launch progress renders directly inside the terminal tab
        // itself (see addUbuntuTab below) — no separate loading-screen gate needed.
        bootstrapReady = true
    }

    // Keep TerminalService alive for the entire lifetime of TerminalPane.
    // Matches Termux: TermuxService runs as long as ANY terminal session is open.
    // Without this, the foreground service stops after Ubuntu setup and the ash tab
    // is left completely unprotected — OEM power manager sends signal 31 and kills it.
    // Bind to TerminalService so sessions are forked from Service context (not Activity).
    // This matches Termux's architecture: phantom process killer spares children of FGS.
    DisposableEffect(Unit) {
        TerminalService.start(context, "Terminal session active")
        // Start Agent API server + install agent shell profile for terminal AI
        McpShellProfile.install(context)
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
            McpShellProfile.stop()
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
        // Termux pattern: only redraw if activity is visible (smali line 113)
        tab?.client?.onTextChanged = {
            if (isActivityVisible) currentView.value?.post { currentView.value?.onScreenUpdated() }
        }
        onDispose { tab?.client?.onTextChanged = null }
    }

    fun renameTab(id: String, newName: String) {
        val trimmed = newName.trim().ifBlank { "Ubuntu" }
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx >= 0) tabs[idx] = tabs[idx].copy(name = trimmed)
    }

    fun writeToDisplay(session: TerminalSession, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        session.getEmulator()?.append(bytes, bytes.size)
        currentView.value?.post { currentView.value?.onScreenUpdated() }
    }

    // Ubuntu proot is the ONLY terminal environment this app ships (bash/ash removed —
    // see AGENTS.md). This single function handles both cases:
    //  - replaceTabId == null: user tapped "+" / "New Ubuntu Terminal" — add another
    //    independent proot session tab. If Ubuntu is already installed this is instant
    //    (no progress screen needed).
    //  - replaceTabId != null: upgrade an existing placeholder tab in place (used once,
    //    for the very first tab on app launch, by the bootstrap LaunchedEffect below).
    fun addUbuntuTab(replaceTabId: String? = null) {
        val ctx = context

        // Fast path: already installed, just opening another tab — fork immediately.
        if (replaceTabId == null && ProotInstaller.isInstalled(ctx)) {
            // Ensure agent tools are available in this new terminal tab
            McpShellProfile.install(ctx)
            val id = System.currentTimeMillis().toString()
            val (session, client) = (boundService?.createSession(isUbuntu = true, projectId = projectId, workDir = loadWorkspacePath(ctx, projectId)) ?: createTerminalSession(ctx, isUbuntu = true, workDir = loadWorkspacePath(ctx, projectId)))
            tabs.add(TabSession(id, "Ubuntu", session, client))
            activeId = id
            // Phase 4: persist after opening a new tab
            scope.launch { TerminalSessionStore.save(context, tabs.map {
                TerminalSessionStore.SavedTab(it.id, it.name, loadWorkspacePath(context, projectId) ?: "/root")
            }) }
            return
        }

        // FIXED 2026-07-03: an install is already actively running (e.g. user tapped "+"
        // for another tab while the first-run download/extract is still going). Jump
        // straight to that tab — which already shows the real % progress — instead of
        // spawning a second, duplicate progress tab that would only ever show a repeating
        // "waiting..." line with no real information. This was the "fills the screen and
        // the real progress bar doesn't show" complaint.
        if (replaceTabId == null && ProotInstaller.isInstallRunning()) {
            val runningId = ProotInstaller.installingTabId
            if (runningId != null && tabs.any { it.id == runningId }) {
                activeId = runningId
                return
            }
        }

        // Slow path: first-time install, or upgrading the initial placeholder tab.
        val id = replaceTabId ?: System.currentTimeMillis().toString()
        val existing = replaceTabId?.let { rid -> tabs.firstOrNull { it.id == rid } }
        val (progressSession, progressClient) = existing?.let { Pair(it.session, it.client) }
            ?: createTerminalSession(ctx, isUbuntu = false)
        if (existing == null) {
            tabs.add(TabSession(id, "Ubuntu", progressSession, progressClient))
        }
        activeId = id
        progressClient.onTextChanged = {
            if (isActivityVisible) currentView.value?.post { currentView.value?.onScreenUpdated() }
        }
        val isFirstTimeInstall = !ProotInstaller.isInstalled(ctx)
        writeToDisplay(progressSession, "\r\n[Ubuntu] Checking installation...\r\n")
        // Start foreground service BEFORE extraction — this raises process OOM priority so
        // Samsung's memory manager won't kill us mid-extraction (plain background threads
        // have the lowest OOM score and get killed first on 3 GB devices under memory pressure).
        TerminalService.start(ctx, "Setting up Ubuntu...")
        ProotInstaller.installingTabId = id
        Thread {
            try {
                // Ensure Termux proot binaries are extracted from assets
                writeToDisplay(progressSession, "[Ubuntu] Preparing proot runtime...\r\n")
                ProotInstaller.ensureBinaries(ctx)
                if (isFirstTimeInstall && BackupManager.hasBackup()) {
                    // A previous container backup exists in shared storage (survives uninstall) —
                    // restore it instead of downloading a fresh Ubuntu rootfs from scratch. This is
                    // what makes every GitHub Actions rebuild's forced uninstall/reinstall NOT wipe
                    // Node/ffmpeg/Remotion/Piper/Ollama/Claude Code/projects every single time.
                    writeToDisplay(progressSession, "[Ubuntu] Found a container backup — restoring instead of a fresh install...\r\n\r\n")
                    BackupManager.restorePrefs(ctx)
                    BackupManager.restoreBackup(ctx) { msg ->
                        TerminalService.updateProgress(ctx, msg.take(60))
                        writeToDisplay(progressSession, "  $msg\r\n")
                    }
                    writeToDisplay(progressSession, "\r\n[Ubuntu] \u2713 Restored from backup! Launching...\r\n\r\n")
                } else if (isFirstTimeInstall) {
                    writeToDisplay(progressSession, "[Ubuntu] First-time setup: downloading Ubuntu rootfs (~250MB)...\r\n")
                    writeToDisplay(progressSession, "[Ubuntu] This may take a few minutes on mobile data.\r\n\r\n")
                    ProotInstaller.install(ctx) { msg ->
                        // Mirror progress to the foreground notification so Android sees activity,
                        // AND append it into the terminal as plain scrolling text (Termux-style
                        // bootstrap-unpack look) — every line is appended, never overwritten, so
                        // status text and the numeric "% downloaded" lines both stay visible
                        // together the whole time, including if mobile data drops mid-download.
                        TerminalService.updateProgress(ctx, msg.take(60))
                        writeToDisplay(progressSession, "  $msg\r\n")
                    }
                    writeToDisplay(progressSession, "\r\n[Ubuntu] ✓ Installation complete! Launching...\r\n\r\n")
                } else {
                    writeToDisplay(progressSession, "[Ubuntu] ✓ Already installed. Launching...\r\n\r\n")
                }
                // Pre-flight binary diagnostics — logcat only (adb debugging), not written to
                // the terminal, to keep the visible text focused on setup status/progress.
                val nativeDir = ctx.applicationInfo.nativeLibraryDir
                val prootBin  = java.io.File(nativeDir, "libproot.so")
                val loaderBin = java.io.File(nativeDir, "libproot-loader.so")
                val tallocBin = java.io.File(nativeDir, "libtalloc.so")
                val shmemBin  = java.io.File(nativeDir, "libandroid-shmem.so")
                val rootfsDir = ProotInstaller.rootfsDir(ctx)
                val bashBin   = java.io.File(rootfsDir, "usr/bin/bash")
                android.util.Log.d(
                    "TerminalPane",
                    "nativeLibraryDir=$nativeDir proot=${prootBin.exists()}/${prootBin.canExecute()} " +
                    "loader=${loaderBin.exists()} talloc=${tallocBin.exists()} shmem=${shmemBin.exists()} " +
                    "rootfs=${rootfsDir.absolutePath} bash=${bashBin.exists()}"
                )
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
                val (session, client) = (boundService?.createSession(isUbuntu = true, projectId = projectId, workDir = loadWorkspacePath(ctx, projectId)) ?: createTerminalSession(ctx, isUbuntu = true, workDir = loadWorkspacePath(ctx, projectId)))
                if (idx >= 0) {
                    tabs[idx] = TabSession(id, "Ubuntu", session, client)
                } else {
                    tabs.add(TabSession(id, "Ubuntu", session, client))
                }
                activeId = id
            }
        }.apply { isDaemon = false; name = "UbuntuSetupThread"; start() }  // non-daemon: survives app backgrounding during extraction
    }

    fun addTab() {
        // Swallow ghost-taps landing within 600ms of a rotation (see lastOrientationChangeAt
        // above) -- this is what was silently spawning an extra Ubuntu tab on rotate.
        if (System.currentTimeMillis() - lastOrientationChangeAt < 600) {
            android.util.Log.d("TerminalPane", "addTab() ignored — within 600ms of a rotation (ghost-tap guard)")
            return
        }
        addUbuntuTab(replaceTabId = null)
    }

    // One-time: upgrade the initial placeholder tab (created synchronously in
    // rememberTerminalState) into the real Ubuntu proot session. Guarded by
    // sharedState.ubuntuBootstrapStarted so split panels sharing this state don't
    // race to install Ubuntu twice.
    //
    // FIXED 2026-07-03 — session-stacking leak: this used to unconditionally call
    // addUbuntuTab() (spawn a brand new real proot+bash session) on every fresh
    // Composition, with no check for an already-running session. Since `remember`
    // state resets completely whenever the Activity is destroyed/recreated (which
    // TECNO HiOS does on a plain minimize while TerminalService/the process survive),
    // every reopen after minimizing spawned ANOTHER live Ubuntu session on top of
    // whatever was already running — never cleaned up unless the user manually closed
    // the tab. Stacking multiple live proot+bash+rootfs-mount trees is a very
    // plausible OOM trigger on a 3GB device, and it fires at exactly the moment of
    // reopening — matching the reported "opens then instantly closes" symptom.
    //
    // Fix: wait for the service binding, then check TerminalService.findLiveUbuntuSession()
    // first. If a live session already exists (survived from before this Activity was
    // recreated), REATTACH to it via updateTerminalSessionClient() instead of forking a
    // duplicate. Only fall through to addUbuntuTab() (real install/fork path) if nothing
    // is already running.
    LaunchedEffect(boundService) {
        val svc = boundService ?: return@LaunchedEffect  // wait until service is actually bound
        if (sharedState.ubuntuBootstrapStarted) return@LaunchedEffect
        sharedState.ubuntuBootstrapStarted = true

        // Rebuild the ENTIRE tab list from every live session the Service already has,
        // not just one — matches real Termux's TermuxService.getTermuxSessions() pattern
        // (confirmed via decompile of the reference APK): the Service's session list is
        // the single source of truth, and reconnecting swaps a fresh UI client onto EVERY
        // session, never just the first. Reattaching only one session would silently
        // orphan any additional Ubuntu tabs that were open before this Activity was
        // torn down and recreated (e.g. OEM killing the Activity on minimize while the
        // Service/process survive).
        val existingSessions = svc.getLiveUbuntuSessions(projectId)
        if (existingSessions.isNotEmpty()) {
            // Kill the placeholder /system/bin/sh session created by rememberTerminalState —
            // it's not in the Service's liveSessions list, so getLiveUbuntuSessions() won't
            // clean it up. Without this, every Activity recreate leaks a sh process.
            tabs.forEach { try { it.session.finishIfRunning() } catch (_: Throwable) {} }
            try {
                val rebuiltTabs = existingSessions.mapIndexed { index, session ->
                    val newClient = SimpleTerminalSessionClient().apply { appContext = context }
                    session.updateTerminalSessionClient(newClient)
                    val name = if (existingSessions.size > 1) "Ubuntu ${index + 1}" else "Ubuntu"
                    TabSession("resumed-$index-${session.hashCode()}", name, session, newClient)
                }
                tabs.clear()
                tabs.addAll(rebuiltTabs)
                activeId = rebuiltTabs.first().id
            } catch (e: Throwable) {
                // Session reattach failed (sessions may be dead/corrupted after OEM kill,
                // or OOM on 3GB device with multiple surviving proot trees).
                // KILL all old sessions before creating a new one — without this, every
                // failed reattach stacks another proot+bash+rootfs-mount tree on top of
                // the old ones, causing OOM "opens then instantly closes" on 3GB devices.
                android.util.Log.e("TerminalPane", "Session reattach failed, killing old sessions and starting fresh", e)
                tabs.clear()
                try {
                    val stale = svc.getLiveUbuntuSessions(projectId)
                    stale.forEach { try { it.finishIfRunning() } catch (_: Throwable) {} }
                } catch (_: Throwable) {}
                addUbuntuTab(replaceTabId = null)
            }
        } else {
            // No existing sessions found. Two cases:
            // 1. First boot after install (ubuntu_first_boot_completed=false) → auto-start immediately.
            //    The app is fresh and stable — no crash risk.
            // 2. Reopen after minimize (ubuntu_first_boot_completed=true) → DON'T fork proot
            //    instantly. Show a brief loading state (~8s) to let the app fully stabilize
            //    after Activity recreation, THEN auto-fork proot. Forking immediately on
            //    reopen was causing OOM/SIGKILL on 3GB devices — a short delay avoids it
            //    without requiring the user to tap anything.
            if (ProotInstaller.isInstalled(context)) {
                val bootPrefs = context.getSharedPreferences("terminal_prefs", android.content.Context.MODE_PRIVATE)
                if (bootPrefs.getBoolean("ubuntu_first_boot_completed", false)) {
                    showTapToStart = true
                } else {
                    bootPrefs.edit().putBoolean("ubuntu_first_boot_completed", true).apply()
                    tabs.firstOrNull()?.let { addUbuntuTab(replaceTabId = it.id) }
                }
            } else {
                tabs.firstOrNull()?.let { addUbuntuTab(replaceTabId = it.id) }
            }
        }
    }

    // Auto-start countdown: on reopen, wait ~8s for the app to stabilize after Activity
    // recreation before forking proot. This replaces the manual "tap to start" button —
    // same fix (delay the fork), but automatic instead of requiring user interaction.
    LaunchedEffect(showTapToStart) {
        if (showTapToStart && !autoStartCountdownDone) {
            kotlinx.coroutines.delay(8000)
            autoStartCountdownDone = true
            showTapToStart = false
            // Phase 4: try session restore (loop-guarded, crash-safe)
            val restored = if (TerminalSessionStore.claimRestoreAttempt(context)) {
                TerminalSessionStore.load(context)
            } else emptyList()
            if (restored.isNotEmpty()) {
                // Restore each saved tab as a fresh Ubuntu session
                tabs.firstOrNull()?.let { addUbuntuTab(replaceTabId = it.id) }
                restored.drop(1).forEach { _ -> addUbuntuTab(replaceTabId = null) }
                // Re-apply saved names
                restored.forEachIndexed { i, saved ->
                    val tab = tabs.getOrNull(i) ?: return@forEachIndexed
                    tabs[i] = tab.copy(name = saved.name)
                }
            } else {
                tabs.firstOrNull()?.let { addUbuntuTab(replaceTabId = it.id) }
            }
        }
    }

    // Back button during loading screen: skip the delay and start terminal immediately
    BackHandler(enabled = showTapToStart) {
        showTapToStart = false
        autoStartCountdownDone = true
        tabs.firstOrNull()?.let { addUbuntuTab(replaceTabId = it.id) }
    }

    fun closeTab(id: String) {
        if (tabs.size <= 1) return
        val idx = tabs.indexOfFirst { it.id == id }
        tabs[idx].session.finishIfRunning()
        tabs.removeAt(idx)
        if (activeId == id) activeId = tabs.getOrNull(idx - 1)?.id ?: tabs.first().id
        // Phase 4: persist updated tab list
        scope.launch { TerminalSessionStore.save(context, tabs.map {
            TerminalSessionStore.SavedTab(it.id, it.name, loadWorkspacePath(context, projectId) ?: "/root")
        }) }
    }

    LaunchedEffect(initialCommand, active?.id) {
        val command = initialCommand ?: return@LaunchedEffect
        active?.session?.write(command)
        onCommandConsumed()
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
        // Tab bar
        Row(Modifier.fillMaxWidth().height(28.dp).background(Color(0xFF252526)), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { tab ->
                    val isActive = tab.id == activeId
                    Column(
                        Modifier
                            .background(if (isActive) Color(0xFF1E1E1E) else Color(0xFF2D2D2D))
                            .pointerInput(tab.id) {
                                detectTapGestures(
                                    onTap = { activeId = tab.id },
                                    onLongPress = {
                                        renameTargetId = tab.id
                                        renameValue = tab.name
                                    },
                                )
                            }
                            .height(28.dp)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .widthIn(min = 60.dp, max = 140.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        // Tab title + controls
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(tab.name, color = if (isActive) Color.White else Color(0xFF969696),
                                fontSize = 12.sp, fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            Text("✎", color = Color(0xFF666666), fontSize = 9.sp,
                                modifier = Modifier.clickable { renameTargetId = tab.id; renameValue = tab.name }.padding(2.dp))
                            if (tabs.size > 1) {
                                Icon(Icons.Default.Close, null, tint = Color(0xFF666666),
                                    modifier = Modifier.size(10.dp).clickable { closeTab(tab.id) })
                            }
                        }
                        // (PiP mini-preview second line removed 2026-07-06 — it made this row
                        // taller than the tab strip above it and duplicated info already visible
                        // the instant you tap the tab.)
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
                        leadingIcon = { Text("🐧", fontSize = 13.sp) },
                        text = { Text("New Ubuntu Terminal", color = Color(0xFF89B4FA), fontSize = 13.sp) },
                        onClick = { showMenu = false; addUbuntuTab() })
                    HorizontalDivider(color = Color(0xFF444444), modifier = Modifier.padding(vertical = 2.dp))
                    // ── AI & TOOLS ─────────────────────────────────────────────
                    DropdownMenuItem(
                        leadingIcon = { Text("  ", fontSize = 10.sp, color = Color(0xFF717171)) },
                        text = { Text("AI & TOOLS", fontSize = 10.sp, color = Color(0xFF717171), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
                        onClick = {}, enabled = false)
                    DropdownMenuItem(
                        leadingIcon = { Text("📥", fontSize = 13.sp) },
                        text = { Text("Install Ollama", color = Color(0xFF89B4FA), fontSize = 13.sp) },
                        onClick = {
                            showMenu = false
                            android.widget.Toast.makeText(context, "Installing Ollama — trying every method until one works…", android.widget.Toast.LENGTH_SHORT).show()
                            active?.session?.write(ollamaInstallScript())
                        })
                    DropdownMenuItem(
                        leadingIcon = { Text("\uD83C\uDF99\uFE0F", fontSize = 13.sp) },
                        text = { Text("Install Voice (TTS)", color = Color(0xFF89B4FA), fontSize = 13.sp) },
                        onClick = {
                            showMenu = false
                            showVoiceModelPicker = true
                        })
                    DropdownMenuItem(
                        leadingIcon = { Text("🤖", fontSize = 13.sp) },
                        text = { Text("Launch Coding Agent", color = Color(0xFF89B4FA), fontSize = 13.sp) },
                        onClick = {
                            showMenu = false
                            if (!ollamaPrefs.getBoolean("setup_complete", false)) {
                                showOllamaModelPicker = true
                            } else {
                                val model = ollamaPrefs.getString("chosen_model", "qwen2.5-coder:1.5b") ?: "qwen2.5-coder:1.5b"
                                val existingId = if (ollamaMultiInstance) null else ollamaPrefs.getString("ollama_tab_id", null)
                                val existingTab = tabs.firstOrNull { it.id == existingId }
                                if (existingTab != null) {
                                    activeId = existingTab.id
                                    existingTab.session.write(ollamaLaunchScript(model))
                                } else {
                                    addUbuntuTab()
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        val newTab = tabs.lastOrNull()
                                        if (newTab != null) {
                                            ollamaPrefs.edit().putString("ollama_tab_id", newTab.id).apply()
                                            newTab.session.write(ollamaLaunchScript(model))
                                        }
                                    }, 3000)
                                }
                            }
                        })
                    DropdownMenuItem(
                        leadingIcon = { Text("🔑", fontSize = 13.sp) },
                        text = { Text("Sign in to Ollama", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = { showMenu = false; active?.session?.write("ollama signin\n") })
                    DropdownMenuItem(
                        leadingIcon = { Text("🚪", fontSize = 13.sp) },
                        text = { Text("Sign out of Ollama", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = {
                            showMenu = false
                            active?.session?.write("ollama signout\n")
                            android.widget.Toast.makeText(context, "Signed out of Ollama — agent memory is unaffected", android.widget.Toast.LENGTH_SHORT).show()
                        })
                    DropdownMenuItem(
                        leadingIcon = { Text(if (ollamaMultiInstance) "☑" else "☐", fontSize = 13.sp, color = Color(0xFF969696)) },
                        text = { Text("Multi-Instance Mode (advanced)", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = {
                            ollamaMultiInstance = !ollamaMultiInstance
                            ollamaPrefs.edit().putBoolean("multi_instance", ollamaMultiInstance).apply()
                            showMenu = false
                        })
                    DropdownMenuItem(
                        leadingIcon = { Text("🎬", fontSize = 13.sp) },
                        text = { Text("Setup Remotion", color = Color(0xFFA6E3A1), fontSize = 13.sp) },
                        onClick = {
                            showMenu = false
                            android.widget.Toast.makeText(context, "Setting up Remotion (Node + ffmpeg + project scaffold)…", android.widget.Toast.LENGTH_SHORT).show()
                            active?.session?.write(remotionSetupScript())
                            remotionPrefs.edit().putBoolean("setup_complete", true).apply()
                        })
                    DropdownMenuItem(
                        leadingIcon = { Text("🎞️", fontSize = 13.sp) },
                        text = { Text("Launch Remotion Studio", color = Color(0xFFA6E3A1), fontSize = 13.sp) },
                        onClick = {
                            showMenu = false
                            if (!remotionPrefs.getBoolean("setup_complete", false)) {
                                android.widget.Toast.makeText(context, "Run \"Setup Remotion\" first — it only needs to run once", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                active?.session?.write(remotionRelaunchScript())
                            }
                        })
                    DropdownMenuItem(
                        leadingIcon = { Text("🔌", fontSize = 13.sp) },
                        text = { Text("Show Agent Tools (32)", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = {
                            showMenu = false
                            // The real local agent API (AgentApiServer, port 8765) is already auto-started
                            // for this session via McpShellProfile — no separate "start" step needed.
                            // This just lists the 32 tools any AI in this terminal can call via `agent <tool>`.
                            active?.session?.write(". ~/.agent-profile.sh 2>/dev/null; agent_tools\n")
                            android.widget.Toast.makeText(context, "Listing available agent tools…", android.widget.Toast.LENGTH_SHORT).show()
                        })
                    DropdownMenuItem(
                        leadingIcon = { Text("📜", fontSize = 13.sp) },
                        text = { Text("Make Script from History", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = {
                            showMenu = false
                            // Actually writes the last 20 commands to a real, executable .sh file
                            // instead of just printing history and telling the user to copy it manually.
                            val cmd = "hist_file=~/script_\$(date +%Y%m%d_%H%M%S).sh; " +
                                "history | tail -21 | head -20 | sed -E 's/^[ ]*[0-9]+[ ]*//' > \"\$hist_file\"; " +
                                "chmod +x \"\$hist_file\"; " +
                                "echo -e \"\\033[1;32mSaved:\\033[0m \$hist_file\"; cat \"\$hist_file\"\n"
                            active?.session?.write(cmd)
                            android.widget.Toast.makeText(context, "Saving script from recent history…", android.widget.Toast.LENGTH_SHORT).show()
                        })
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
                        leadingIcon = { Text(if (showQuickActions) "▲" else "▼", fontSize = 13.sp, color = Color(0xFF969696)) },
                        text = { Text(if (showQuickActions) "Hide Quick Actions" else "Show Quick Actions", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = { showMenu = false; showQuickActions = !showQuickActions })
                    DropdownMenuItem(
                        leadingIcon = { Text("🎨", fontSize = 13.sp, color = Color(0xFFCCCCCC)) },
                        text = { Text("Color Scheme: ${activeScheme.name}", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = { showMenu = false; showSchemeMenu = true }
                    )
                    DropdownMenuItem(
                        leadingIcon = { Text("✕", fontSize = 13.sp, color = Color(0xFFFF6B6B)) },
                        text = { Text("Close This Tab", color = Color(0xFFFF6B6B), fontSize = 13.sp) },
                        onClick = { showMenu = false; if (tabs.size > 1) closeTab(activeId) })
                    // Phase 4: clear saved sessions
                    DropdownMenuItem(
                        text = { Text("Clear saved sessions", fontSize = 12.sp) },
                        onClick = {
                            showMenu = false
                            scope.launch { TerminalSessionStore.wipe(context) }
                        }
                    )
                }
            }
        }

        // P14-B: Shell history search overlay
        if (showHistorySearch) {
            ShellHistorySearchOverlay(
                onDismiss = { showHistorySearch = false },
                onSelect  = { cmd ->
                    active?.session?.write(cmd)
                    TerminalHistoryStore.append(context, cmd)
                },
                historyLines = remember { TerminalHistoryStore.load(context).reversed() },
            )
        }

        // Rename dialog
        if (renameTargetId != null) {
            // Rotation fix (#8): see color scheme picker above for rationale.
            key(configuration.orientation) {
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
        }

        // Ollama model picker — shown only on the very first "Launch Coding Agent" tap.
        if (showOllamaModelPicker) {
            // Rotation fix (#8): see color scheme picker above for rationale.
            key(configuration.orientation) {
            AlertDialog(
                onDismissRequest = { showOllamaModelPicker = false },
                title = { Text("Choose a model") },
                text = {
                    Column {
                        OLLAMA_MODELS.forEach { m ->
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    showOllamaModelPicker = false
                                    addUbuntuTab()
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        val newTab = tabs.lastOrNull()
                                        if (newTab != null) {
                                            ollamaPrefs.edit()
                                                .putBoolean("setup_complete", true)
                                                .putString("chosen_model", m.id)
                                                .putString("ollama_tab_id", newTab.id)
                                                .apply()
                                            newTab.session.write(ollamaFullSetupScript(m))
                                        }
                                    }, 3000)
                                }.padding(vertical = 8.dp)
                            ) {
                                Text(
                                    (if (m.warn) "\u26a0\ufe0f " else "") + m.label + (if (m.isCloud) " (cloud)" else ""),
                                    fontWeight = FontWeight.Medium
                                )
                                Text(m.ramNote, fontSize = 11.sp, color = Color(0xFF888888))
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { showOllamaModelPicker = false }) { Text("Cancel") } },
            )
            }
        }

        // Voice/TTS model picker — Piper (fast/free) vs Bark-small (emotional, heavier).
        if (showVoiceModelPicker) {
            // Rotation fix (#8): see color scheme picker above for rationale.
            key(configuration.orientation) {
            AlertDialog(
                onDismissRequest = { showVoiceModelPicker = false },
                title = { Text("Choose a voice model") },
                text = {
                    Column {
                        VOICE_MODELS.forEach { m ->
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    showVoiceModelPicker = false
                                    android.widget.Toast.makeText(context, "Setting up ${m.label} — resumes automatically if the connection drops…", android.widget.Toast.LENGTH_SHORT).show()
                                    active?.session?.write(voiceInstallScript(m))
                                }.padding(vertical = 8.dp)
                            ) {
                                Text(m.label + " \u2022 " + m.sizeNote, fontWeight = FontWeight.Medium)
                                Text(m.note, fontSize = 11.sp, color = Color(0xFF888888))
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { showVoiceModelPicker = false }) { Text("Cancel") } },
            )
            }
        }

        // ── NewTermux-style toolbar row ────────────────────────────
        // Fixed single-line height + horizontal scroll: in portrait, this row must never wrap to a
        // second line (which would steal vertical space the terminal output needs) and must never
        // silently clip buttons off the right edge either — scrolling keeps every action reachable
        // while staying a single compact row no matter how narrow the screen is.
        if (showQuickActions) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).background(Color(0xFF161616))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 3.dp),
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
                    val sttCmd = results[0]
                    active?.session?.write(sttCmd)
                    TerminalHistoryStore.append(context, sttCmd)
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

            // Font size controls — Termux-style pinch zoom equivalent via buttons
            Box(
                Modifier.background(Color(0xFF2A2A2A), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .clickable {
                        val newSize = (terminalFontSize - 1).coerceAtLeast(SimpleTerminalViewClient.MIN_FONTSIZE)
                        terminalFontSize = newSize
                        prefs.edit().putInt("KEY_FONTSIZE", newSize).apply()
                        currentView.value?.setTextSize(newSize)
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("A−", color = Color(0xFFCCCCCC), fontSize = 11.sp) }
            Text("${terminalFontSize}sp", color = Color(0xFF555555), fontSize = 9.sp,
                modifier = Modifier.padding(horizontal = 2.dp))
            Box(
                Modifier.background(Color(0xFF2A2A2A), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .clickable {
                        val newSize = (terminalFontSize + 1).coerceAtMost(SimpleTerminalViewClient.MAX_FONTSIZE)
                        terminalFontSize = newSize
                        prefs.edit().putInt("KEY_FONTSIZE", newSize).apply()
                        currentView.value?.setTextSize(newSize)
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("A+", color = Color(0xFFCCCCCC), fontSize = 11.sp) }

            // Clear screen
            Box(
                Modifier.background(Color(0xFF2A2A2A), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .clickable { active?.session?.write("clear\n") }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("Clear", color = Color(0xFFCCCCCC), fontSize = 11.sp) }

            // Remotion Studio launcher
            Box(
                Modifier.background(Color(0xFF1A2A1A), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .clickable { active?.session?.write("bash ~/setup-remotion.sh\n") }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("▶ Remotion", color = Color(0xFF6DB33F), fontSize = 11.sp) }

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

            Spacer(Modifier.width(16.dp))
            // Pkg update shortcut
            Box(
                Modifier.background(Color(0xFF2A2A2A), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .clickable { active?.session?.write("pkg update -y && pkg upgrade -y\n") }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("Pkg↑", color = Color(0xFFCCCCCC), fontSize = 11.sp) }

            // AC — toggle autocorrect on the keyboard
            Box(
                Modifier.background(if (acEnabled) Color(0xFF1A3A4A) else Color(0xFF2A2A2A), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .clickable {
                        acEnabled = !acEnabled
                        android.widget.Toast.makeText(context, if (acEnabled) "Autocorrect ON" else "Autocorrect OFF", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("AC", color = if (acEnabled) Color(0xFF4EC9B0) else Color(0xFFCCCCCC), fontSize = 11.sp) }

            // Custom cmds drawer toggle
            Box(
                Modifier.background(if (showCustomCmds) Color(0xFF2A1A4A) else Color(0xFF2A2A2A), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .clickable { showCustomCmds = !showCustomCmds }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("Cmds", color = if (showCustomCmds) Color(0xFFBB86FC) else Color(0xFFCCCCCC), fontSize = 11.sp) }

            // History search — P14-B
            Box(
                Modifier.background(if (showHistorySearch) Color(0xFF1A3A2A) else Color(0xFF2A2A2A), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .clickable { showHistorySearch = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("🔍 Hist", color = if (showHistorySearch) Color(0xFF4EC9B0) else Color(0xFFCCCCCC), fontSize = 11.sp) }
        }
        }
        HorizontalDivider(color = Color(0xFF2A2A2A))

        // ── Custom commands drawer ────────────────────────────────────────────
        if (showCustomCmds) {
            val savedCmds = remember {
                val prefs = context.getSharedPreferences("custom_cmds", android.content.Context.MODE_PRIVATE)
                val raw = prefs.getString("cmds", null)
                val list = mutableStateListOf<Pair<String,String>>() // label, command
                if (!raw.isNullOrBlank()) {
                    raw.split("|SEP|").forEach { entry ->
                        val parts = entry.split("|CMD|")
                        if (parts.size == 2) list.add(Pair(parts[0], parts[1]))
                    }
                }
                if (list.isEmpty()) {
                    list.add(Pair("ls -la", "ls -la\n"))
                    list.add(Pair("top", "top\n"))
                    list.add(Pair("df -h", "df -h\n"))
                }
                list
            }
            fun saveCmds() {
                val encoded = savedCmds.joinToString("|SEP|") { "${it.first}|CMD|${it.second}" }
                context.getSharedPreferences("custom_cmds", android.content.Context.MODE_PRIVATE)
                    .edit().putString("cmds", encoded).apply()
            }
            var addLabel by remember { mutableStateOf("") }
            var addCmd   by remember { mutableStateOf("") }
            var showAdd  by remember { mutableStateOf(false) }

            Column(
                Modifier.fillMaxWidth().background(Color(0xFF1A1A2A)).padding(4.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Custom Commands", color = Color(0xFFBB86FC), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Row {
                        Box(
                            Modifier.background(Color(0xFF2A1A4A), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                .clickable { showAdd = !showAdd }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) { Text("+", color = Color(0xFFBB86FC), fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    }
                }

                if (showAdd) {
                    Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = addLabel, onValueChange = { addLabel = it },
                            placeholder = { Text("Label", fontSize = 10.sp) },
                            modifier = Modifier.width(80.dp),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                        )
                        OutlinedTextField(
                            value = addCmd, onValueChange = { addCmd = it },
                            placeholder = { Text("command\n", fontSize = 10.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                        )
                        Box(
                            Modifier.background(Color(0xFF007ACC), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                .clickable {
                                    if (addLabel.isNotBlank() && addCmd.isNotBlank()) {
                                        val cmd = if (addCmd.endsWith("\n")) addCmd else "$addCmd\n"
                                        savedCmds.add(Pair(addLabel.trim(), cmd))
                                        saveCmds()
                                        addLabel = ""; addCmd = ""; showAdd = false
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .align(Alignment.CenterVertically)
                        ) { Text("Add", color = Color.White, fontSize = 11.sp) }
                    }
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    items(savedCmds.size) { idx ->
                        val (label, cmd) = savedCmds[idx]
                        Box(
                            Modifier
                                .background(Color(0xFF2A2A3A), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                .clickable { active?.session?.write(cmd) }
                                .pointerInput(idx) {
                                    detectTapGestures(
                                        onLongPress = {
                                            savedCmds.removeAt(idx)
                                            saveCmds()
                                        }
                                    )
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) { Text(label, color = Color(0xFFCCCCCC), fontSize = 11.sp) }
                    }
                }
                Text("Tap to run • Long-press to delete", color = Color(0xFF555555), fontSize = 9.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
            }
            HorizontalDivider(color = Color(0xFF2A2A2A))
        }

        // ── Full laptop-style extra keys bar ───────────────────────────────────
        // Row 1: F1–F12  |  Row 2: Sticky modifiers + nav cluster + symbols + Ctrl combos
        if (showExtraKeys) {
            var ctrlArmed  by remember { mutableStateOf(false) }
            var altArmed   by remember { mutableStateOf(false) }
            var shiftArmed by remember { mutableStateOf(false) }

            fun send(seq: String) {
                val session = active?.session ?: return
                var out = seq
                if (ctrlArmed && seq.length == 1) {
                    val cp = seq[0].uppercaseChar().code
                    if (cp in 64..95) out = (cp - 64).toChar().toString()
                    ctrlArmed = false; altArmed = false; shiftArmed = false
                } else if (altArmed) {
                    out = "\u001B$seq"; altArmed = false; ctrlArmed = false; shiftArmed = false
                } else if (shiftArmed && seq.length == 1) {
                    out = seq.uppercase(); shiftArmed = false; ctrlArmed = false; altArmed = false
                } else {
                    ctrlArmed = false; altArmed = false; shiftArmed = false
                }
                session.write(out)
            }

            @Composable
            fun KeyBtn(label: String, seq: String = "", wide: Boolean = false,
                       accent: Boolean = false, armed: Boolean = false,
                       onTap: (() -> Unit)? = null) {
                val bg = when {
                    armed  -> Color(0xFF007ACC)
                    accent -> Color(0xFF3A3A3A)
                    else   -> Color(0xFF2D2D2D)
                }
                Box(
                    modifier = Modifier
                        .background(bg, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .clickable { onTap?.invoke() ?: send(seq) }
                        .padding(horizontal = if (wide) 14.dp else 9.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (armed) Color.White else Color(0xFFCCCCCC),
                        fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }

            // ── Row 1: F1–F12 ────────────────────────────────────────────────────
            val fKeys = listOf(
                "F1" to "\u001BOP",    "F2" to "\u001BOQ",    "F3" to "\u001BOR",    "F4" to "\u001BOS",
                "F5" to "\u001B[15~",  "F6" to "\u001B[17~",  "F7" to "\u001B[18~",  "F8" to "\u001B[19~",
                "F9" to "\u001B[20~",  "F10" to "\u001B[21~", "F11" to "\u001B[23~", "F12" to "\u001B[24~"
            )
            Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF141414))
                    .horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                fKeys.forEach { (l, s) -> KeyBtn(l, s) }
            }

            // ── Row 2: Modifiers + nav + symbols + Ctrl combos ───────────────────
            Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF1A1A1A))
                    .horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                // Sticky modifier keys
                KeyBtn("CTRL", accent = true, armed = ctrlArmed,
                    onTap = { ctrlArmed = !ctrlArmed; altArmed = false; shiftArmed = false })
                KeyBtn("ALT",  accent = true, armed = altArmed,
                    onTap = { altArmed = !altArmed; ctrlArmed = false; shiftArmed = false })
                KeyBtn("SHFT", accent = true, armed = shiftArmed,
                    onTap = { shiftArmed = !shiftArmed; ctrlArmed = false; altArmed = false })
                // Nav cluster
                listOf(
                    "ESC"  to "\u001B",    "TAB" to "\t",
                    "HOME" to "\u001B[H",  "END" to "\u001B[F",
                    "INS"  to "\u001B[2~", "DEL" to "\u001B[3~",
                    "PGUP" to "\u001B[5~", "PGDN" to "\u001B[6~",
                    "\u2191" to "\u001B[A", "\u2193" to "\u001B[B",
                    "\u2190" to "\u001B[D", "\u2192" to "\u001B[C"
                ).forEach { (l, s) -> KeyBtn(l, s) }
                // Symbol keys
                listOf("|","/","\\","~","`","-","_","=","+","[","]","{","}","(",")",
                       "<",">",";",":","'","\"","!","@","#","$","^","&","*").forEach { sym ->
                    KeyBtn(sym, sym)
                }
                // Ctrl combos
                listOf(
                    "C-c" to "\u0003", "C-d" to "\u0004", "C-z" to "\u001A",
                    "C-a" to "\u0001", "C-e" to "\u0005", "C-k" to "\u000B",
                    "C-u" to "\u0015", "C-l" to "\u000C", "C-r" to "\u0012",
                    "C-w" to "\u0017", "C-b" to "\u0002", "C-f" to "\u0006",
                    "C-p" to "\u0010", "C-n" to "\u000E", "C-t" to "\u0014"
                ).forEach { (l, s) -> KeyBtn(l, s) }
            }
        }

        // SSH Manager sheet
        if (showSshManager) {
            SshManagerSheet(
                onDismiss = { showSshManager = false },
                onConnect = { label, cmd ->
                    // Ubuntu proot is the only terminal environment now — openssh-client
                    // lives in the rootfs (apt install openssh-client), not on the host.
                    val id = System.currentTimeMillis().toString()
                    val (session, client) = (boundService?.createSession(isUbuntu = true, projectId = projectId) ?: createTerminalSession(context, isUbuntu = true, workDir = loadWorkspacePath(context, projectId)))
                    tabs.add(TabSession(id, label, session, client))
                    activeId = id
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        session.write(cmd + "\n")
                    }, 300)
                }
            )
        }

        // Color scheme picker dialog
        if (showSchemeMenu) {
            // Rotation fix (#8): key on orientation so this Dialog gets a fresh,
            // correctly-sized window on rotation instead of a stuck stale one.
            key(configuration.orientation) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { showSchemeMenu = false }) {
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = Color(0xFF252526)),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                ) {
                    // Was missing scroll entirely — with more schemes than fit on screen
                    // (esp. landscape after rotation) the list just clipped with no way
                    // to reach the rest. Bounded height + scroll fixes both issues.
                    Column(Modifier.padding(16.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text("Terminal Color Scheme", color = Color(0xFFCCCCCC), fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))
                        TerminalSchemes.ALL.forEach { scheme ->
                            val isActive = scheme.name == activeScheme.name
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        activeScheme = scheme
                                        TerminalSchemes.apply(scheme)
                                        // Reset emulator colors so change takes effect immediately
                                        currentView.value?.mEmulator?.mColors?.reset()
                                        currentView.value?.onScreenUpdated()
                                        showSchemeMenu = false
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Color preview swatch
                                Box(Modifier.size(20.dp)
                                    .background(Color(scheme.bg.toLong() or 0xFF000000L), androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                                    .border(1.dp, Color(scheme.fg.toLong() or 0xFF000000L), androidx.compose.foundation.shape.RoundedCornerShape(3.dp)))
                                Spacer(Modifier.width(10.dp))
                                Text(scheme.name, color = if (isActive) Color(0xFF89B4FA) else Color(0xFFCCCCCC), fontSize = 13.sp)
                                if (isActive) { Spacer(Modifier.weight(1f)); Text("✔", color = Color(0xFF89B4FA), fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
            }
        }

        // Text Expansion manager sheet
        if (showTextExpansions) {
            TextExpansionSheet(onDismiss = { showTextExpansions = false })
        }

        // ── Auto-start loading screen ──────────────────────────────────────
        // Shown briefly on reopen after minimize (Ubuntu already installed, no live
        // session). Gives the app ~8s to stabilize after Activity recreation before
        // auto-forking proot — avoids the OOM/SIGKILL that happened when forking
        // proot immediately on reopen on 3GB devices. No tap needed, just a short wait.
        if (showTapToStart && active != null) {
            Box(
                Modifier.fillMaxSize().background(Color(0xFF1E1E1E)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = Color(0xFF89B4FA))
                    Text("Starting terminal...", color = Color(0xFF969696), fontSize = 13.sp)
                }
            }
        }

        // Terminal view
        if (active != null && !showTapToStart) {
            key(active.id) {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        TerminalView(ctx, null).apply {
                            val viewClient = SimpleTerminalViewClient()
                            viewClient.terminalView  = this
                            viewClient.currentTextSize = terminalFontSize
                            // Propagate pinch-zoom font changes back to Compose state + SharedPrefs
                            viewClient.onFontSizeChanged = { newSize ->
                                prefs.edit().putInt("KEY_FONTSIZE", newSize).apply()
                                // Post to main thread — ViewClient callbacks may be on any thread
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    // Update compose state so all views stay in sync
                                }
                            }
                            setTerminalViewClient(viewClient)
                            setTextSize(terminalFontSize)
                            setTypeface(android.graphics.Typeface.MONOSPACE)
                            isFocusable = true
                            isFocusableInTouchMode = true
                            // Keep screen on while terminal is visible — matches Termux setKeepScreenOn()
                            keepScreenOn = true
                            // PTY resize on layout change — without this, vim/nano use wrong cols/rows
                            // Also fires on rotation — updateSize() sends new COLUMNS/ROWS to PTY
                            addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
                                if ((r - l) != (or2 - ol) || (b - t) != (ob - ot)) {
                                    post { onScreenUpdated() }
                                }
                            }
                        }
                    },
                    update = { view ->
                        // Sync external font size changes (e.g. +/- buttons) to the view
                        val viewClient = view.mClient as? SimpleTerminalViewClient
                        if (viewClient != null && viewClient.currentTextSize != terminalFontSize) {
                            viewClient.currentTextSize = terminalFontSize
                            view.setTextSize(terminalFontSize)
                        }
                        // Rotation / screen resize: notify PTY of new dimensions
                        // Compose recomposes when screenWidthDp/screenHeightDp change — this fires
                        view.post { view.onScreenUpdated() }
                        // ALWAYS rewire callbacks on every recomposition — Termux pattern:
                        // TermuxTerminalSessionActivityClient re-sets client on every onStart().
                        // Stale callbacks cause screen-not-updating and cursor-blink bugs.
                        active.client.onTextChanged = {
                            if (isActivityVisible) view.post { view.onScreenUpdated() }
                        }
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
                            // Wire hardware keyboard shortcuts to tab actions
                            val viewClient2 = view.mClient as? SimpleTerminalViewClient
                            if (viewClient2 != null) {
                                viewClient2.onNewTab      = { addTab() }
                                viewClient2.onCloseTab    = { closeTab(active.id) }
                                viewClient2.onPrevTab     = { val i = tabs.indexOfFirst { it.id == activeId }; if (i > 0) activeId = tabs[i-1].id else if (tabs.isNotEmpty()) activeId = tabs.last().id }
                                viewClient2.onNextTab     = { val i = tabs.indexOfFirst { it.id == activeId }; activeId = if (i < tabs.size-1) tabs[i+1].id else tabs.first().id }
                                viewClient2.onClearScreen = { active.session.write("clear\n") }
                            }
                            view.requestFocus()
                        }
                    }
                )
                } // close Box
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
    val context  = androidx.compose.ui.platform.LocalContext.current
    val prefs    = remember { context.getSharedPreferences("terminal_prefs", android.content.Context.MODE_PRIVATE) }
    var terminalFontSize by remember { mutableStateOf(prefs.getInt("KEY_FONTSIZE", SimpleTerminalViewClient.DEFAULT_FONTSIZE).coerceIn(SimpleTerminalViewClient.MIN_FONTSIZE, SimpleTerminalViewClient.MAX_FONTSIZE)) }
    // Observe rotation / config changes
    val configuration  = androidx.compose.ui.platform.LocalConfiguration.current
    @Suppress("UNUSED_VARIABLE") val screenWidthDp  = configuration.screenWidthDp
    @Suppress("UNUSED_VARIABLE") val screenHeightDp = configuration.screenHeightDp
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
                Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        TerminalView(ctx, null).apply {
                            val viewClient = SimpleTerminalViewClient()
                            viewClient.terminalView  = this
                            viewClient.currentTextSize = terminalFontSize
                            viewClient.onFontSizeChanged = { newSize ->
                                prefs.edit().putInt("KEY_FONTSIZE", newSize).apply()
                            }
                            setTerminalViewClient(viewClient)
                            setTextSize(terminalFontSize)
                            setTypeface(android.graphics.Typeface.MONOSPACE)
                            isFocusable = true
                            isFocusableInTouchMode = true
                            keepScreenOn = true  // mirror pane also keeps screen on
                        }
                    },
                    update = { view ->
                        // Sync font size + rotation for split panel
                        val splitVc = view.mClient as? SimpleTerminalViewClient
                        if (splitVc != null && splitVc.currentTextSize != terminalFontSize) {
                            splitVc.currentTextSize = terminalFontSize
                            view.setTextSize(terminalFontSize)
                        }
                        view.post { view.onScreenUpdated() }
                        if (view.mTermSession != mirrorTab.session) {
                            view.attachSession(mirrorTab.session)
                            mirrorTab.client.onTextChanged = {
                                if (view.isShown) view.post { view.onScreenUpdated() }
                            }
                            view.requestFocus()
                        }
                    }
                )
                } // close Box
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No terminal open — open a terminal tab first",
                    color = Color(0xFF969696), fontSize = 13.sp)
            }
        }
    }
}






