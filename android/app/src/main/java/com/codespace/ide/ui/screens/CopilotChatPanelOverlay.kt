package com.codespace.ide.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Theme colors passed from parent — matches the app's current theme
data class ChatPanelColors(
    val background: Color,
    val surface: Color,
    val text: Color,
    val textSecondary: Color,
    val accent: Color,
    val userBubble: Color,
    val assistantBubble: Color,
    val inputBg: Color,
    val divider: Color,
    val headerBg: Color,
    val scrim: Color,
)

// Default to dark theme colors
private val DefaultChatColors = ChatPanelColors(
    background = Color(0xFF1E1E1E),
    surface = Color(0xFF252526),
    text = Color(0xFFD4D4D4),
    textSecondary = Color(0xFF858585),
    accent = Color(0xFF007ACC),
    userBubble = Color(0xFF007ACC),
    assistantBubble = Color(0xFF2D2D2D),
    inputBg = Color(0xFF252526),
    divider = Color(0xFF444444),
    headerBg = Color(0xFF252526),
    scrim = Color(0x66000000),
)

// ── Mode ──────────────────────────────────────────────────────────────────────
private enum class ChatMode { ASK, AGENT, PLAN }

// ── Data ──────────────────────────────────────────────────────────────────────
private data class ChatMsg(val role: String, val text: String)

private const val PREFS_CHAT = "copilot_chat"
private const val KEY_MSGS   = "messages_v2"

// Ollama runs locally — same as Termux setup (pkg install ollama; ollama serve)
// nemotron-3-super:cloud offloads inference to NVIDIA cloud, but server is local
private const val OLLAMA_LOCAL = "http://localhost:11434"

private val http = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()

private fun saveHistory(ctx: Context, msgs: List<ChatMsg>) {
    val arr = JSONArray()
    msgs.takeLast(50).forEach { arr.put(JSONObject().put("role", it.role).put("text", it.text)) }
    ctx.getSharedPreferences(PREFS_CHAT, Context.MODE_PRIVATE)
        .edit().putString(KEY_MSGS, arr.toString()).apply()
}

private fun loadHistory(ctx: Context): List<ChatMsg> {
    val str = ctx.getSharedPreferences(PREFS_CHAT, Context.MODE_PRIVATE)
        .getString(KEY_MSGS, null) ?: return emptyList()
    return try {
        val arr = JSONArray(str)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ChatMsg(o.getString("role"), o.getString("text"))
        }
    } catch (_: Exception) { emptyList() }
}

private suspend fun fetchModels(baseUrl: String): List<String> = withContext(Dispatchers.IO) {
    try {
        val resp = http.newCall(Request.Builder().url("$baseUrl/api/tags").get().build()).execute()
        if (!resp.isSuccessful) return@withContext emptyList()
        val json = JSONObject(resp.body?.string() ?: "")
        val arr = json.getJSONArray("models")
        (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
    } catch (_: Exception) { emptyList() }
}

private suspend fun chat(
    baseUrl: String,
    model: String,
    messages: List<ChatMsg>,
    mode: ChatMode,
): String = withContext(Dispatchers.IO) {
    val systemPrompt = when (mode) {
        ChatMode.ASK   -> "You are a helpful coding assistant inside CodeSpace IDE. Answer concisely."
        ChatMode.AGENT -> "You are an autonomous coding agent inside CodeSpace IDE. You can read/write files and run terminal commands. Describe each action you take step by step."
        ChatMode.PLAN  -> "You are a planning assistant inside CodeSpace IDE. Break the user's request into numbered steps. List steps and wait for approval before suggesting execution."
    }
    val msgs = JSONArray()
    msgs.put(JSONObject().put("role", "system").put("content", systemPrompt))
    messages.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.text)) }

    val body = JSONObject()
        .put("model", model)
        .put("messages", msgs)
        .put("stream", false)
        .toString()

    val resp = http.newCall(
        Request.Builder()
            .url("$baseUrl/api/chat")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    ).execute()
    if (!resp.isSuccessful) throw Exception("Ollama error ${resp.code}")
    val json = JSONObject(resp.body?.string() ?: "")
    json.getJSONObject("message").getString("content")
}

// ── UI ────────────────────────────────────────────────────────────────────────
@Composable
internal fun CopilotChatPanelOverlay(
    onClose: () -> Unit,
    colors: ChatPanelColors = DefaultChatColors,
) {
    val context   = LocalContext.current
    val scope     = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var mode          by remember { mutableStateOf(ChatMode.ASK) }
    var chatInput     by remember { mutableStateOf("") }
    var chatLoading   by remember { mutableStateOf(false) }
    var error         by remember { mutableStateOf("") }
    var showModelMenu by remember { mutableStateOf(false) }
    var ollamaUrl     by remember { mutableStateOf(OLLAMA_LOCAL) }
    var availModels   by remember { mutableStateOf(listOf("nemotron-3-super:cloud", "qwen2.5-coder:7b", "llama3.2")) }
    var selectedModel by remember { mutableStateOf("nemotron-3-super:cloud") }

    val messages = remember {
        mutableStateListOf<ChatMsg>().apply { addAll(loadHistory(context)) }
    }

    // Auto-detect running Ollama models on open
    // Ollama runs locally (Termux-style) — server started by "Setup Ollama AI" button
    LaunchedEffect(Unit) {
        val local = fetchModels(OLLAMA_LOCAL)
        if (local.isNotEmpty()) {
            ollamaUrl = OLLAMA_LOCAL
            availModels = local
            selectedModel = local.firstOrNull { it.contains("nemotron-3-super") } ?: local.firstOrNull { it.contains("nemotron") } ?: local.first()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun send(userText: String) {
        if (userText.isBlank() || chatLoading) return
        val msg = ChatMsg("user", userText)
        messages.add(msg)
        chatInput = ""
        error = ""
        chatLoading = true
        scope.launch {
            try {
                val reply = chat(ollamaUrl, selectedModel, messages.toList(), mode)
                messages.add(ChatMsg("assistant", reply))
                saveHistory(context, messages.toList())
            } catch (e: Exception) {
                error = e.message ?: "Unknown error"
                messages.add(ChatMsg("assistant", "Error: ${e.message}"))
            } finally {
                chatLoading = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(colors.scrim).clickable { onClose() }) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(320.dp)
                .background(colors.background, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                .clickable(enabled = false) {}
        ) {
            // ── Header ────────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SmartToy, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copilot Chat", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Model picker
                    Box {
                        Text(
                            selectedModel.take(12),
                            color = colors.accent, fontSize = 10.sp,
                            modifier = Modifier
                                .background(colors.surface, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .clickable { showModelMenu = true },
                        )
                        DropdownMenu(expanded = showModelMenu, onDismissRequest = { showModelMenu = false }) {
                            availModels.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m, fontSize = 12.sp) },
                                    onClick = { selectedModel = m; showModelMenu = false },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    // Clear history
                    Icon(
                        Icons.Default.DeleteOutline, null,
                        tint = colors.textSecondary, modifier = Modifier.size(16.dp).clickable {
                            messages.clear()
                            saveHistory(context, emptyList())
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Default.Close, null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(16.dp).clickable { onClose() },
                    )
                }
            }

            // ── Ask / Agent / Plan mode tabs ──────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ChatMode.entries.forEach { m ->
                    val selected = m == mode
                    Box(
                        Modifier
                            .background(
                                if (selected) colors.accent else colors.surface,
                                RoundedCornerShape(6.dp),
                            )
                            .clickable { mode = m }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            m.name.lowercase().replaceFirstChar { it.uppercase() },
                            color = if (selected) Color.White else colors.accent,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // Mode description
                Text(
                    when (mode) {
                        ChatMode.ASK   -> "Q&A"
                        ChatMode.AGENT -> "Acts"
                        ChatMode.PLAN  -> "Steps"
                    },
                    color = colors.textSecondary, fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }

            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = colors.surface)

            // ── Messages ──────────────────────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            when (mode) {
                                ChatMode.ASK   -> "Ask me anything about your code."
                                ChatMode.AGENT -> "I will take actions — read files, run commands, edit code."
                                ChatMode.PLAN  -> "Describe a task and I will break it into steps for your approval."
                            },
                            color = colors.textSecondary, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
                items(messages) { msg ->
                    val isUser = msg.role == "user"
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                    ) {
                        Box(
                            Modifier
                                .background(
                                    if (isUser) colors.accent else colors.surface,
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(8.dp)
                                .widthIn(max = 260.dp)
                        ) {
                            Text(msg.text, color = colors.text, fontSize = 12.sp)
                        }
                    }
                }
                if (chatLoading) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = colors.accent,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Thinking...", color = colors.textSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }

            // ── Error bar ─────────────────────────────────────────────────────
            if (error.isNotEmpty()) {
                Text(
                    error,
                    color = Color(0xFFFF6B6B),
                    fontSize = 10.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF3D1A1A))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            HorizontalDivider(color = colors.surface)

            // ── Input ─────────────────────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(colors.surface, RoundedCornerShape(8.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = chatInput,
                    onValueChange = { chatInput = it },
                    placeholder = {
                        Text(
                            when (mode) {
                                ChatMode.ASK   -> "Ask Copilot..."
                                ChatMode.AGENT -> "Tell agent what to do..."
                                ChatMode.PLAN  -> "Describe your goal..."
                            },
                            fontSize = 12.sp, color = colors.textSecondary,
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = colors.text,
                        unfocusedTextColor = colors.text,
                    ),
                    maxLines = 3,
                    enabled = !chatLoading,
                )
                IconButton(
                    onClick = { send(chatInput.trim()) },
                    enabled = !chatLoading && chatInput.isNotBlank(),
                ) {
                    Icon(Icons.Default.Send, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}


// ── Inline (non-overlay) version — renders inside the layout, not on top ──
@Composable
internal fun CopilotChatPanelInline(
    onClose: () -> Unit,
    colors: ChatPanelColors = DefaultChatColors,
) {
    val context   = LocalContext.current
    val scope     = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var mode          by remember { mutableStateOf(ChatMode.ASK) }
    var chatInput     by remember { mutableStateOf("") }
    var chatLoading   by remember { mutableStateOf(false) }
    var error         by remember { mutableStateOf("") }
    var showModelMenu by remember { mutableStateOf(false) }
    var ollamaUrl     by remember { mutableStateOf(OLLAMA_LOCAL) }
    var availModels   by remember { mutableStateOf(listOf("nemotron-3-super:cloud", "qwen2.5-coder:7b", "llama3.2")) }
    var selectedModel by remember { mutableStateOf("nemotron-3-super:cloud") }

    val messages = remember {
        mutableStateListOf<ChatMsg>().apply { addAll(loadHistory(context)) }
    }

    LaunchedEffect(Unit) {
        val local = fetchModels(OLLAMA_LOCAL)
        if (local.isNotEmpty()) {
            ollamaUrl = OLLAMA_LOCAL
            availModels = local
            selectedModel = local.firstOrNull { it.contains("nemotron-3-super") } ?: local.firstOrNull { it.contains("nemotron") } ?: local.first()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun send(userText: String) {
        if (userText.isBlank() || chatLoading) return
        val msg = ChatMsg("user", userText)
        messages.add(msg)
        chatInput = ""
        error = ""
        chatLoading = true
        scope.launch {
            try {
                val reply = chat(ollamaUrl, selectedModel, messages.toList(), mode)
                messages.add(ChatMsg("assistant", reply))
                saveHistory(context, messages.toList())
            } catch (e: Exception) {
                error = e.message ?: "Unknown error"
                messages.add(ChatMsg("assistant", "Error: ${e.message}"))
            } finally {
                chatLoading = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        // ── Header ────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Psychology, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copilot Chat", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Text(
                        selectedModel.take(12),
                        color = colors.accent, fontSize = 10.sp,
                        modifier = Modifier
                            .background(colors.surface, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .clickable { showModelMenu = true },
                    )
                    DropdownMenu(expanded = showModelMenu, onDismissRequest = { showModelMenu = false }) {
                        availModels.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m, fontSize = 12.sp) },
                                onClick = { selectedModel = m; showModelMenu = false },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Default.DeleteOutline, null,
                    tint = colors.textSecondary, modifier = Modifier.size(16.dp).clickable {
                        messages.clear()
                        saveHistory(context, emptyList())
                    },
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Default.Close, null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(16.dp).clickable { onClose() },
                )
            }
        }

        // ── Ask / Agent / Plan mode tabs ──────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ChatMode.values().forEach { m ->
                val isSelected = mode == m
                val modeColor = if (isSelected) colors.accent else colors.textSecondary
                Row(
                    Modifier
                        .background(if (isSelected) colors.surface else Color.Transparent, RoundedCornerShape(4.dp))
                        .clickable { mode = m }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val modeIcon = when (m) {
                        ChatMode.ASK   -> Icons.Default.QuestionAnswer
                        ChatMode.AGENT -> Icons.Default.AutoMode
                        ChatMode.PLAN  -> Icons.Default.ListAlt
                    }
                    Icon(modeIcon, null, tint = modeColor, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(m.name.lowercase().replaceFirstChar { it.titlecase() },
                        fontSize = 11.sp, color = modeColor, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }

        HorizontalDivider(color = colors.divider)

        // ── Messages ──────────────────────────────────────────────────────
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Default.Psychology, null, tint = colors.textSecondary.copy(alpha = 0.4f), modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Ask me anything about your code", fontSize = 12.sp, color = colors.textSecondary)
                    }
                }
            }
            items(messages) { msg ->
                val isUser = msg.role == "user"
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isUser) colors.userBubble else colors.assistantBubble,
                        modifier = Modifier.widthIn(max = 280.dp),
                    ) {
                        Text(
                            msg.text,
                            Modifier.padding(12.dp),
                            fontSize = 13.sp,
                            color = if (isUser) Color.White else colors.text,
                        )
                    }
                }
            }
            if (chatLoading) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        CircularProgressIndicator(Modifier.padding(8.dp).size(20.dp), strokeWidth = 2.dp, color = colors.accent)
                    }
                }
            }
        }

        if (error.isNotEmpty()) {
            Text(error, fontSize = 10.sp, color = Color(0xFFEF4444), modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        }

        // ── Input ─────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = chatInput,
                onValueChange = { chatInput = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask Copilot\u2026", color = colors.textSecondary) },
                enabled = !chatLoading,
                maxLines = 4,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.text,
                    unfocusedTextColor = colors.text,
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.divider,
                    focusedContainerColor = colors.inputBg,
                    unfocusedContainerColor = colors.inputBg,
                ),
            )
            IconButton(
                onClick = { scope.launch { send(chatInput) } },
                enabled = !chatLoading && chatInput.isNotBlank(),
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = colors.accent)
            }
        }
    }
}
