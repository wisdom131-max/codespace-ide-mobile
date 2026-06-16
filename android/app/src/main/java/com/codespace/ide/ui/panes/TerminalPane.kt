package com.codespace.ide.ui.panes

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

private data class PtySession(
    val id: String,
    val name: String,
    val session: TerminalSession,
)

private fun createTermuxSession(context: Context, name: String): TerminalSession {
    val termuxPrefix = "/data/data/com.termux/files/usr"
    val termuxHome = "/data/data/com.termux/files/home"
    val shell = "$termuxPrefix/bin/bash"
    val env = arrayOf(
        "TERM=xterm-256color",
        "PREFIX=$termuxPrefix",
        "HOME=$termuxHome",
        "TMPDIR=$termuxPrefix/tmp",
        "LANG=en_US.UTF-8",
        "PATH=$termuxPrefix/bin:$termuxPrefix/bin/applets:/system/bin:/system/xbin",
        "LD_LIBRARY_PATH=$termuxPrefix/lib",
        "SHELL=$shell",
        "COLORTERM=truecolor",
    )
    return TerminalSession(
        shell,
        termuxHome,
        arrayOf(),
        env,
        TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
        object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {}
            override fun onTitleChanged(changedSession: TerminalSession) {}
            override fun onSessionFinished(finishedSession: TerminalSession) {}
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
            override fun onPasteTextFromClipboard(session: TerminalSession?) {}
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
            override fun logError(tag: String, message: String) {}
            override fun logWarn(tag: String, message: String) {}
            override fun logInfo(tag: String, message: String) {}
            override fun logDebug(tag: String, message: String) {}
            override fun logVerbose(tag: String, message: String) {}
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
            override fun logStackTrace(tag: String, e: Exception) {}
        }
    )
}

@Composable
fun TerminalPane() {
    val context = LocalContext.current
    var showTerminalMenu by remember { mutableStateOf(false) }

    val sessions = remember {
        mutableStateListOf(
            PtySession("1", "bash", createTermuxSession(context, "bash"))
        )
    }
    var activeId by remember { mutableStateOf("1") }
    val activeSession = sessions.firstOrNull { it.id == activeId } ?: sessions.firstOrNull()

    fun addTerminal() {
        val newId = System.currentTimeMillis().toString()
        val num = sessions.size + 1
        sessions.add(PtySession(newId, "bash $num", createTermuxSession(context, "bash $num")))
        activeId = newId
    }

    fun closeTerminal(id: String) {
        if (sessions.size <= 1) return
        val idx = sessions.indexOfFirst { it.id == id }
        sessions[idx].session.finishIfRunning()
        sessions.removeAt(idx)
        if (activeId == id) activeId = sessions.getOrNull(idx - 1)?.id ?: sessions.first().id
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {

        // Tab bar
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF252526)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                sessions.forEach { session ->
                    val isActive = session.id == activeId
                    Row(
                        Modifier
                            .background(if (isActive) Color(0xFF1E1E1E) else Color(0xFF2D2D2D))
                            .clickable { activeId = session.id }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            session.name,
                            color = if (isActive) Color.White else Color(0xFF969696),
                            fontSize = 13.sp,
                            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                        )
                        if (sessions.size > 1) {
                            Icon(
                                Icons.Default.Close, null,
                                tint = Color(0xFF969696),
                                modifier = Modifier.padding(start = 4.dp)
                                    .clickable { closeTerminal(session.id) }.padding(2.dp),
                            )
                        }
                    }
                }
            }
            IconButton(onClick = { addTerminal() }) {
                Icon(Icons.Default.Add, null, tint = Color(0xFF969696))
            }
            Box {
                IconButton(onClick = { showTerminalMenu = true }) {
                    Icon(Icons.Default.MoreVert, null, tint = Color(0xFF969696))
                }
                DropdownMenu(
                    expanded = showTerminalMenu,
                    onDismissRequest = { showTerminalMenu = false },
                    offset = DpOffset(0.dp, 4.dp),
                    modifier = Modifier.background(Color(0xFF2D2D2D)),
                ) {
                    listOf(
                        "New Terminal" to { addTerminal() },
                        "Kill Terminal" to { if (sessions.size > 1) closeTerminal(activeId) },
                    ).forEach { (label, action) ->
                        DropdownMenuItem(
                            text = { Text(label, color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                            onClick = { showTerminalMenu = false; action() },
                        )
                    }
                }
            }
        }

        // PTY Terminal View
        if (activeSession != null) {
            key(activeSession.id) {
                AndroidView(
                    factory = { ctx ->
                        TerminalView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            attachSession(activeSession.session)
                            setTerminalViewClient(object : TerminalViewClient {
                                override fun logError(tag: String, message: String) {}
                                override fun logWarn(tag: String, message: String) {}
                                override fun logInfo(tag: String, message: String) {}
                                override fun logDebug(tag: String, message: String) {}
                                override fun logVerbose(tag: String, message: String) {}
                                override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
                                override fun logStackTrace(tag: String, e: Exception) {}
                                override fun onScroll(e: android.view.MotionEvent, distanceX: Float, distanceY: Float): Boolean = false
                                override fun onScale(scale: Float): Float = scale
                                override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                                    requestFocus()
                                    return true
                                }
                                override fun shouldBackButtonCauseSingleEscape(): Boolean = false
                                override fun isTerminalViewSelected(): Boolean = true
                                override fun copyModeChanged(copyMode: Boolean) {}
                                override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent, session: TerminalSession): Boolean = false
                                override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent): Boolean = false
                                override fun onLongPress(e: android.view.MotionEvent): Boolean = false
                                override fun readControlKey(): Boolean = false
                                override fun readAltKey(): Boolean = false
                                override fun readShiftKey(): Boolean = false
                                override fun readFnKey(): Boolean = false
                                override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
                                override fun onEmulatorSet() {}
                            })
                            setTextSize(13)
                            keepScreenOn = true
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        view.attachSession(activeSession.session)
                    }
                )
            }
        }
    }
}
