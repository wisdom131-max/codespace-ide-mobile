package com.codespace.ide.ui.panes

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.data.SecureTokenStore
import com.codespace.ide.domain.ChatMessage
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

private const val PREFS_NAME = "ai_chat_history"
private const val KEY_HISTORY = "chat_history"
private const val CODESPACE_URL = "https://turbo-system-xrw4697pr99x3rjj-11434.app.github.dev"

private val httpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

private fun saveHistory(context: Context, messages: List<ChatMessage>) {
    val arr = JSONArray()
    messages.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(KEY_HISTORY, arr.toString()).apply()
}

private fun loadHistory(context: Context): List<ChatMessage> {
    val str = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_HISTORY, null) ?: return emptyList()
    return try {
        val arr = JSONArray(str)
        (0 until arr.length()).map {
            val obj = arr.getJSONObject(it)
            ChatMessage(obj.getString("role"), obj.getString("content"))
        }
    } catch (e: Exception) { emptyList() }
}

private suspend fun callCodespaceModel(
    model: String,
    messages: List<ChatMessage>,
): String {
    val messagesJson = JSONArray()
    messages.forEach { m ->
        messagesJson.put(JSONObject().put("role", m.role).put("content", m.content))
    }
    val body = JSONObject()
        .put("model", model)
        .put("messages", messagesJson)
        .toString()
    val request = Request.Builder()
        .url("$CODESPACE_URL/v1/chat/completions")
        .header("Content-Type", "application/json")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()
    val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
    if (!response.isSuccessful) throw Exception("Codespace returned ${response.code}. Make sure Ollama is running.")
    val json = JSONObject(response.body?.string() ?: "")
    return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
}

private suspend fun callCopilot(apiKey: String, messages: List<ChatMessage>): String {
    val messagesJson = JSONArray()
    messages.forEach { m ->
        messagesJson.put(JSONObject().put("role", m.role).put("content", m.content))
    }
    val body = JSONObject()
        .put("model", "gpt-4o")
        .put("messages", messagesJson)
        .toString()
    val request = Request.Builder()
        .url("https://api.githubcopilot.com/chat/completions")
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")
        .header("Editor-Version", "vscode/1.85.0")
        .header("Copilot-Integration-Id", "vscode-chat")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()
    val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
    if (!response.isSuccessful) throw Exception("GitHub Copilot error (${response.code}). Check your GitHub token.")
    val json = JSONObject(response.body?.string() ?: "")
    return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
}

@Composable
fun AiAssistantPane(tokenStore: SecureTokenStore) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf("nemotron-mini") }
    val models = listOf("nemotron-mini", "qwen2.5-coder:1.5b", "copilot")
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val messages = remember {
        val saved = loadHistory(context)
        mutableStateListOf<ChatMessage>().apply {
            if (saved.isEmpty()) add(ChatMessage("assistant", "Hi! I am VN Code AI. Select a model above and ask me anything!"))
            else addAll(saved)
        }
    }

    suspend fun sendMessage(userMessage: String) {
        if (userMessage.isBlank()) return
        messages.add(ChatMessage("user", userMessage))
        loading = true
        input = ""
        try {
            val reply = when (selectedModel) {
                "copilot" -> {
                    val key = tokenStore.aiKey("OPENAI") ?: tokenStore.aiKey("OPENROUTER") ?: ""
                    if (key.isBlank()) throw Exception("No GitHub token found. Go to Settings and add your GitHub token.")
                    callCopilot(key, messages.toList())
                }
                else -> callCodespaceModel(selectedModel, messages.toList())
            }
            messages.add(ChatMessage("assistant", reply))
        } catch (e: Exception) {
            messages.add(ChatMessage("assistant", "⚠️ ${e.message}"))
        } finally {
            saveHistory(context, messages)
            loading = false
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        // Model selector
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            models.forEach { model ->
                val isSelected = model == selectedModel
                Box(
                    Modifier
                        .background(
                            if (isSelected) Color(0xFF007ACC) else Color(0xFFEEEEEE),
                            RoundedCornerShape(16.dp)
                        )
                        .clickable(onClick = { selectedModel = model })
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        model,
                        fontSize = 12.sp,
                        color = if (isSelected) Color.White else Color(0xFF333333),
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }

        HorizontalDivider()

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            items(messages) { msg ->
                val isUser = msg.role == "user"
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isUser) Color(0xFF007ACC) else Color(0xFFF0F0F0),
                        modifier = Modifier.widthIn(max = 280.dp),
                    ) {
                        Text(
                            msg.content,
                            Modifier.padding(12.dp),
                            fontSize = 13.sp,
                            color = if (isUser) Color.White else Color(0xFF1A1A1A),
                        )
                    }
                }
            }
            if (loading) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }

        // Input
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask VN Code AI…", fontSize = 13.sp) },
                enabled = !loading,
                maxLines = 4,
            )
            IconButton(
                onClick = { scope.launch { sendMessage(input) } },
                enabled = !loading && input.isNotBlank(),
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = Color(0xFF007ACC))
            }
        }
    }
}
