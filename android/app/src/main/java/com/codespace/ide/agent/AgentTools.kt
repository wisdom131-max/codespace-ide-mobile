package com.codespace.ide.agent

import android.content.Context
import com.codespace.ide.data.SecureTokenStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AgentTools — gives the in-app AI agent real capabilities.
 * Mirrors what a Superagent can do: file ops, command execution, git,
 * secrets, and more. Used by CopilotChatPanelOverlay in AGENT mode.
 *
 * Tool-calling uses a text-based protocol (works with ANY Ollama model,
 * not just ones with native tool-calling support):
 *   <tool>{"name":"run_command","arguments":{"command":"ls -la"}}</tool>
 *
 * The agent loop parses these, executes via AgentTools, and feeds
 * results back to the model until it produces a final text answer.
 */
object AgentTools {

    // ── Tool definitions (sent to the model in the system prompt) ────────
    const val TOOLS_DESCRIPTION = """
You have access to these tools for acting on the user's environment:

1. run_command — Run a shell command (Android shell, has ls, cat, mkdir, rm, cp, grep, find, git, etc.)
   <tool>{"name":"run_command","arguments":{"command":"git status"}}</tool>

2. read_file — Read the contents of a file
   <tool>{"name":"read_file","arguments":{"path":"/data/data/com.codespace.ide/files/project/src/Main.kt"}}</tool>

3. write_file — Write content to a file (creates or overwrites)
   <tool>{"name":"write_file","arguments":{"path":"/path/to/file.txt","content":"file content here"}}</tool>

4. list_files — List files in a directory
   <tool>{"name":"list_files","arguments":{"path":"/data/data/com.codespace.ide/files"}}</tool>

5. search_files — Search for a text pattern in files under a directory
   <tool>{"name":"search_files","arguments":{"path":"/path/to/dir","pattern":"TODO"}}</tool>

6. git_commit_push — Stage all changes, commit, and push to GitHub
   <tool>{"name":"git_commit_push","arguments":{"message":"fix: update main activity","repo_dir":"/path/to/repo"}}</tool>

7. render_remotion — Render a Remotion video composition (runs in terminal)
   <tool>{"name":"render_remotion","arguments":{"composition":"MyVideo","output":"/path/to/out.mp4","project_dir":"/path/to/remotion-project"}}</tool>

8. save_secret — Store a secret value securely (encrypted)
   <tool>{"name":"save_secret","arguments":{"key":"OPENAI_API_KEY","value":"sk-..."}}</tool>

9. get_secret — Retrieve a stored secret
   <tool>{"name":"get_secret","arguments":{"key":"OPENAI_API_KEY"}}</tool>

10. web_fetch — Fetch content from a URL
    <tool>{"name":"web_fetch","arguments":{"url":"https://api.github.com/repos/wisdom131-max/codespace-ide-mobile"}}</tool>

When you want to use a tool, output the <tool>...</tool> tag. Wait for the result.
You can use multiple tools in sequence. When done, give a final summary.
"""

    // ── Regex to extract tool calls from model output ────────────────────
    private val TOOL_REGEX = Regex("""<tool>(\{.*?})</tool>""", RegexOption.DOT_MATCHES_ALL)

    /** Parse tool calls from model output. Returns list of (name, args) pairs. */
    fun parseToolCalls(text: String): List<Pair<String, JSONObject>> {
        return TOOL_REGEX.findAll(text).map { match ->
            try {
                val json = JSONObject(match.groupValues[1])
                val name = json.getString("name")
                val args = json.optJSONObject("arguments") ?: JSONObject()
                name to args
            } catch (_: Exception) {
                "error" to JSONObject().put("message", "Failed to parse tool call")
            }
        }.toList()
    }

    /** Check if the model output contains tool calls. */
    fun hasToolCalls(text: String): Boolean = TOOL_REGEX.containsMatchIn(text)

    /** Strip tool call tags from text (for display). */
    fun stripToolCalls(text: String): String =
        text.replace(TOOL_REGEX, "").trim()

    // ── Tool execution ───────────────────────────────────────────────────

    /** Execute a tool by name. Returns the result as a string. */
    fun executeTool(name: String, args: JSONObject, context: Context): String {
        return try {
            when (name) {
                "run_command" -> runCommand(
                    args.getString("command"),
                    args.optString("workdir", null)
                )
                "read_file" -> readFile(args.getString("path"))
                "write_file" -> writeFile(
                    args.getString("path"),
                    args.getString("content")
                )
                "list_files" -> listFiles(args.getString("path"))
                "search_files" -> searchFiles(
                    args.getString("path"),
                    args.getString("pattern")
                )
                "git_commit_push" -> gitCommitPush(
                    args.getString("message"),
                    args.optString("repo_dir", null),
                    context
                )
                "render_remotion" -> renderRemotion(
                    args.getString("composition"),
                    args.getString("output"),
                    args.optString("project_dir", null)
                )
                "save_secret" -> saveSecret(
                    args.getString("key"),
                    args.getString("value"),
                    context
                )
                "get_secret" -> getSecret(
                    args.getString("key"),
                    context
                )
                "web_fetch" -> webFetch(args.getString("url"))
                else -> "Unknown tool: $name"
            }
        } catch (e: Exception) {
            "Error executing $name: ${e.message}"
        }
    }

    // ── Individual tool implementations ──────────────────────────────────

    private fun runCommand(command: String, workdir: String?): String {
        val parts = command.split("\s+".toRegex())
        val builder = ProcessBuilder(*parts.toTypedArray())
            .redirectErrorStream(true)
        workdir?.let { File(it).takeIf { f -> f.exists() }?.let { dir -> builder.directory(dir) } }
        val proc = builder.start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        val exitCode = proc.waitFor()
        return if (exitCode == 0) {
            if (output.isBlank()) "(command completed, no output)" else output.take(4000)
        } else {
            "Exit code $exitCode\n$output".take(4000)
        }
    }

    private fun readFile(path: String): String {
        val file = File(path)
        if (!file.exists()) return "File not found: $path"
        if (file.isDirectory) return "Path is a directory, not a file: $path"
        if (file.length() > 500_000) return "File too large (${file.length()} bytes). Use run_command with head/tail."
        return file.readText().take(8000)
    }

    private fun writeFile(path: String, content: String): String {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "Wrote ${content.length} chars to $path"
    }

    private fun listFiles(path: String): String {
        val dir = File(path)
        if (!dir.exists()) return "Directory not found: $path"
        if (!dir.isDirectory) return "Not a directory: $path"
        val files = dir.listFiles()?.sortedBy { it.name } ?: return "Empty directory"
        return files.joinToString("\n") { f ->
            val type = if (f.isDirectory) "[DIR] " else "      "
            "$type${f.name} (${f.length()} bytes)"
        }.take(4000)
    }

    private fun searchFiles(path: String, pattern: String): String {
        val dir = File(path)
        if (!dir.exists()) return "Directory not found: $path"
        val results = mutableListOf<String>()
        dir.walkTopDown().take(500).forEach { f ->
            if (f.isFile && f.length() < 100_000) {
                try {
                    f.useLines { lines ->
                        lines.forEachIndexed { i, line ->
                            if (line.contains(pattern, ignoreCase = true)) {
                                results.add("${f.absolutePath}:${i + 1}: ${line.trim().take(200)}")
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        return if (results.isEmpty()) "No matches for '$pattern' in $path"
               else results.joinToString("\n").take(4000)
    }

    private fun gitCommitPush(message: String, repoDir: String?, context: Context): String {
        val dir = repoDir ?: context.filesDir.absolutePath
        val repo = File(dir)
        if (!File(repo, ".git").exists()) return "Not a git repository: $dir"

        fun git(vararg args: String): String {
            val cmd = listOf("git") + args
            val proc = ProcessBuilder(cmd)
                .directory(repo)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            return out.trim()
        }

        val addResult = git("add", "-A")
        val commitResult = git("commit", "-m", message)
        val pushResult = git("push")

        return "git add: $addResult\ngit commit: $commitResult\ngit push: $pushResult".take(4000)
    }

    private fun renderRemotion(composition: String, output: String, projectDir: String?): String {
        // Remotion rendering runs via npx — this constructs the command
        // The user should run this in the terminal tab (proot environment)
        val dir = projectDir ?: "."
        val cmd = "cd $dir && npx remotion render $composition $output"
        return "Remotion render command ready. Run in terminal:\n$cmd"
    }

    private fun saveSecret(key: String, value: String, context: Context): String {
        val store = SecureTokenStore(context)
        store.setAiKey(key, value)
        return "Secret '$key' saved securely."
    }

    private fun getSecret(key: String, context: Context): String {
        val store = SecureTokenStore(context)
        val value = store.aiKey(key)
        return if (value != null) value else "No secret found for key '$key'"
    }

    private fun webFetch(url: String): String {
        return try {
            val conn = java.net.URL(url).openConnection()
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.inputStream.bufferedReader().use { it.readText().take(8000) }
        } catch (e: Exception) {
            "Failed to fetch $url: ${e.message}"
        }
    }
}
