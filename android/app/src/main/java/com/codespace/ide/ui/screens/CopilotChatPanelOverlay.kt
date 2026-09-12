// NOTE: The `CopilotChatPanelOverlay` composable near the top of this file is
// DEAD (never invoked, kept for reference). Everything from `CopilotChatPanelInline`
// down is LIVE — it is the chat panel wired inside ProjectShellScreen.kt.

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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.codespace.ide.agent.AgentTools
import com.codespace.ide.editor.ProjectSettingsStore
import com.codespace.ide.chat.ChatProviderRegistry
import com.codespace.ide.chat.ChatRequest
import com.codespace.ide.data.SecureTokenStore
import com.codespace.ide.ai.WorkspaceContextProvider
import androidx.compose.material.icons.automirrored.filled.*

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
private enum class ChatEntryKind {
    USER, ASSISTANT, ERROR, TOOL, CONNECT_CARD;
    companion object {
        fun of(role: String, text: String): ChatEntryKind = when {
            role == "user" -> USER
            role == "card_connect" -> CONNECT_CARD
            role == "tool" -> TOOL
            text.startsWith("Error:") -> ERROR
            else -> ASSISTANT
        }
    }
}

// R4-TYPED-ENTRY: one message model for the transcript; kind drives rendering.
// Persistence unchanged (save/load still write role+text only) — old histories
// with "Error:" replies auto-classify to ERROR on load via the of() heuristic.
private data class ChatMsg(
    val role: String,
    val text: String,
    val kind: ChatEntryKind = ChatEntryKind.of(role, text),
    // R7-FEEDBACK: local thumbs up/down on assistant replies — "up" | "down" | null.
    val rating: String? = null,
)

/**
 * STREAMING (2026-09-11): events chat() surfaces to the panel's live bubble.
 * IterationStart clears the bubble for each agentic round-trip; Delta grows it;
 * ToolDone appends a status line after each executed tool.
 */
private sealed interface ChatStreamEvent {
    data class IterationStart(val index: Int) : ChatStreamEvent
    data class Delta(val text: String) : ChatStreamEvent
    data class ToolDone(val tool: String) : ChatStreamEvent
}

private const val PREFS_CHAT = "copilot_chat"
private const val KEY_MSGS   = "messages_v2"


private fun saveHistory(ctx: Context, msgs: List<ChatMsg>) {
    val arr = JSONArray()
    msgs.takeLast(50).forEach { m ->
        val mo = JSONObject().put("role", m.role).put("text", m.text)
        if (m.rating != null) mo.put("rating", m.rating)
        arr.put(mo)
    }
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
            ChatMsg(o.getString("role"), o.getString("text"), rating = o.optString("rating").ifBlank { null })
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
        s.messages.takeLast(50).forEach { m ->
            val mo = JSONObject().put("role", m.role).put("text", m.text)
            if (m.rating != null) mo.put("rating", m.rating)
            msgsArr.put(mo)
        }
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
                    ChatMsg(m.getString("role"), m.getString("text"),
                        rating = m.optString("rating").ifBlank { null })
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

// ── Registered chat providers ─────────────────────────────────────────────────
// Model strings look like "openai:gpt-4o" — the prefix is a ChatProvider.id resolved
// via ChatProviderRegistry. The panel has ZERO per-provider knowledge (no enum, no
// prefix set, no when-branches): every provider — cloud API or local model server —
// registers itself and answers through the same ChatProvider.complete() entry point.
// Adding a provider = one self-contained registration (see com.codespace.ide.chat).
private fun registeredModelEntries(tokenStore: SecureTokenStore?): List<String> =
    ChatProviderRegistry.available(tokenStore)
        .map { "${it.id}:${it.defaultModel}" }

/**
 * FIX (404 regression): the picker previously offered ONLY the hardcoded default
 * models, all of which have since been retired at the vendors (404 model-not-found
 * on every provider). This fetches each available provider's LIVE model list from
 * its own /models endpoint and merges it with the defaults, so the picker always
 * shows models that actually exist right now. Entries stay "providerId:model".
 */
private suspend fun fetchLiveModelEntries(tokenStore: SecureTokenStore?): List<String> =
    ChatProviderRegistry.available(tokenStore).flatMap { provider ->
        val key = try { tokenStore?.aiKey(provider.id.uppercase()) } catch (_: Exception) { null }
        val live = try { provider.fetchModels(key) } catch (_: Exception) { emptyList() }
        val models = (listOf(provider.defaultModel) + live).distinct()
        models.map { "${provider.id}:${it}" }
    }.distinct()

private fun buildSystemPrompt(mode: ChatMode, context: Context, workspaceCtx: String, projectRootPath: String? = null): String {
    // R2-AUTOINSTR: per-project instruction files (AGENTS.md / copilot-instructions.md /
    // .github/copilot-instructions.md / CLAUDE.md) auto-attach to EVERY request,
    // opt-out per project via the chip or prefs.
    val autoBlock = try {
        if (!projectRootPath.isNullOrBlank() &&
            com.codespace.ide.agent.AutoInstructionsProvider.isEnabled(context, projectRootPath)) {
            com.codespace.ide.agent.AutoInstructionsProvider.buildBlock(projectRootPath)
        } else ""
    } catch (_: Exception) { "" }
    val tail = (if (autoBlock.isNotEmpty()) "\n\n$autoBlock" else "") +
        (if (workspaceCtx.isNotEmpty()) "\n\n$workspaceCtx" else "")
    return when (mode) {
        ChatMode.ASK   -> "You are a helpful coding assistant inside VN Code. Answer concisely." + tail
        ChatMode.AGENT -> """
You are an autonomous coding agent running inside VN Code — a VS Code-style
Android IDE with a built-in Ubuntu Linux terminal (no root needed).

## APP VOCABULARY — what the user calls things vs what they actually are

| What the user says              | What it actually is in the app                          |
|---------------------------------|---------------------------------------------------------|
| "the dashboard"                 | PREVIEW tab → Dashboard mode                            |
| "the terminal" / "the console"  | TERMINAL tab (Ubuntu shell, proot-based)                |
| "the editor"                    | The main code editor area (centre of the screen)        |
| "the file tree" / "the sidebar" | Explorer panel (left side, folder icon)                 |
| "the chat" / "the AI"           | This panel (Copilot Chat, the one you're in now)        |
| "the preview"                   | PREVIEW tab — renders HTML/SVG/Markdown/local server    |
| "the source control" / "git"    | Source Control panel (git icon in left sidebar)         |
| "the problems tab"              | PROBLEMS tab in the bottom bar                          |
| "the ports tab"                 | PORTS tab in the bottom bar                             |
| "open a file"                   | Tap the file in Explorer OR type path → it opens in editor |
| "run it" / "run the project"    | Type command in TERMINAL tab                            |
| "the bottom bar"                | The tab row at the bottom: Terminal, Preview, Problems… |
| "the left bar"                  | The icon sidebar: Explorer, Search, Git, Debug, Extensions |

## CRITICAL RULES — follow these on every response

### 1. Your file edits are STAGED as pending proposals
When you use write_file the edit does NOT land on disk immediately — it is staged
as a pending change. The user reviews your proposed edits (with diffs) and taps
Apply to write them to disk. Keep working normally:
- Later reads of a file you edited return YOUR staged version, so you can keep
  iterating with write_file across multiple steps.
- Do NOT tell the user the file "is now open" — instead say
  "Staged [filename] — review and tap Apply in the chat to save it to disk."
- Do not use run_command (sed, tee, git checkout) to edit files — command-driven
  writes bypass the pending buffer. Use write_file for ALL file edits.
- Visual files (SVG/HTML/MD) open in the editor and Preview AFTER the user applies.

### 2. Write visuals to the project root or /root/
- SVG files:  /root/preview.svg   (auto-opens in Preview → SVG mode)
- HTML files: /root/dashboard.html (auto-opens in Preview → HTML mode)  
- Markdown:   /root/README.md
Use /root/ as your default working directory unless the user specifies otherwise.

### 3. Never say "go to X tab" or "enter the path"
The app handles navigation automatically after write_file. Do not instruct the user
to switch tabs, enter file paths, or do anything manual — it just opens.

### 4. Always paste visual content (SVG/HTML) inline in chat too
Even though the file auto-opens, paste the content in your reply so the user can
see what was written and copy-paste it elsewhere if needed.

### 5. "Dashboard" means PREVIEW tab, Dashboard mode — treat it like a web page
When the user says "add this to the dashboard" or "show on the dashboard", write an
HTML file to /root/dashboard.html. It will auto-open in Preview.

### 6. Never silently fail
If a tool returns an error, say exactly what went wrong. Never pretend success.

### 7. Ubuntu terminal is the only shell
All run_command calls execute inside the Ubuntu proot terminal. Standard Linux
commands work (apt, git, node, python3). Android host commands do NOT work here.

### 8. Plan first for multi-step work
Before ANY task that needs 3+ steps or edits 2+ files: call the plan tool with
your proposed steps and STOP — the turn ends so the user can review the plan
card and Approve or Revise. After approval arrives as the user's next message,
execute the steps in order, calling plan again after each step to update its
status (pending -> in_progress -> completed). Single-step work needs no plan.

""" + AgentTools.TOOLS_DESCRIPTION +
            com.codespace.ide.agent.McpClientManager.toolDocs(context) + tail
        ChatMode.PLAN  -> "You are a planning assistant inside VN Code. Break the user's request into numbered steps. List steps and wait for approval before suggesting execution." + tail
    }
}

/** Full request conversation (leading system entry + history) — shared by chat() and the context gauge. */
private fun convMsgsOf(
    systemPrompt: String,
    messages: List<ChatMsg>,
    attachments: List<com.codespace.ide.chat.ChatAttachment> = emptyList(),
): JSONArray {
    val convMsgs = JSONArray()
    convMsgs.put(JSONObject().put("role", "system").put("content", systemPrompt))
    // R3-ATTACH: attached file/selection content rides the LAST user message
    val attBlock = com.codespace.ide.chat.ChatAttachmentInjector.buildBlock(attachments)
    val lastUserIdx = messages.indexOfLast { it.role == "user" }
    messages.forEachIndexed { i, m ->
        val content = if (i == lastUserIdx && attBlock.isNotEmpty()) m.text + "\n\n" + attBlock else m.text
        convMsgs.put(JSONObject().put("role", m.role).put("content", content))
    }
    return convMsgs
}

/**
 * CONTEXT GAUGE (2026-09-11): per-provider REAL token count (Gemini :countTokens /
 * Anthropic count_tokens / jtokkit BPE), heuristic estimate fallback; live-or-static
 * context window via TokenCounter. Never throws, never blocks >5s.
 */
private suspend fun computeContextUsage(
    model: String,
    systemPrompt: String,
    convMsgs: JSONArray,
    tokenStore: SecureTokenStore?,
): Pair<Int?, Int?> {
    val colonIdx = model.indexOf(':')
    if (colonIdx <= 0) return null to null
    val providerId = model.substring(0, colonIdx)
    val provider = ChatProviderRegistry.byId(providerId) ?: return null to null
    val apiModel = model.substring(colonIdx + 1)
    val key = try { tokenStore?.aiKey(providerId.uppercase()) } catch (_: Exception) { null }
    val req = ChatRequest(apiModel, systemPrompt, convMsgs, key)
    val used = try {
        kotlinx.coroutines.withTimeoutOrNull(5000) { provider.countTokens(req) }
    } catch (_: Exception) { null }
        ?: com.codespace.ide.chat.TokenCounter.countOpenAiCompatible(systemPrompt, convMsgs, apiModel)
    val max = com.codespace.ide.chat.TokenCounter.modelContextLimit(provider, apiModel, key)
    return used to max
}

/** Recomputes the gauge for the conversation the NEXT send will carry. Fire-and-forget. */
private suspend fun updateContextGauge(
    selectedModel: String,
    mode: ChatMode,
    context: Context,
    tokenStore: SecureTokenStore?,
    messages: List<ChatMsg>,
    projectRootPath: String?,
    currentFilePath: String?,
    openFilePaths: List<String>,
    onComputed: (Int?, Int?) -> Unit,
) {
    try {
        val wsCtx = WorkspaceContextProvider.buildContext(projectRootPath, currentFilePath, openFilePaths)
        val systemPrompt = buildSystemPrompt(mode, context, wsCtx)
        val usage = computeContextUsage(selectedModel, systemPrompt, convMsgsOf(systemPrompt, messages), tokenStore)
        onComputed(usage.first, usage.second)
    } catch (_: Exception) { }
}

private suspend fun chat(
    model: String,
    messages: List<ChatMsg>,
    mode: ChatMode,
    context: Context,
    tokenStore: SecureTokenStore? = null,
    onOpenFile: ((String) -> Unit)? = null,
    onSwitchToPreview: ((String) -> Unit)? = null,
    projectRootPath: String? = null,
    currentFilePath: String? = null,
    openFilePaths: List<String> = emptyList(),
    onStreamEvent: ((ChatStreamEvent) -> Unit)? = null,
    includeImplicitCtx: Boolean = true,
    attachments: List<com.codespace.ide.chat.ChatAttachment> = emptyList(),
): String = withContext(Dispatchers.IO) {
    // P41-X: Build workspace context for AI prompts
    // R3-ATTACH: includeImplicitCtx=false turns OFF the implicit workspace
    // context (explicit attachments/auto-instructions only) — VS Code parity.
    val workspaceCtx = if (includeImplicitCtx)
        WorkspaceContextProvider.buildContext(projectRootPath, currentFilePath, openFilePaths) else ""
    // MCP: lazy first-chat discovery — spawns enabled external MCP servers once,
    // tools/list results feed the external-tools docs block below.
    com.codespace.ide.agent.McpClientManager.ensureDiscovered(context)
    
    val systemPrompt = buildSystemPrompt(mode, context, workspaceCtx, projectRootPath)

    val convMsgs = convMsgsOf(systemPrompt, messages, attachments)

    // Agentic loop: call model -> parse tool calls -> execute -> feed results -> repeat
    val maxIterations = 10
    val colonIdx = model.indexOf(':')
    val providerPrefix = if (colonIdx > 0) model.substring(0, colonIdx) else ""
    // Registry dispatch: any registered provider (cloud API or local model server)
    // answers through the same ChatProvider.complete() entry point. Same tool loop
    // wraps around whichever one answers.
    val provider = if (colonIdx > 0) ChatProviderRegistry.byId(providerPrefix) else null

    for (iteration in 0 until maxIterations) {
        onStreamEvent?.invoke(ChatStreamEvent.IterationStart(iteration))
        val deltaSink: ((String) -> Unit)? =
            onStreamEvent?.let { sink -> { d: String -> sink(ChatStreamEvent.Delta(d)) } }
        val content = if (provider != null) {
            val apiModel = model.substring(colonIdx + 1)
            if (!provider.isAvailable(tokenStore)) throw Exception(provider.unavailableMessage())
            val req = ChatRequest(apiModel, systemPrompt, convMsgs, tokenStore?.aiKey(providerPrefix.uppercase()))
            if (deltaSink != null) provider.completeStreaming(req, deltaSink)
            else provider.complete(req)
        } else {
            throw Exception("'$model' is not a registered chat provider. Add an API key in Settings first.")
        }

        if (mode == ChatMode.AGENT && AgentTools.hasToolCalls(content)) {
            // Add assistant response to conversation
            convMsgs.put(JSONObject().put("role", "assistant").put("content", content))

            // Parse and execute all tool calls
            val toolCalls = AgentTools.parseToolCalls(content)
            val toolResults = StringBuilder()
            // R7-PLAN: a staged plan ENDS this agent turn (VS Code plan_response
            // semantics) — the user reviews the card and Approve/Revise resumes.
            var planStaged = false
            for ((toolName, toolArgs) in toolCalls) {
                // R6-PENDING-EDITS (decision #1): in AGENT mode, write_file STAGES
                // into PendingChangesStore instead of writing disk — staging is
                // UNGATED at every permission level (content that cannot reach disk
                // needs no approval gate). Only Apply, which the user initiates from
                // the review card, ever writes to disk.
                val stagedMsg: String? = if (mode == ChatMode.AGENT && toolName == "write_file") {
                    try {
                        com.codespace.ide.chat.PendingChangesStore.stage(
                            toolArgs.getString("path"), toolArgs.getString("content"))
                    } catch (_: Exception) { null }
                } else null
                // P-FLOW: In Manual flow mode, pause and wait for the user to tap
                // Approve/Reject on the floating card before running this tool call.
                // In Auto mode (default), awaitApproval() returns true immediately.
                // Staged writes skip the gate entirely (decision #1).
                val argsSummary = toolArgs.toString().take(160)
                val approved = stagedMsg != null ||
                    com.codespace.ide.agent.AgentFlowGate.awaitApproval(context, toolName, argsSummary)
                if (toolName == "plan" && approved) planStaged = true
                val result = if (stagedMsg != null) {
                    stagedMsg
                } else if (approved) {
                    AgentTools.executeTool(toolName, toolArgs, context)
                } else {
                    "Skipped — rejected by user in Manual Flow Mode."
                }
                val resultForTranscript = if (ProjectSettingsStore.verboseToolOutput.value) {
                    result
                } else {
                    result.lineSequence().firstOrNull()?.take(200) ?: result.take(200)
                }
                onStreamEvent?.invoke(ChatStreamEvent.ToolDone(toolName))
                toolResults.append("[Tool: $toolName] Result:\n$resultForTranscript\n\n")
                // Auto-open file in editor + switch preview when AI writes a visual file
                // R6: staged writes skip auto-open — the file is NOT on disk yet and
                // must not be opened in the editor until the user applies it.
                if (approved && toolName == "write_file" && stagedMsg == null) {
                    val writtenPath = try { toolArgs.getString("path") } catch (_: Exception) { "" }
                    if (writtenPath.isNotBlank()) {
                        val lower = writtenPath.lowercase()
                        val isVisual = lower.endsWith(".svg") || lower.endsWith(".html") ||
                                       lower.endsWith(".htm") || lower.endsWith(".md")
                        if (isVisual) {
                            onOpenFile?.invoke(writtenPath)
                            onSwitchToPreview?.invoke(writtenPath)
                        } else {
                            onOpenFile?.invoke(writtenPath)
                        }
                    }
                }
            }

            if (planStaged) {
                return@withContext "Plan ready — review it in the chat panel, then tap Approve (executes the steps) or Revise (tell me what to change)."
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
// CROSS-ROUTING FIX (2026-09-06): shared initial model selection. Order:
// 1. the persisted ChatModelSelection value (what Settings' provider switch
//    and the other chat panel last wrote), 2. the provider the user activated
//    in Settings (tokenStore "active" key - previously WRITE-ONLY, now honored),
// 3. registry default. Never just "first provider" blindly.
private fun chatModelSelectionInitial(context: android.content.Context, tokenStore: com.codespace.ide.data.SecureTokenStore?): String {
    com.codespace.ide.chat.ChatModelSelection.get(context)?.let { return it }
    // R5: fresh installs start on "Auto" — VS Code parity. Resolves at send
    // time to the active provider's default (same endpoint the old fallback
    // picked), and follows the Settings provider switch from then on.
    return com.codespace.ide.chat.ChatModelSelection.AUTO_MODEL
}

@Composable
internal fun CopilotChatPanelOverlay(
    onClose: () -> Unit,
    colors: ChatPanelColors = DefaultChatColors,
    tokenStore: SecureTokenStore? = null,
    onOpenFile: ((String) -> Unit)? = null,
    onSwitchToPreview: ((String) -> Unit)? = null,
) {
    val context   = LocalContext.current
    val scope     = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var mode          by remember { mutableStateOf(ChatMode.ASK) }
    var chatInput     by remember { mutableStateOf("") }
    var chatLoading   by remember { mutableStateOf(false) }
    var liveStreamText by remember { mutableStateOf("") }
    var ctxUsed        by remember { mutableStateOf<Int?>(null) }
    var ctxMax         by remember { mutableStateOf<Int?>(null) }
    var error         by remember { mutableStateOf("") }
    var showModelMenu by remember { mutableStateOf(false) }
    var availModels   by remember { mutableStateOf(registeredModelEntries(tokenStore)) }
    var selectedModel by remember { mutableStateOf(chatModelSelectionInitial(context, tokenStore)) }
    // 404-fix: fetch the LIVE model lists once when the panel first composes,
    // so the picker only ever offers models that exist right now. Falls back to
    // defaults if the network call fails.
    var liveModelsFetched by remember { mutableStateOf(false) }
    LaunchedEffect(liveModelsFetched) {
        if (!liveModelsFetched) {
            liveModelsFetched = true
            val live = fetchLiveModelEntries(tokenStore)
            if (live.isNotEmpty()) {
                availModels = live
                // keep current selection valid; if it vanished (retired model),
                // snap to the first available current model
                if (selectedModel !in live) {
                    val curPrefix = selectedModel.substringBefore(':', "")
                    val sameProvider = live.filter { it.startsWith(curPrefix + ":") }
                    val snapped = sameProvider.firstOrNull() ?: live.firstOrNull() ?: selectedModel
                    selectedModel = snapped
                    com.codespace.ide.chat.ChatModelSelection.set(context, snapped)
                }
            }
        }
    }

    val messages = remember {
        mutableStateListOf<ChatMsg>().apply { addAll(loadHistory(context)) }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // STREAMING: keep the live bubble in view — scroll every ~120 chars so we don't
    // fight recomposition on every delta.
    LaunchedEffect(liveStreamText.length / 120, chatLoading) {
        if (chatLoading && liveStreamText.isNotEmpty() && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size)
        }
    }

    fun send(userText: String) {
        if (userText.isBlank() || chatLoading) return
        val msg = ChatMsg("user", userText)
        messages.add(msg)
        chatInput = ""
        error = ""
        chatLoading = true
        liveStreamText = ""
        scope.launch {
            try {
                com.codespace.ide.chat.ChatModelSelection.set(context, selectedModel)
                val sink: ((ChatStreamEvent) -> Unit) = { ev ->
                    when (ev) {
                        is ChatStreamEvent.IterationStart -> liveStreamText = ""
                        is ChatStreamEvent.Delta -> liveStreamText += ev.text
                        is ChatStreamEvent.ToolDone -> liveStreamText += "\n⚙ " + ev.tool + " — done"
                    }
                }
                val reply = chat(selectedModel, messages.toList(), mode, context, tokenStore, onOpenFile, onSwitchToPreview, onStreamEvent = sink)
                messages.add(ChatMsg("assistant", reply))
                saveHistory(context, messages.toList())
            } catch (e: Exception) {
                error = e.message ?: "Unknown error"
                messages.add(ChatMsg("assistant", "Error: ${e.message}"))
            } finally {
                chatLoading = false
                liveStreamText = ""
                // CONTEXT GAUGE: estimate for the NEXT send — unawaited, never delays the reply
                scope.launch {
                    updateContextGauge(selectedModel, mode, context, tokenStore, messages.toList(), null, null, emptyList()) { u, m ->
                        ctxUsed = u; ctxMax = m
                    }
                }
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
                                    onClick = { selectedModel = m; com.codespace.ide.chat.ChatModelSelection.set(context, m); showModelMenu = false },
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
                        LiveStreamIndicator(
                            liveText = liveStreamText,
                            accent = colors.accent,
                            surface = colors.surface,
                            text = colors.text,
                            textSecondary = colors.textSecondary,
                        )
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

            // ── Context gauge (running per-turn token usage) ─────────────────────
            ChatContextGauge(
                usedTokens = ctxUsed,
                maxTokens = ctxMax,
                textSecondary = colors.textSecondary,
                warning = Color(0xFFF59E0B),
                error = Color(0xFFEF4444),
            )

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
                    Icon(Icons.AutoMirrored.Filled.Send, null, tint = colors.accent, modifier = Modifier.size(18.dp))
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

    // Smooth sinusoidal float — EaseInOutSine gives a natural, organic bob
    // Amplitude: 2px idle, 4px while thinking
    val floatOffset by infinite.animateFloat(
        initialValue = -1f,
        targetValue  =  1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isThinking) 800 else 2200,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "float",
    )

    // Subtle idle rotation sway — ±2° like a gentle head-tilt, period = 4.4s
    val sway by infinite.animateFloat(
        initialValue = -2f,
        targetValue  =  2f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isThinking) 1600 else 4400,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sway",
    )

    // Blink: quick scaleY squash every ~3 s idle / ~1.2 s thinking
    // Uses animateFloatAsState so the snap-back is instant (no spring jank)
    var blinking by remember { mutableStateOf(false) }
    LaunchedEffect(isThinking) {
        while (true) {
            kotlinx.coroutines.delay(if (isThinking) 1200L else 3000L)
            blinking = true
            kotlinx.coroutines.delay(80L)   // squash duration
            blinking = false
        }
    }
    val blinkScaleY by androidx.compose.animation.core.animateFloatAsState(
        targetValue    = if (blinking) 0.78f else 1f,
        animationSpec  = tween(70, easing = androidx.compose.animation.core.FastOutLinearInEasing),
        label = "blink",
    )

    // Glow ring pulse — only visible while thinking; fades in/out gracefully
    val glowAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue   = if (isThinking) 0.6f else 0f,
        animationSpec = tween(400),
        label = "glow-fade",
    )
    val glowPulse by infinite.animateFloat(
        initialValue = 0.5f,
        targetValue  = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow-pulse",
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        // Glow ring — fades in when thinking, pulses at 600ms
        if (glowAlpha > 0f) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = glowAlpha * glowPulse }
                    .background(Color(0xFF5B6EF5), androidx.compose.foundation.shape.CircleShape),
            )
        }
        Image(
            painter = painterResource(id = com.codespace.ide.R.drawable.copilot_bot),
            contentDescription = "Copilot",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = floatOffset * (if (isThinking) 4f else 2f)
                    rotationZ    = sway * (if (isThinking) 0.5f else 1f)
                    scaleY       = blinkScaleY
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
    onOpenFile: ((String) -> Unit)? = null,
    onSwitchToPreview: ((String) -> Unit)? = null,
    // P39: AI code actions (Explain/Optimize/etc from the editor's lightbulb menu) deliver
    // their prompt here to be auto-sent as a new chat message.
    pendingPrompt: String? = null,
    onPendingPromptConsumed: (() -> Unit)? = null,
    // P41-X: Workspace-aware AI context — project root + current file + open files
    projectRootPath: String? = null,
    currentFilePath: String? = null,
    openFilePaths: List<String> = emptyList(),
    // Item3: open Connectors Hub from the chat panel overflow menu
    onOpenConnectors: (() -> Unit)? = null,
    // R1-CHAT-PARITY: insert-at-cursor bridge for chat code blocks — routes
    // through the shared dispatcher so code lands in the focused editor.
    keyInsertDispatcher: com.codespace.ide.editor.KeyInsertDispatcher? = null,
) {
    val context   = LocalContext.current
    val scope     = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var mode          by remember { mutableStateOf(ChatMode.ASK) }
    var chatInput     by remember { mutableStateOf("") }
    var chatLoading   by remember { mutableStateOf(false) }
    var liveStreamText by remember { mutableStateOf("") }
    var ctxUsed        by remember { mutableStateOf<Int?>(null) }
    var ctxMax         by remember { mutableStateOf<Int?>(null) }
    var error         by remember { mutableStateOf("") }
    var showModelMenu by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) } // Item3: chat-panel overflow menu
    // R7-FIND: in-transcript find bar (distinct from session search)
    var findActive by remember { mutableStateOf(false) }
    var findQuery  by remember { mutableStateOf("") }
    // R1-CHAT-PARITY: cancelable in-flight chat job + session-rename dialog target
    var chatJob by remember { mutableStateOf<Job?>(null) }
    var renameTargetId by remember { mutableStateOf<String?>(null) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val copyCodeToClipboard: (String) -> Unit = { code ->
        clipboard.setText(androidx.compose.ui.text.AnnotatedString(code))
        android.widget.Toast.makeText(context, "Code copied", android.widget.Toast.LENGTH_SHORT).show()
    }
    val insertCodeAtCursor: ((String) -> Unit)? = keyInsertDispatcher?.let { d ->
        { code: String -> d.dispatch(code) }
    }
    // R2-AUTOINSTR: chip toggle state for THIS project's instruction files
    var autoInstrEnabled by remember(projectRootPath) {
        mutableStateOf(
            projectRootPath?.let { com.codespace.ide.agent.AutoInstructionsProvider.isEnabled(context, it) } ?: true
        )
    }
    // R3-ATTACH: pending file attachments (clear on send) + implicit ctx toggle
    var attachments by remember(projectRootPath) {
        mutableStateOf<List<com.codespace.ide.chat.ChatAttachment>>(emptyList())
    }
    var showAttachPicker by remember { mutableStateOf(false) }
    var implicitCtxOn by remember {
        mutableStateOf(
            context.getSharedPreferences(PREFS_CHAT, Context.MODE_PRIVATE)
                .getBoolean("implicit_workspace_ctx", true)
        )
    }
    var availModels   by remember { mutableStateOf(registeredModelEntries(tokenStore)) }
    var selectedModel by remember { mutableStateOf(chatModelSelectionInitial(context, tokenStore)) }
    // R5-PINNING: starred favorites shown first in the picker
    var pinnedModels by remember { mutableStateOf(com.codespace.ide.chat.ChatModelSelection.getPinned(context)) }
    // 404-fix: fetch the LIVE model lists once when the panel first composes,
    // so the picker only ever offers models that exist right now. Falls back to
    // defaults if the network call fails.
    var liveModelsFetched by remember { mutableStateOf(false) }
    LaunchedEffect(liveModelsFetched) {
        if (!liveModelsFetched) {
            liveModelsFetched = true
            val live = fetchLiveModelEntries(tokenStore)
            if (live.isNotEmpty()) {
                availModels = live
                // keep current selection valid; if it vanished (retired model),
                // snap to the first available current model
                if (selectedModel != com.codespace.ide.chat.ChatModelSelection.AUTO_MODEL && selectedModel !in live) {
                    val curPrefix = selectedModel.substringBefore(':', "")
                    val sameProvider = live.filter { it.startsWith(curPrefix + ":") }
                    val snapped = sameProvider.firstOrNull() ?: live.firstOrNull() ?: selectedModel
                    selectedModel = snapped
                    com.codespace.ide.chat.ChatModelSelection.set(context, snapped)
                }
            }
        }
    }

    // R5-PER-MODE: each chat mode remembers its own model; switching modes
    // swaps the picker to that mode's last-used model (global selection as
    // fallback). Never writes back — only picking persists.
    LaunchedEffect(mode) {
        val perMode = com.codespace.ide.chat.ChatModelSelection.getForMode(context, mode.name)
        val target = perMode ?: com.codespace.ide.chat.ChatModelSelection.get(context)
        if (!target.isNullOrBlank() && target != selectedModel) selectedModel = target
    }

    // ── Sessions (UI bucket #5) ─────────────────────────────────────────
    val sessions = remember {
        mutableStateListOf<ChatSession>().apply {
            val loaded = loadSessions(context)
            addAll(if (loaded.isEmpty()) listOf(newSession()) else loaded)
        }
    }
    var activeSessionId by remember { mutableStateOf(sessions.first().id) }
    val activeSession: ChatSession = sessions.find { it.id == activeSessionId } ?: sessions.first()

    // R6-PENDING-EDITS: the staging buffer is session-scoped — register the live
    // session + project root so staged entries and checkpoints bind to the
    // project the user is working in.
    LaunchedEffect(activeSession.id, projectRootPath) {
        com.codespace.ide.chat.PendingChangesStore.activeSessionId = activeSession.id
        com.codespace.ide.chat.PendingChangesStore.activeProjectRoot = projectRootPath
        com.codespace.ide.chat.ChatPlanStore.activeSessionId = activeSession.id
    }

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

    // ── R1-CHAT-PARITY: slash commands ──────────────────────────────────────
    // Intercepted in send() before the model call; each maps to a UI action.
    fun handleCommand(name: String, arg: String) {
        when (name) {
            "clear" -> { messages.clear(); persistSessions() }
            "new" -> startNewSession()
            "rename" -> {
                if (arg.isNotBlank()) {
                    activeSession.title = arg.take(40)
                    saveSessions(context, sessions)
                } else {
                    renameTargetId = activeSessionId
                }
            }
            "models" -> showModelMenu = true
            "tools" -> {
                messages.add(ChatMsg("assistant", com.codespace.ide.chat.ChatSlashCommands.toolsText()))
                persistSessions()
            }
            "help" -> {
                messages.add(ChatMsg("assistant", com.codespace.ide.chat.ChatSlashCommands.helpText()))
                persistSessions()
            }
            else -> messages.add(ChatMsg("assistant", "Unknown command: /$name — try /help"))
        }
    }

    // ── R1-CHAT-PARITY: stop the in-flight generation ──────────────────────
    // Cancels the coroutine (streaming reads cooperate via ensureActive) and
    // keeps whatever streamed so far as a partial reply.
    fun stopChat() {
        chatJob?.cancel()
        com.codespace.ide.agent.AgentFlowGate.pending.value = null
        if (liveStreamText.isNotBlank()) {
            messages.add(ChatMsg("assistant", liveStreamText + "\n\n_[stopped]_"))
        }
        chatLoading = false
        liveStreamText = ""
        chatJob = null
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // STREAMING: keep the live bubble in view — scroll every ~120 chars so we don't
    // fight recomposition on every delta.
    LaunchedEffect(liveStreamText.length / 120, chatLoading) {
        if (chatLoading && liveStreamText.isNotEmpty() && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size)
        }
    }

    fun send(userText: String) {
        if (userText.isBlank() || chatLoading) return
        // R1-CHAT-PARITY: slash commands never reach the model
        val cmd = com.codespace.ide.chat.ChatSlashCommands.parse(userText)
        if (cmd != null) {
            chatInput = ""
            handleCommand(cmd.name, cmd.arg)
            return
        }
        val msg = ChatMsg("user", userText)
        // R3-ATTACH: merge explicit chips + "#file" tokens; chips clear on send
        val hashAtts = com.codespace.ide.chat.ChatAttachmentInjector.resolveHashTokens(userText, projectRootPath)
        val sendAtts = (attachments + hashAtts).distinctBy { it.path }
        attachments = emptyList()
        messages.add(msg)
        chatInput = ""
        error = ""
        chatLoading = true
        liveStreamText = ""
        chatJob = scope.launch {
            try {
                com.codespace.ide.chat.ChatModelSelection.set(context, selectedModel)
                // R5-AUTO: "auto" never reaches the wire — resolve to the
                // active provider's default right before dispatch.
                val effModel = com.codespace.ide.chat.ChatModelSelection.resolveAuto(context, selectedModel, tokenStore)
                val toolsUsed = mutableListOf<String>()
                val sink: ((ChatStreamEvent) -> Unit) = { ev ->
                    when (ev) {
                        is ChatStreamEvent.IterationStart -> liveStreamText = ""
                        is ChatStreamEvent.Delta -> liveStreamText += ev.text
                        is ChatStreamEvent.ToolDone -> { liveStreamText += "\n⚙ " + ev.tool + " — done"; toolsUsed.add(ev.tool) }
                    }
                }
                val reply = chat(effModel, messages.toList(), mode, context, tokenStore, onOpenFile, onSwitchToPreview, projectRootPath, currentFilePath, openFilePaths, onStreamEvent = sink, includeImplicitCtx = implicitCtxOn, attachments = sendAtts)
                // R4-TYPED-ENTRY: tools-used transcript chip rides before the reply
                if (toolsUsed.isNotEmpty()) messages.add(ChatMsg("tool", toolsUsed.distinct().joinToString(", ")))
                messages.add(ChatMsg("assistant", reply))
                // Phase 1 C3: request_connector ran mid-loop -> inline Connect card
                com.codespace.ide.agent.AgentConnectorManager.consumePendingConnectCard()?.let { svc ->
                    messages.add(ChatMsg("card_connect", svc))
                }
                persistSessions()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // Stop button / disposal — stopChat() already finalized state.
            } catch (e: Exception) {
                error = e.message ?: "Unknown error"
                messages.add(ChatMsg("assistant", "Error: ${e.message}"))
                persistSessions()
            } finally {
                chatLoading = false
                liveStreamText = ""
                // CONTEXT GAUGE: estimate for the NEXT send — unawaited, never delays the reply
                scope.launch {
                    updateContextGauge(com.codespace.ide.chat.ChatModelSelection.resolveAuto(context, selectedModel, tokenStore), mode, context, tokenStore, messages.toList(), projectRootPath, currentFilePath, openFilePaths) { u, m ->
                        ctxUsed = u; ctxMax = m
                    }
                }
            }
        }
    }

    // ── R1-CHAT-PARITY: retry last turn ──────────────────────────────────────
    // Drops everything after the last user message (its replies included) and
    // re-sends that same question through the normal send() path.
    fun retryLastTurn() {
        if (chatLoading) return
        val lastUserIdx = messages.indexOfLast { it.role == "user" }
        if (lastUserIdx < 0) return
        val userText = messages[lastUserIdx].text
        while (messages.size > lastUserIdx) messages.removeAt(messages.size - 1)
        send(userText)
    }

    // R7-FEEDBACK: thumbs up/down on an assistant message (toggle on repeat tap).
    fun rateMessage(idx: Int, r: String) {
        val cur = messages.getOrNull(idx) ?: return
        val newRating = if (cur.rating == r) null else r
        messages[idx] = cur.copy(rating = newRating)
        persistSessions()
    }

    // P39: auto-send AI code actions (Explain/Optimize/etc) delivered from the editor's
    // lightbulb menu as soon as this panel is composed with a pending prompt.
    LaunchedEffect(pendingPrompt) {
        if (!pendingPrompt.isNullOrBlank()) {
            send(pendingPrompt)
            onPendingPromptConsumed?.invoke()
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
                                    Icon(Icons.Default.Edit, "Rename session", tint = colors.textSecondary,
                                        modifier = Modifier.size(12.dp).clickable { renameTargetId = s.id })
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
                if (messages.any { it.role == "user" } && !chatLoading) {
                    Icon(
                        Icons.Default.Refresh, "Retry last turn",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(16.dp).clickable { retryLastTurn() },
                    )
                    Spacer(Modifier.width(8.dp))
                }
                ChatModelMenuButton(
                    selectedModel = selectedModel,
                    availModels = availModels,
                    pinned = pinnedModels,
                    colors = colors,
                    expanded = showModelMenu,
                    onExpandedChange = { showModelMenu = it },
                    onPick = { m ->
                        selectedModel = m
                        com.codespace.ide.chat.ChatModelSelection.set(context, m)
                        // R5: per-mode memory — each mode keeps its own model
                        com.codespace.ide.chat.ChatModelSelection.setForMode(context, mode.name, m)
                    },
                    onTogglePin = { pinnedModels = com.codespace.ide.chat.ChatModelSelection.togglePin(context, selectedModel) },
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Default.DeleteOutline, null,
                    tint = colors.textSecondary, modifier = Modifier.size(16.dp).clickable {
                        messages.clear()
                        persistSessions()
                    },
                )
                Spacer(Modifier.width(8.dp))
                if (onOpenConnectors != null) {
                    Icon(
                        Icons.Default.AddLink, "Add connector",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(16.dp).clickable { onOpenConnectors() },
                    )
                    Spacer(Modifier.width(8.dp))
                    Box {
                        Icon(
                            Icons.Default.MoreVert, "More",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(16.dp).clickable { showOverflowMenu = true },
                        )
                        DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.Extension, null, tint = colors.textSecondary, modifier = Modifier.size(14.dp)) },
                                text = { Text("Connectors Hub", fontSize = 12.sp) },
                                onClick = { showOverflowMenu = false; onOpenConnectors() },
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
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
                        ChatMode.PLAN  -> Icons.AutoMirrored.Filled.ListAlt
                    }
                    Icon(modeIcon, null, tint = modeColor, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(m.name.lowercase().replaceFirstChar { it.titlecase() },
                        fontSize = 11.sp, color = modeColor, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
            // R7-FIND: in-chat find toggle (distinct from the session-search icon)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.Search, "Find in chat",
                tint = if (findActive) colors.accent else colors.textSecondary,
                modifier = Modifier.size(16.dp).clickable {
                    findActive = !findActive
                    if (!findActive) findQuery = ""
                })
        }

        // R7-FIND: the find bar itself (filter + live match count)
        if (findActive) {
            ChatFindBar(
                query = findQuery,
                onQueryChange = { findQuery = it },
                matchCount = if (findQuery.isBlank()) 0 else messages.count { it.text.contains(findQuery, true) },
                onClose = { findActive = false; findQuery = "" },
                colors = colors,
            )
        }

        HorizontalDivider(color = colors.divider)

        // ── Messages ──────────────────────────────────────────────────────
        // R7-FIND: active query filters the transcript to matching messages
        val findActiveNow = findActive && findQuery.isNotBlank()
        val visibleMsgs = if (findActiveNow)
            messages.filter { it.text.contains(findQuery, ignoreCase = true) } else messages
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
            itemsIndexed(visibleMsgs) { i, msg ->
                if (msg.role == "card_connect") {
                    ChatConnectCard(
                        serviceId = msg.text,
                        accent = colors.accent,
                        text = colors.text,
                        textSecondary = colors.textSecondary,
                        surface = colors.surface,
                        onConnect = { onOpenConnectors?.invoke() },
                    )
                } else if (msg.kind == ChatEntryKind.TOOL) {
                    ChatToolChip(text = msg.text, colors = colors)
                } else if (msg.kind == ChatEntryKind.ERROR) {
                    ChatErrorBubble(text = msg.text.removePrefix("Error: "), colors = colors)
                } else {
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
                        if (isUser) {
                            Text(msg.text, Modifier.padding(12.dp), fontSize = 13.sp, color = Color.White)
                        } else {
                            ChatMarkdownBody(
                                text = msg.text,
                                textColor = colors.text,
                                accent = colors.accent,
                                surface = colors.surface,
                                divider = colors.divider,
                                onCopyCode = copyCodeToClipboard,
                                onInsertCode = insertCodeAtCursor,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
                // R7-FEEDBACK: thumbs on assistant replies (hidden while find-filtering)
                if (!isUser && !findActiveNow) {
                    ChatFeedbackRow(
                        rating = msg.rating,
                        onRate = { r -> rateMessage(i, r) },
                        colors = colors,
                    )
                }
                }
            }
            // R6-PENDING-EDITS: review card rides at the end of the transcript
            // while THIS session has staged proposals. revision.value is read in
            // composition so any store mutation (stage/apply/discard/drift) recomposes.
            val pendingEntries = com.codespace.ide.chat.PendingChangesStore.revision.value.let {
                com.codespace.ide.chat.PendingChangesStore.pendingFor(activeSession.id)
            }
            if (pendingEntries.isNotEmpty()) {
                item {
                    ChatDiffReviewCard(
                        pending = pendingEntries,
                        sessionId = activeSession.id,
                        colors = colors,
                    )
                }
            }
            // R7-PLAN: review/progress card while THIS session has a plan
            val activePlan = com.codespace.ide.chat.ChatPlanStore.revision.value.let {
                com.codespace.ide.chat.ChatPlanStore.planFor(activeSession.id)
            }
            if (activePlan != null && activePlan.steps.isNotEmpty()) {
                item {
                    ChatPlanCard(
                        plan = activePlan,
                        onApprove = {
                            com.codespace.ide.chat.ChatPlanStore.setApproved(activeSession.id)
                            send("Plan approved — execute all steps now.")
                        },
                        onRevise = { chatInput = "Revise the plan: " },
                        onClear = { com.codespace.ide.chat.ChatPlanStore.clear(activeSession.id) },
                        colors = colors,
                    )
                }
            }
            // R7-FOLLOW-UPS: suggestion chips under the last assistant reply
            if (!chatLoading && !findActiveNow && visibleMsgs.isNotEmpty()) {
                val lastMsg = visibleMsgs.last()
                if (lastMsg.role == "assistant" && lastMsg.kind != ChatEntryKind.ERROR) {
                    item {
                        ChatFollowUpChips(
                            suggestions = suggestedFollowUps(
                                isAgentMode = mode == ChatMode.AGENT,
                                hasPendingStaged = pendingEntries.isNotEmpty(),
                                planAwaitingReview = activePlan != null && !activePlan.approved,
                                planInProgress = activePlan != null && activePlan.approved && !activePlan.allDone,
                                lastReplyWasError = false,
                            ),
                            onPick = { s -> chatInput = s },
                            colors = colors,
                        )
                    }
                }
            }
            if (chatLoading) {
                item {
                    LiveStreamIndicator(
                        liveText = liveStreamText,
                        accent = colors.accent,
                        surface = colors.assistantBubble,
                        text = colors.text,
                        textSecondary = colors.textSecondary,
                    )
                }
            }
        }

        if (error.isNotEmpty()) {
            Text(error, fontSize = 10.sp, color = Color(0xFFEF4444), modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        }

        // ── Context gauge (running per-turn token usage) ─────────────────────
        ChatContextGauge(
            usedTokens = ctxUsed,
            maxTokens = ctxMax,
            textSecondary = colors.textSecondary,
            warning = Color(0xFFF59E0B),
            error = Color(0xFFEF4444),
        )

        // R2-AUTOINSTR: chip shows when the project has instruction files
        AutoInstructionsChip(
            projectRoot = projectRootPath,
            enabled = autoInstrEnabled,
            onToggle = {
                if (!projectRootPath.isNullOrBlank()) {
                    val newState = !autoInstrEnabled
                    autoInstrEnabled = newState
                    com.codespace.ide.agent.AutoInstructionsProvider.setEnabled(context, projectRootPath, newState)
                }
            },
            colors = colors,
        )

        // R3-ATTACH: pending attachment chips + picker dialog
        if (attachments.isNotEmpty()) {
            ChatAttachmentChips(
                attachments = attachments,
                onRemove = { a -> attachments = attachments.filterNot { it == a } },
                colors = colors,
            )
        }
        if (showAttachPicker) {
            ChatAttachPickerDialog(
                projectRoot = projectRootPath,
                onPick = { a ->
                    if (attachments.none { it.path == a.path }) attachments = attachments + a
                    showAttachPicker = false
                },
                onPickSelection = { a ->
                    if (attachments.none { it.path == a.path }) attachments = attachments + a
                    showAttachPicker = false
                },
                onDismiss = { showAttachPicker = false },
                colors = colors,
            )
        }

        // ── Input ─────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { showAttachPicker = true }, enabled = !chatLoading && !projectRootPath.isNullOrBlank()) {
                Icon(Icons.Default.AttachFile, "Attach file to chat", tint = colors.textSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = {
                implicitCtxOn = !implicitCtxOn
                context.getSharedPreferences(PREFS_CHAT, Context.MODE_PRIVATE)
                    .edit().putBoolean("implicit_workspace_ctx", implicitCtxOn).apply()
            }) {
                Icon(Icons.Default.AccountTree, "Implicit workspace context on/off",
                    tint = if (implicitCtxOn) colors.accent else colors.textSecondary,
                    modifier = Modifier.size(18.dp))
            }
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
            if (chatLoading) {
                IconButton(onClick = { stopChat() }) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color(0xFFEF4444))
                }
            } else {
                IconButton(
                    onClick = { send(chatInput) },
                    enabled = chatInput.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = colors.accent)
                }
            }
        }
            } // end chat column
        }

        // R1-CHAT-PARITY: session rename dialog (row pencil icon or /rename)
        renameTargetId?.let { tid ->
            val target = sessions.find { it.id == tid }
            if (target != null) {
                SessionRenameDialog(
                    currentTitle = target.title,
                    onDismiss = { renameTargetId = null },
                    onConfirm = { nt ->
                        target.title = nt
                        saveSessions(context, sessions)
                        renameTargetId = null
                    },
                )
            }
        }

        // P-FLOW: Approve/Reject floating card for Manual Flow Mode
        val pendingApproval = com.codespace.ide.agent.AgentFlowGate.pending
        pendingApproval.let { pa ->
            val ap = pa.value
            if (ap != null) {
                ChatApprovalCard(approval = ap)
            }
        }
    }
}

