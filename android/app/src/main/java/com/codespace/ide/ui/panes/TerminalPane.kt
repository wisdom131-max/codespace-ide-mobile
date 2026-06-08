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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

private const val TERMINAL_PREFS = "terminal_history"

data class TerminalSession(
    val id: String,
    val name: String,
    val lines: MutableList<String> = mutableListOf("CodeSpace Terminal — type a command and press Enter"),
    var workingDir: String = "/storage/emulated/0",
)

fun saveTerminals(context: Context, sessions: List<TerminalSession>, activeId: String) {
    val arr = JSONArray()
    sessions.forEach { s ->
        val linesArr = JSONArray()
        s.lines.forEach { linesArr.put(it) }
        arr.put(
            JSONObject()
                .put("id", s.id)
                .put("name", s.name)
                .put("lines", linesArr)
                .put("workingDir", s.workingDir)
        )
    }
    context.getSharedPreferences(TERMINAL_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString("sessions", arr.toString())
        .putString("activeId", activeId)
        .apply()
}

fun loadTerminals(context: Context): Pair<List<TerminalSession>, String> {
    val prefs = context.getSharedPreferences(TERMINAL_PREFS, Context.MODE_PRIVATE)
    val str = prefs.getString("sessions", null)
    val activeId = prefs.getString("activeId", "1") ?: "1"
    if (str == null) return Pair(listOf(TerminalSession("1", "bash")), "1")
    return try {
        val arr = JSONArray(str)
        val sessions = (0 until arr.length()).map {
            val obj = arr.getJSONObject(it)
            val linesArr = obj.getJSONArray("lines")
            val lines = (0 until linesArr.length()).map { i -> linesArr.getString(i) }.toMutableList()
            TerminalSession(
                id = obj.getString("id"),
                name = obj.getString("name"),
                lines = lines,
                workingDir = obj.getString("workingDir"),
            )
        }
        Pair(sessions, activeId)
    } catch (e: Exception) {
        Pair(listOf(TerminalSession("1", "bash")), "1")
    }
}

@Composable
fun TerminalPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    val (savedSessions, savedActiveId) = remember { loadTerminals(context) }
    val sessions = remember { mutableStateListOf(*savedSessions.toTypedArray()) }
    var activeId by remember { mutableStateOf(savedActiveId) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    var showTerminalMenu by remember { mutableStateOf(false) }

    val activeSession = sessions.firstOrNull { it.id == activeId } ?: sessions.firstOrNull()

    // Auto-scroll to bottom when new output arrives
    LaunchedEffect(activeSession?.lines?.size) {
        val size = activeSession?.lines?.size ?: 0
        if (size > 0) listState.animateScrollToItem(size - 1)
    }

    fun saveAll() = saveTerminals(context, sessions, activeId)

    fun addTerminal() {
        val newId = System.currentTimeMillis().toString()
        val num = sessions.size + 1
        sessions.add(TerminalSession(newId, "bash $num"))
        activeId = newId
        saveAll()
    }

    fun closeTerminal(id: String) {
        if (sessions.size <= 1) return
        val idx = sessions.indexOfFirst { it.id == id }
        sessions.removeAt(idx)
        if (activeId == id) activeId = sessions.getOrNull(idx - 1)?.id ?: sessions.first().id
        saveAll()
    }

    fun clearTerminal() {
        val session = sessions.firstOrNull { it.id == activeId } ?: return
        session.lines.clear()
        saveAll()
    }

    fun runCommand(cmd: String) {
        val session = sessions.firstOrNull { it.id == activeId } ?: return
        val trimmed = cmd.trim()
        if (trimmed.isBlank()) return
        val shortDir = session.workingDir.replace("/storage/emulated/0", "~").replace("/data/data/com.termux/files/home", "~")
        session.lines.add("PROMPT:$shortDir")
        session.lines.add("$ $trimmed")
        val sessionIdx = sessions.indexOfFirst { it.id == activeId }

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val workingDir = File(session.workingDir)
                    when {
                        trimmed.startsWith("cd ") -> {
                            val newPath = trimmed.substring(3).trim()
                            val newDir = if (newPath.startsWith("/")) File(newPath) else File(workingDir, newPath)
                            if (newDir.exists() && newDir.isDirectory) {
                                sessions[sessionIdx] = session.copy(workingDir = newDir.absolutePath)
                                listOf("-> ${newDir.absolutePath}")
                            } else listOf("cd: no such directory: $newPath")
                        }
                        trimmed == "clear" -> { session.lines.clear(); listOf() }
                        trimmed == "ls" -> workingDir.listFiles()
                            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
                            ?.map { if (it.isDirectory) "${it.name}/" else it.name }
                            ?: listOf("Permission denied")
                        trimmed == "pwd" -> listOf(workingDir.absolutePath)
                        else -> {
                            val termuxPrefix = "/data/data/com.termux/files/usr"
                            val termuxHome = "/data/data/com.termux/files/home"
                            val pb = ProcessBuilder("sh", "-c", trimmed)
                                .directory(workingDir)
                                .redirectErrorStream(true)
                            pb.environment().apply {
                                put("PREFIX", termuxPrefix)
                                put("HOME", termuxHome)
                                put("TMPDIR", "$termuxPrefix/tmp")
                                put("LANG", "en_US.UTF-8")
                                put("PATH", "$termuxPrefix/bin:$termuxPrefix/bin/applets:/system/bin:/system/xbin")
                                put("LD_LIBRARY_PATH", "$termuxPrefix/lib")
                            }
                            val process = pb.start()
                            val reader = BufferedReader(InputStreamReader(process.inputStream))
                            val output = mutableListOf<String>()
                            var line: String?
                            while (reader.readLine().also { line = it } != null) output.add(line!!)
                            process.waitFor()
                            if (output.isEmpty()) listOf("Done") else output
                        }
                    }
                }
                session.lines.addAll(result)
            } catch (e: Exception) {
                session.lines.add("Error: ${e.message}")
            }
            saveAll()
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {

        // ── Internal tab bar: bash session tabs + "+" + "⋮" ────────────────────
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
                            .clickable { activeId = session.id; saveAll() }
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
                                Icons.Default.Close, contentDescription = "Close",
                                tint = Color(0xFF969696),
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .clickable { closeTerminal(session.id) }
                                    .padding(2.dp),
                            )
                        }
                    }
                }
            }
            IconButton(onClick = { addTerminal() }) {
                Icon(Icons.Default.Add, contentDescription = "New terminal", tint = Color(0xFF969696))
            }
            // Internal ⋮ menu (Clear, Kill, etc.)
            Box {
                IconButton(onClick = { showTerminalMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = Color(0xFF969696))
                }
                DropdownMenu(
                    expanded = showTerminalMenu,
                    onDismissRequest = { showTerminalMenu = false },
                    offset = DpOffset(0.dp, 4.dp),
                    modifier = Modifier.background(Color(0xFF2D2D2D)),
                ) {
                    listOf(
                        "Clear Terminal" to { clearTerminal() },
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

        // ── Output area + input row ──────────────────────────────────────────────
        // Tapping anywhere in the output scrolls and re-focuses the input field
        if (activeSession != null) {
            Column(
                Modifier
                    .fillMaxSize()
                    // IMPORTANT: tapping the output area focuses the text field
                    // so the keyboard pops up reliably — even after switching tabs
                    .clickable(indication = null, interactionSource = remember {
                        androidx.compose.foundation.interaction.MutableInteractionSource()
                    }) {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }
            ) {
                // Scrollable output
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    items(activeSession.lines) { line ->
                        Text(
                            line,
                            color = when {
                                line.startsWith("$") -> Color(0xFF89B4FA)
                                line.startsWith("Error") -> Color(0xFFF38BA8)
                                line.startsWith("->") -> Color(0xFFA6E3A1)
                                else -> Color(0xFFCDD6F4)
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }

                // ── Input row ────────────────────────────────────────────────────
                // Prompt + BasicTextField. Tapping this row also requests focus.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF252526))
                        .clickable(indication = null, interactionSource = remember {
                            androidx.compose.foundation.interaction.MutableInteractionSource()
                        }) {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        "❯ ",
                        color = Color(0xFF4EC994),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 2.dp, end = 4.dp),
                    )
                    BasicTextField(
                        value = input,
                        onValueChange = { newValue ->
                            // Enter key submits command
                            if (newValue.endsWith("\n")) {
                                runCommand(newValue.trimEnd('\n'))
                                input = ""
                            } else {
                                input = newValue
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        textStyle = TextStyle(
                            color = Color(0xFFCDD6F4),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        ),
                        cursorBrush = SolidColor(Color(0xFF89B4FA)),
                        maxLines = 6,
                    )
                }
            }
        }
    }
}
