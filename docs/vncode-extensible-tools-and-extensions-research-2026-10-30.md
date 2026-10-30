# VN Code extensible tools and Extensions platform research

Date: 2026-10-30. Research and architecture planning only. Nothing was implemented, no production file, test, dependency or configuration was modified, and nothing was committed or pushed.

## 1. Repository identity and working-tree safety

| Field | Value |
|---|---|
| Branch | `main` |
| HEAD | `dd7088447f4e57dc5da2ab250d9a96cd3badf974` |
| Working tree | Two untracked docs files only: `docs/vncode-engineering-audit-2026-10-30.md` (33784 bytes), `docs/vscode-vncode-copilot-capability-audit.md` (40769 bytes). No staged changes, no modifications, no deletions. |
| Prior audits read | Both files above, in full |
| Archive inspected | `wisdom131-max/vn-code-archive` at `063c4ee12a34` (2026-10-27), `src/AgentTools.kt` (536 lines), `src/chat/NativeToolProtocol.kt`, `src/chat/ToolExecutionEvidence.kt`, `src/chat/CustomModeStore.kt`, `src/ai/WorkspaceContextProvider.kt`, `src/ui/screens/ChatStreamUi.kt` |
| VS Code inspected | `microsoft/vscode` at `ce233327f26ef7791b7fff0143c24e81a8126bd2`; `microsoft/vscode-copilot-chat` at `5863f5a7088958050792b5dccbe8b46c6e13eccc` |
| Docs inspected | `https://code.visualstudio.com/docs/agents/reference/tools-reference` (markdown source, fetched 2026-10-30), `https://code.visualstudio.com/docs/agents` navigation |

The two docs files are deliverables from earlier turns in this conversation. They are untracked by design, since no commit was authorised.

## 2. Verification of the previous audit

All six claims verified against the current source. No claim is stale, and no difference was found that changes a conclusion.

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 1 | `/tools` returns 32 builtin names | Confirmed | `AgentApiServer.kt:190` builds the list from `AgentTools.toolNames() + McpClientManager.cachedToolNames(ctx)`. `BUILTIN_TOOL_NAMES` (`AgentTools.kt:151-161`) parsed at this revision contains exactly 32 distinct names. |
| 2 | All 32 have execution branches | Confirmed | `AgentTools.kt:172-215`: every name except `plan` maps to a real function (`runCommand`, `gitCommitPush`, `gitStatus`, `saveSecret`, `webFetch`, `AgentEntityManager.*`, `AgentScheduler.*`, `installPackage`, and so on). |
| 3 | Only five are advertised | Confirmed | `AgentToolWire.kt:179-181` (`ALL_TOOLS`) lists exactly `run_command`, `read_file`, `write_file`, `list_files`, `search_files`. |
| 4 | Chat prompt sends a five-name protocol, not full docs | Confirmed | `AgentToolWire.kt:145-156` builds `protocol(mode)` from `ALL_TOOLS.filter { allowsTool(mode, it) }` and joins names. `ChatSession.kt:331` appends only that string. `TOOLS_DESCRIPTION` (`AgentTools.kt:34-130`) and `McpClientManager.toolDocs` (`:452`) are referenced in exactly one place, `AgentApiServer.kt:239`, never in `chat/panel`. |
| 5 | `allowsTool` rejects anything outside the exposed set | Confirmed | `AgentToolWire.kt:56-60`. `decide()` (`:103-120`) consults it, so a well-formed call to a registered-but-unlisted tool is refused. |
| 6 | `plan` is a stub | Confirmed | `AgentTools.kt:174-177`: returns the literal string "Plan staging returns with the new chat." |

Two nuances worth recording, which refine rather than contradict the audit:

- The `AGENT` branch of `allowsTool` excludes `save_secret` and `get_secret`, but because neither name is in `ALL_TOOLS`, that exclusion never has any effect. It is defensive code for a future exposure change, not an active policy.
- `agent_tools` can report more than 32 when MCP servers are configured and cached, because the `/tools` route appends `McpClientManager.cachedToolNames`. The user's reported 32 therefore proves no MCP tools were cached at that moment, not that MCP is absent.

## 3. Complete inventory of the 32 registered tools

Model exposure means advertised in the chat system prompt. Execution "Confirmed" means a concrete implementation exists in the dispatch target (read and reasoned from source, not run on device). Schema is derived from the argument reads at the dispatch site (`AgentTools.kt:172-215`) and matches the documented examples in `TOOLS_DESCRIPTION` (`:34-130`). A/E/G columns are the current code behaviour, not the proposal.

| Tool | Category | Purpose | Reg | Exposed | Schema | Dispatcher | Execution | A | E | G | Permission | Dependencies | Limitations |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| run_command | Terminal | Shell command in proot | Yes | Yes | `{command, workdir?}` | `runCommand` (`:243`) | Confirmed | Blocked | Blocked | Allowed | Gate + approval card | proot rootfs | No exit code in result, host to guest path translation (`:250-266`), no session reuse |
| read_file | Files | Read text file | Yes | Yes | `{path}` | `readFile` (`:286`) | Confirmed | Allowed | Allowed | Allowed | None | none | Disk only, ignores unsaved buffers |
| write_file | Files | Write or stage file | Yes | Yes | `{path, content}` | `writeFile` (`:302`) | Confirmed | Blocked | Allowed (staged) | Allowed | Approval in AGENT | none | No delete, no rename, no patch apply |
| list_files | Files | List directory | Yes | Yes | `{path}` | `listFiles` (`:329`) | Confirmed | Allowed | Allowed | Allowed | None | none | No recursion cap documented |
| search_files | Search | Pattern search | Yes | Yes | `{path, pattern}` | `searchFiles` (`:340`) | Confirmed | Allowed | Allowed | Allowed | None | none | Linear scan, no ignore rules, no result cap documented |
| git_status | Git | Working tree state | Yes | No | `{repo_dir?}` | `gitStatus` (`:189`) | Confirmed | Blocked | Blocked | Blocked | Gate | git in proot | Shell output, not structured |
| git_diff | Git | Diff | Yes | No | `{staged?, repo_dir?}` | `gitDiff` (`:190`) | Confirmed | Blocked | Blocked | Blocked | Gate | git in proot | Unbounded output risk |
| git_branch | Git | list/create/switch/merge/delete | Yes | No | `{action, name?, repo_dir?}` | `gitBranch` (`:188`, actions `:428-432`) | Confirmed | Blocked | Blocked | Blocked | Gate | git in proot | Delete and merge are destructive with no dedicated consent |
| git_commit_push | Git | Commit and push | Yes | No | `{message, repo_dir?}` | `gitCommitPush` (`:186`) | Confirmed | Blocked | Blocked | Blocked | Gate | git, credentials | Push rights unvalidated |
| git_pull_rebase | Git | Pull or rebase | Yes | No | `{repo_dir?}` | `gitPullRebase` (`:187`) | Confirmed | Blocked | Blocked | Blocked | Gate | git, credentials | Conflict handling unproven |
| save_secret | Secrets | Store secret | Yes | No | `{key, value}` | `saveSecret` (`:191`) | Confirmed | Blocked | Blocked | Blocked | Intentionally excluded | encrypted store | Never advertised anywhere |
| get_secret | Secrets | Read secret | Yes | No | `{key}` | `getSecret` (`:192`) | Confirmed | Blocked | Blocked | Blocked | Intentionally excluded | encrypted store | Same |
| detect_secrets | Secrets | Scan text or file | Yes | No | `{text?, path?}` | `detectSecrets` (`:193`) | Confirmed | Blocked | Blocked | Blocked | Gate | none | Read-only, safe to expose |
| web_fetch | Web | HTTP GET | Yes | No | `{url, headers?}` | `webFetch` (`:194`) | Confirmed | Blocked | Blocked | Blocked | Gate | network (HTTPS) | No size cap documented |
| web_search | Web | Search the web | Yes | No | `{query}` | `webSearch` (`:195`) | Confirmed | Blocked | Blocked | Blocked | Gate | network plus backend | Depends on configured endpoint |
| save_memory | Memory | Write agent memory | Yes | No | `{key, value}` | `AgentMemory.save` (`:196`) | Confirmed | Blocked | Blocked | Blocked | Gate | none | No size or key policy |
| read_memory | Memory | Read memory | Yes | No | `{}` | `AgentMemory.readAll` (`:197`) | Confirmed | Blocked | Blocked | Blocked | None needed | none | Dumps everything |
| delete_memory | Memory | Delete memory | Yes | No | `{key}` | `AgentMemory.delete` (`:198`) | Confirmed | Blocked | Blocked | Blocked | Gate | none | Destructive |
| list_connectors | Plugins | List connections | Yes | No | `{}` | `AgentConnectorManager` (`:199`) | Confirmed | Blocked | Blocked | Blocked | None needed | connector store | Read-only |
| connect_service | Plugins | Start OAuth | Yes | No | `{service, scopes[]}` | `:200` | Confirmed | Blocked | Blocked | Blocked | Approval | OAuth app config | Requires browser handoff |
| use_connector | Plugins | Call connector API | Yes | No | `{service, method, endpoint, body?}` | `:201` | Confirmed | Blocked | Blocked | Blocked | Approval | token | Arbitrary endpoint surface |
| request_connector | Plugins | Request a connection | Yes | No | `{service}` | `:202` | Confirmed | Blocked | Blocked | Blocked | None | none | Emits a card |
| create_entity | Data | Create record | Yes | No | `{entity, data}` | `AgentEntityManager.create` (`:203`) | Confirmed | Blocked | Blocked | Blocked | Approval | backend config | Backend coupling |
| read_entities | Data | Read records | Yes | No | `{entity, filter?}` | `:204` | Confirmed | Blocked | Blocked | Blocked | None | backend config | Read-only |
| update_entity | Data | Update record | Yes | No | `{entity, filter, data}` | `:205` | Confirmed | Blocked | Blocked | Blocked | Approval | backend config | Filter syntax unvalidated |
| delete_entity | Data | Delete record | Yes | No | `{entity, filter}` | `:206` | Confirmed | Blocked | Blocked | Blocked | Approval | backend config | Destructive |
| schedule_task | Automation | Schedule work | Yes | No | `{name, cron, command}` | `AgentScheduler.schedule` (`:209`) | Confirmed | Blocked | Blocked | Blocked | Approval | scheduler | Cron expression validation |
| list_tasks | Automation | List scheduled | Yes | No | `{}` | `:212` | Confirmed | Blocked | Blocked | Blocked | None | scheduler | Read-only |
| cancel_task | Automation | Cancel scheduled | Yes | No | `{name}` | `:213` | Confirmed | Blocked | Blocked | Blocked | Approval | scheduler | Destructive |
| upload_file | Files | Upload to URL | Yes | No | `{path, url}` | `uploadFile` (`:214`) | Confirmed | Blocked | Blocked | Blocked | Approval | destination | Exfiltration surface |
| install_package | Build | apt/pip/npm install | Yes | No | `{manager, package, project_dir?}` | `installPackage` (`:215`, commands `:557-559`) | Confirmed | Blocked | Blocked | Blocked | Approval | proot, network | Slow, heavy |
| plan | Agent | Stage a plan | Yes | No | `{steps:[{title, detail?, status}]}` | `:177` | **Stub** | Blocked | Blocked | Blocked | None | none | Returns a fixed sentence |

### 3.1 Classification

- **Fully implemented and model-accessible (5):** run_command, read_file, write_file, list_files, search_files.
- **Implemented but hidden from the model (26):** the five git tools, two secret tools, detect_secrets, two web tools, three memory tools, four connector tools, four entity tools, three scheduler tools, upload_file, install_package. All verified with real dispatch targets.
- **Partially implemented (0 in the 32, but relevant adjacent):** the executor's own quality limits, for example run_command lacking an exit code and search_files lacking ignore rules, are quality gaps inside otherwise complete tools.
- **Stubbed (1):** `plan`.
- **Registered without execution (0):** every name has a branch. This is a notable and verifiable absence of the "registered but dead" failure mode.
- **Unavailable through dependencies (0 strictly, 4 conditionally):** connector, entity, scheduler and upload tools require external configuration but their code paths exist.

### 3.2 What `plan` needs to be functional

The archive had a working `ChatPlanStore` (`src/chat/ChatPlanStore.kt`) plus a card UI (`src/ui/screens/ChatPlanCard.kt`). A real implementation needs four parts: a persisted plan store keyed by conversation (files or SharedPreferences, with the same unreadable-read protection used by `CustomEndpointStore`), a `plan` branch that parses `steps[]` and writes the store, a row type rendering the plan with per-step status, and a rule that staging a plan ends the turn so the user can approve. None of that exists in the current tree, which is exactly why the stub was left in place.

## 4. VS Code and GitHub Copilot research

### 4.1 Source and documentation revisions

- `microsoft/vscode` `ce233327f26ef7791b7fff0143c24e81a8126bd2`
- `microsoft/vscode-copilot-chat` `5863f5a7088958050792b5dccbe8b46c6e13eccc`
- Docs: tools and context reference, fetched 2026-10-30, markdown source 5310 bytes.

### 4.2 The five layers that must stay separate

| Layer | What it is | Where it lives | Evidence |
|---|---|---|---|
| Registered by the application | Native tool metadata and implementations | `chat/common/tools/languageModelToolsService.ts` (`IToolData` 45, `IToolImpl` 441, `invokeTool` 717) | Native registry |
| Contributed by Extensions | Tools an extension registers through the API | Extension host, surfaced through the same tools service | `confirmationMessages` 435 applies equally |
| Contributed by MCP servers | Tools from MCP servers, namespaced by server | MCP integration, same tools service | Docs: "Extensions and MCP servers can provide additional tools beyond those listed here" |
| Exposed for a particular request | The subset placed in this prompt, after harness, mode, tool-set and approval filters | Per-request tool selection | Docs: availability depends on harness, enabled extensions, session capabilities |
| Executable | What the model may actually invoke, after approval gates | `invokeTool` plus `prepareToolInvocation` (443) | Approval is per call |

A tool can be registered and executable while not being exposed, which is precisely VN Code's current state for 27 names. The reverse, exposed but not executable, is prevented in VS Code by the same registry serving both listing and invocation.

### 4.3 Registration, schemas, discovery, approval

- Registration is data first: `IToolData` carries identity and metadata, `IToolImpl` carries behaviour. Tool identity is a string id, and the service is the single lookup for both listing and invocation.
- Schemas are declared as JSON schema on the tool, not embedded in prose. VN Code currently ships prose examples only.
- Discovery/selection is per request and per harness. The docs state plainly that available references "depend on your harness, enabled extensions, and session capabilities", and that user-defined tool sets are deprecated in the Copilot harness. There is no universal list.
- Approval is metadata driven: `confirmationMessages` (435) plus `prepareToolInvocation` (443), and the docs are explicit that "Referencing a tool does not bypass its approval and permission controls."
- Virtual tools and grouping exist as a documented mechanism (tool groups in the Tools tab, built-in aliases). Notably the docs say referencing a tool is not the same as selecting a tool set, and that the identifiers are local-harness reference names that do not map one to one onto Copilot harness tools.
- Subagents are a first class tool: `#agent/runSubagent` appears in the reference list, matching `src/extension/tools/node/executionSubagentTool.ts` in the Copilot repository.
- Extension-contributed tools and MCP tools both flow through the same approval model, which is the strongest architectural lesson available from this research.

### 4.4 Tool counts, reported honestly

| Source | Count | What it counts | Notes |
|---|---|---|---|
| VN Code, `BUILTIN_TOOL_NAMES` | 32 | Registered builtin names | Verified by parsing source this revision |
| VN Code, `AgentToolWire.ALL_TOOLS` | 5 | Names advertised to the model | Verified |
| VN Code, `/tools` route | 32 plus cached MCP tools | Builtin plus enabled MCP | `AgentApiServer.kt:190` |
| VS Code tools-and-context reference | 29 unique identifiers | Tools **and** context items in one table | Fetched 2026-10-30 from the markdown source. Includes context items such as selection, so it is not a tool-only count. |
| VS Code native tool registry | Not stated as a fixed number | `IToolData` registrations | No official count published |
| Copilot harness tools | Not stated as a fixed number | Harness-specific | Docs: review the Tools tab; identifiers do not map one to one |

Do not claim a universal Copilot tool count. The documentation explicitly makes the list a function of harness, installed extensions, MCP servers and session, and it warns that reference identifiers are local harness names.

## 5. Capability comparison

Status vocabulary: Supported, Partial, Missing. Evidence is from this revision.

| Capability | VS Code / Copilot | VN Code | Gap |
|---|---|---|---|
| Workspace understanding | Codebase search tool, file glob, directory list, directory read | Multi-root context block plus active file (`WorkspaceContextSource.kt:29-93`), `list_files`, `search_files` | No project structure, entry points, dependency inventory |
| File search | `#search/fileSearch`, `#search/textSearch`, `#search/listDirectory` | `list_files`, `search_files` (linear scan) | No glob support, no ignore rules, no result cap |
| Semantic search | `#search/codebase` | None | Missing |
| Symbol navigation | `#search/usages` covers references, implementations, definitions | LSP exists inside the editor (`CodeEditor.kt:655` workspace symbols, `:693` definition) but is not a service | Present but unreachable by tools |
| Go to definition | Same | Editor callback `onLspDefinition` (`CodeEditor.kt:2099-2104`) | Needs service-level extraction |
| Find references | Same | Not found in `lsp/` (no definition/references request wrapper) | Needs implementation |
| Diagnostics | `#read/problems` over `ILanguageDiagnosticsService.getDiagnostics` | `DiagnosticManager` (`diagnostics/DiagnosticManager.kt:42,100,130`) exists; not exposed | Needs a read tool |
| Terminal integration | `#execute/runInTerminal`, `#execute/getTerminalOutput`, `#read/terminalLastCommand`, `#read/terminalSelection` | `run_command` only, one-shot | Needs exit codes, output reading, session observation |
| Build integration | Tasks: `#execute/createAndRunTask` | `BuildRunner.runBuild` (`build/BuildRunner.kt:108`), `GradleErrorParser` (`:32,150,211,220`) | Exists, not a tool |
| Test execution | `#execute/testFailure` plus testing commands | No test runner found in `build/` | Missing |
| Git operations | SCM service plus `GetScmChangesTool` (`scmChangesTool.ts:40-76`) | `scm/GitService.kt` (30-362) structured API exists; tools use shell `gitRun` instead | Duplicate paths, tools bypass the structured service |
| Editor selection and open buffer | Attachments with ranges (`chatContext.ts:422`) | None | Missing |
| Code editing | `#edit/editFiles`, `#edit/createFile`, `#edit/createDirectory` | `write_file` (creates, stages in Edit) | No create-directory, no patch edit, no delete or rename |
| Diff review | `IChatEditingSession` (`chatEditingService.ts:144-173`) | Staged writes, per-file accept (`PendingChangesStore`) | No grouped review, no diff view |
| Subagents | `#agent/runSubagent` | None | Missing |
| Planning | Plan workflow in agents docs | Stub | Needs real store |
| Browser research | `#web/fetch` plus browser tools | `web_fetch`, `web_search` exist, unexposed | Exposing is enough to start |
| Context management | Prompt builders, compaction | `HistoryBudget`, per-model context override (`ChatSettingsPrefs.kt:103-111`) | No summarisation |
| Tool approval | `confirmationMessages` plus permissions docs | `AgentFlowGate` (`AgentFlowGate.kt:28`), `ChatPermissionStore` (`:29-86`), `ChatApprovalCard.kt` | Solid; needs per-tool provenance |

## 6. Recommended additional built-in capabilities

Targets from your brief: 50 to 60 soon, 70 to 90 mature, Extensions and Plugins unbounded. The list below is capability driven, not count driven.

### 6.1 Group A, expose immediately (already executable)

The 26 hidden tools. Recommended first wave, which is the smallest set that adds real capability without new blast radius: `git_status`, `git_diff`, `git_branch` (list and switch only), `detect_secrets`, `read_memory`, `save_memory`, `list_tasks`, `read_entities`, `list_connectors`, `web_fetch`. Second wave, still approval gated: `web_search`, `delete_memory`, `create_entity`, `update_entity`, `schedule_task`, `cancel_task`, `use_connector`, `connect_service`, `request_connector`, `git_commit_push`, `upload_file`, `install_package`. Never: `save_secret`, `get_secret`, `delete_entity`, `git_pull_rebase` (rebase rewrites history), unless you decide otherwise per item.

### 6.2 Group B, low cost from existing services

| Proposed tool | Source already present | Work needed |
|---|---|---|
| `git_status_struct` | `scm/GitService.kt:68 status()` returns `ScmRepoState` | Thin adapter, no new git logic |
| `git_changes` | `GitService.status` plus diff parsing | Adapter plus bounded diff |
| `git_log` | Not present as a function; `GitService` has branches but no log | Small addition |
| `build_project` | `BuildRunner.runBuild` (`:108`) and `BuildResult` (`:29`) | Adapter plus cancellation |
| `build_problems` | `GradleErrorParser.extractErrors` (`:32`), `extractWarnings` (`:150`), `extractAllProblems` (`:211`), `generateSummary` (`:220`) | Adapter only |
| `diagnostics` | `DiagnosticManager.publishDiagnostics` (`:100`) and `Diagnostic` (`:42`) | Read adapter plus filter |
| `lsp_hover`, `lsp_symbols`, `lsp_definition` | `lsp/LspHoverHandler.kt`, `DocumentSymbolCache.kt`, `CodeEditor.kt:693` | Requires moving callbacks from composables into a service |
| `open_file`, `list_open_editors` | Editor state in `EditorPane` and `CodeEditor` | Needs an editor state accessor outside composables |
| `read_buffer` | Editor buffer plus `EditorInsertBus` pattern | Needs buffer access API |

### 6.3 Group C, important new built-ins

| Proposed tool | Why new work is justified |
|---|---|
| `find_references` | No LSP references wrapper exists; high value for refactors |
| `rename_symbol` | LSP rename exists (`LspManager.kt:3450`) but is UI bound; needs preview and approval |
| `code_actions` | `LspCodeActionHandler.kt` exists, no service entry point |
| `test_run`, `test_results` | No test runner at all; requires Gradle task execution and output parsing |
| `apply_patch` | Current `write_file` rewrites whole files; a patch tool reduces token cost and risk |
| `create_directory`, `move_path`, `delete_path` | File operations the agent cannot perform today |
| `terminal_tail`, `terminal_exit` | Requires persistent session and exit code contract |
| `plan` proper | Store plus card plus turn-ending rule |
| `context_summary` | Compaction primitive |
| `project_overview` | Entry points, build files, dependencies |
| `search_glob` | Glob-aware search with ignore rules |

## 7. Extensible tool architecture

```
            +---------------------------- Tool providers ----------------------------+
            |  builtin    |  extension    |  plugin    |  mcp    |  third-party       |
            +------+-------------+--------------+---------+-----------+---------------+
                   |             |              |         |           |
                   v             v              v         v           v
            +---------------------- Tool Registry (single source of truth) -----------+
            |  ids, namespaces, schemas, permissions, availability, health          |
            +-------------------------------+---------------------------------------+
                                            |
        +-----------------------------------+-----------------------------------+
        |                                   |                                   |
        v                                   v                                   v
 Exposure / discovery engine        Permission resolver                 Execution engine
 (mode, task relevance, budget)     (mode, approval, per-tool)          (timeouts, cancel, results)
        |                                   |                                   |
        +-----------------------------------+-----------------------------------+
                                            |
                                            v
                             Activity and result presentation
```

Design rules, non-negotiable in my recommendation:

1. The registry is the only place that knows a tool exists. Listing and invocation both read it, so "registered but not exposed" is a deliberate filter, never an accident of a second hand-written list.
2. Exposure is computed per request from mode, permission, provider enablement, task relevance and a token budget. There is no global `ALL_TOOLS`.
3. Every tool carries provenance (`builtin`, `extension`, `plugin`, `mcp`, `third-party`). UI, approvals and activity presentation switch on provenance, not on name prefixes.
4. Execution returns a structured result (status, stdout or payload, error class, duration, truncation flag), not a bare string. VN Code currently returns strings everywhere (`AgentTools.executeTool`).
5. Approval is resolved before invocation by a single gate, reusing `AgentFlowGate` and `ChatPermissionStore` rather than a new mechanism.

## 8. Common tool contract

Proposed shape, Kotlin, not implemented:

```kotlin
enum class ToolSource { BUILTIN, EXTENSION, PLUGIN, MCP, THIRD_PARTY }

data class ToolId(val namespace: String, val name: String) {
    val value: String get() = "$namespace.$name"
}

data class ToolSpec(
    val id: ToolId,
    val displayName: String,
    val description: String,
    val version: Int,
    val source: ToolSource,
    val providerId: String,          // "builtin", extension id, plugin id, mcp server
    val inputSchema: String,          // JSON schema text
    val outputSchema: String?,
    val categories: List<String>,     // files, search, git, build, terminal...
    val modes: Set<ChatMode>,
    val permissions: Set<ToolPermission>,   // FILES_READ, FILES_WRITE, SHELL, NETWORK, SECRETS, EXTERNAL
    val requiresApproval: ApprovalPolicy,
    val timeoutMs: Long,
    val cancellable: Boolean,
    val tags: List<String>            // relevance hints for discovery
)

interface ToolProvider {
    val providerId: String
    suspend fun listTools(): List<ToolSpec>
    suspend fun execute(call: ToolCall, ctx: ToolContext): ToolResult
}

data class ToolResult(
    val status: ToolStatus,           // OK, FAILED, REFUSED, TIMEOUT, TRUNCATED
    val summary: String,              // one line for the activity row
    val payload: String,              // detail for the model
    val truncated: Boolean = false,
    val durationMs: Long = 0
)
```

Collision rules: ids are `namespace.name` with the namespace reserved per source (`builtin`, `ext.<publisher>.<ext>`, `plugin.<id>`, `mcp.<server>`). Registration rejects duplicates with a diagnostic, never silently overwrites. MCP tool names already follow `mcp_<server>_<tool>` in `McpClientManager.callTool` (`:478-484`), so a migration maps prefix to namespace.

## 9. Dynamic tool discovery

The model must never receive every installed tool. Proposed pipeline, in order:

1. **Provider availability.** Drop tools from disabled or unhealthy providers. MCP health already has a natural probe.
2. **Mode filter.** Reuse `allowsTool` semantics, now driven by `spec.modes`.
3. **Permission filter.** Drop anything the user has permanently denied, unless the mode is Agent and the tool is approval-eligible.
4. **Task relevance.** Score by tags and keywords against the current request plus recent turns. Simple keyword and category matching first; embeddings later if evidence supports it.
5. **Budget.** Cap by schema token cost, not by count. Rough math: a compact tool definition is 60 to 120 tokens; a 16k-token prompt can carry perhaps 30 to 50 definitions before crowding out code. That is why grouping and staged exposure matter more than raw counts.
6. **Staged discovery.** Two meta tools, `tools_search(query)` and `tools_describe(id)`, let the model pull in a definition on demand. This is how hundreds of installed tools stay viable.

Grouping follows the VS Code model: groups or aliases (`files`, `search`, `git`, `build`) that expand to member tools, with the group itself advertised compactly. Virtual tools are the mechanism that keeps the visible surface small while the registry stays large.

Long-tail strategy: advertise a core set of roughly 10 to 15 per mode, plus the discovery tools, plus a one-line index of available groups. That supports hundreds installed.

## 10. Extension platform architecture

Definitions, used consistently from now on: **Extensions** expand the IDE (languages, debuggers, formatters, linters, themes, navigation, build systems). **Plugins** connect the IDE to external services (GitHub, databases, cloud APIs, MCP servers). They never merge, and they never share an install surface.

Proposed components: manifest parser, compatibility checker, extension store (records, versions, enabled state), activation manager (lazy, event driven), classloader or runtime per extension, contribution registry (tools, commands, languages, formatters, debug adapters, diagnostics, code actions), permission grant store, health and crash reporter.

## 11. Extension manifest proposal

```json
{
  "id": "vncode.python",
  "displayName": "Python",
  "description": "Python language support: LSP client, formatter, test runner",
  "publisher": "vncode",
  "version": "0.3.0",
  "icon": "assets/icon.png",
  "categories": ["Languages", "Linters", "Formatters"],
  "engines": { "vncode": ">=1.4" },
  "dependencies": [{ "id": "vncode.core-lsp", "range": "^2.0" }],
  "activationEvents": ["onLanguage:python", "onCommand:python.runTests"],
  "contributes": {
    "tools": [{ "id": "run_tests", "schema": "schemas/run_tests.json", "modes": ["edit", "agent"] }],
    "commands": [{ "id": "python.runTests", "title": "Run Python tests" }],
    "languages": [{ "id": "python", "extensions": [".py"], "lsp": { "command": "pylsp", "transport": "stdio" } }],
    "formatters": [{ "language": "python", "command": "black" }],
    "debuggers": [{ "type": "python", "adapter": "dap.json" }],
    "themes": [{ "id": "python-dark", "path": "themes/dark.json" }]
  },
  "permissions": ["files.read", "files.write.workspace", "process.spawn.workspace", "network.none"],
  "storage": { "maxMb": 40 }
}
```

Design notes: `engines.vncode` gives compatibility negotiation; `activationEvents` gives lazy activation; permissions are declared, not implied by capability; contributed tools reference a JSON schema file rather than inline prose, which fixes the current documentation gap.

## 12. Extension lifecycle

Discovery, install, validate (manifest, signature, compatibility), stage into a versioned directory, activate on event, register contributions, execute, deactivate, update, disable, uninstall. Each stage must be failure isolated: a manifest that throws is skipped with a logged diagnostic, never a crash. Activation must be lazy by default; an extension that fails to activate must not block the IDE or the chat.

Crash recovery: activation and tool execution run inside a supervisor that catches `Throwable`, records a health entry, disables the extension after repeated failures, and surfaces one honest row in chat rather than a silent disappearance.

## 13. Extension API

Proposed surface: `contributeTool(spec, handler)`, `contributeCommand`, `languages.register`, `diagnostics.publish`, `codeActions.register`, `formatters.register`, `debug.registerAdapter`, `editor.decorations`, `build.registerIntegration`, plus a scoped `workspace` facade.

Versioning: the API is versioned with `apiVersion` and semver ranges in the manifest; additive changes bump minor; removals require a major and a deprecation window with a runtime warning row. Never expose internal engine classes, or every internal refactor becomes a breaking change.

## 14. Android runtime and resource constraints

| Option | Feasibility on this device class | Assessment |
|---|---|---|
| Kotlin/JVM in-process (separate class loader) | High | Fastest, lowest memory, but no isolation; one bad extension can crash the process |
| Kotlin/JVM in a separate process (Android service) | Medium | Real isolation, but IPC surface and per-process memory cost (tens of MB) |
| WASM | Low to medium | Needs a WASM runtime dependency; good isolation, limited library ecosystem for language tooling |
| JavaScript (QuickJS or similar) | Medium | Small footprint, but little of the required tooling is JS-first on Android |
| Node.js | Low | Heavy, poor Android fit, packaging pain |
| Native binaries | Low | ABI and seccomp constraints already bit us with proot |

Recommendation: two tiers. Tier 1 is in-process JVM extensions with declared permissions and a supervised classloader, for tools, themes, formatters, snippets and simple integrations. Tier 2 is process-based or proot-hosted, for language servers and debuggers, reusing the pattern that already works (`ProotInstaller.execOnce`, `LspManager`). Budgets: 40 MB storage per extension, lazy activation, no background work without an explicit event, unload on idle.

## 15. Extension security

Declared permissions mapped to runtime gates: filesystem scope, network, process spawn, secret access, terminal, workspace trust. Untrusted extension output (including tool results and MCP payloads) is marked untrusted in the prompt and can never authorise another action. Destructive actions always re-enter the approval gate, regardless of extension, because the gate lives in the executor, not in the extension. Uninstall removes storage, grants and cached credentials.

## 16. Extension marketplace research

Facts that constrain the plan: the VS Code Marketplace terms restrict use to Visual Studio Code family products, so it cannot be treated as a drop-in marketplace for VN Code. Open VSX is the usual alternative for non-VS Code products, but its extensions still assume desktop Node and Electron APIs, so packaging compatibility is the real limit, not licensing alone.

Practical path: support local package installation first (a zip with a manifest, installable offline), then a small first-party registry, and only then consider an Open VSX-compatible client. Ratings and reviews are a later concern.

## 17. LSP architecture

Present today: `lsp/LspManager.kt` (3450+ lines), `JsonRpcClient.kt`, `LspLifecycle.kt`, `LspServerLifecycle.kt`, `LspDocumentSync.kt`, `LspDiagnosticsHandler.kt`, `LspCompletionHandler.kt`, `LspHoverHandler.kt`, `LspCodeActionHandler.kt`, `LspFormattingHandler.kt`, `LspSignatureHandler.kt`, `LspWorkspaceHandler.kt`, `DocumentSymbolCache.kt`, plus `DiagnosticManager`.

What is missing for tools: a service-level facade. Definition and workspace symbols exist as composable callbacks (`CodeEditor.kt:655,693,2099`), and no references request wrapper was found. The work is extraction, not protocol implementation: expose `definition`, `references`, `symbols`, `hover`, `diagnostics`, `codeActions`, `rename` on a narrow interface, then wrap each in a tool.

## 18. DAP architecture

Nothing exists today. Required pieces: a DAP client over the existing `JsonRpcClient` pattern, adapter launch inside proot (JDWP for Java, debugpy for Python, others later), session model (breakpoints, stepping, variables, stack, evaluate), and a tool surface limited at first to `debug_start`, `debug_breakpoints`, `debug_continue`, `debug_step`, `debug_stack`, `debug_variables`, `debug_stop`. Debugging is a P3 capability; it needs its own approval round.

## 19. Plugins and MCP architecture

Present: `McpClientManager.loadConfig`, tool cache (`:432`), docs (`:452`), execution with a 90 s bound (`:478`), shared approval gate (`AgentTools.kt:217-222`). Missing: exposure to the model, any Plugins UI, per-plugin credentials, connection state, health, retries, and activity presentation.

Proposed layers: plugin registry (installed plugins, enabled state, health), transport adapter (MCP stdio today, HTTP later), credential store per plugin, tool mapping into the shared registry as `mcp.<server>.<tool>`, and one reusable activity row keyed by plugin provenance. Naming rule: the word Plugins everywhere in UI and docs, never Connectors.

## 20. Tool permissions and mode restrictions

| Capability | Ask | Edit | Agent | Notes |
|---|---|---|---|---|
| Read files | Yes | Yes | Yes | |
| Search workspace | Yes | Yes | Yes | |
| Read diagnostics | Yes | Yes | Yes | New tool |
| Modify files | No | Yes (staged) | Yes (approval) | Staging in Edit must stay |
| Run terminal commands | No | No | Approval | Proposed, matches current `allowsTool` |
| Git inspection | Yes | Yes | Yes | Read-only |
| Destructive git | No | Approval | Approval | Explicit consent per operation |
| Install Extensions | No | No | Approval | |
| External plugin actions | No | Approval | Approval | |
| Secrets read/write | No | No | No | Never model-callable |

Persistence and revocation: reuse `ChatPermissionStore` (`KEY_LEVEL` at `:29`, `KEY_AUTO_TOOLS` at `:30`, `autoApprovedTools` at `:80`, `allowTool` at `:86`) and extend it with per-source grants (`ext:<id>`, `plugin:<id>`) rather than inventing a second store. Registration never implies availability.

## 21. Tool activity presentation

Every tool execution produces one activity record: tool id, provenance, human label (existing `stepLabel` for the five, `spec.displayName` for everything else), state, duration and a bounded summary. Records group into the collapsible chevron section already approved, collapsed by default, failures forced visible, running steps not dismissible, dismissal presentation-only. Because records carry provenance, the same row renders builtin, Extension, Plugin and MCP activity without special cases.

## 22. Proposed expansion list

Column meanings: Modes A/E/G; Pri priority; Mem memory impact; Cx complexity; Ext whether it belongs to an Extension.

| Proposed name | Category | Purpose | Existing | Source dependency | New work | Modes | Perm | Pri | Mem | Cx | Ext |
|---|---|---|---|---|---|---|---|---|---|---|---|
| builtin.git.status | Git | Structured status | Yes | `scm/GitService.kt:68` | Adapter | A/E/G | none | P0 | Low | Low | no |
| builtin.git.changes | Git | Changed files and diff | Partial | `GitService` plus diff | Adapter, bounds | A/E/G | none | P0 | Low | Low | no |
| builtin.git.log | Git | Commit history | No | `GitService` | New parser | A/E/G | none | P1 | Low | Low | no |
| builtin.build.run | Build | Run Gradle build | Yes | `BuildRunner.runBuild:108` | Adapter, cancel | E/G | approval | P0 | Med | Med | no |
| builtin.build.problems | Build | Parsed errors | Yes | `GradleErrorParser:32,150,211` | Adapter | A/E/G | none | P0 | Low | Low | no |
| builtin.diagnostics.file | Diagnostics | Problems for a file | Yes | `DiagnosticManager:100` | Adapter | A/E/G | none | P0 | Low | Low | no |
| builtin.diagnostics.workspace | Diagnostics | All problems, capped | Yes | `DiagnosticManager` | Filter and cap | A/E/G | none | P1 | Low | Low | no |
| builtin.lsp.hover | Code intel | Type and docs | Partial | `LspHoverHandler.kt` | Service facade | A/E/G | none | P1 | Low | Med | no |
| builtin.lsp.definition | Code intel | Go to definition | Partial | `CodeEditor.kt:693,2099` | Service facade | A/E/G | none | P0 | Low | Med | no |
| builtin.lsp.references | Code intel | Find references | No | LSP `textDocument/references` | New wrapper | A/E/G | none | P1 | Low | Med | no |
| builtin.lsp.symbols | Code intel | Document symbols | Partial | `DocumentSymbolCache.kt` | Service facade | A/E/G | none | P1 | Low | Med | no |
| builtin.lsp.workspaceSymbols | Code intel | Workspace symbols | Partial | `CodeEditor.kt:655` | Service facade | A/E/G | none | P2 | Low | Med | no |
| builtin.lsp.codeActions | Code intel | Suggested fixes | Partial | `LspCodeActionHandler.kt` | Facade plus preview | E/G | approval | P2 | Low | High | no |
| builtin.lsp.rename | Code intel | Rename symbol | Partial | `LspManager.kt:3450` | Facade plus preview | E/G | approval | P2 | Low | High | no |
| builtin.editor.state | Editor | Open file, tabs, language | Partial | Editor state | Accessor API | A/E/G | none | P0 | Low | Med | no |
| builtin.editor.selection | Editor | Selection text and range | No | Editor state | Accessor API | A/E/G | none | P0 | Low | Med | no |
| builtin.editor.buffer | Editor | Unsaved content | No | Editor state | Accessor API | A/E/G | none | P1 | Low | Med | no |
| builtin.files.createDirectory | Files | Make a directory | No | `resolveToolPath` | New branch | E/G | none | P1 | Low | Low | no |
| builtin.files.patch | Files | Apply a targeted patch | No | `writeFile` | New implementation | E/G | approval | P1 | Low | High | no |
| builtin.files.delete | Files | Delete a path | No | file IO | New, trash first | G | approval | P2 | Low | Med | no |
| builtin.files.move | Files | Move or rename | No | file IO | New | E/G | approval | P2 | Low | Med | no |
| builtin.search.glob | Search | Glob-aware search | Partial | `searchFiles` | Add glob and ignores | A/E/G | none | P1 | Low | Med | no |
| builtin.terminal.tail | Terminal | Read recent output | No | terminal session | Persistent session | A/E/G | none | P1 | Med | High | no |
| builtin.process.list | Runtime | Running processes | No | proot | New read tool | A/E/G | none | P2 | Low | Med | no |
| builtin.logs.read | Runtime | App or Gradle logs | Partial | existing log stores | Adapter | A/E/G | none | P1 | Low | Low | no |
| builtin.test.run | Testing | Run tests | No | Gradle tasks | Runner plus parser | E/G | approval | P1 | Med | High | no |
| builtin.test.results | Testing | Failure details | No | test report parser | New | A/E/G | none | P1 | Low | Med | no |
| builtin.agent.plan | Agent | Plan and track steps | Stub | new plan store | Store, card, turn rule | A/E/G | approval | P0 | Low | Med | no |
| builtin.agent.summary | Agent | Compact history | No | `HistoryBudget` | New | A/E/G | none | P2 | Low | Med | no |
| builtin.project.overview | Project | Structure and entry points | No | file scan | New | A/E/G | none | P1 | Med | Med | no |
| ext.python.runTests | Extension | Python tests | No | Extension runtime | Extension | E/G | approval | P2 | Med | High | yes |
| ext.java.debug | Extension | Java debugging | No | DAP | Extension plus DAP | E/G | approval | P3 | High | High | yes |
| plugin.github.issue | Plugin | External action | Partial | `AgentConnectorManager` | Plugin UI | A/E/G | approval | P3 | Low | High | no |
| mcp.database.query | MCP | External tool | Yes | `McpClientManager:478` | Exposure only | A/E/G | approval | P2 | Low | Med | no |

Count summary: 27 hidden builtins to expose, 8 adapters over existing services, 12 genuinely new builtins, 2 Extension examples, 1 Plugin example, 1 MCP example. Reaching 50 to 60 soon is realistic from groups A and B alone; 70 to 90 mature needs group C plus Extensions.

## 23. Implementation roadmap

### Phase 1, fix current exposure (P0)

Dependencies: none. Components: `AgentToolWire`, `ChatSession`, `AgentTools`. Work: generate the prompt from the registry, send real schemas and descriptions, replace `ALL_TOOLS` with a per-mode allowlist, make `plan` real or keep it hidden but honest, and add a test asserting registration, exposure and execution agree for every name. Risks: widened blast radius; mitigate with the first-wave list and the existing approval gate. Tests: registry parity test, per-mode exposure snapshot, refusal for unlisted names. Complexity: low to medium. Acceptance: no tool is registered and unreachable without an explicit reason string, and every advertised tool has a schema.

### Phase 2, tool contracts and registry

Dependencies: Phase 1. Components: new registry and contract, `executeTool` migration, MCP adapter. Risks: a big-bang migration; migrate one provider at a time. Tests: duplicate id rejection, schema validation, provider disable, result status mapping. Complexity: high. Acceptance: adding a tool requires one registration and zero edits to prompt code.

### Phase 3, IDE intelligence

Dependencies: Phase 2. Components: LSP facade, editor state accessor, diagnostics, build, git adapters, terminal exit codes. Risks: main-thread IO, LSP server load. Tests: per-tool unit tests plus device checks. Complexity: high. Acceptance: agent can diagnose, build, and verify in one turn.

### Phase 4, Extension foundation

Dependencies: Phase 2. Components: manifest, store, activation, classloader, permission grants. Risks: process stability. Tests: broken manifest, activation failure, disable, uninstall. Complexity: very high. Acceptance: a sample extension contributes one tool that appears in chat and runs through the same gate.

### Phase 5, language and debugger Extensions

Dependencies: Phase 4 and LSP/DAP work. Complexity: very high. Acceptance: one language end to end, including diagnostics as tools.

### Phase 6, Plugins and MCP

Dependencies: Phase 2 (registry), Phase 1 (exposure). Components: plugin registry, credentials, activity row. Risks: prompt injection from untrusted output. Acceptance: a plugin tool is discoverable, approval gated, and its activity is visible.

### Phase 7, marketplace

Dependencies: Phases 4 and 6. Risks: distribution and licensing. Acceptance: install from a local package offline, then from a small registry.

## 24. Risks and unresolved questions

| Risk or question | Impact | Position |
|---|---|---|
| Exposing 27 tools at once | Destructive actions reachable | Stage in two waves with per-tool approval |
| `plan` semantics | A plan that does not end the turn is misleading | Decide the turn-ending rule before implementing |
| LSP callbacks trapped in composables | Extraction cost underestimated | Prototype one tool (definition) before committing |
| Git duplication (`GitService` versus shell `gitRun`) | Inconsistent behaviour | Prefer `GitService` for new tools, migrate later |
| Schema token cost | Prompt bloat | Budget by tokens, not counts; ship discovery tools early |
| Extension isolation on Android | Crash blast radius | In-process first, process tier only for LSP and DAP |
| Marketplace licensing | Distribution blocked | Local packages first, no VS Code Marketplace dependency |
| MCP trust | Prompt injection | Untrusted marking plus approval, no autonomous install |
| Test tooling absent | Cannot verify its own work | Prioritise Phase 3 build and test tools |

## 25. Decisions requiring my approval

1. Approve the two-wave exposure list, or edit it tool by tool. Recommended wave one is the ten read-only names in section 6.1.
2. Confirm secrets tools stay permanently unavailable to the model.
3. Confirm `git_pull_rebase` and `delete_entity` remain blocked, or approve them with explicit consent cards.
4. Approve `plan` being implemented properly, including the turn-ending rule and a persisted plan store.
5. Approve the single registry plus per-request exposure design, which retires `ALL_TOOLS`.
6. Approve schema-first tool definitions replacing prose documentation.
7. Approve the Extension versus Plugin split and the Plugins naming rule in UI and docs.
8. Approve the Android runtime tiering (in-process JVM first, process or proot for LSP and DAP).
9. Approve the Phase 1 to Phase 7 sequence, or reorder.
10. Approve starting with Phase 1 only, so exposure is fixed and tested before any new architecture lands.
