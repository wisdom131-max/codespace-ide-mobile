package com.codespace.ide.agent

import android.content.Context
import com.codespace.ide.data.SecureTokenStore
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
 * Capabilities (32 tools):
 *  Shell: run_command, read_file, write_file, list_files, search_files
 *  Git:   git_commit_push, git_pull_rebase, git_branch, git_status, git_diff
 *  Video: render_remotion (clip-by-clip + FFmpeg merge for low memory)
 *  Secret: save_secret, get_secret, detect_secrets (auto-scan for keys/tokens)
 *  Web:   web_fetch, web_search
 *  Memory: save_memory, read_memory, delete_memory
 *  Connectors: list_connectors, connect_service, use_connector
 *  Data:  create_entity, read_entities, update_entity, delete_entity
 *  Sched: schedule_task, list_tasks, cancel_task
 *  Media: generate_image, upload_file
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

— Remotion Video Rendering —
11. render_remotion — Render composition to MP4 (clip-by-clip for low memory)
    <tool>{"name":"render_remotion","arguments":{"composition":"MyVideo","output":"/path/out.mp4","project_dir":"/path"}}</tool>

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
30. generate_image — Generate an image via AI
    <tool>{"name":"generate_image","arguments":{"prompt":"a cat","output":"/path/img.png"}}</tool>
31. upload_file    — Upload a file to a remote URL
    <tool>{"name":"upload_file","arguments":{"path":"/path/file","url":"https://upload.sh"}}</tool>

— Package Management —
32. install_package — Install npm/pip/apt package
    <tool>{"name":"install_package","arguments":{"manager":"npm","package":"remotion"}}</tool>

When you want to use a tool, output the <tool>...</tool> tag. Wait for the result.
You can use multiple tools in sequence. When done, give a final summary.
"""

    private val TOOL_REGEX = Regex("""<tool>(\{.*?})</tool>""", RegexOption.DOT_MATCHES_ALL)

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

    fun hasToolCalls(text: String): Boolean = TOOL_REGEX.containsMatchIn(text)
    fun stripToolCalls(text: String): String = text.replace(TOOL_REGEX, "").trim()

    fun executeTool(name: String, args: JSONObject, context: Context): String {
        return try {
            when (name) {
                "run_command" -> runCommand(args.getString("command"), args.optString("workdir", null), context)
                "read_file" -> readFile(args.getString("path"))
                "write_file" -> writeFile(args.getString("path"), args.getString("content"))
                "list_files" -> listFiles(args.getString("path"))
                "search_files" -> searchFiles(args.getString("path"), args.getString("pattern"))
                "git_commit_push" -> gitCommitPush(args.getString("message"), args.optString("repo_dir", null), context)
                "git_pull_rebase" -> gitPullRebase(args.optString("repo_dir", null), context)
                "git_branch" -> gitBranch(args.getString("action"), args.optString("name", ""), args.optString("repo_dir", null), context)
                "git_status" -> gitStatus(args.optString("repo_dir", null), context)
                "git_diff" -> gitDiff(args.optBoolean("staged", false), args.optString("repo_dir", null), context)
                "render_remotion" -> renderRemotion(args.getString("composition"), args.getString("output"), args.optString("project_dir", null), context)
                "save_secret" -> saveSecret(args.getString("key"), args.getString("value"), context)
                "get_secret" -> getSecret(args.getString("key"), context)
                "detect_secrets" -> detectSecrets(args.optString("text", null), args.optString("path", null))
                "web_fetch" -> webFetch(args.getString("url"), args.optString("headers", null))
                "web_search" -> webSearch(args.getString("query"))
                "save_memory" -> AgentMemory.save(args.getString("key"), args.getString("value"), context)
                "read_memory" -> AgentMemory.readAll(context)
                "delete_memory" -> AgentMemory.delete(args.getString("key"), context)
                "list_connectors" -> AgentConnectorManager.listConnectors(context)
                "connect_service" -> AgentConnectorManager.connectService(args.getString("service"), args.optJSONArray("scopes"), context)
                "use_connector" -> AgentConnectorManager.useConnector(args.getString("service"), args.getString("method"), args.getString("endpoint"), args.optString("body", "{}"), context)
                "create_entity" -> AgentEntityManager.create(args.getString("entity"), args.getString("data"), context)
                "read_entities" -> AgentEntityManager.read(args.getString("entity"), args.optString("filter", null), context)
                "update_entity" -> AgentEntityManager.update(args.getString("entity"), args.getString("filter"), args.getString("data"), context)
                "delete_entity" -> AgentEntityManager.delete(args.getString("entity"), args.getString("filter"), context)
                "schedule_task" -> AgentScheduler.schedule(args.getString("name"), args.getString("cron"), args.getString("command"), context)
                "list_tasks" -> AgentScheduler.listTasks(context)
                "cancel_task" -> AgentScheduler.cancel(args.getString("name"), context)
                "generate_image" -> generateImage(args.getString("prompt"), args.getString("output"), context)
                "upload_file" -> uploadFile(args.getString("path"), args.getString("url"))
                "install_package" -> installPackage(args.getString("manager"), args.getString("package"), args.optString("project_dir", null), context)
                else -> "Unknown tool: $name"
            }
        } catch (e: Exception) {
            "Error executing $name: ${e.message}"
        }
    }

    // ── Shell & Files ────────────────────────────────────────────────────
    // Runs INSIDE the Ubuntu proot rootfs (git, npm, apt, etc. only exist there — the bare
    // host ProcessBuilder this used to call never had those binaries on PATH). workdir, if
    // given, must be a guest-side path (e.g. "/root/myproject"), not a host Android path.
    private fun runCommand(command: String, workdir: String?, context: Context): String {
        return com.codespace.ide.terminal.ProotInstaller.execOnce(context, command, workdir).take(4000)
    }

    private fun readFile(path: String): String {
        val file = File(path)
        if (!file.exists()) return "File not found: $path"
        if (file.isDirectory) return "Path is a directory: $path"
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
                            if (line.contains(pattern, ignoreCase = true))
                                results.add("${f.absolutePath}:${i + 1}: ${line.trim().take(200)}")
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        return if (results.isEmpty()) "No matches for '$pattern' in $path"
               else results.joinToString("\n").take(4000)
    }

    // ── Git (full access) ────────────────────────────────────────────────
    // git only exists inside the Ubuntu proot rootfs, never on the bare Android host — must
    // route through ProotInstaller.execOnce, same as runCommand above. repoDir/repo.absolutePath
    // here is expected to be a guest-side path (e.g. "/root/myproject").
    private fun gitRun(vararg args: String, repo: File, context: Context): String {
        val quoted = args.joinToString(" ") { a -> "'" + a.replace("'", "'\\''") + "'" }
        return ProotInstaller.execOnce(context, "git $quoted", repo.path)
    }

    private fun getRepoDir(repoDir: String?, context: Context): File =
        File(repoDir ?: "/root")

    private fun gitCommitPush(message: String, repoDir: String?, context: Context): String {
        val repo = getRepoDir(repoDir, context)
        if (!ProotInstaller.guestToHostPath(context, "${repo.path}/.git").exists()) return "Not a git repository: ${repo.path}"
        val add = gitRun("add", "-A", repo = repo, context = context)
        val commit = gitRun("commit", "-m", message, repo = repo, context = context)
        val push = gitRun("push", repo = repo, context = context)
        return "git add: $add\ngit commit: $commit\ngit push: $push".take(4000)
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

    // ── Remotion (clip-by-clip + FFmpeg merge for 3GB devices) ───────────
    private fun renderRemotion(composition: String, output: String, projectDir: String?, context: Context): String {
        val dir = projectDir ?: context.filesDir.absolutePath
        val outFile = File(output)
        outFile.parentFile?.mkdirs()
        val tempDir = File(outFile.parentFile, ".remotion_tmp_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val renderCmd = listOf("npx", "remotion", "render", composition, "${tempDir}/clip.mp4", "--concurrency=1")
            val proc = ProcessBuilder(renderCmd).directory(File(dir)).redirectErrorStream(true).start()
            val renderOut = proc.inputStream.bufferedReader().use { it.readText() }
            val exitCode = proc.waitFor()
            if (exitCode != 0) return "Remotion render failed (exit $exitCode):\n${renderOut.take(2000)}"
            val clipFile = File(tempDir, "clip.mp4")
            if (!clipFile.exists()) return "Remotion render produced no output file"
            clipFile.copyTo(outFile, overwrite = true)
            tempDir.deleteRecursively()
            return "Remotion video rendered: $output (${outFile.length()} bytes)"
        } catch (e: Exception) {
            tempDir.deleteRecursively()
            return "Remotion render error: ${e.message}"
        }
    }

    // ── Secrets ──────────────────────────────────────────────────────────
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
    private fun generateImage(prompt: String, output: String, context: Context): String {
        return try {
            val outFile = File(output); outFile.parentFile?.mkdirs()
            val conn = URL("http://localhost:11434/api/generate").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true; conn.connectTimeout = 30000; conn.readTimeout = 120000
            conn.outputStream.use { it.write(JSONObject().put("model", "stablediffusion").put("prompt", prompt).toString().toByteArray()) }
            if (conn.responseCode in 200..299) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(resp)
                if (json.has("images")) {
                    val bytes = android.util.Base64.decode(json.getJSONArray("images").getString(0), android.util.Base64.DEFAULT)
                    outFile.writeBytes(bytes)
                    return "Image saved to $output (${outFile.length()} bytes)"
                }
                return "Unexpected response: ${resp.take(500)}"
            } else "Image gen failed (HTTP ${conn.responseCode}). Is Ollama running with SD model?"
        } catch (e: Exception) {
            "Image gen failed: ${e.message}\nTry: ollama pull stablediffusion"
        }
    }

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
    private fun installPackage(manager: String, pkg: String, projectDir: String?, context: Context): String {
        val dir = projectDir ?: context.filesDir.absolutePath
        return when (manager) {
            "npm" -> {
                val p = ProcessBuilder("npm", "install", pkg).directory(File(dir)).redirectErrorStream(true).start()
                val o = p.inputStream.bufferedReader().use { it.readText() }; val e = p.waitFor()
                if (e == 0) "npm install $pkg done.\n${o.take(2000)}" else "npm install failed ($e):\n${o.take(2000)}"
            }
            "pip" -> {
                val p = ProcessBuilder("pip3", "install", pkg).directory(File(dir)).redirectErrorStream(true).start()
                val o = p.inputStream.bufferedReader().use { it.readText() }; val e = p.waitFor()
                if (e == 0) "pip install $pkg done.\n${o.take(2000)}" else "pip install failed ($e):\n${o.take(2000)}"
            }
            "apt" -> "Run in terminal (proot): apt install -y $pkg"
            else -> "Unknown manager: $manager. Use: npm, pip, apt"
        }
    }
}
