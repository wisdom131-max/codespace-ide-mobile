package com.codespace.ide.ui.panes

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
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
import com.codespace.ide.terminal.DeviceCompatibility
import com.codespace.ide.terminal.OllamaSetup
import com.codespace.ide.terminal.ProotInstaller
import com.codespace.ide.terminal.TerminalModeManager
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

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow


private class SimpleTerminalSessionClient : TerminalSessionClient {
    var onTextChanged: (() -> Unit)? = null
    override fun onTextChanged(changedSession: TerminalSession) { onTextChanged?.invoke() }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}
    var appContext: Context? = null
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
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) { Log.e(tag, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "", e) }
}

private class SimpleTerminalViewClient : TerminalViewClient {
    var terminalView: TerminalView? = null
    override fun onScale(scale: Float): Float = scale
    override fun onSingleTapUp(e: MotionEvent?) {
        terminalView?.let { v ->
            v.requestFocus()
            val imm = v.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
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
    override fun onEmulatorSet() {}
    override fun logError(tag: String?, message: String?) { Log.e(tag, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "", e) }
}

internal data class TabSession(val id: String, val name: String, val session: TerminalSession, val client: SimpleTerminalSessionClient)

private fun createTerminalSession(context: Context, isUbuntu: Boolean = false): Pair<TerminalSession, SimpleTerminalSessionClient> {
    val client = SimpleTerminalSessionClient()
    client.appContext = context.applicationContext

    if (isUbuntu) {
        val (proot, args, envVars) = ProotInstaller.launchArgs(context)
        val session = TerminalSession(proot, "/", args, envVars, 4000, client)
        return Pair(session, client)
    }

    val env = BusyboxInstaller.environmentFor(context)
    val shell = env["SHELL"]?.let { if (java.io.File(it).exists()) it else "/system/bin/sh" } ?: "/system/bin/sh"
    val home = env["HOME"] ?: context.filesDir.absolutePath
    val envArray = env.map { (k, v) -> "$k=$v" }.toTypedArray()
    val args = when {
        shell.contains("bash") -> arrayOf("--login", "-i")
        else -> arrayOf("--login")
    }
    val session = TerminalSession(shell, home, args, envArray, 4000, client)
    return Pair(session, client)
}


// ─────────────────────────────────────────────────────────────────────────────
// Shared terminal state — lifted up so TerminalPane and SplitTerminalPanel
// can both read the same sessions and active tab.
// ─────────────────────────────────────────────────────────────────────────────
class TerminalState(
    val tabs: androidx.compose.runtime.snapshots.SnapshotStateList<TabSession>,
    initialActiveId: String,
) {
    var activeId by androidx.compose.runtime.mutableStateOf(initialActiveId)
    var pinnedId by androidx.compose.runtime.mutableStateOf<String?>(null)  // pinned mirror session

    val active: TabSession? get() = tabs.firstOrNull { it.id == activeId }
    val pinned: TabSession? get() = tabs.firstOrNull { it.id == (pinnedId ?: activeId) }
}

@androidx.compose.runtime.Composable
fun rememberTerminalState(context: android.content.Context): TerminalState {
    return androidx.compose.runtime.remember {
        val terminalMode = TerminalModeManager(context)
        val deviceCompat = DeviceCompatibility(context)
        val (session, client) = createTerminalSession(context)
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
fun TerminalPane(
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

    // Use shared state if provided, otherwise own state
    val sharedState = externalState ?: rememberTerminalState(context)
    val tabs = sharedState.tabs

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { BusyboxInstaller.installIfNeeded(context) }
        bootstrapReady = true
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
        tab?.client?.onTextChanged = { }
        onDispose { tab?.client?.onTextChanged = null }
    }

    fun addTab() {
        val id = System.currentTimeMillis().toString()
        val (session, client) = createTerminalSession(context)
        tabs.add(TabSession(id, "bash ${tabs.size + 1}", session, client))
        activeId = id
    }

    fun renameTab(id: String, newName: String) {
        val trimmed = newName.trim().ifBlank { "bash" }
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx >= 0) tabs[idx] = tabs[idx].copy(name = trimmed)
    }

    fun addUbuntuTab() {
        val ctx = context
        Thread {
            if (!ProotInstaller.isInstalled(ctx)) {
                ProotInstaller.install(ctx) { msg ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val id = System.currentTimeMillis().toString()
                val (session, client) = createTerminalSession(ctx, isUbuntu = true)
                tabs.add(TabSession(id, "ubuntu", session, client))
                activeId = id
            }
        }.apply { isDaemon = true; start() }
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
                    DropdownMenuItem(text = { Text("New Terminal", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = { showMenu = false; addTab() })
                    DropdownMenuItem(text = { Text("Setup Offline Shell", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = { showMenu = false; BusyboxInstaller.ensureOfflineShell(context); OllamaSetup(context).installProfile(); android.widget.Toast.makeText(context, "Offline shell ready", android.widget.Toast.LENGTH_SHORT).show() })
                    DropdownMenuItem(text = { Text("Set default: Ollama / Offline", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = { showMenu = false; terminalMode.setMode(TerminalModeManager.MODE_OLLAMA); android.widget.Toast.makeText(context, "Default set to Ollama / Offline", android.widget.Toast.LENGTH_SHORT).show() })
                    DropdownMenuItem(text = { Text("Set default: Ubuntu", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = { showMenu = false; terminalMode.setMode(TerminalModeManager.MODE_UBUNTU); android.widget.Toast.makeText(context, "Default set to Ubuntu", android.widget.Toast.LENGTH_SHORT).show() })
                    if (!deviceCompat.shouldUseOfflineOnly()) {
                        DropdownMenuItem(text = { Text("Open Ubuntu", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                            onClick = { showMenu = false; addUbuntuTab() })
                    }
                    DropdownMenuItem(text = { Text("Kill Terminal", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
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
                        }
                    },
                    update = { view ->
                        if (view.mTermSession != active.session) {
                            view.attachSession(active.session)
                            active.client.onTextChanged = { view.post { view.onScreenUpdated() } }
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
fun SplitTerminalPanel(sharedState: TerminalState) {
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
                        }
                    },
                    update = { view ->
                        if (view.mTermSession != mirrorTab.session) {
                            view.attachSession(mirrorTab.session)
                            // Second observer on the same client — updates both views
                            val existing = mirrorTab.client.onTextChanged
                            mirrorTab.client.onTextChanged = {
                                existing?.invoke()
                                view.post { view.onScreenUpdated() }
                            }
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

