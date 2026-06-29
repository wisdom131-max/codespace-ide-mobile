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

// ── Mode ──────────────────────────────────────────────────────────────────────
private enum class ChatMode { ASK, AGENT, PLAN }

// ── Data ──────────────────────────────────────────────────────────────────────
private data class ChatMsg(val role: String, val text: String)

private const val PREFS_CHAT = "copilot_chat"
private const val KEY_MSGS   = "messages_v2"

// Ollama endpoint — tries localhost first (when Ollama running in terminal tab)
private const val OLLAMA_LOCAL = "http://localhost:11434"
// Codespace fallback (Wisdom's Codespace port-forwarded Ollama)
private const val OLLAMA_CS    = "https://turbo-system-xrw4697pr99x3rjj-11434.app.github.dev"

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
internal fun CopilotChatPanelOverlay(onClose: () -> Unit) {
    val context   = LocalContext.current
    val scope     = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var mode          by remember { mutableStateOf(ChatMode.ASK) }
    var chatInput     by remember { mutableStateOf("") }
    var chatLoading   by remember { mutableStateOf(false) }
    var error         by remember { mutableStateOf("") }
    var showModelMenu by remember { mutableStateOf(false) }
    var ollamaUrl     by remember { mutableStateOf(OLLAMA_LOCAL) }
    var availModels   by remember { mutableStateOf(listOf("llama3", "mistral", "codellama")) }
    var selectedModel by remember { mutableStateOf("llama3") }

    val messages = remember {
        mutableStateListOf<ChatMsg>().apply { addAll(loadHistory(context)) }
    }

    // Auto-detect running Ollama models on open
    LaunchedEffect(Unit) {
        val local = fetchModels(OLLAMA_LOCAL)
        if (local.isNotEmpty()) {
            ollamaUrl = OLLAMA_LOCAL
            availModels = local
            selectedModel = local.first()
        } else {
            val cs = fetchModels(OLLAMA_CS)
            if (cs.isNotEmpty()) {
                ollamaUrl = OLLAMA_CS
                availModels = cs
                selectedModel = cs.first()
            }
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

    Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable { onClose() }) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(320.dp)
                .background(Color(0xFF1E1E2E), RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                .clickable(enabled = false) {}
        ) {
            // ── Header ────────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SmartToy, null, tint = Color(0xFF7C3AED), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copilot Chat", color = Color(0xFFCDD6F4), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Model picker
                    Box {
                        Text(
                            selectedModel.take(12),
                            color = Color(0xFF89B4FA), fontSize = 10.sp,
                            modifier = Modifier
                                .background(Color(0xFF313244), RoundedCornerShape(4.dp))
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
                        tint = Color(0xFF6C7086), modifier = Modifier.size(16.dp).clickable {
                            messages.clear()
                            saveHistory(context, emptyList())
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Default.Close, null,
                        tint = Color(0xFF6C7086),
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
                                if (selected) Color(0xFF7C3AED) else Color(0xFF313244),
                                RoundedCornerShape(6.dp),
                            )
                            .clickable { mode = m }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            m.name.lowercase().replaceFirstChar { it.uppercase() },
                            color = if (selected) Color.White else Color(0xFF89B4FA),
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
                    color = Color(0xFF6C7086), fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }

            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = Color(0xFF313244))

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
                            color = Color(0xFF6C7086), fontSize = 12.sp,
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
                                    if (isUser) Color(0xFF7C3AED) else Color(0xFF313244),
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(8.dp)
                                .widthIn(max = 260.dp)
                        ) {
                            Text(msg.text, color = Color(0xFFCDD6F4), fontSize = 12.sp)
                        }
                    }
                }
                if (chatLoading) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color(0xFF7C3AED),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Thinking...", color = Color(0xFF6C7086), fontSize = 11.sp)
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

            HorizontalDivider(color = Color(0xFF313244))

            // ── Input ─────────────────────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(Color(0xFF313244), RoundedCornerShape(8.dp)),
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
                            fontSize = 12.sp, color = Color(0xFF6C7086),
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = Color(0xFFCDD6F4),
                        unfocusedTextColor = Color(0xFFCDD6F4),
                    ),
                    maxLines = 3,
                    enabled = !chatLoading,
                )
                IconButton(
                    onClick = { send(chatInput.trim()) },
                    enabled = !chatLoading && chatInput.isNotBlank(),
                ) {
                    Icon(Icons.Default.Send, null, tint = Color(0xFF7C3AED), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
