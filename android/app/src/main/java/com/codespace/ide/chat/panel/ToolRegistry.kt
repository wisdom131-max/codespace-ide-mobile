package com.codespace.ide.chat.panel

/**
 * PHASE 1 (owner approval 2026-10-30): ONE registry is the source of truth for tool
 * registration, model exposure and invocation validation.
 *
 * Why this file exists: before Phase 1 the chat advertised a hand-maintained five-name
 * list while 32 tools were registered and executable, so 27 working tools were
 * unreachable and nobody could tell whether a name was missing on purpose. Every tool
 * now carries either a set of chat modes (exposed) or a written reason (hidden), and
 * the tests fail if the registry and the executor ever disagree again.
 *
 * Exposure is a decision about the MODEL, never about capability. A hidden tool is
 * still registered and still executable by an authorized caller through the agent API;
 * it is simply not offered to the chat model. Hiding never removes a working feature
 * from the app, and exposure never adds an approval bypass: [AgentToolWire.decide]
 * still consults this registry before any call is dispatched.
 */
internal data class ToolSpec(
    /** Exact registered name, matching a branch in AgentTools.executeTool. */
    val name: String,
    /** Short human label for activity rows and transcripts. */
    val displayName: String,
    /** One or two sentences for the model: what it does and what it returns. */
    val description: String,
    /** JSON schema of the arguments, as advertised to the model. */
    val schema: String,
    /** Modes in which the model may be offered this tool. Empty means never exposed. */
    val modes: Set<ChatMode>,
    /** Required when [modes] is empty: why the tool is not offered. */
    val hiddenReason: String? = null,
)

internal object ToolRegistry {

    private val READ_ALL: Set<ChatMode> = setOf(ChatMode.ASK, ChatMode.EDIT, ChatMode.AGENT)
    private val WRITE_MODES: Set<ChatMode> = setOf(ChatMode.EDIT, ChatMode.AGENT)
    private val EXEC_MODES: Set<ChatMode> = setOf(ChatMode.AGENT)
    private val NONE: Set<ChatMode> = emptySet()

    private const val OBJ = "{\"type\":\"object\""

    // ---------------------------------------------------------------------------------
    // The registry. Order matches AgentTools.BUILTIN_TOOL_NAMES; the parity test fails
    // if it ever stops matching, so a new executor branch cannot be silently orphaned.
    // ---------------------------------------------------------------------------------
    val ALL: List<ToolSpec> = listOf(
        // --- Exposed: pre-existing tools, kept exactly as they behaved before Phase 1 ---
        ToolSpec(
            name = "run_command",
            displayName = "Running a command",
            description = "Run a shell command inside the Ubuntu session. Returns the combined " +
                "output text; it does not report an exit code. Changes nothing outside the project.",
            schema = OBJ + ",\"required\":[\"command\"],\"properties\":{\"command\":{\"type\":\"string\"}," +
                "\"workdir\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
        ),
        ToolSpec(
            name = "read_file",
            displayName = "Reading a file",
            description = "Read a text file and return its contents. Reports an explicit not-found " +
                "or denied message instead of empty text.",
            schema = OBJ + ",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"}}}",
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "write_file",
            displayName = "Writing a file",
            description = "Write a file. In Edit mode the write is STAGED for review and nothing " +
                "touches disk until the user applies it; in Agent mode it writes behind the approval gate.",
            schema = OBJ + ",\"required\":[\"path\",\"content\"],\"properties\":{\"path\":{\"type\":\"string\"}," +
                "\"content\":{\"type\":\"string\"}}}",
            modes = WRITE_MODES,
        ),
        // VS Code parity (verified against microsoft/vscode-copilot-chat
        // src/extension/tools/common/toolNames.ts, 2026-10-31):
        //   replace_string_in_file  <- ToolName.ReplaceString / MultiReplaceString
        //   get_errors              <- ToolName.GetErrors
        //   create_directory        <- ToolName.CreateDirectory
        //   get_changed_files       <- ToolName.GetScmChanges
        ToolSpec(
            name = "replace_string_in_file",
            displayName = "Replacing text in a file",
            description = "Replace ONE exact string in an existing file. old_string must match " +
                "exactly one place, or set replace_all. Cheaper and safer than rewriting a whole file.",
            schema = OBJ + ",\"required\":[\"path\",\"old_string\",\"new_string\"],\"properties\":{" +
                "\"path\":{\"type\":\"string\"},\"old_string\":{\"type\":\"string\"}," +
                "\"new_string\":{\"type\":\"string\"},\"replace_all\":{\"type\":\"boolean\"}}}",
            modes = WRITE_MODES,
        ),
        ToolSpec(
            name = "get_errors",
            displayName = "Reading diagnostics",
            description = "Report the errors and warnings the language servers and compilers are " +
                "currently producing, optionally filtered to one file. States when the data is stale.",
            schema = OBJ + ",\"properties\":{\"path\":{\"type\":\"string\"}}}",
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "create_directory",
            displayName = "Creating a directory",
            description = "Create a directory, including missing parents. Refuses when a file " +
                "already exists at that path.",
            schema = OBJ + ",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"}}}",
            modes = WRITE_MODES,
        ),
        ToolSpec(
            name = "get_changed_files",
            displayName = "Listing changed files",
            description = "List the files changed in the repository (git status), without their " +
                "full diffs.",
            schema = OBJ + ",\"properties\":{\"repo_dir\":{\"type\":\"string\"}}}",
            modes = READ_ALL,
        ),

        ToolSpec(
            name = "list_files",
            displayName = "Listing files",
            description = "List the entries of one directory. Returns names, not contents.",
            schema = OBJ + ",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"}}}",
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "search_files",
            displayName = "Searching the project",
            description = "Search file contents under a path for a literal pattern and return the " +
                "matching lines with their file and line number.",
            schema = OBJ + ",\"required\":[\"path\",\"pattern\"],\"properties\":{\"path\":{\"type\":\"string\"}," +
                "\"pattern\":{\"type\":\"string\"}}}",
            modes = READ_ALL,
        ),

        // --- Hidden: state-changing git operations ---
        ToolSpec(
            name = "git_commit_push",
            displayName = "Committing and pushing",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "Writes history and pushes to a remote. State-changing; Phase 2 with its own consent card.",
        ),
        ToolSpec(
            name = "git_pull_rebase",
            displayName = "Pulling and rebasing",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "Destructive: rewrites history. Owner decision 3 (2026-10-30) keeps it blocked.",
        ),
        ToolSpec(
            name = "git_branch",
            displayName = "Managing branches",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "One tool performs list, create, switch, merge and delete. Not read-only until the " +
                "action is narrowed to an allowlist, which is new code (Phase 2).",
        ),

        // --- Exposed: read-only git inspection ---
        ToolSpec(
            name = "git_status",
            displayName = "Checking git status",
            description = "Show the working tree status of a repository (branch and changed paths). " +
                "Output is capped; a truncation marker is appended when the cap is reached.",
            schema = OBJ + ",\"properties\":{\"repo_dir\":{\"type\":\"string\"}}}",
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "git_diff",
            displayName = "Reading a diff",
            description = "Show the unified diff of unstaged changes, or of staged changes when " +
                "staged is true. Output is capped; a truncation marker is appended when the cap is reached.",
            schema = OBJ + ",\"properties\":{\"staged\":{\"type\":\"boolean\"},\"repo_dir\":{\"type\":\"string\"}}}",
            modes = READ_ALL,
        ),

        // --- Hidden: secrets ---
        ToolSpec(
            name = "save_secret",
            displayName = "Saving a secret",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "Owner decision 2 (2026-10-30): secrets are never exposed to the model, in any mode.",
        ),
        ToolSpec(
            name = "get_secret",
            displayName = "Reading a secret",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "Owner decision 2 (2026-10-30): a model that could read secrets would copy keys into " +
                "the transcript. Never exposed.",
        ),

        // --- Exposed: secret scanning is pure and read-only ---
        ToolSpec(
            name = "detect_secrets",
            displayName = "Scanning for secrets",
            description = "Scan inline text or one file for things that look like credentials and list " +
                "the suspicious findings. Reads only; it never stores or transmits anything.",
            schema = OBJ + ",\"properties\":{\"text\":{\"type\":\"string\"},\"path\":{\"type\":\"string\"}}}",
            modes = READ_ALL,
        ),

        // --- Hidden: network egress ---
        ToolSpec(
            name = "web_fetch",
            displayName = "Fetching a URL",
            description = "Fetch one http(s) page and return its text, bounded. It is network " +
                "egress that leaves the device, and the response is untrusted content.",
            schema = OBJ + ",\"required\":[\"url\"],\"properties\":{\"url\":{\"type\":\"string\"}," +
                "\"headers\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
        ),
        ToolSpec(
            name = "web_search",
            displayName = "Searching the web",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "Network egress through a backend endpoint; wave 2, behind the web_fetch policy.",
        ),

        // --- Hidden: memory writes; read is exposed ---
        ToolSpec(
            name = "save_memory",
            displayName = "Saving a memory",
            description = "Save one durable note for this project that later conversations can read " +
                "with read_memory. Agent mode only; every call passes the approval gate.",
            schema = OBJ + ",\"required\":[\"key\",\"value\"],\"properties\":{" +
                "\"key\":{\"type\":\"string\"},\"value\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
        ),
        ToolSpec(
            name = "read_memory",
            displayName = "Reading memories",
            description = "List the saved agent memories (keys with a short preview of each value). " +
                "Returns an explicit note when nothing has been saved yet.",
            schema = OBJ + ",\"properties\":{}}",
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "delete_memory",
            displayName = "Deleting a memory",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "Destructive durable change with no undo. Travels with the Phase 2 memory redesign.",
        ),

        // --- Exposed: connector/plugin inventory is read-only ---
        ToolSpec(
            name = "list_connectors",
            displayName = "Listing connections",
            description = "List the external services that are available or already connected. " +
                "Read-only inventory; it never performs an external call.",
            schema = OBJ + ",\"properties\":{}}",
            modes = READ_ALL,
        ),

        // --- Hidden: external actions ---
        ToolSpec(
            name = "connect_service",
            displayName = "Connecting a service",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "Starts an OAuth flow (an external side effect). Phase 6 plugin permissions.",
        ),
        ToolSpec(
            name = "use_connector",
            displayName = "Using a connection",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "Executes arbitrary calls against an external API. Requires the plugin permission " +
                "model (Phase 6).",
        ),
        ToolSpec(
            name = "request_connector",
            displayName = "Requesting a connection",
            description = "Show a Connect card for a service in the chat. Nothing external " +
                "happens until the user taps Connect on that card.",
            schema = OBJ + ",\"required\":[\"service\"],\"properties\":{\"service\":{\"type\":\"string\"}}}",
            // Owner ruling 2026-10-31: the card is consent, not an action, so it is offered in
            // every mode. Hiding it left the card unreachable, which the owner reported.
            modes = READ_ALL,
        ),

        // --- Hidden: data writes; read is exposed ---
        ToolSpec(
            name = "create_entity",
            displayName = "Creating a record",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "Writes records. State-changing; wave 2.",
        ),
        ToolSpec(
            name = "read_entities",
            displayName = "Reading records",
            description = "Read records of one entity type, optionally filtered by exact field matches. " +
                "Returns JSON records, or an explicit message when the store is empty or unavailable.",
            schema = OBJ + ",\"required\":[\"entity\"],\"properties\":{\"entity\":{\"type\":\"string\"}," +
                "\"filter\":{\"type\":\"string\"}}}",
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "update_entity",
            displayName = "Updating records",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "Writes records. State-changing; wave 2.",
        ),
        ToolSpec(
            name = "delete_entity",
            displayName = "Deleting records",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "Destructive with no undo. Owner decision 3 (2026-10-30) keeps it blocked.",
        ),

        // --- Hidden: background work ---
        ToolSpec(
            name = "schedule_task",
            displayName = "Scheduling a task",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "Creates recurring background work (durable side effect). Wave 2 with explicit consent.",
        ),
        ToolSpec(
            name = "list_tasks",
            displayName = "Listing scheduled tasks",
            description = "List the scheduled tasks and their cron expressions. Read-only.",
            schema = OBJ + ",\"properties\":{}}",
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "cancel_task",
            displayName = "Cancelling a scheduled task",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "Mutates scheduled work. Wave 2.",
        ),

        // --- Hidden: heavy or outward-facing file actions ---
        ToolSpec(
            name = "upload_file",
            displayName = "Uploading a file",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "Sends file bytes to an external URL: an exfiltration channel. Needs its own approval design.",
        ),
        ToolSpec(
            name = "install_package",
            displayName = "Installing a package",
            description = "Install a package with apt, pip or npm inside the session rootfs. High " +
                "blast radius, so Agent mode only and behind the approval gate.",
            schema = OBJ + ",\"required\":[\"manager\",\"package\"],\"properties\":{" +
                "\"manager\":{\"type\":\"string\"},\"package\":{\"type\":\"string\"}," +
                "\"project_dir\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
        ),

        // --- Hidden: stub ---
        ToolSpec(
            name = "plan",
            displayName = "Planning",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "Stub: returns a fixed sentence (AgentTools.kt:177). Stays hidden until a real plan " +
                "store exists; owner deferred implementation and approved only the turn-ending rule.",
        ),
    )

    /** Every registered name, in registry order. */
    fun names(): List<String> = ALL.map { it.name }

    /** Tools the model may be offered in [mode], in registry order. */
    fun exposed(mode: ChatMode): List<ToolSpec> = ALL.filter { mode in it.modes }

    /** Names advertised in [mode]. */
    fun exposedNames(mode: ChatMode): List<String> = exposed(mode).map { it.name }

    /** Tools never offered to the model, each carrying its reason. */
    fun hidden(): List<ToolSpec> = ALL.filter { it.modes.isEmpty() }

    /** The single answer to "may the model invoke this name in this mode". */
    fun allows(mode: ChatMode, name: String): Boolean =
        ALL.any { it.name == name && mode in it.modes }

    fun spec(name: String): ToolSpec? = ALL.firstOrNull { it.name == name }

    /**
     * The prompt section for one mode: name, description and JSON schema per tool.
     * Names alone were the Phase 0 defect: the model could see a tool existed but not
     * what it took, so it guessed argument names.
     */
    fun promptBlock(mode: ChatMode): String {
        val specs = exposed(mode)
        if (specs.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n\nYou can act on this project. To use a tool, emit exactly one block:\n")
        sb.append("<tool>{\"name\":\"<tool>\",\"arguments\":{...}}</tool>\n")
        sb.append("Tools available in this mode:\n")
        for (s in specs) {
            sb.append("- ").append(s.name).append(": ").append(s.description.trim()).append('\n')
            sb.append("  arguments: ").append(s.schema).append('\n')
        }
        return sb.toString()
    }
}
