package com.codespace.ide.agent

import android.content.Context
import com.codespace.ide.data.SecureTokenStore
import com.codespace.ide.testing.TestDiscoveryService
import com.codespace.ide.testing.TestStore
import com.codespace.ide.testing.TestRunManager
import com.codespace.ide.testing.TestResultStore
import com.codespace.ide.project.TaskRunner
import com.codespace.ide.terminal.ProotInstaller
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * AgentTools — gives ANY AI launched in the app (via API or terminal) full
 * agent capabilities, mirroring what a Base44 Superagent can do.
 *
 * Capabilities (31 tools):
 *  Shell: run_command, read_file, write_file, list_files, search_files
 *  Git:   git_commit_push, git_pull_rebase, git_branch, git_status, git_diff
 *  Secret: save_secret, get_secret, detect_secrets (auto-scan for keys/tokens)
 *  Web:   web_fetch, web_search
 *  Memory: save_memory, read_memory, delete_memory
 *  Connectors: list_connectors, connect_service, use_connector
 *  Data:  create_entity, read_entities, update_entity, delete_entity
 *  Sched: schedule_task, list_tasks, cancel_task
 *  Media: upload_file
 *  Pkg:   install_package (npm, pip, apt)
 *
 * Tool-calling protocol (text-based, works with ANY model):
 *   <tool>{"name":"run_command","arguments":{"command":"ls -la"}}</tool>
 */
object AgentTools {

    const val TOOLS_DESCRIPTION = """
You have access to these tools for acting on the user's environment:

— Shell & Files —
1. run_command    — Run a shell command
   <tool>{"name":"run_command","arguments":{"command":"git status","workdir":"/path"}}</tool>
2. read_file      — Read file contents (max 500KB)
   <tool>{"name":"read_file","arguments":{"path":"/path/to/file"}}</tool>
3. write_file     — Write content to a file (creates or overwrites)
   <tool>{"name":"write_file","arguments":{"path":"/path/to/file","content":"..."}}</tool>
4. list_files     — List files in a directory
   <tool>{"name":"list_files","arguments":{"path":"/path/to/dir"}}</tool>
5. search_files   — Search for a text pattern in files
   <tool>{"name":"search_files","arguments":{"path":"/path","pattern":"TODO"}}</tool>

— Planning (multi-step tasks) —
P1. plan           — Stage a structured plan / todo list for user review and
   progress tracking. Call BEFORE starting multi-step work; after approval call
   again with updated statuses after each step. Staging a plan ENDS your turn.
   <tool>{"name":"plan","arguments":{"steps":[{"title":"Fix parser","detail":"Lexer.kt off-by-one","status":"pending"},{"title":"Add tests","status":"pending"}]}}</tool>
   status: pending | in_progress | completed

— Git (full access: push, pull, commit, branch, merge) —
6.  git_commit_push — Stage all, commit, push
    <tool>{"name":"git_commit_push","arguments":{"message":"fix: update UI","repo_dir":"/path"}}</tool>
7.  git_pull_rebase — Pull with rebase
    <tool>{"name":"git_pull_rebase","arguments":{"repo_dir":"/path"}}</tool>
8.  git_branch      — create/switch/list/merge/delete branches
    <tool>{"name":"git_branch","arguments":{"action":"create","name":"feature-x","repo_dir":"/path"}}</tool>
9.  git_status      — Show working tree status
    <tool>{"name":"git_status","arguments":{"repo_dir":"/path"}}</tool>
10. git_diff        — Show changes (staged or unstaged)
    <tool>{"name":"git_diff","arguments":{"staged":true,"repo_dir":"/path"}}</tool>

— Secrets (detection + secure encrypted storage) —
12. save_secret    — Store a secret encrypted
    <tool>{"name":"save_secret","arguments":{"key":"OPENAI_API_KEY","value":"sk-..."}}</tool>
13. get_secret     — Retrieve a stored secret
    <tool>{"name":"get_secret","arguments":{"key":"OPENAI_API_KEY"}}</tool>
14. detect_secrets — Scan text/files for API keys, tokens, credentials
    <tool>{"name":"detect_secrets","arguments":{"text":"...or path":"/path"}}</tool>

— Web —
15. web_fetch      — Fetch content from a URL
    <tool>{"name":"web_fetch","arguments":{"url":"https://api.github.com/user"}}</tool>
16. web_search     — Search the web
    <tool>{"name":"web_search","arguments":{"query":"how to use ffmpeg"}}</tool>

— Memory (persistent across sessions) —
17. save_memory    — Save a fact or note
    <tool>{"name":"save_memory","arguments":{"key":"decision","value":"Use clip rendering"}}</tool>
18. read_memory    — Read all saved memories
    <tool>{"name":"read_memory","arguments":{}}</tool>
19. delete_memory  — Delete a memory entry
    <tool>{"name":"delete_memory","arguments":{"key":"old"}}</tool>

— Connectors (OAuth: Gmail, Calendar, Slack, etc.) —
20. list_connectors — Show available/connected connectors
    <tool>{"name":"list_connectors","arguments":{}}</tool>
21. connect_service — Initiate OAuth flow (returns auth URL)
    <tool>{"name":"connect_service","arguments":{"service":"gmail","scopes":["read","send"]}}</tool>
22. use_connector   — Call API with connector token
    <tool>{"name":"use_connector","arguments":{"service":"gmail","method":"GET","endpoint":"/messages"}}</tool>
23. request_connector — Show the user an inline "Connect <Service>" card in the chat when they need a service that isn't connected yet (works for all connector types)
    <tool>{"name":"request_connector","arguments":{"service":"sentry"}}</tool>

— Data Entities (local SQLite CRUD) —
23. create_entity  — Create a data record
    <tool>{"name":"create_entity","arguments":{"entity":"Task","data":"{\"title\":\"Build\",\"status\":\"pending\"}"}}</tool>
24. read_entities  — Query records with filter
    <tool>{"name":"read_entities","arguments":{"entity":"Task","filter":"{\"status\":\"pending\"}"}}</tool>
25. update_entity  — Update matching records
    <tool>{"name":"update_entity","arguments":{"entity":"Task","filter":"{}","data":"{}"}}</tool>
26. delete_entity  — Delete matching records
    <tool>{"name":"delete_entity","arguments":{"entity":"Task","filter":"{}"}}</tool>

— Scheduling —
27. schedule_task  — Schedule a task (cron expression)
    <tool>{"name":"schedule_task","arguments":{"name":"Daily Build","cron":"0 9 * * *","command":"git pull"}}</tool>
28. list_tasks     — List scheduled tasks
    <tool>{"name":"list_tasks","arguments":{}}</tool>
29. cancel_task    — Cancel a scheduled task
    <tool>{"name":"cancel_task","arguments":{"name":"Daily Build"}}</tool>

— Media —

30. upload_file    — Upload a file to a remote URL
    <tool>{"name":"upload_file","arguments":{"path":"/path/file","url":"https://upload.sh"}}</tool>

— Package Management —
31. install_package — Install npm/pip/apt package
    <tool>{"name":"install_package","arguments":{"manager":"npm","package":"lodash"}}</tool>

When you want to use a tool, output the <tool>...</tool> tag. Wait for the result.
You can use multiple tools in sequence. When done, give a final summary.
"""

    private val TOOL_REGEX = Regex("""<(tool|tool_call)>\s*(\{.*?\})\s*</\1>""", RegexOption.DOT_MATCHES_ALL)

    fun parseToolCalls(text: String): List<Pair<String, JSONObject>> {
        return TOOL_REGEX.findAll(text).map { match ->
            try {
                val json = JSONObject(match.groupValues[2])
                val name = json.getString("name")
                val args = json.optJSONObject("arguments") ?: JSONObject()
                name to args
            } catch (_: Exception) {
                "error" to JSONObject().put("message", "Failed to parse tool call")
            }
        }.toList()
    }

    // R1-CHAT-PARITY: canonical builtin tool name list. MUST stay in sync with
    // the `when` branches in executeTool below (a mismatch only affects the
    // /tools chat listing, never execution). MCP tools (mcp_<server>_<tool>)
    // are dynamic and NOT listed here — they surface via McpClientManager.
    private val BUILTIN_TOOL_NAMES: List<String> = listOf(
        "run_command", "run_in_terminal", "read_file", "write_file",
        "create_file", "insert_edit_into_file", "multi_replace_string_in_file",
        "list_dir", "grep_search", "file_search", "semantic_search",
        "search_workspace_symbols", "read_project_structure",
        "apply_patch", "manage_todo_list", "create_new_jupyter_notebook",
        "copilot_getNotebookSummary", "read_notebook_cell_output", "edit_notebook_file",
        "run_notebook_cell", "get_terminal_output", "terminal_last_command",
        "runTests", "test_search", "test_failure", "create_and_run_task", "run_task",
        "get_task_output", "get_project_setup_info",
        "get_search_view_results", "github_repo", "view_image", "tool_search",
        "replace_string_in_file", "get_errors", "create_directory", "get_changed_files",
        "list_files", "search_files",
        "git_commit_push", "git_pull_rebase", "git_branch", "git_status", "git_diff",
        "save_secret", "get_secret", "detect_secrets", "web_fetch", "web_search",
        "save_memory", "read_memory", "delete_memory",
        "list_connectors", "connect_service", "use_connector", "request_connector",
        "create_entity", "read_entities", "update_entity", "delete_entity",
        "schedule_task", "list_tasks", "cancel_task", "upload_file", "install_package",
        "plan",
    )

    /** Builtin tool names for the /tools chat command (see BUILTIN_TOOL_NAMES). */
    fun toolNames(): List<String> = BUILTIN_TOOL_NAMES

    fun hasToolCalls(text: String): Boolean = TOOL_REGEX.containsMatchIn(text)
    fun stripToolCalls(text: String): String = text.replace(TOOL_REGEX, "").trim()

    // CH11 (P4h): suspend — the MCP branch was runBlocking { callTool }, blocking
    // the calling thread for up to 60s per call with Stop unable to cancel mid-call.
    // The bridge is now a normal cancellable suspend call with a hard 90s bound.
    suspend fun executeTool(name: String, args: JSONObject, context: Context): String {
        return try {
            when (name) {
                "run_command" -> runCommand(args.getString("command"), args.optString("workdir").ifBlank { null }, context)
                // REMOVAL 7/7 (advisor-approved stub 2026-10-27): ChatPlanStore is
                // archived and removed with the old chat; the plan tool answers
                // plainly until the rebuilt chat re-registers it. No chat classes.
                "plan" -> "Plan staging returns with the new chat."
                // CH02 (P3b): the four file tools now resolve their path through the
                // shared guest->host choke point below — the model was systematically
                // exposed to GUEST paths (terminal output, run_command results) while
                // these tools read the raw string as a HOST File.
                "read_file" -> readFile(args.getString("path"), context)
                "write_file" -> writeFile(args.getString("path"), args.getString("content"), context)
                // VS Code parity aliases: same implementation, their tool name.
                "create_file" -> createFile(args.getString("path"), args.getString("content"), context)
                "insert_edit_into_file" -> replaceStringInFile(
                    args.getString("path"), args.getString("old_string"), args.getString("new_string"),
                    args.optBoolean("replace_all", false), context)
                "multi_replace_string_in_file" -> multiReplaceFile(args.getString("path"), args.optString("replacements"), args.optBoolean("replace_all", false), context)
                "list_dir" -> listFiles(args.getString("path"), context)
                "grep_search" -> searchFiles(args.getString("path"), args.getString("pattern"), context)
                "file_search" -> findFiles(args.getString("query"), args.optString("path").ifBlank { null }, context)
                "semantic_search" -> semanticSearch(args.getString("query"), args.optString("path").ifBlank { null }, context)
                "search_workspace_symbols" -> workspaceSymbols(args.getString("query"), args.optString("path").ifBlank { null }, context)
                "read_project_structure" -> projectStructure(args.optString("path").ifBlank { null }, args.optInt("depth", 2), context)
                "run_in_terminal" -> runCommand(args.getString("command"), args.optString("workdir").ifBlank { null }, context)
                // --- notebooks, patches, todos, and the agent's own terminal record ---
                "apply_patch" -> applyPatch(args.getString("path"), args.getString("patch"), context)
                "manage_todo_list" -> manageTodoList(args.optString("todos"), context)
                "create_new_jupyter_notebook" -> createNotebook(args.getString("path"), args.optString("cells"), context)
                "copilot_getNotebookSummary" -> notebookSummary(args.getString("path"), context)
                "read_notebook_cell_output" -> notebookCellOutput(args.getString("path"), args.optInt("index", 0), context)
                "edit_notebook_file" -> editNotebook(
                    args.getString("path"), args.optInt("index", -1), args.optString("cell_type", "code"),
                    args.optString("source"), args.optBoolean("clear_outputs", false), context)
                "run_notebook_cell" -> runNotebookCell(args.getString("path"), args.optInt("index", 0), context)
                "get_terminal_output" -> terminalOutput(args.optInt("runs", 1))
                "terminal_last_command" -> terminalLastCommand()
                // --- tests and tasks: backed by the real test and task subsystems ---
                "runTests" -> runTestsTool(args.optString("query"), args.optInt("limit", 10), context)
                "test_search" -> testSearch(args.getString("query"), context)
                "test_failure" -> testFailures()
                "create_and_run_task" -> runTaskTool(args.optString("task"), context)
                "run_task" -> runTaskTool(args.optString("task"), context)
                "get_task_output" -> taskOutput(args.optString("task"))
                "get_project_setup_info" -> projectSetupInfo(context)
                // --- view state, GitHub, images, and the inventory itself ---
                "get_search_view_results" -> searchViewResults()
                "github_repo" -> githubRepoTool(
                    args.optString("repo"), args.optString("what", "overview"),
                    args.optString("path"), args.optString("state"), context)
                "view_image" -> viewImage(args.getString("path"), context)
                "tool_search" -> toolSearch(args.getString("query"))
                // VS Code parity (2026-10-31): ToolName.ReplaceString, GetErrors,
                // CreateDirectory, GetScmChanges. The two that read are bounded like the
                // existing git tools so they cannot crowd the context window.
                "replace_string_in_file" -> replaceStringInFile(
                    args.getString("path"),
                    args.getString("old_string"),
                    args.getString("new_string"),
                    args.optBoolean("replace_all", false),
                    context,
                )
                "get_errors" -> com.codespace.ide.util.ToolOutputBounds.bound(
                    getErrors(args.optString("path").ifBlank { null })
                )
                "create_directory" -> createDirectory(args.getString("path"), context)
                "get_changed_files" -> com.codespace.ide.util.ToolOutputBounds.bound(
                    gitStatus(args.optString("repo_dir").ifBlank { null }, context)
                )
                "list_files" -> listFiles(args.getString("path"), context)
                "search_files" -> searchFiles(args.getString("path"), args.getString("pattern"), context)
                "git_commit_push" -> gitCommitPush(args.getString("message"), args.optString("repo_dir").ifBlank { null }, context)
                "git_pull_rebase" -> gitPullRebase(args.optString("repo_dir").ifBlank { null }, context)
                "git_branch" -> gitBranch(args.getString("action"), args.optString("name", ""), args.optString("repo_dir").ifBlank { null }, context)
                // PHASE 1: inspected output is capped, so a giant diff/status can never
                // eat the context window. The marker states how much was dropped.
                "git_status" -> com.codespace.ide.util.ToolOutputBounds.bound(
                    gitStatus(args.optString("repo_dir").ifBlank { null }, context)
                )
                "git_diff" -> com.codespace.ide.util.ToolOutputBounds.bound(
                    gitDiff(args.optBoolean("staged", false), args.optString("repo_dir").ifBlank { null }, context)
                )
                "save_secret" -> saveSecret(args.getString("key"), args.getString("value"), context)
                "get_secret" -> getSecret(args.getString("key"), context)
                "detect_secrets" -> detectSecrets(args.optString("text").ifBlank { null }, args.optString("path").ifBlank { null })
                "web_fetch" -> webFetch(args.getString("url"), args.optString("headers").ifBlank { null })
                "web_search" -> webSearch(args.getString("query"))
                // M5: every memory access carries the owner's scope and the calling session,
                // so a "current session only" setting is enforced in the tool, not just in the UI.
                "save_memory" -> AgentMemory.save(
                    args.getString("key"), args.getString("value"), context,
                    currentMemoryScope(), MemoryScopeState.activeSessionId,
                )
                "read_memory" -> AgentMemory.readAll(
                    context, currentMemoryScope(), MemoryScopeState.activeSessionId,
                )
                "delete_memory" -> AgentMemory.delete(
                    args.getString("key"), context,
                    currentMemoryScope(), MemoryScopeState.activeSessionId,
                )
                "list_connectors" -> AgentConnectorManager.listConnectors(context)
                "connect_service" -> AgentConnectorManager.connectService(args.getString("service"), args.optJSONArray("scopes"), context)
                "use_connector" -> AgentConnectorManager.useConnector(args.getString("service"), args.getString("method"), args.getString("endpoint"), args.optString("body", "{}"), context)
                "request_connector" -> AgentConnectorManager.requestConnectorCard(args.getString("service"), context)
                "create_entity" -> AgentEntityManager.create(args.getString("entity"), args.getString("data"), context)
                "read_entities" -> AgentEntityManager.read(args.getString("entity"), args.optString("filter").ifBlank { null }, context)
                "update_entity" -> AgentEntityManager.update(args.getString("entity"), args.getString("filter"), args.getString("data"), context)
                "delete_entity" -> AgentEntityManager.delete(args.getString("entity"), args.getString("filter"), context)
                // IG02 (P2c): persist the project the task belongs to — the unattended run
                // gates on that project's TrustState entry (headless, fail closed).
                "schedule_task" -> AgentScheduler.schedule(
                    args.getString("name"), args.getString("cron"), args.getString("command"),
                    com.codespace.ide.security.TrustState.activeProjectRoot(context), context)
                "list_tasks" -> AgentScheduler.listTasks(context)
                "cancel_task" -> AgentScheduler.cancel(args.getString("name"), context)
                "upload_file" -> uploadFile(args.getString("path"), args.getString("url"))
                "install_package" -> installPackage(args.getString("manager"), args.getString("package"), args.optString("project_dir").ifBlank { null }, context)
                // MCP: external stdio MCP server tools (mcp_<server>_<tool>) bridge
                // through McpClientManager — SAME dispatch path, so the SAME
                // AgentFlowGate AUTO/MANUAL approval already gated this call.
                else -> if (name.startsWith("mcp_")) {
                    // CH11 (P4h): cancellable + hard-bounded; runBlocking is GONE.
                    kotlinx.coroutines.withTimeoutOrNull(90_000L) {
                        McpClientManager.callTool(name, args, context)
                    } ?: "MCP tool '$name' timed out after 90s — the call was abandoned (Stop now cancels it too). Check the server or retry."
                } else "Unknown tool: $name"
            }
        } catch (e: Exception) {
            "Error executing $name: ${e.message}"
        }
    }

    // ── CH06 (P4h): truncation honesty ─────────────────────────────────────
    // Every output cap used to slice SILENTLY — the model believed it saw the
    // whole output/file and answered about content it never received. Each
    // capped result now carries a marker naming the cap and the real size.
    private fun truncMark(raw: String, max: Int): String =
        if (raw.length <= max) raw
        else raw.take(max) + "\n[... TRUNCATED — showing " + max + " of " + raw.length + " chars. Ask for a specific range/section to see the rest.]"

    // ── Shell & Files ────────────────────────────────────────────────────
    // Runs INSIDE the Ubuntu proot rootfs (git, npm, apt, etc. only exist there — the bare
    // host ProcessBuilder this used to call never had those binaries on PATH). workdir, if
    // given, must be a guest-side path (e.g. "/root/myproject"), not a host Android path.
    private fun runCommand(command: String, workdir: String?, context: Context): String {
        // C-4 (audit #2854): the chat panel advertises HOST project roots, so the
        // model passes host paths as workdir — but execOnce runs inside the proot
        // GUEST, where the host path fails the `[-d workdir]` guard SILENTLY and the
        // command lands in guest HOME (git "not a git repository"). Translate
        // host->guest when the path maps (/host-files, /sdcard, rootfs); guest-style
        // paths and untranslatable paths pass through unchanged (old behavior).
        val guestWorkdir = workdir?.let { wd ->
            val translated = ProotInstaller.hostToGuestPath(context, wd)
            if (translated != null && translated != wd) {
                com.codespace.ide.diagnostics.AppOutputLog.log(
                    "[C4] run_command workdir translated host->guest: " + wd + " -> " + translated, "terminal")
                translated
            } else wd
        }
        // U6-c: announce the run BEFORE it executes, so a long-running command is visible
        // in the terminal pane while it runs, not only after it returns. execOnce is a
        // one-shot exec (not the user's interactive shell), so the honest thing to show is
        // this feed's record of the real command, status and output - typing it into the
        // live session instead would execute it a second time.
        val runId = AgentTerminalFeed.begin(command, guestWorkdir)
        val out: String
        try {
            out = com.codespace.ide.terminal.ProotInstaller.execOnce(context, command, guestWorkdir)
        } catch (t: Throwable) {
            // Never leave a row stuck on "running", and never claim success: the run is
            // marked FAILED with the real message, then the throw continues to the caller.
            AgentTerminalFeed.finish(runId, t.message ?: t.toString(), failed = true)
            throw t
        }
        // An exec that produced no shell result at all is still finished (not "running").
        AgentTerminalFeed.finish(runId, out)
        // I2 — TERMINAL BRIDGE: last agent-run command + output are attachable in chat
        com.codespace.ide.terminal.TerminalAiBridge.recordRun(command, out, guestWorkdir)
        // REFRESH-FAMILY: agent shell ops (mkdir/touch/rm/git) also change the tree
        com.codespace.ide.util.FsChangeNotifier.notifyChanged()
        return truncMark(out, 16000)  // CH06 (P4h): marked, not silent
    }

    /** CH02/Round 1: one canonical host identity for direct and staged file operations. */
    private fun resolveToolPath(context: Context, path: String): String =
        ToolPathResolver.resolve(context, path)

    private fun readFile(path: String, context: android.content.Context): String {
        // CH02: resolve either dialect first, then look up the staged overlay under
        // BOTH spellings (staging keys may predate this fix in either dialect).
        val resolvedPath = resolveToolPath(context, path)
        val staged = com.codespace.ide.chat.PendingChangesStore.overlayFor(path)
            ?: com.codespace.ide.chat.PendingChangesStore.overlayFor(resolvedPath)
        if (staged != null) {
            return truncMark("[staged pending version — not yet on disk]\n" + staged, 16000)  // CH06 (P4h)
        }
        val file = File(resolvedPath)
        if (!file.exists()) return "File not found: $path"
        if (file.isDirectory) return "Path is a directory: $path"
        if (file.length() > 500_000) return "File too large (${file.length()} bytes). Use run_command with head/tail."
        return truncMark(file.readText(), 16000)  // CH06 (P4h)
    }

    private fun writeFile(path: String, content: String, context: android.content.Context): String {
        val resolvedPath = resolveToolPath(context, path)
        // V0-GATE FIX (2026-10-05): the DIRECT (non-agent) write path also logs to
        // the chat channel now — agent-mode stages go through PendingChangesStore,
        // but any direct write was previously invisible in every chat-channel export.
        com.codespace.ide.diagnostics.AppOutputLog.log(
            "[chat] write_file DIRECT write: raw=" + path + " resolved=" + resolvedPath, "chat")
        val target = File(resolvedPath)
        // Section 5: capture the BEFORE image so the Files Changed row can show a real diff
        // and Undo can restore, then record only AFTER the write returned - a failed write
        // therefore never appears as a change.
        val before = try {
            if (target.exists() && target.isFile) target.readText() else null
        } catch (t: Throwable) {
            null
        }
        target.parentFile?.mkdirs()
        target.writeText(content)
        AgentWriteJournal.record(target.absolutePath, before, content)
        // REFRESH-FAMILY: AI file writes now reach the Explorer's reactive channel
        com.codespace.ide.util.FsChangeNotifier.notifyChanged()
        // U6-d: re-scanning is not enough - a write inside CLOSED folders stays invisible.
        // Reveal expands the ancestors (never opens the file, never collapses anything).
        com.codespace.ide.util.ExplorerReveal.requestFor(target.absolutePath)
        return "Wrote ${content.length} chars to ${target.absolutePath}"
    }

    /**
     * VS Code parity: replace_string_in_file. Reads the CURRENT file, so the match is checked
     * against what is on disk now rather than against something the model remembers, and hands
     * the write to writeFile so the before-image, the journal entry, Undo and the Explorer
     * refresh all behave exactly as they do for a full write.
     */
    private fun replaceStringInFile(
        path: String,
        oldText: String,
        newText: String,
        replaceAll: Boolean,
        context: android.content.Context,
    ): String {
        val target = File(resolveToolPath(context, path))
        if (!target.exists()) return "File not found: " + path
        if (!target.isFile) return "Not a file: " + path
        val current = try {
            target.readText()
        } catch (t: Throwable) {
            return "Could not read " + path + ": " + (t.message ?: t.javaClass.simpleName)
        }
        return when (val outcome = ReplaceText.apply(current, oldText, newText, replaceAll)) {
            is ReplaceOutcome.Refused -> outcome.reason
            is ReplaceOutcome.Ok -> {
                writeFile(target.absolutePath, outcome.updated, context)
                "Replaced " + outcome.count + " occurrence(s) in " + target.name +
                    " (" + outcome.updated.length + " chars now)"
            }
        }
    }

    /** VS Code parity: get_errors. Reads the live diagnostics the app already collects. */
    private fun getErrors(pathFilter: String?): String =
        DiagnosticReport.format(
            com.codespace.ide.diagnostics.DiagnosticManager.getAllDiagnostics(),
            pathFilter,
        )

    /** VS Code parity: create_directory. Idempotent, and never creates over a file. */
    private fun createDirectory(path: String, context: android.content.Context): String {
        val dir = File(resolveToolPath(context, path))
        if (dir.isFile) return "A file already exists at " + dir.absolutePath
        if (dir.isDirectory) return "Directory already exists: " + dir.absolutePath
        val ok = try {
            dir.mkdirs()
        } catch (t: Throwable) {
            false
        }
        if (!ok) return "Could not create " + dir.absolutePath
        com.codespace.ide.util.FsChangeNotifier.notifyChanged()
        return "Created " + dir.absolutePath
    }

    /** get_search_view_results: exactly what the search panel is showing, or that it is idle. */
    private fun searchViewResults(): String {
        val snap = SearchViewState.current()
            ?: return "The search panel has not run a search in this session. Run one there, or use grep_search."
        if (snap.hits.isEmpty()) return "The last search '" + snap.query + "' returned no results."
        val sb = StringBuilder("Search '" + snap.query + "': " + snap.hits.size + " result(s)")
        if (snap.truncated) sb.append(" (truncated to 200)")
        sb.append('\n')
        for (h in snap.hits.take(100)) {
            sb.append(h.file).append(':').append(h.line).append(' ')
                .append(h.text.trim().take(160)).append('\n')
        }
        return sb.toString().trim()
    }

    /**
     * github_repo. Reads GitHub over REST with the token the Source Control panel stored. The
     * repository defaults to the project's own origin remote, so it answers about the repo the
     * user is actually looking at rather than one the model guessed.
     */
    private suspend fun githubRepoTool(
        repoArg: String,
        what: String,
        pathArg: String,
        state: String,
        context: android.content.Context,
    ): String {
        val token = runCatching { SecureTokenStore(context).githubToken }.getOrNull()
        if (token.isNullOrBlank()) {
            return "GitHub is not connected. Connect it in the Source Control panel, then ask again."
        }
        val root = File(resolveToolPath(context, "."))
        val gitConfig = File(root, ".git/config")
        val fromRemote = if (gitConfig.exists()) {
            runCatching { GitHubRepoSupport.repoRefFromGitConfig(gitConfig.readText()) }.getOrNull()
        } else {
            null
        }
        val repo = repoArg.trim().ifBlank {
            fromRemote ?: return "No repo given, and this project has no GitHub origin remote to default to."
        }
        if (!repo.matches(Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+"))) return "Repository must be owner/name."
        val wanted = what.trim().ifBlank { "overview" }
        val requestPath = when (wanted) {
            "overview" -> "/repos/$repo"
            "issues" -> "/repos/$repo/issues?state=" + state.ifBlank { "open" } + "&per_page=20"
            "prs" -> "/repos/$repo/pulls?state=" + state.ifBlank { "open" } + "&per_page=20"
            "commits" -> "/repos/$repo/commits?per_page=15"
            "file" -> {
                if (pathArg.isBlank()) return "what=file needs a path."
                "/repos/$repo/contents/" + pathArg.trimStart('/')
            }
            else -> return "Unknown what='" + wanted + "'. Use overview, issues, prs, file, or commits."
        }
        return try {
            val response = githubGet(requestPath, token)
            if (response.first != 200) {
                return "GitHub answered " + response.first + " for " + requestPath + ": " + response.second.take(300)
            }
            when (wanted) {
                "overview" -> GitHubRepoSupport.renderOverview(response.second)
                "issues" -> GitHubRepoSupport.renderItems(response.second, "issues")
                "prs" -> GitHubRepoSupport.renderItems(response.second, "pull requests")
                "commits" -> renderCommits(response.second)
                else -> {
                    val obj = JSONObject(response.second)
                    val encoded = obj.optString("content").replace("\n", "")
                    val decoded = if (encoded.isBlank()) "" else
                        String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
                    GitHubRepoSupport.renderFile(pathArg, decoded)
                }
            }
        } catch (t: Throwable) {
            "GitHub request failed: " + (t.message ?: t.javaClass.simpleName)
        }
    }

    private fun renderCommits(json: String): String {
        val arr = runCatching { JSONArray(json) }.getOrNull()
            ?: return "GitHub returned something that is not a commit list."
        if (arr.length() == 0) return "No commits found."
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sha = o.optString("sha").take(7)
            val message = o.optJSONObject("commit")?.optString("message")?.lineSequence()?.firstOrNull().orEmpty()
            sb.append(sha).append(' ').append(message.take(120)).append('\n')
        }
        return sb.toString().trim()
    }

    private fun githubGet(path: String, token: String): Pair<Int, String> {
        val url = URL("https://api.github.com" + path)
        val conn = url.openConnection() as HttpsURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer " + token)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "vn-code")
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to body
        } finally {
            conn.disconnect()
        }
    }

    /**
     * view_image. VS Code returns image BYTES as a tool result part (viewImageTool.tsx:
     * LanguageModelDataPart.image), which a String return cannot do, so the bytes go to
     * ToolImageQueue and ride the next send as a WP8 attachment. Downscaled first: a phone photo
     * straight into a request is a payload the budget guard would reject anyway.
     */
    private fun viewImage(path: String, context: android.content.Context): String {
        val file = File(resolveToolPath(context, path))
        if (!file.exists() || !file.isFile) return "File not found: $path"
        val lower = file.name.lowercase()
        val mime = when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".bmp") -> "image/bmp"
            else -> return "$path is not an image (png, jpg, webp, gif, bmp). Use read_file for text."
        }
        if (file.length() > 8L * 1024L * 1024L) {
            return "Cannot view " + file.name + ": " + (file.length() / (1024 * 1024)) + "MB exceeds the 8MB limit."
        }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return "Could not decode $path as an image."
        val sample = maxOf(1, maxOf(width, height) / 1568)
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: return "Could not decode $path as an image."
        val bytes = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bytes)
        val base64 = android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
        val label = file.name + " (" + bitmap.width + "x" + bitmap.height + ")"
        val queued = ToolImageQueue.enqueue(ToolImageQueue.Pending(file.name, "image/jpeg", base64, label))
        if (!queued) {
            return "Not attached: " + ToolImageQueue.MAX_PENDING + " image(s) are already waiting for the next request."
        }
        return "Image " + label + " attached to your next request. It needs a model that accepts image " +
            "input; with a text-only model the provider rejects the request rather than the image " +
            "disappearing quietly."
    }

    /** tool_search: search this registry, so the model can find a tool it was not handed. */
    private fun toolSearch(query: String): String {
        val q = query.trim()
        val all = com.codespace.ide.chat.panel.ToolRegistry.names()
        if (q.isEmpty()) return "Give a query. " + all.size + " tools are registered."
        val hits = all.mapNotNull { name ->
            val spec = com.codespace.ide.chat.panel.ToolRegistry.spec(name) ?: return@mapNotNull null
            val text = spec.name + " " + spec.displayName + " " + spec.description
            if (!text.contains(q, ignoreCase = true)) return@mapNotNull null
            val modeText = if (spec.modes.isEmpty()) {
                "not offered: " + (spec.hiddenReason ?: "no reason recorded")
            } else {
                "modes " + spec.modes.joinToString("/") { it.name }
            }
            spec.name + " - " + spec.displayName + " (" + modeText + ")"
        }
        if (hits.isEmpty()) return "No tool matches '" + q + "'."
        return hits.take(20).joinToString("\n")
    }

    /**
     * runTests. Uses the real pipeline: discover, then run each leaf test through
     * TestRunManager, which enforces one run at a time (two gradle daemons produce garbage).
     */
    private suspend fun runTestsTool(query: String?, limit: Int, context: android.content.Context): String {
        val root = File(resolveToolPath(context, "."))
        if (!root.exists()) return "No project root is open, so there is nothing to test."
        val scan = try {
            TestDiscoveryService.scanProject(context, root)
        } catch (t: Throwable) {
            return "Test discovery failed: " + (t.message ?: "error")
        }
        if (scan.testCount == 0) {
            return "No tests discovered in " + root.name + " (" + scan.filesWithTests + " file(s) scanned)."
        }
        val needle = query?.trim().orEmpty()
        val leaves = TestStore.leafItems().filter { item ->
            needle.isEmpty() || item.testId.contains(needle, ignoreCase = true) ||
                item.filePath.contains(needle, ignoreCase = true)
        }
        if (leaves.isEmpty()) {
            return "No discovered test matches '" + needle + "'. " + scan.testCount + " test(s) exist."
        }
        val capped = leaves.take(limit.coerceIn(1, 25))
        val results = ArrayList<TestRunManager.TestRunResult>()
        for (item in capped) {
            results.add(
                TestRunManager.runTest(
                    context = context,
                    projectRoot = root.absolutePath,
                    hostFilePath = item.filePath,
                    language = item.language,
                    testId = item.testId,
                    suite = item.suite,
                    lineIndex = item.lineIndex,
                ),
            )
        }
        val passed = results.count { it.status.name == "PASSED" }
        val failed = results.filter { it.status.name == "FAILED" || it.status.name == "ERRORED" }
        val sb = StringBuilder()
        sb.append("Ran ").append(results.size).append(" of ").append(leaves.size)
            .append(" test(s): ").append(passed).append(" passed, ").append(failed.size).append(" failed")
        if (leaves.size > capped.size) sb.append(" [").append(leaves.size - capped.size).append(" not run: limit]")
        sb.append('\n')
        for (r in failed) {
            sb.append("- ").append(r.testId)
            if (r.exitCode != null) sb.append(" (exit ").append(r.exitCode).append(')')
            sb.append('\n')
        }
        val messages = TestResultStore.items.value.values
            .filter { it.computedState.name == "FAILED" || it.computedState.name == "ERRORED" }
            .mapNotNull { item -> item.message?.let { item.testId + ": " + it.take(300) } }
        if (messages.isNotEmpty()) {
            sb.append("Failures:\n")
            for (m in messages.take(10)) sb.append(m).append('\n')
        }
        return sb.toString().trim()
    }

    /** test_search: query the discovered-test index, the same one the Test explorer reads. */
    private suspend fun testSearch(query: String, context: android.content.Context): String {
        val needle = query.trim()
        if (needle.isEmpty()) return "test_search needs a query."
        if (TestStore.leafItems().isEmpty()) {
            val root = File(resolveToolPath(context, "."))
            try {
                TestDiscoveryService.scanProject(context, root)
            } catch (t: Throwable) {
                return "Test discovery failed: " + (t.message ?: "error")
            }
        }
        val hits = TestStore.leafItems().filter { item ->
            item.testId.contains(needle, ignoreCase = true) || item.filePath.contains(needle, ignoreCase = true)
        }
        if (hits.isEmpty()) return "No discovered test matches '" + needle + "'."
        return hits.take(40).joinToString("\n") { item ->
            item.filePath + ":" + (item.lineIndex + 1) + " " + item.testId
        }
    }

    /** test_failure: what actually failed in the last run, message included. */
    private fun testFailures(): String {
        val run = TestResultStore.latestRun.value
        val bad = TestResultStore.items.value.values
            .filter { it.computedState.name == "FAILED" || it.computedState.name == "ERRORED" }
        if (bad.isEmpty()) {
            return if (run == null) "No test run has been recorded yet." else "The last test run has no failures."
        }
        val sb = StringBuilder()
        if (run != null) sb.append("Last run: ").append(run.passed).append(" passed, ")
            .append(run.failed).append(" failed")
        sb.append('\n')
        for (item in bad.take(20)) {
            sb.append("- ").append(item.testId).append(" [").append(item.computedState.name).append("]")
            item.message?.let { sb.append(": ").append(it.take(300)) }
            sb.append('\n')
        }
        return sb.toString().trim()
    }

    /** run_task / create_and_run_task: run a catalogue task. There is no ad-hoc task model yet. */
    private suspend fun runTaskTool(requested: String, context: android.content.Context): String {
        val wanted = requested.trim()
        if (wanted.isEmpty()) {
            return "Which task? Available: " +
                TaskRunner.CATALOGUE.joinToString(", ") { it.id.name + " (" + it.displayName + ")" }
        }
        val task = TaskRunner.CATALOGUE.firstOrNull {
            it.id.name.equals(wanted, ignoreCase = true) || it.displayName.equals(wanted, ignoreCase = true)
        } ?: return "No such task: '" + wanted + "'. Available: " +
            TaskRunner.CATALOGUE.joinToString(", ") { it.id.name }
        val root = File(resolveToolPath(context, "."))
        if (!root.exists()) return "No project root is open."
        val result = try {
            TaskRunner.run(context, task.id, root.absolutePath)
        } catch (t: Throwable) {
            return "Task " + task.id.name + " failed to start: " + (t.message ?: "error")
        }
        return task.id.name + " " + result.status.name + " in " + (result.durationMs / 1000) +
            "s (errors " + result.errorCount + ", warnings " + result.warningCount + ")\n" +
            result.output.takeLast(2000)
    }

    /** get_task_output: the last result the task tab is showing for a task. */
    private fun taskOutput(requested: String): String {
        val ids = TaskRunner.CATALOGUE.map { it.id }
        val wanted = requested.trim()
        val selected = if (wanted.isEmpty()) {
            ids.filter { TaskRunner.stateFor(it).result != null }
        } else {
            ids.filter { it.name.equals(wanted, ignoreCase = true) }
        }
        if (selected.isEmpty()) {
            return if (wanted.isEmpty()) {
                "No task has run in this session. Available: " + ids.joinToString(", ") { it.name }
            } else {
                "No such task: '" + wanted + "'. Available: " + ids.joinToString(", ") { it.name }
            }
        }
        val sb = StringBuilder()
        for (id in selected) {
            val run = TaskRunner.stateFor(id)
            sb.append(id.name).append(" -> ").append(run.state.name).append('\n')
            val output = run.result?.output
            if (output.isNullOrBlank()) sb.append("(no output recorded)\n")
            else sb.append(output.takeLast(3000)).append('\n')
        }
        return sb.toString().trim()
    }

    /** get_project_setup_info: read the root markers and say how to build, test and run. */
    private fun projectSetupInfo(context: android.content.Context): String {
        val root = File(resolveToolPath(context, "."))
        if (!root.exists()) return "No project root is open."
        val names = root.list()?.toList().orEmpty()
        return ProjectSetup.describe(names)
    }

    /** VS Code's apply_patch: strict, verified, all or nothing. */
    private fun applyPatch(path: String, patch: String, context: android.content.Context): String {
        val target = File(resolveToolPath(context, path))
        if (!target.exists() || !target.isFile) return "File not found: $path"
        val current = try {
            target.readText()
        } catch (t: Throwable) {
            return "Could not read $path: " + (t.message ?: "error")
        }
        return when (val result = PatchApplier.apply(current, patch)) {
            is EditResult.Refused -> "Patch not applied: " + result.reason + " Nothing was changed."
            is EditResult.Ok -> {
                writeFile(target.absolutePath, result.text, context)
                result.detail + " in " + target.name + "."
            }
        }
    }

    /** VS Code's manage_todo_list. Empty argument reads the board back. */
    private fun manageTodoList(rawTodos: String, context: android.content.Context): String {
        val store = File(resolveToolPath(context, ".codespace/todos.json"))
        if (rawTodos.isBlank()) {
            return TodoBoard.render(if (store.exists()) store.readText() else null)
        }
        return when (val result = TodoBoard.write(rawTodos)) {
            is EditResult.Refused -> "Todo list not saved: " + result.reason
            is EditResult.Ok -> {
                store.parentFile?.mkdirs()
                store.writeText(result.text)
                result.detail + "\n" + TodoBoard.render(result.text)
            }
        }
    }

    private fun createNotebook(path: String, cellsJson: String, context: android.content.Context): String {
        val target = File(resolveToolPath(context, path))
        if (target.exists()) return "File already exists: $path"
        val cells = ArrayList<Pair<String, String>>()
        if (cellsJson.isNotBlank()) {
            try {
                val arr = org.json.JSONArray(cellsJson)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: return "cells entry " + i + " is not an object."
                    cells.add(o.optString("cell_type", "code") to o.optString("source"))
                }
            } catch (t: Throwable) {
                return "cells must be a JSON array of {cell_type, source}."
            }
        }
        return try {
            target.parentFile?.mkdirs()
            target.writeText(NotebookModel.newNotebook(cells))
            "Created " + path + " with " + (if (cells.isEmpty()) 1 else cells.size) + " cell(s)."
        } catch (t: Throwable) {
            "Could not create $path: " + (t.message ?: "error")
        }
    }

    private fun notebookSummary(path: String, context: android.content.Context): String {
        val target = File(resolveToolPath(context, path))
        if (!target.exists()) return "File not found: $path"
        val text = try {
            target.readText()
        } catch (t: Throwable) {
            return "Could not read $path: " + (t.message ?: "error")
        }
        return when (val r = NotebookModel.summary(text)) {
            is EditResult.Refused -> r.reason
            is EditResult.Ok -> r.text
        }
    }

    private fun notebookCellOutput(path: String, index: Int, context: android.content.Context): String {
        val target = File(resolveToolPath(context, path))
        if (!target.exists()) return "File not found: $path"
        val text = try {
            target.readText()
        } catch (t: Throwable) {
            return "Could not read $path: " + (t.message ?: "error")
        }
        return when (val r = NotebookModel.outputsOf(text, index)) {
            is EditResult.Refused -> r.reason
            is EditResult.Ok -> r.text
        }
    }

    private fun editNotebook(
        path: String,
        index: Int,
        cellType: String,
        source: String,
        clearOutputs: Boolean,
        context: android.content.Context,
    ): String {
        val target = File(resolveToolPath(context, path))
        if (!target.exists()) return "File not found: $path"
        val current = try {
            target.readText()
        } catch (t: Throwable) {
            return "Could not read $path: " + (t.message ?: "error")
        }
        if (!NotebookModel.isNotebook(current)) return "$path is not an .ipynb notebook."
        val result = if (clearOutputs) {
            NotebookModel.clearOutputs(current, index)
        } else {
            NotebookModel.editCell(current, index, cellType, source)
        }
        return when (result) {
            is EditResult.Refused -> "Notebook not changed: " + result.reason
            is EditResult.Ok -> {
                writeFile(target.absolutePath, result.text, context)
                result.detail
            }
        }
    }

    /**
     * run_notebook_cell. There is no kernel to talk to, so the cell is written to a temporary
     * python file beside the notebook and run with the system python3. It reports exactly that
     * rather than pretending a kernel session exists.
     */
    private fun runNotebookCell(path: String, index: Int, context: android.content.Context): String {
        val target = File(resolveToolPath(context, path))
        if (!target.exists()) return "File not found: $path"
        val text = try {
            target.readText()
        } catch (t: Throwable) {
            return "Could not read $path: " + (t.message ?: "error")
        }
        val cells = NotebookModel.cells(text) ?: return "$path is not a notebook."
        val cell = cells.firstOrNull { it.index == index } ?: return "Cell " + index + " does not exist."
        if (cell.kind != "code") return "Cell " + index + " is markdown, so there is nothing to run."
        if (cell.source.isBlank()) return "Cell " + index + " is empty."
        val script = File(target.parentFile, ".nb_cell_" + index + ".py")
        return try {
            script.writeText(cell.source)
            val out = runCommand("python3 " + script.name, target.parentFile?.absolutePath, context)
            "Ran cell " + index + " with python3 (no kernel session is kept):\n" + out
        } catch (t: Throwable) {
            "Could not run cell " + index + ": " + (t.message ?: "error")
        } finally {
            script.delete()
        }
    }

    /** get_terminal_output: the agent's own command record, not the user's interactive shell. */
    private fun terminalOutput(runs: Int): String {
        val wanted = runs.coerceIn(1, 10)
        val all = AgentTerminalFeed.runs
        if (all.isEmpty()) return "The agent has not run any command in this session."
        val slice = all.takeLast(wanted)
        val sb = StringBuilder("[agent command record, not the interactive terminal]\n")
        for (r in slice) {
            sb.append('$').append(' ').append(r.command).append(" -> ").append(r.status.name)
            if (r.exitCode != null) sb.append(" (exit ").append(r.exitCode).append(')')
            sb.append('\n')
            val out = r.output?.trim()
            if (!out.isNullOrEmpty()) sb.append(out.take(2000)).append('\n')
        }
        return sb.toString().trim()
    }

    private fun terminalLastCommand(): String {
        val last = AgentTerminalFeed.runs.lastOrNull()
            ?: return "The agent has not run any command in this session."
        return "Last agent command: " + last.command + " (" + last.status.name +
            (last.exitCode?.let { ", exit " + it } ?: "") + ")"
    }

    /** VS Code's create_file: refuses to clobber, because "create" that overwrites is a trap. */
    private fun createFile(path: String, content: String, context: android.content.Context): String {
        val target = File(resolveToolPath(context, path))
        if (target.exists()) return "File already exists: $path. Use write_file to replace it."
        return writeFile(path, content, context)
    }

    /** VS Code's multi_replace_string_in_file: several edits, applied all or nothing. */
    private fun multiReplaceFile(
        path: String,
        replacementsJson: String,
        replaceAll: Boolean,
        context: android.content.Context,
    ): String {
        val target = File(resolveToolPath(context, path))
        if (!target.exists() || !target.isFile) return "File not found: $path"
        val current = try {
            target.readText()
        } catch (t: Throwable) {
            return "Could not read $path: " + (t.message ?: "error")
        }
        val pairs = ArrayList<Pair<String, String>>()
        try {
            val arr = org.json.JSONArray(replacementsJson)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: return "Replacement " + (i + 1) + " is not an object."
                val o = item.optString("old_string")
                val n = item.optString("new_string")
                if (o.isEmpty()) return "Replacement " + (i + 1) + " has an empty old_string."
                pairs.add(o to n)
            }
        } catch (t: Throwable) {
            return "replacements must be a JSON array of {old_string,new_string} objects."
        }
        return when (val outcome = RepoSearch.replaceMany(current, pairs, replaceAll)) {
            is ReplaceOutcome.Refused -> outcome.reason
            is ReplaceOutcome.Ok -> {
                writeFile(target.absolutePath, outcome.updated, context)
                "Replaced " + outcome.count + " occurrence(s) across " + pairs.size +
                    " edit(s) in " + target.name + "."
            }
        }
    }

    /** Bounded project walk shared by the search-shaped tools. */
    private fun walkTextFiles(
        root: File,
        contentPattern: Regex?,
        nameNeedle: String?,
        context: android.content.Context,
    ): Pair<List<Pair<String, String>>, Int> {
        val files = ArrayList<Pair<String, String>>()
        var skipped = 0
        val base = root.absolutePath
        val walked = root.walkTopDown().take(RepoSearch.MAX_FILES + 1).toList()
        for (f in walked) {
            if (!f.isFile) continue
            if (files.size >= RepoSearch.MAX_FILES) break
            val name = f.name
            if (nameNeedle != null && !name.lowercase().contains(nameNeedle)) continue
            if (f.length() > 512L * 1024L) {
                skipped++
                continue
            }
            val text = try {
                f.readText()
            } catch (t: Throwable) {
                skipped++
                continue
            }
            if (text.indexOf('\u0000') >= 0) {
                skipped++
                continue
            }
            if (contentPattern != null && !contentPattern.containsMatchIn(text)) continue
            files.add(f.absolutePath.removePrefix(base).trimStart('/') to text)
        }
        return files to skipped
    }

    /** VS Code's file_search: find files whose NAME matches, no content scan. */
    private fun findFiles(query: String, path: String?, context: android.content.Context): String {
        val root = File(resolveToolPath(context, path ?: "."))
        if (!root.exists()) return "Path not found: " + (path ?: ".")
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return "file_search needs a non-empty query."
        val (hits, skipped) = walkTextFiles(root, null, needle, context)
        if (hits.isEmpty()) return "No file names match '" + query + "'" + if (skipped > 0) " (" + skipped + " unreadable files skipped)." else "."
        val shown = hits.take(RepoSearch.MAX_HITS).joinToString("\n") { it.first }
        val cut = if (hits.size > RepoSearch.MAX_HITS) "\n[... " + (hits.size - RepoSearch.MAX_HITS) + " more]" else ""
        return shown + cut
    }

    /**
     * semantic_search. HONEST: this is keyword matching over file text, not an embedding
     * index. VS Code's is a maintained semantic index; ours says what it is so the model
     * does not tell the user we searched by meaning.
     */
    private fun semanticSearch(query: String, path: String?, context: android.content.Context): String {
        val root = File(resolveToolPath(context, path ?: "."))
        if (!root.exists()) return "Path not found: " + (path ?: ".")
        val terms = query.lowercase().split(Regex("[^a-z0-9_]+")).filter { it.length >= 3 }.take(6)
        if (terms.isEmpty()) return "semantic_search needs a query with at least one word of 3+ characters."
        val pattern = try {
            Regex("(?i)" + terms.joinToString("|") { Regex.escape(it) })
        } catch (t: Throwable) {
            return "Could not build a search pattern from that query."
        }
        val (files, skipped) = walkTextFiles(root, pattern, null, context)
        val scored = files.map { (rel, text) ->
            val score = terms.count { text.contains(it, ignoreCase = true) }
            Triple(rel, score, text)
        }.sortedByDescending { it.second }.take(40)
        if (scored.isEmpty()) return "No files mention any of: " + terms.joinToString(", ") + "."
        val sb = StringBuilder()
        sb.append("[keyword search, not a semantic index] Best matches for '").append(query).append("':\n")
        for ((rel, score, text) in scored) {
            val lineNo = text.lineSequence().indexOfFirst { line ->
                terms.any { line.contains(it, ignoreCase = true) }
            } + 1
            val snippet = text.lineSequence().drop((lineNo - 1).coerceAtLeast(0)).firstOrNull()?.trim()?.take(140).orEmpty()
            sb.append(rel).append(':').append(lineNo).append(" (").append(score).append(" terms) ").append(snippet).append('\n')
        }
        if (skipped > 0) sb.append("[").append(skipped).append(" unreadable or binary files skipped]\n")
        return sb.toString().trim()
    }

    /** VS Code's search_workspace_symbols, text-based, and labelled as such. */
    private fun workspaceSymbols(query: String, path: String?, context: android.content.Context): String {
        val root = File(resolveToolPath(context, path ?: "."))
        if (!root.exists()) return "Path not found: " + (path ?: ".")
        val (files, _) = walkTextFiles(root, null, null, context)
        val hits = RepoSearch.symbols(files, query)
        if (hits.isEmpty()) return "No declarations matching '" + query + "' (text-based symbol scan)."
        return "[text-based symbol scan, not language-server intelligence]\n" + hits.joinToString("\n")
    }

    /** VS Code's read_project_structure, bounded by depth and entry count. */
    private fun projectStructure(path: String?, depth: Int, context: android.content.Context): String {
        val root = File(resolveToolPath(context, path ?: "."))
        if (!root.exists()) return "Path not found: " + (path ?: ".")
        val paths = ArrayList<String>()
        for (f in root.walkTopDown().maxDepth(depth.coerceIn(1, 6)).take(600)) {
            if (f.absolutePath == root.absolutePath) continue
            val rel = f.absolutePath.removePrefix(root.absolutePath).trimStart('/')
            if (rel.isEmpty()) continue
            paths.add(if (f.isDirectory) rel + "/" else rel)
        }
        return RepoSearch.structure(paths, depth)
    }

    private fun listFiles(path: String, context: android.content.Context): String {
        val dir = File(resolveToolPath(context, path))
        if (!dir.exists()) return "Directory not found: $path"
        if (!dir.isDirectory) return "Not a directory: $path"
        val files = dir.listFiles()?.sortedBy { it.name } ?: return "Empty directory"
        return truncMark(files.joinToString("\n") { f ->
            val type = if (f.isDirectory) "[DIR] " else "      "
            "$type${f.name} (${f.length()} bytes)"
        }, 4000)  // CH06 (P4h)
    }

    private fun searchFiles(path: String, pattern: String, context: android.content.Context): String {
        val dir = File(resolveToolPath(context, path))
        if (!dir.exists()) return "Directory not found: $path"
        val results = mutableListOf<String>()
        // CH06 (P4h): the walk caps are now HONEST — the old take(500) stopped
        // silently (matches looked complete) and large files were skipped with
        // no note. Cap hit + skipped-file count now ride the result.
        val walked = dir.walkTopDown().take(501).toList()
        val fileCapHit = walked.size > 500
        var skippedLarge = 0
        walked.take(500).forEach { f ->
            if (!f.isFile) return@forEach
            // CH06 (P4h): over-cap files are counted and SKIPPED with an honest
            // note in the result (was: silently invisible).
            if (f.length() >= 100_000) { skippedLarge++; return@forEach }
            try {
                // R6-PENDING-EDITS: staged files search the STAGED content
                val stagedContent = com.codespace.ide.chat.PendingChangesStore.overlayFor(f.absolutePath)
                if (stagedContent != null) {
                    stagedContent.lineSequence().forEachIndexed { i, line ->
                        if (line.contains(pattern, ignoreCase = true))
                            results.add("${f.absolutePath}:${i + 1}: ${line.trim().take(200)}")
                    }
                } else {
                    f.useLines { lines ->
                        lines.forEachIndexed { i, line ->
                            if (line.contains(pattern, ignoreCase = true))
                                results.add("${f.absolutePath}:${i + 1}: ${line.trim().take(200)}")
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        if (results.isEmpty()) {
            val notes = buildList {
                if (fileCapHit) add("the 500-file scan cap was hit — results are PARTIAL; narrow the path")
                if (skippedLarge > 0) add("$skippedLarge file(s) over 100KB were skipped")
            }
            return if (notes.isEmpty()) "No matches for '$pattern' in $path"
                   else "No matches for '$pattern' in $path (but: " + notes.joinToString("; ") + ")"
        }
        var joined = results.joinToString("\n")
        if (fileCapHit) joined += "\n[NOTE: 500-file scan cap hit — matches may be MISSING beyond it; narrow the path]"
        if (skippedLarge > 0) joined += "\n[NOTE: $skippedLarge file(s) over 100KB were skipped by the search cap]"
        return truncMark(joined, 4000)  // CH06 (P4h)
    }

    // ── Git (full access) ────────────────────────────────────────────────
    // git only exists inside the Ubuntu proot rootfs, never on the bare Android host — must
    // route through ProotInstaller.execOnce, same as runCommand above. repoDir/repo.absolutePath
    // here is expected to be a guest-side path (e.g. "/root/myproject").
    private fun gitRun(vararg args: String, repo: File, context: Context): String {
        // P-SCM-11: Route through GitCommandExecutor for safe.directory + auth + structured errors
        val token = SecureTokenStore(context).githubToken
        val result = com.codespace.ide.scm.GitCommandExecutor.run(
            context = context,
            args = args.toList(),
            workdir = repo.path,
            token = token,
        )
        return when (result) {
            is com.codespace.ide.scm.GitResult.Ok -> result.output
            is com.codespace.ide.scm.GitResult.Err -> result.error.message
        }
    }

    private fun getRepoDir(repoDir: String?, context: Context): File =
        File(repoDir ?: "/root")

    private fun gitCommitPush(message: String, repoDir: String?, context: Context): String {
        val repo = getRepoDir(repoDir, context)
        if (!ProotInstaller.guestToHostPath(context, "${repo.path}/.git").exists()) return "Not a git repository: ${repo.path}"
        val add = gitRun("add", "-A", repo = repo, context = context)
        val commit = gitRun("commit", "-m", message, repo = repo, context = context)
        val push = gitRun("push", repo = repo, context = context)
        return truncMark("git add: $add\ngit commit: $commit\ngit push: $push", 4000)  // CH06 (P4h)
    }

    private fun gitPullRebase(repoDir: String?, context: Context): String {
        val repo = getRepoDir(repoDir, context)
        if (!ProotInstaller.guestToHostPath(context, "${repo.path}/.git").exists()) return "Not a git repository: ${repo.path}"
        return "git pull --rebase: ${gitRun("pull", "--rebase", repo = repo, context = context)}".take(4000)
    }

    private fun gitBranch(action: String, name: String, repoDir: String?, context: Context): String {
        val repo = getRepoDir(repoDir, context)
        if (!ProotInstaller.guestToHostPath(context, "${repo.path}/.git").exists()) return "Not a git repository: ${repo.path}"
        return when (action) {
            "create" -> { gitRun("checkout", "-b", name, repo = repo, context = context); "Created and switched to branch '$name'" }
            "switch" -> { gitRun("checkout", name, repo = repo, context = context); "Switched to branch '$name'" }
            "list" -> gitRun("branch", "-a", repo = repo, context = context).take(4000)
            "merge" -> "Merged '$name': ${gitRun("merge", name, repo = repo, context = context)}".take(4000)
            "delete" -> { gitRun("branch", "-d", name, repo = repo, context = context); "Deleted branch '$name'" }
            else -> "Unknown branch action: $action. Use: create, switch, list, merge, delete"
        }
    }

    private fun gitStatus(repoDir: String?, context: Context): String {
        val repo = getRepoDir(repoDir, context)
        if (!ProotInstaller.guestToHostPath(context, "${repo.path}/.git").exists()) return "Not a git repository: ${repo.path}"
        return gitRun("status", "--short", repo = repo, context = context).take(4000)
    }

    private fun gitDiff(staged: Boolean, repoDir: String?, context: Context): String {
        val repo = getRepoDir(repoDir, context)
        if (!ProotInstaller.guestToHostPath(context, "${repo.path}/.git").exists()) return "Not a git repository: ${repo.path}"
        val args = if (staged) arrayOf("diff", "--cached") else arrayOf("diff")
        return gitRun(*args, repo = repo, context = context).take(4000)
    }

    private fun saveSecret(key: String, value: String, context: Context): String {
        SecureTokenStore(context).setAiKey(key, value)
        return "Secret '$key' saved securely (encrypted, Keystore-backed)."
    }

    private fun getSecret(key: String, context: Context): String {
        val v = SecureTokenStore(context).aiKey(key)
        return v ?: "No secret found for key '$key'"
    }

    private val SECRET_PATTERNS = listOf(
        Regex("AKIA[0-9A-Z]{16}") to "AWS Access Key",
        Regex("gh[pousr]_[A-Za-z0-9]{36,}") to "GitHub Token",
        Regex("AIza[0-9A-Za-z_\\-]{35}") to "Google API Key",
        // GEMINI-AUTH-KEY (2026-09-11): new AI Studio authorization keys.
        Regex("AQ\\.[0-9A-Za-z_\\-]{25,}") to "Google Gemini Auth Key",
        Regex("ya29\\.[0-9A-Za-z_\\-]+") to "Google OAuth Token",
        Regex("sk-[A-Za-z0-9]{48}") to "OpenAI API Key",
        Regex("sk-ant-[A-Za-z0-9_\\-]{95}") to "Anthropic API Key",
        Regex("xox[baprs]-[A-Za-z0-9-]+") to "Slack Token",
        Regex("(sk|pk|rk)_(live|test)_[A-Za-z0-9]{24,}") to "Stripe Key",
        Regex("(?i)(api[_-]?key|secret[_-]?key|auth[_-]?token)\\s*[=:]\\s*['\"]?[A-Za-z0-9_\\-]{20,}['\"]?") to "Generic API Key",
        Regex("-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----") to "Private Key",
        Regex("eyJ[A-Za-z0-9_\\-]+\\.eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+") to "JWT Token",
    )

    private fun detectSecrets(text: String?, path: String?): String {
        val content = when {
            text != null -> text
            path != null -> {
                val f = File(path)
                if (!f.exists()) return "File not found: $path"
                if (f.length() > 500_000) return "File too large, use run_command with grep"
                f.readText()
            }
            else -> return "Provide either 'text' or 'path' argument"
        }
        val findings = mutableListOf<String>()
        for ((pattern, label) in SECRET_PATTERNS) {
            for (match in pattern.findAll(content)) {
                val v = match.value
                val masked = if (v.length > 12) v.take(4) + "..." + v.takeLast(4) else "***"
                findings.add("DETECTED $label: $masked")
            }
        }
        return if (findings.isEmpty()) "No secrets detected."
               else "Found ${findings.size} potential secret(s):\n" + findings.joinToString("\n")
    }

    // ── Web ──────────────────────────────────────────────────────────────
    private fun webFetch(url: String, headersJson: String?): String {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000; conn.readTimeout = 30000
            headersJson?.let { JSONObject(it).let { h -> for (k in h.keys()) conn.setRequestProperty(k, h.getString(k)) } }
            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
                       else conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
            "HTTP $code\n${body.take(8000)}"
        } catch (e: Exception) { "Failed to fetch $url: ${e.message}" }
    }

    private fun webSearch(query: String): String {
        return try {
            val eq = java.net.URLEncoder.encode(query, "UTF-8")
            val conn = URL("https://html.duckduckgo.com/html/?q=$eq").openConnection() as HttpURLConnection
            conn.connectTimeout = 10000; conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            val results = mutableListOf<String>()
            Regex("""<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(html).take(5).forEach { m ->
                    val title = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                    results.add("$title - ${m.groupValues[1]}")
                }
            if (results.isEmpty()) "No results for '$query'." else "Results:\n" + results.joinToString("\n")
        } catch (e: Exception) { "Search failed: ${e.message}" }
    }

    // ── Media ────────────────────────────────────────────────────────────
    private fun uploadFile(path: String, url: String): String {
        return try {
            val f = File(path)
            if (!f.exists()) return "File not found: $path"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            f.inputStream().use { i -> conn.outputStream.use { o -> i.copyTo(o) } }
            val code = conn.responseCode
            "Upload HTTP $code"
        } catch (e: Exception) { "Upload failed: ${e.message}" }
    }

    // ── Package management ───────────────────────────────────────────────
    // fix(#13 hard-bucket, 2026-07-08): npm/pip3/apt binaries only exist inside the
    // Ubuntu proot rootfs — they are NOT on the bare Android host PATH. This tool was
    // the one remaining spot still calling a raw host ProcessBuilder for npm/pip (same
    // root cause runCommand()/git tools elsewhere in this file already fixed — see their
    // comments), so every AI-driven "install this package" request was silently failing
    // (ProcessBuilder throws "No such file or directory", uncaught here) for npm/pip, and
    // for apt didn't even attempt it — it just told the AI to ask a human to type it into
    // a terminal manually, defeating the entire point of an AI package-install tool. Now
    // routes all three through ProotInstaller.execOnce, the same bridge already proven
    // correct for run_command/git — so the SAME 31 tools genuinely work identically for
    // both the in-app chat panel and any terminal-launched AI (Claude Code, Ollama CLI).
    private fun installPackage(manager: String, pkg: String, projectDir: String?, context: Context): String {
        val command = when (manager) {
            "npm" -> "npm install $pkg"
            "pip" -> "pip3 install --break-system-packages $pkg"
            "apt" -> "apt-get install -y $pkg"
            else -> return "Unknown manager: $manager. Use: npm, pip, apt"
        }
        return truncMark(ProotInstaller.execOnce(context, command, projectDir), 16000)
    }
}
