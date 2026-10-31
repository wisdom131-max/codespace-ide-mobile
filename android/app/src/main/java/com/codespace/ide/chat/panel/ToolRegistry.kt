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
            description = "Stage everything and commit with this message. Pushes to the remote when one is configured.",
            schema = OBJ + ",\"required\":[\"message\"],\"properties\":{,\"message\":{\"type\":\"string\"},\"repo_dir\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
        ),
        ToolSpec(
            name = "git_pull_rebase",
            displayName = "Pulling and rebasing",
            description = "Pull with rebase. Rewrites local history, so it is Agent-only and every call is approved.",
            schema = OBJ + ",\"properties\":{\"repo_dir\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
        ),
        ToolSpec(
            name = "git_branch",
            displayName = "Managing branches",
            description = "List, create, switch, rename, merge or delete branches with one action argument.",
            schema = OBJ + ",\"required\":[\"action\"],\"properties\":{,\"action\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"repo_dir\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
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
            description = "Store a credential in the encrypted store. The value is written, never echoed back.",
            schema = OBJ + ",\"required\":[\"key\", \"value\"],\"properties\":{,\"key\":{\"type\":\"string\"},\"value\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
        ),
        ToolSpec(
            name = "get_secret",
            displayName = "Reading a secret",
            description = "Read one stored secret by key so it can be used in a command you are about to run.",
            schema = OBJ + ",\"required\":[\"key\"],\"properties\":{,\"key\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
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
            description = "Search the web and return titles, links and snippets.",
            schema = OBJ + ",\"required\":[\"query\"],\"properties\":{,\"query\":{\"type\":\"string\"}}}",
            modes = READ_ALL,
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
            description = "Delete one saved memory by key. There is no undo, so say what is being removed.",
            schema = OBJ + ",\"required\":[\"key\"],\"properties\":{,\"key\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
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

        // --- VS Code parity: the rest of their exposed inventory, mapped by mode ---
        ToolSpec(
            name = "list_dir",
            displayName = "Listing a directory",
            description = "List one directory. Their name for our list_files; same behaviour.",
            schema = OBJ + ",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"}}}" ,
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "grep_search",
            displayName = "Searching file text",
            description = "Search file contents with a regular expression. Their name for our search_files.",
            schema = OBJ + ",\"required\":[\"path\", \"pattern\"],\"properties\":{\"path\":{\"type\":\"string\"}, \"pattern\":{\"type\":\"string\"}}}" ,
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "file_search",
            displayName = "Finding files by name",
            description = "Find files whose NAME matches, without reading contents.",
            schema = OBJ + ",\"required\":[\"query\"],\"properties\":{\"query\":{\"type\":\"string\"}, \"path\":{\"type\":\"string\"}}}" ,
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "semantic_search",
            displayName = "Searching the project by keyword",
            description = "Keyword search over file text with ranked snippets. It is NOT an embedding index, and the result says so.",
            schema = OBJ + ",\"required\":[\"query\"],\"properties\":{\"query\":{\"type\":\"string\"}, \"path\":{\"type\":\"string\"}}}" ,
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "search_workspace_symbols",
            displayName = "Searching declarations",
            description = "Find declarations (fun/class/def/const...) whose name matches. Text-based, not language-server intelligence.",
            schema = OBJ + ",\"required\":[\"query\"],\"properties\":{\"query\":{\"type\":\"string\"}, \"path\":{\"type\":\"string\"}}}" ,
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "read_project_structure",
            displayName = "Reading the project structure",
            description = "An indented tree of the project, bounded by depth and entry count.",
            schema = OBJ + ",\"properties\":{\"path\":{\"type\":\"string\"}, \"depth\":{\"type\":\"string\"}}}" ,
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "create_file",
            displayName = "Creating a file",
            description = "Create a NEW file and refuse to overwrite an existing one.",
            schema = OBJ + ",\"required\":[\"path\", \"content\"],\"properties\":{\"path\":{\"type\":\"string\"}, \"content\":{\"type\":\"string\"}}}" ,
            modes = WRITE_MODES,
        ),
        ToolSpec(
            name = "insert_edit_into_file",
            displayName = "Editing a file in place",
            description = "Replace one exact string in a file. Their name for replace_string_in_file.",
            schema = OBJ + ",\"required\":[\"path\", \"old_string\", \"new_string\"],\"properties\":{\"path\":{\"type\":\"string\"}, \"old_string\":{\"type\":\"string\"}, \"new_string\":{\"type\":\"string\"}, \"replace_all\":{\"type\":\"string\"}}}" ,
            modes = WRITE_MODES,
        ),
        ToolSpec(
            name = "multi_replace_string_in_file",
            displayName = "Editing several places at once",
            description = "Several exact replacements in one file, applied all or nothing.",
            schema = OBJ + ",\"required\":[\"path\", \"replacements\"],\"properties\":{\"path\":{\"type\":\"string\"}, \"replacements\":{\"type\":\"string\"}, \"replace_all\":{\"type\":\"string\"}}}" ,
            modes = WRITE_MODES,
        ),
        ToolSpec(
            name = "run_in_terminal",
            displayName = "Running a command",
            description = "Run a shell command. Their name for run_command; Agent mode only.",
            schema = OBJ + ",\"required\":[\"command\"],\"properties\":{\"command\":{\"type\":\"string\"}, \"workdir\":{\"type\":\"string\"}}}" ,
            modes = EXEC_MODES,
        ),

        // --- VS Code parity: notebooks, patches, todos, agent terminal record ---
        ToolSpec(
            name = "apply_patch",
            displayName = "Applying a patch",
            description = "Apply a unified diff to one file. Strict: every context line must match or nothing is changed.",
            schema = OBJ + ",\"required\":[\"path\", \"patch\"],\"properties\":{\"path\":{\"type\":\"string\"}, \"patch\":{\"type\":\"string\"}}}" ,
            modes = WRITE_MODES,
        ),
        ToolSpec(
            name = "manage_todo_list",
            displayName = "Managing the todo list",
            description = "Read or write the project todo board. Call with no todos to read it back.",
            schema = OBJ + ",\"properties\":{\"todos\":{\"type\":\"string\"}}}" ,
            modes = WRITE_MODES,
        ),
        ToolSpec(
            name = "create_new_jupyter_notebook",
            displayName = "Creating a notebook",
            description = "Create a new .ipynb notebook, optionally with starter cells.",
            schema = OBJ + ",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"}}}" ,
            modes = WRITE_MODES,
        ),
        ToolSpec(
            name = "edit_notebook_file",
            displayName = "Editing a notebook cell",
            description = "Add or replace a notebook cell. index -1 appends; clear_outputs empties a cell",
            schema = OBJ + ",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"}, \"index\":{\"type\":\"string\"}, \"cell_type\":{\"type\":\"string\"}, \"source\":{\"type\":\"string\"}, \"clear_outputs\":{\"type\":\"string\"}}}" ,
            modes = WRITE_MODES,
        ),
        ToolSpec(
            name = "copilot_getNotebookSummary",
            displayName = "Reading a notebook summary",
            description = "One line per cell: index, type, first line. Reads the .ipynb JSON, no kernel needed.",
            schema = OBJ + ",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"}}}" ,
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "read_notebook_cell_output",
            displayName = "Reading notebook cell output",
            description = "The saved text outputs of one cell, or that it has none.",
            schema = OBJ + ",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"}, \"index\":{\"type\":\"string\"}}}" ,
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "run_notebook_cell",
            displayName = "Running a notebook cell",
            description = "Run one code cell with python3. No kernel session is kept, and the result says so.",
            schema = OBJ + ",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"}, \"index\":{\"type\":\"string\"}}}" ,
            modes = EXEC_MODES,
        ),
        ToolSpec(
            name = "get_terminal_output",
            displayName = "Reading command output",
            description = "Output of the last commands the AGENT ran. It is not the interactive shell buffer.",
            schema = OBJ + ",\"properties\":{\"runs\":{\"type\":\"string\"}}}" ,
            modes = EXEC_MODES,
        ),
        ToolSpec(
            name = "terminal_last_command",
            displayName = "Reading the last command",
            description = "The last command the agent ran and how it ended.",
            schema = OBJ + ",\"properties\":{}}" ,
            modes = EXEC_MODES,
        ),

        // --- Hidden: external actions ---
        ToolSpec(
            name = "connect_service",
            displayName = "Connecting a service",
            description = "Start the OAuth flow for a service. The user still approves in the consent sheet.",
            schema = OBJ + ",\"required\":[\"service\"],\"properties\":{,\"service\":{\"type\":\"string\"},\"scopes\":{\"type\":\"array\"}}}",
            modes = EXEC_MODES,
        ),
        ToolSpec(
            name = "use_connector",
            displayName = "Using a connection",
            description = "Call an authorized service's API through its connector after per-call consent.",
            schema = OBJ + ",\"required\":[\"service\", \"method\", \"endpoint\"],\"properties\":{,\"service\":{\"type\":\"string\"},\"method\":{\"type\":\"string\"},\"endpoint\":{\"type\":\"string\"},\"body\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
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
            description = "Create a record in an app entity from a JSON object.",
            schema = OBJ + ",\"required\":[\"entity\", \"data\"],\"properties\":{,\"entity\":{\"type\":\"string\"},\"data\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
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
            description = "Update records matching a filter with the given fields.",
            schema = OBJ + ",\"required\":[\"entity\", \"filter\", \"data\"],\"properties\":{,\"entity\":{\"type\":\"string\"},\"filter\":{\"type\":\"string\"},\"data\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
        ),
        ToolSpec(
            name = "delete_entity",
            displayName = "Deleting records",
            description = "Delete records matching a filter. No undo.",
            schema = OBJ + ",\"required\":[\"entity\", \"filter\"],\"properties\":{,\"entity\":{\"type\":\"string\"},\"filter\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
        ),

        // --- Hidden: background work ---
        ToolSpec(
            name = "schedule_task",
            displayName = "Scheduling a task",
            description = "Schedule a recurring command with cron syntax in the current project.",
            schema = OBJ + ",\"required\":[\"name\", \"cron\", \"command\"],\"properties\":{,\"name\":{\"type\":\"string\"},\"cron\":{\"type\":\"string\"},\"command\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
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
            description = "Cancel a scheduled task by name.",
            schema = OBJ + ",\"required\":[\"name\"],\"properties\":{,\"name\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
        ),

        // --- Hidden: heavy or outward-facing file actions ---
        ToolSpec(
            name = "upload_file",
            displayName = "Uploading a file",
            description = "Upload a project file and return a link. This sends bytes out, so it is Agent-only.",
            schema = OBJ + ",\"required\":[\"path\"],\"properties\":{,\"path\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
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

        // --- Tests, tasks, and project setup: wired to the real subsystems ---
        ToolSpec(
            name = "test_search",
            displayName = "Searching tests",
            description = "Query the discovered-test index the Test explorer reads.",
            schema = OBJ + ",\"required\":[\"query\"],\"properties\":{\"query\":{\"type\":\"string\"}}}",
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "test_failure",
            displayName = "Reading test failures",
            description = "Failures and messages from the last test run.",
            schema = OBJ + ",\"properties\":{}}",
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "get_task_output",
            displayName = "Reading task output",
            description = "The last recorded output for a task the task tab ran.",
            schema = OBJ + ",\"properties\":{\"task\":{\"type\":\"string\"}}}",
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "get_project_setup_info",
            displayName = "Reading project setup",
            description = "Build, test and run commands, each backed by a build marker file that was actually found.",
            schema = OBJ + ",\"properties\":{}}",
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "runTests",
            displayName = "Running tests",
            description = "Discover and run tests through the same runner the Test explorer uses. Reports what passed and what did not.",
            schema = OBJ + ",\"properties\":{\"query\":{\"type\":\"string\"}, \"limit\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
        ),
        ToolSpec(
            name = "run_task",
            displayName = "Running a task",
            description = "Run a task from the task catalogue (BUILD_DEBUG, TEST, LINT...).",
            schema = OBJ + ",\"properties\":{\"task\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
        ),
        ToolSpec(
            name = "create_and_run_task",
            displayName = "Creating and running a task",
            description = "Run a catalogue task by id or display name. There is no ad-hoc task model yet, so an unknown name is refused with the list.",
            schema = OBJ + ",\"properties\":{\"task\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
        ),

        // --- View state, GitHub, images, inventory lookup ---
        ToolSpec(
            name = "get_search_view_results",
            displayName = "Reading search results",
            description = "The hits the search panel is showing right now. Reads its snapshot; it does not re-run the search.",
            schema = OBJ + ",\"properties\":{}}",
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "view_image",
            displayName = "Viewing an image",
            description = "Attach an image to your next request so you can actually see it. Downscaled to 1568px; needs a model that accepts image input.",
            schema = OBJ + ",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"}}}",
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "tool_search",
            displayName = "Searching the tool list",
            description = "Find a tool by name or description, including tools not offered in this mode and why.",
            schema = OBJ + ",\"required\":[\"query\"],\"properties\":{\"query\":{\"type\":\"string\"}}}",
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "github_repo",
            displayName = "Reading a GitHub repository",
            description = "Overview, issues, pull requests, commits or a file, read over REST with the token the Source Control panel stored. Defaults to this project's origin remote.",
            schema = OBJ + ",\"properties\":{\"repo\":{\"type\":\"string\"}, \"what\":{\"type\":\"string\"}, \"path\":{\"type\":\"string\"}, \"state\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
        ),

        // --- Terminal selection and the blocking question gate ---
        ToolSpec(
            name = "terminal_selection",
            displayName = "Reading the terminal selection",
            description = "What the user has selected in the terminal. Says so plainly when nothing is selected.",
            schema = OBJ + ",\"properties\":{}}",
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "vscode_askQuestions",
            displayName = "Asking the user a question",
            description = "Ask and WAIT. The user's real answer comes back as this tool's result; no answer means no answer, never a guess.",
            schema = OBJ + ",\"required\":[\"question\"],\"properties\":{\"question\":{\"type\":\"string\"}, \"options\":{\"type\":\"string\"}, \"allowFreeText\":{\"type\":\"string\"}}}",
            modes = READ_ALL,
        ),
        ToolSpec(
            name = "vscode_get_confirmation",
            displayName = "Asking for confirmation",
            description = "Ask the user to proceed or cancel and wait for the answer. Treat any non-answer as NO.",
            schema = OBJ + ",\"required\":[\"message\"],\"properties\":{\"message\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
        ),
        ToolSpec(
            name = "vscode_get_confirmation_with_options",
            displayName = "Asking for confirmation with choices",
            description = "Same gate, with your own option labels instead of Proceed/Cancel.",
            schema = OBJ + ",\"required\":[\"message\"],\"properties\":{\"message\":{\"type\":\"string\"}, \"options\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
        ),

        // --- Hidden: present in VS Code, and why each is not offered here ---
        ToolSpec(
            name = "run_vscode_command",
            displayName = "Running an editor command",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "VS Code internal command ids have no mobile equivalent. The real actions here are separate tools, not string ids.",
        ),
        ToolSpec(
            name = "runSubagent",
            displayName = "Running a subagent",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "Owner instruction 2026-10-31: subagents stay out. There is also no subagent runtime in the app.",
        ),
        ToolSpec(
            name = "search_subagent",
            displayName = "Running a search subagent",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "Owner instruction 2026-10-31: subagents stay out.",
        ),
        ToolSpec(
            name = "execution_subagent",
            displayName = "Running an execution subagent",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "Owner instruction 2026-10-31: subagents stay out.",
        ),
        ToolSpec(
            name = "vscode_get_terminal_confirmation",
            displayName = "Asking before a terminal command",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "run_in_terminal already passes the same gate as every other command.",
        ),
        ToolSpec(
            name = "resolve_memory_file_uri",
            displayName = "Resolving a memory file URI",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "Memories are keyed and stored as files under the app's own directory. A URI form would need a virtual memory filesystem exposed to other tools; read_memory already returns the content.",
        ),
        ToolSpec(
            name = "get_vscode_api",
            displayName = "Reading the VS Code API",
            description = "",
            schema = "",
            modes = NONE,
            hiddenReason = "No extension host exists to introspect, so there is no API surface to describe.",
        ),

        // --- Hidden: stub ---
        ToolSpec(
            name = "plan",
            displayName = "Planning",
            description = "Stage a plan as a checklist the user can follow. Each step lands in the todo board, so an approved plan is the same board the work gets ticked off in.",
            schema = OBJ + ",\"required\":[\"steps\"],\"properties\":{\"steps\":{\"type\":\"array\"},\"title\":{\"type\":\"string\"}}}",
            modes = EXEC_MODES,
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
