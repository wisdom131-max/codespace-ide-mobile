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
import com.codespace.ide.terminal.ProotInstaller
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

private data class TabSession(val id: String, val name: String, val session: TerminalSession, val client: SimpleTerminalSessionClient)

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
    val session = TerminalSession(shell, home, arrayOf(shell.substringAfterLast("/"), "--login"), envArray, 4000, client)
    return Pair(session, client)
}

@Composable
fun TerminalPane() {
    val context = LocalContext.current
    var bootstrapReady by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            BusyboxInstaller.installIfNeeded(context)
        }
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

    val tabs = remember {
        val (session, client) = createTerminalSession(context)
        mutableStateListOf(TabSession("1", "bash", session, client))
    }
    var activeId by remember { mutableStateOf("1") }
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

    Column(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
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
                    DropdownMenuItem(text = { Text("Open Ubuntu", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = { showMenu = false; addUbuntuTab() })
                    DropdownMenuItem(text = { Text("Kill Terminal", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = { showMenu = false; if (tabs.size > 1) closeTab(activeId) })
                }
            }
        }

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
                            setTypeface(Typeface.MONOSPACE)
                            isFocusable = true
                            isFocusableInTouchMode = true
                        }
                    },
                    update = { view ->
                        if (view.mTermSession != active.session) {
                            view.attachSession(active.session)
                            active.client.onTextChanged = { view.onScreenUpdated() }
                            view.requestFocus()
                        }
                    }
                )
            }
        }
    }
}
