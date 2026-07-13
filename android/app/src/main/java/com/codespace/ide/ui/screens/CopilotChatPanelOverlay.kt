// ⚠️ DEAD CODE — DO NOT EDIT OR RELY ON THIS FILE
// Replaced by CopilotChatPanelInline (wired inside ProjectShellScreen.kt).
// This overlay is never invoked. Kept for reference only.

package com.codespace.ide.ui.screens

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.codespace.ide.agent.AgentTools
import com.codespace.ide.data.SecureTokenStore
import com.codespace.ide.domain.AiProviderId
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

// ── Sessions (UI bucket #5) ─────────────────────────────────────────────────
// Multiple named chat threads instead of one flat history. Persisted as a single JSON
// blob (fine at this scale — 50-message cap per session, sessions list itself is small).
private const val KEY_SESSIONS = "sessions_v1"

private data class ChatSession(
    val id: String,
    var title: String,
    val mode: ChatMode,
    val messages: MutableList<ChatMsg> = mutableListOf(),
    var updatedAt: Long = System.currentTimeMillis(),
)

private fun newSession(mode: ChatMode = ChatMode.ASK): ChatSession =
    ChatSession(id = java.util.UUID.randomUUID().toString(), title = "New chat", mode = mode)

private fun saveSessions(ctx: Context, sessions: List<ChatSession>) {
    val arr = JSONArray()
    sessions.forEach { s ->
        val msgsArr = JSONArray()
        s.messages.takeLast(50).forEach { msgsArr.put(JSONObject().put("role", it.role).put("text", it.text)) }
        arr.put(
            JSONObject()
                .put("id", s.id)
                .put("title", s.title)
                .put("mode", s.mode.name)
                .put("updatedAt", s.updatedAt)
                .put("messages", msgsArr)
        )
    }
    ctx.getSharedPreferences(PREFS_CHAT, Context.MODE_PRIVATE)
        .edit().putString(KEY_SESSIONS, arr.toString()).apply()
}

private fun loadSessions(ctx: Context): MutableList<ChatSession> {
    val prefs = ctx.getSharedPreferences(PREFS_CHAT, Context.MODE_PRIVATE)
    val str = prefs.getString(KEY_SESSIONS, null)
    if (str != null) {
        return try {
            val arr = JSONArray(str)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                val msgsArr = o.getJSONArray("messages")
                val msgs = (0 until msgsArr.length()).map { j ->
                    val m = msgsArr.getJSONObject(j)
                    ChatMsg(m.getString("role"), m.getString("text"))
                }.toMutableList()
                ChatSession(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    mode = try { ChatMode.valueOf(o.getString("mode")) } catch (_: Exception) { ChatMode.ASK },
                    messages = msgs,
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                )
            }.sortedByDescending { it.updatedAt }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }
    // One-time migration: fold the old single-thread history (if any) into a session so
    // existing conversations aren't lost when this feature ships.
    val legacy = loadHistory(ctx)
    return if (legacy.isNotEmpty()) {
        val migrated = newSession().apply {
            messages.addAll(legacy)
            title = legacy.firstOrNull { it.role == "user" }?.text?.take(30) ?: "Previous chat"
        }
        mutableListOf(migrated)
    } else {
        mutableListOf()
    }
}

private fun relativeTime(ts: Long): String {
    val diffMs = System.currentTimeMillis() - ts
    val mins = diffMs / 60000
    return when {
        mins < 1 -> "now"
        mins < 60 -> "${mins}m"
        mins < 60 * 24 -> "${mins / 60}h"
        else -> "${mins / (60 * 24)}d"
    }
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

// ── BYOK (bring-your-own-key) API providers ────────────────────────────────────
// Real per-vendor calls, using whatever key the user pasted into Settings
// (SecureTokenStore.aiKey, keyed by AiProviderId.name — same store Settings already
// writes to). Model strings in the picker look like "openai:gpt-4o" — the prefix
// picks which of these run; anything else (e.g. "qwen2.5-coder:7b") falls through to
// the local Ollama call untouched.
private val API_PROVIDER_PREFIXES = setOf("openai", "claude", "deepseek", "gemini", "openrouter")

/** Every AiProviderId (besides Ollama, which is handled separately/locally) whose API key is
 *  already saved in Settings shows up as a "prefix:model" entry in the model picker. */
private fun apiModelEntries(tokenStore: SecureTokenStore?): List<String> {
    if (tokenStore == null) return emptyList()
    return AiProviderId.entries
        .filter { it != AiProviderId.OLLAMA && !tokenStore.aiKey(it.name).isNullOrBlank() }
        .map { val prefix = it.name.lowercase(); "$prefix:${defaultModelFor(prefix)}" }
}

private fun defaultModelFor(prefix: String): String = when (prefix) {
    "openai"     -> "gpt-4o"
    "claude"     -> "claude-3-5-sonnet-20241022"
    "deepseek"   -> "deepseek-chat"
    "gemini"     -> "gemini-1.5-flash"
    "openrouter" -> "anthropic/claude-3.5-sonnet"
    else         -> ""
}

/** convMsgs (JSONArray of {role, content}) minus the leading system entry — Claude/Gemini
 *  take the system prompt as a separate top-level field, not as a message in the array. */
private fun stripSystemMessage(convMsgs: JSONArray): JSONArray {
    val out = JSONArray()
    for (i in 0 until convMsgs.length()) {
        val m = convMsgs.getJSONObject(i)
        if (m.optString("role") != "system") out.put(m)
    }
    return out
}

// OpenAI, DeepSeek, and OpenRouter all speak the identical OpenAI-compatible
// /v1/chat/completions shape — one function covers all three.
private suspend fun callOpenAiCompatible(
    url: String, apiKey: String, model: String, convMsgs: JSONArray,
): String = withContext(Dispatchers.IO) {
    val body = JSONObject().put("model", model).put("messages", convMsgs).toString()
    val resp = http.newCall(
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    ).execute()
    if (!resp.isSuccessful) throw Exception("API error (${resp.code}). Check your key in Settings.")
    val json = JSONObject(resp.body?.string() ?: "")
    json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
}

// Anthropic's Messages API — different shape: x-api-key header, separate "system" field,
// response content is a list of blocks rather than a single message string.
private suspend fun callClaude(
    apiKey: String, model: String, systemPrompt: String, convMsgs: JSONArray,
): String = withContext(Dispatchers.IO) {
    val body = JSONObject()
        .put("model", model)
        .put("max_tokens", 4096)
        .put("system", systemPrompt)
        .put("messages", stripSystemMessage(convMsgs))
        .toString()
    val resp = http.newCall(
        Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    ).execute()
    if (!resp.isSuccessful) throw Exception("Claude API error (${resp.code}). Check your key in Settings.")
    val json = JSONObject(resp.body?.string() ?: "")
    json.getJSONArray("content").getJSONObject(0).getString("text")
}

// Google's Generative Language API — "contents"/"parts" shape, assistant role is "model"
// not "assistant", system prompt goes in "systemInstruction".
private suspend fun callGemini(
    apiKey: String, model: String, systemPrompt: String, convMsgs: JSONArray,
): String = withContext(Dispatchers.IO) {
    val contents = JSONArray()
    val stripped = stripSystemMessage(convMsgs)
    for (i in 0 until stripped.length()) {
        val m = stripped.getJSONObject(i)
        val role = if (m.optString("role") == "assistant") "model" else "user"
        contents.put(
            JSONObject().put("role", role)
                .put("parts", JSONArray().put(JSONObject().put("text", m.optString("content"))))
        )
    }
    val body = JSONObject()
        .put("contents", contents)
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
        .toString()
    val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
    val resp = http.newCall(
        Request.Builder().url(url).header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType())).build()
    ).execute()
    if (!resp.isSuccessful) throw Exception("Gemini API error (${resp.code}). Check your key in Settings.")
    val json = JSONObject(resp.body?.string() ?: "")
    json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
        .getJSONArray("parts").getJSONObject(0).getString("text")
}

private suspend fun chat(
    baseUrl: String,
    model: String,
    messages: List<ChatMsg>,
    mode: ChatMode,
    context: Context,
    tokenStore: SecureTokenStore? = null,
): String = withContext(Dispatchers.IO) {
    val systemPrompt = when (mode) {
        ChatMode.ASK   -> "You are a helpful coding assistant inside CodeSpace IDE. Answer concisely."
        ChatMode.AGENT -> """
You are an autonomous coding agent running inside CodeSpace IDE on Android.

## CRITICAL RULES — read before every response

### 1. The editor IS NOT a browser
This is a code editor, not a web browser. Writing SVG or HTML to a file does NOT
display it visually by itself. The user CANNOT see rendered SVG/HTML just because
you wrote it to disk. They can only see it if they open the PREVIEW tab.

### 2. After write_file, ALWAYS tell the user EXACTLY how to see the result
Never say "done" or "the file is saved" and stop there. Always end with:
  - The exact file path you wrote to
  - The exact steps to view it in this app, e.g.:
    "To see it: tap the PREVIEW tab (bottom bar) → select SVG mode → paste the path"
    "To see it: tap the PREVIEW tab → select HTML mode → paste the file path"
    "To see it: open the file in the Explorer tab"

### 3. SVG/HTML/images — preferred approach
When the user asks to "create" or "show" a visual (icon, image, chart, dashboard widget):
  a. Write the file to /root/preview.svg (or .html) using write_file
  b. ALSO paste the full SVG/HTML content directly in your chat reply so the user
     can copy-paste it if needed
  c. Tell the user: "Switch to the PREVIEW tab, choose SVG (or HTML) mode, and enter
     the path /root/preview.svg"

### 4. Dashboard = PREVIEW tab, Dashboard mode
When the user says "dashboard", they mean the PREVIEW tab in Dashboard mode.
To add something to the dashboard:
  - Write an HTML file to /root/dashboard.html with your content
  - Tell the user: "Switch to PREVIEW tab → Dashboard mode → enter /root/dashboard.html"
  - Do NOT assume the dashboard updates automatically — the user must navigate there

### 5. Never silently fail
If a tool returns an error or empty result, say so explicitly. Do not pretend the
task succeeded. Do not give the user a file path that does not exist.

### 6. File paths inside this app
The Ubuntu proot rootfs root is /root/. Safe paths: /root/preview.svg,
/root/dashboard.html, /root/myproject/. Do not write to /data/data/ or
other Android-restricted paths.

""" + AgentTools.TOOLS_DESCRIPTION
        ChatMode.PLAN  -> "You are a planning assistant inside CodeSpace IDE. Break the user's request into numbered steps. List steps and wait for approval before suggesting execution."
    }

    // Build conversation as mutable JSON array
    val convMsgs = JSONArray()
    convMsgs.put(JSONObject().put("role", "system").put("content", systemPrompt))
    messages.forEach { convMsgs.put(JSONObject().put("role", it.role).put("content", it.text)) }

    // Agentic loop: call model -> parse tool calls -> execute -> feed results -> repeat
    val maxIterations = 10
    val colonIdx = model.indexOf(':')
    val providerPrefix = if (colonIdx > 0) model.substring(0, colonIdx) else ""
    val isApiProvider = providerPrefix in API_PROVIDER_PREFIXES

    for (iteration in 0 until maxIterations) {
        // "openai:gpt-4o", "claude:...", "deepseek:...", "gemini:...", "openrouter:..." route
        // to the matching BYOK API using the key saved in Settings; everything else (plain
        // Ollama tags like "qwen2.5-coder:7b") goes to the local Ollama server. Same tool loop
        // wraps around whichever one answers.
        val content = if (isApiProvider) {
            val apiModel = model.substring(colonIdx + 1)
            val key = tokenStore?.aiKey(providerPrefix.uppercase())
            if (key.isNullOrBlank()) throw Exception("No ${providerPrefix} API key found. Add it in Settings.")
            when (providerPrefix) {
                "openai"     -> callOpenAiCompatible("https://api.openai.com/v1/chat/completions", key, apiModel, convMsgs)
                "deepseek"   -> callOpenAiCompatible("https://api.deepseek.com/v1/chat/completions", key, apiModel, convMsgs)
                "openrouter" -> callOpenAiCompatible("https://openrouter.ai/api/v1/chat/completions", key, apiModel, convMsgs)
                "claude"     -> callClaude(key, apiModel, systemPrompt, convMsgs)
                "gemini"     -> callGemini(key, apiModel, systemPrompt, convMsgs)
                else         -> throw Exception("Unknown provider: $providerPrefix")
            }
        } else {
            val body = JSONObject()
                .put("model", model)
                .put("messages", convMsgs)
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

        if (mode == ChatMode.AGENT && AgentTools.hasToolCalls(content)) {
            // Add assistant response to conversation
            convMsgs.put(JSONObject().put("role", "assistant").put("content", content))

            // Parse and execute all tool calls
            val toolCalls = AgentTools.parseToolCalls(content)
            val toolResults = StringBuilder()
            for ((toolName, toolArgs) in toolCalls) {
                val result = AgentTools.executeTool(toolName, toolArgs, context)
                toolResults.append("[Tool: $toolName] Result:\n$result\n\n")
            }

            // Feed tool results back as user message
            convMsgs.put(JSONObject().put("role", "user").put("content",
                "Tool execution results:\n$toolResults\nContinue with the next step or give a final summary if done."))
        } else {
            return@withContext content
        }
    }
    "Agent reached maximum tool iterations (10). The task may require more steps."
}

// ── UI ────────────────────────────────────────────────────────────────────────
@Composable
internal fun CopilotChatPanelOverlay(
    onClose: () -> Unit,
    colors: ChatPanelColors = DefaultChatColors,
    tokenStore: SecureTokenStore? = null,
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
    var availModels   by remember {
        mutableStateOf(
            listOf("nemotron-3-super:cloud", "qwen2.5-coder:7b", "llama3.2") + apiModelEntries(tokenStore)
        )
    }
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
            availModels = local + apiModelEntries(tokenStore)
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
                val reply = chat(ollamaUrl, selectedModel, messages.toList(), mode, context, tokenStore)
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


/**
 * The Copilot bot icon — idles with a gentle float+blink so it reads as "alive", and switches
 * to a faster, more energetic float + pulsing glow while [isThinking] (i.e. chatLoading) is true,
 * so it visibly looks like it's working on a reply.
 */
@Composable
internal fun AnimatedBotIcon(
    modifier: Modifier = Modifier,
    isThinking: Boolean = false,
) {
    val infinite = rememberInfiniteTransition(label = "bot-idle")

    // Continuous float (bob up/down) — faster + taller amplitude while thinking
    val floatOffset by infinite.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isThinking) 550 else 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "float",
    )

    // Periodic "blink" — a quick vertical squash, on a slow loop so it doesn't feel jittery
    var blinking by remember { mutableStateOf(false) }
    LaunchedEffect(isThinking) {
        while (true) {
            kotlinx.coroutines.delay(if (isThinking) 900L else 2600L)
            blinking = true
            kotlinx.coroutines.delay(110L)
            blinking = false
        }
    }
    val blinkScaleY by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (blinking) 0.82f else 1f,
        animationSpec = tween(90),
        label = "blink",
    )

    // Subtle glow pulse behind the icon while thinking
    val glowAlpha by infinite.animateFloat(
        initialValue = 0.15f,
        targetValue = if (isThinking) 0.55f else 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isThinking) 500 else 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        if (isThinking) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = glowAlpha }
                    .background(Color(0xFF5B6EF5), androidx.compose.foundation.shape.CircleShape),
            )
        }
        Image(
            painter = painterResource(id = com.codespace.ide.R.drawable.copilot_bot),
            contentDescription = "Copilot",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = floatOffset * (if (isThinking) 4f else 2.5f)
                    scaleY = blinkScaleY
                },
        )
    }
}

// ── Inline (non-overlay) version — renders inside the layout, not on top ──
@Composable
internal fun CopilotChatPanelInline(
    onClose: () -> Unit,
    colors: ChatPanelColors = DefaultChatColors,
    tokenStore: SecureTokenStore? = null,
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
    var availModels   by remember {
        mutableStateOf(
            listOf("nemotron-3-super:cloud", "qwen2.5-coder:7b", "llama3.2") + apiModelEntries(tokenStore)
        )
    }
    var selectedModel by remember { mutableStateOf("nemotron-3-super:cloud") }

    // ── Sessions (UI bucket #5) ─────────────────────────────────────────
    val sessions = remember {
        mutableStateListOf<ChatSession>().apply {
            val loaded = loadSessions(context)
            addAll(if (loaded.isEmpty()) listOf(newSession()) else loaded)
        }
    }
    var activeSessionId by remember { mutableStateOf(sessions.first().id) }
    val activeSession: ChatSession = sessions.find { it.id == activeSessionId } ?: sessions.first()

    // Sessions sidebar visibility: auto-reveals once the panel is dragged wide enough,
    // but the chevron lets you pin it open/closed regardless of current width.
    var sessionsPinned by remember { mutableStateOf<Boolean?>(null) } // null = auto (width-based)
    var showSearch     by remember { mutableStateOf(false) }
    var searchQuery    by remember { mutableStateOf("") }
    var showFilterMenu by remember { mutableStateOf(false) }
    var filterMode     by remember { mutableStateOf<ChatMode?>(null) } // null = All

    val messages = remember {
        mutableStateListOf<ChatMsg>().apply { addAll(activeSession.messages) }
    }

    fun persistSessions() {
        activeSession.messages.clear()
        activeSession.messages.addAll(messages)
        activeSession.updatedAt = System.currentTimeMillis()
        if (activeSession.title == "New chat") {
            messages.firstOrNull { it.role == "user" }?.let { activeSession.title = it.text.take(30) }
        }
        saveSessions(context, sessions)
    }

    fun switchSession(id: String) {
        // Save the outgoing session's messages before switching.
        persistSessions()
        activeSessionId = id
        messages.clear()
        messages.addAll(sessions.find { it.id == id }?.messages ?: emptyList())
        mode = sessions.find { it.id == id }?.mode ?: ChatMode.ASK
    }

    fun startNewSession() {
        persistSessions()
        val s = newSession(mode)
        sessions.add(0, s)
        activeSessionId = s.id
        messages.clear()
    }

    fun deleteSession(id: String) {
        if (sessions.size <= 1) return // always keep at least one session around
        val wasActive = id == activeSessionId
        sessions.removeAll { it.id == id }
        saveSessions(context, sessions)
        if (wasActive) switchSession(sessions.first().id)
    }

    LaunchedEffect(Unit) {
        val local = fetchModels(OLLAMA_LOCAL)
        if (local.isNotEmpty()) {
            ollamaUrl = OLLAMA_LOCAL
            availModels = local + apiModelEntries(tokenStore)
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
                val reply = chat(ollamaUrl, selectedModel, messages.toList(), mode, context, tokenStore)
                messages.add(ChatMsg("assistant", reply))
                persistSessions()
            } catch (e: Exception) {
                error = e.message ?: "Unknown error"
                messages.add(ChatMsg("assistant", "Error: ${e.message}"))
                persistSessions()
            } finally {
                chatLoading = false
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val autoShowSessions = maxWidth > 460.dp
        val showSessionsList = sessionsPinned ?: autoShowSessions

        Row(Modifier.fillMaxSize().background(colors.background)) {
            // ── Sessions sidebar ─────────────────────────────────────────
            if (showSessionsList) {
                Column(Modifier.width(160.dp).fillMaxHeight().background(colors.surface)) {
                    // Sessions header — new / search / filter / expand(pin) / close controls
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("SESSIONS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = colors.textSecondary)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Add, "New session", tint = colors.textSecondary,
                                modifier = Modifier.size(14.dp).clickable { startNewSession() })
                            Icon(Icons.Default.Search, "Search sessions", tint = if (showSearch) colors.accent else colors.textSecondary,
                                modifier = Modifier.size(14.dp).clickable { showSearch = !showSearch })
                            Box {
                                Icon(Icons.Default.FilterList, "Filter sessions", tint = if (filterMode != null) colors.accent else colors.textSecondary,
                                    modifier = Modifier.size(14.dp).clickable { showFilterMenu = true })
                                DropdownMenu(expanded = showFilterMenu, onDismissRequest = { showFilterMenu = false }) {
                                    DropdownMenuItem(text = { Text("All", fontSize = 12.sp) }, onClick = { filterMode = null; showFilterMenu = false })
                                    ChatMode.values().forEach { m ->
                                        DropdownMenuItem(
                                            text = { Text(m.name.lowercase().replaceFirstChar { it.titlecase() }, fontSize = 12.sp) },
                                            onClick = { filterMode = m; showFilterMenu = false },
                                        )
                                    }
                                }
                            }
                            // "Expand" pins the sidebar open even if the panel gets narrow again;
                            // tapping it once more (now acting as "close") unpins/hides it.
                            Icon(
                                if (sessionsPinned == true) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore,
                                "Pin sessions list", tint = colors.textSecondary,
                                modifier = Modifier.size(14.dp).clickable {
                                    sessionsPinned = if (sessionsPinned == true) false else true
                                },
                            )
                        }
                    }
                    if (showSearch) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            placeholder = { Text("Search…", fontSize = 11.sp, color = colors.textSecondary) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = colors.text),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(44.dp),
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.accent, unfocusedBorderColor = colors.divider,
                                focusedContainerColor = colors.inputBg, unfocusedContainerColor = colors.inputBg,
                            ),
                        )
                    }
                    HorizontalDivider(color = colors.divider)
                    val visibleSessions = sessions
                        .filter { filterMode == null || it.mode == filterMode }
                        .filter { searchQuery.isBlank() || it.title.contains(searchQuery, ignoreCase = true) ||
                            it.messages.any { m -> m.text.contains(searchQuery, ignoreCase = true) } }
                        .sortedByDescending { it.updatedAt }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(visibleSessions, key = { it.id }) { s ->
                            val isActive = s.id == activeSessionId
                            Column(
                                Modifier.fillMaxWidth()
                                    .background(if (isActive) colors.accent.copy(alpha = 0.15f) else Color.Transparent)
                                    .clickable { switchSession(s.id) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(s.title, fontSize = 11.sp, color = colors.text, fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (sessions.size > 1) {
                                        Icon(Icons.Default.Close, "Delete session", tint = colors.textSecondary,
                                            modifier = Modifier.size(12.dp).clickable { deleteSession(s.id) })
                                    }
                                }
                                Text(
                                    s.messages.lastOrNull()?.text?.take(40) ?: "No messages yet",
                                    fontSize = 9.sp, color = colors.textSecondary, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                Text(relativeTime(s.updatedAt), fontSize = 8.sp, color = colors.textSecondary)
                            }
                        }
                    }
                }
                VerticalDivider(color = colors.divider)
            }

            // ── Chat column ───────────────────────────────────────────────
            Column(Modifier.weight(1f).fillMaxHeight()) {
        // ── Header ────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedBotIcon(modifier = Modifier.size(22.dp), isThinking = chatLoading)
                Spacer(Modifier.width(6.dp))
                Text("Copilot Chat", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Text(
                        selectedModel.take(12),
                        color = colors.accent, fontSize = 10.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
                        persistSessions()
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
                        AnimatedBotIcon(modifier = Modifier.size(40.dp))
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
            } // end chat column
        }
    }
}

