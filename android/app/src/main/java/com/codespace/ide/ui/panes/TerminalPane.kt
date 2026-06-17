package com.codespace.ide.ui.panes

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.terminal.PtySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

private val ANSI_REGEX = Regex("\\x1B\\[[0-9;]*[a-zA-Z]|\\x1B\\([AB]|\\r")

private data class TermSession(
    val id: String,
    val name: String,
    val lines: MutableList<String>,
    val pty: PtySession,
)

private fun newPty(): PtySession {
    val termuxPrefix = "/data/data/com.termux/files/usr"
    val termuxHome = "/data/data/com.termux/files/home"
    val shell = if (java.io.File("$termuxPrefix/bin/bash").exists())
        "$termuxPrefix/bin/bash" else "/system/bin/sh"

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
        "PS1=user@vncode:\\w\$ ",
    )

    return PtySession(
        shellPath = shell,
        workingDir = termuxHome,
        args = arrayOf(shell, "-i"),
        envVars = env,
        rows = 30,
        cols = 90,
    )
}

@Composable
fun TerminalPane() {
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var input by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val sessions = remember {
        mutableStateListOf(TermSession("1", "bash", mutableListOf(), newPty()))
    }
    var activeId by remember { mutableStateOf("1") }
    val active = sessions.firstOrNull { it.id == activeId } ?: sessions.firstOrNull()

    LaunchedEffect(activeId) {
        val session = sessions.firstOrNull { it.id == activeId } ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val reader = BufferedReader(InputStreamReader(session.pty.inputStream))
            val buffer = CharArray(4096)
            try {
                while (true) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    val chunk = String(buffer, 0, read)
                    val clean = chunk.replace(ANSI_REGEX, "")
                    if (clean.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            val newLines = clean.split("\n")
                            newLines.forEachIndexed { idx, part ->
                                if (idx == 0 && session.lines.isNotEmpty()) {
                                    session.lines[session.lines.size - 1] =
                                        session.lines.last() + part
                                } else {
                                    session.lines.add(part)
                                }
                            }
                            if (session.lines.size > 1000) {
                                repeat(session.lines.size - 1000) { session.lines.removeAt(0) }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(active?.lines?.size) {
        val size = active?.lines?.size ?: 0
        if (size > 0) listState.animateScrollToItem(size - 1)
    }

    fun addSession() {
        val id = System.currentTimeMillis().toString()
        sessions.add(TermSession(id, "bash ${sessions.size + 1}", mutableListOf(), newPty()))
        activeId = id
    }

    fun closeSession(id: String) {
        if (sessions.size <= 1) return
        val idx = sessions.indexOfFirst { it.id == id }
        sessions[idx].pty.destroy()
        sessions.removeAt(idx)
        if (activeId == id) activeId = sessions.getOrNull(idx - 1)?.id ?: sessions.first().id
    }

    fun sendInput(text: String) {
        val session = sessions.firstOrNull { it.id == activeId } ?: return
        scope.launch(Dispatchers.IO) { session.pty.write(text) }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {

        Row(Modifier.fillMaxWidth().background(Color(0xFF252526)), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                sessions.forEach { s ->
                    val isActive = s.id == activeId
                    Row(
                        Modifier.background(if (isActive) Color(0xFF1E1E1E) else Color(0xFF2D2D2D))
                            .clickable { activeId = s.id }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(s.name, color = if (isActive) Color.White else Color(0xFF969696),
                            fontSize = 13.sp, fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal)
                        if (sessions.size > 1) {
                            Icon(Icons.Default.Close, null, tint = Color(0xFF969696),
                                modifier = Modifier.padding(start = 4.dp).clickable { closeSession(s.id) }.padding(2.dp))
                        }
                    }
                }
            }
            IconButton(onClick = { addSession() }) { Icon(Icons.Default.Add, null, tint = Color(0xFF969696)) }
            Box {
                IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, null, tint = Color(0xFF969696)) }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false },
                    offset = DpOffset(0.dp, 4.dp), modifier = Modifier.background(Color(0xFF2D2D2D))) {
                    DropdownMenuItem(text = { Text("New Terminal", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = { showMenu = false; addSession() })
                    DropdownMenuItem(text = { Text("Kill Terminal", color = Color(0xFFCCCCCC), fontSize = 13.sp) },
                        onClick = { showMenu = false; if (sessions.size > 1) closeSession(activeId) })
                }
            }
        }

        if (active != null) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clickable(indication = null, interactionSource = remember {
                        androidx.compose.foundation.interaction.MutableInteractionSource()
                    }) {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    },
            ) {
                items(active.lines.size) { idx ->
                    Text(
                        active.lines[idx],
                        color = Color(0xFFCDD6F4),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
                item {
                    Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = input,
                            onValueChange = { newVal ->
                                if (newVal.length > input.length) {
                                    val added = newVal.substring(input.length)
                                    sendInput(added)
                                } else if (newVal.length < input.length) {
                                    sendInput("\u007F") // backspace
                                }
                                input = newVal
                                if (newVal.endsWith("\n")) input = ""
                            },
                            modifier = Modifier.weight(1f).focusRequester(focusRequester),
                            textStyle = TextStyle(
                                color = Color(0xFFCDD6F4),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                            ),
                            cursorBrush = SolidColor(Color(0xFF89B4FA)),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
