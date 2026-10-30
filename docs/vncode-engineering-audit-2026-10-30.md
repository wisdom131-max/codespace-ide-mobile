# VN Code engineering audit and improvement proposal

Date: 2026-10-30. This is an investigation and design document only. No production code, test, dependency, resource or build file was modified to produce it, and nothing was committed or pushed.

## 1. Repository identity and working-tree safety

| Field | Value |
|---|---|
| Branch | `main` |
| Commit SHA | `dd7088447f4e57dc5da2ab250d9a96cd3badf974` |
| Working tree | Clean except one untracked file, `docs/vscode-vncode-copilot-capability-audit.md` (previous research deliverable) |
| Remote | `wisdom131-max/codespace-ide-mobile` |
| Archive repository | `wisdom131-max/vn-code-archive`, default branch `main`, latest commit `063c4ee12a34`, committed 2026-10-27T00:07:40Z, private, 129 files (110 Kotlin) |

Module layout of the current repository: `android/app` (Compose UI, chat panel, agent, terminal, editor) and `android/engine` (provider adapters, SSE, URL contracts, model catalogue, history budget, session state). The archive has no such split: it is a flat `src/` tree with chat, providers and screens side by side, plus an `extensions-own/remotion` example plugin.

Archive is reference material only. Nothing from it is restored here.

## 2. Dismissible agent activity indicators

| Field | Information |
|---|---|
| Current behaviour | Every tool step adds a free-standing status row. `ChatSession` appends `ChatRowModel.InfoNote(AgentToolWire.stepLabel(call) + ": " + summary)` at `ChatSession.kt:607`. Rendered by `InfoNoteRow` (`ChatListRenderer.kt:63`). Rows carry no identity, no state and no dismiss control, and they persist in the transcript as `role = "note"` (`ChatTranscript.kt:36-44`). |
| Desired behaviour | A small X inside each completed activity indicator that hides only that indicator. No deletion of messages, tool results or history; no effect on running tools or terminal processes; errors never hideable. |
| Root cause | The row model has no per-row identity and no presentation-level visibility concept. `ChatRowModel` (`ChatRowModel.kt:8-24`) is a sealed interface of value objects; `ui.value.copy(rows = ...)` is append-only (`ChatSession.kt:462`). Nothing can address a single row. |
| Evidence | `ChatRowModel.kt:8-24`, `ChatSession.kt:462,607`, `ChatListRenderer.kt:63`, `ChatTranscript.kt:36-44` |
| Existing capability | Rows are already presentation-only objects flowing from session state to renderer; the transcript is a separate store. So a hide flag is architecturally possible without touching execution. |
| Gap | Row identity, a `hiddenRowIds` set in UI state, and a rule forbidding dismissal of `Failed` and of running states. |
| Proposed solution | 1) Add `val rowId: String` to each `ChatRowModel` (generated once at creation, not on recomposition). 2) Add `hiddenRowIds: Set<String>` to the panel UI state, mutated by `hide(rowId)`. 3) Renderer filters hidden rows except `Failed`. 4) Dismiss button only on completed step rows. Transcript store untouched, so history and model context are unaffected. |
| Risk | Low. Risk is accidental disguise of a failure, so the filter must be type-aware, not id-only. A row id collision would hide two rows; ids must be unique per append. |
| Dependencies | None. This is the natural first UI change, but it needs design approval. |
| Approval required | Yes (UI). |

Design note per the screenshots: the X appears only on completed indicators, sits top-right, and matches the refresh-icon weight already used in the pill. A dismissed row is gone from this view for this session; reopening the transcript reintroduces it unless we also persist the hidden set, which is a separate decision (recommended: do not persist, so history stays auditable).

## 3. The 32-tool discrepancy

### 3.1 Where the number comes from

`agent_tools` is a guest shell helper that calls the local agent API. The route is `GET /tools` in `AgentApiServer.kt:183-193`:

```kotlin
val tools = AgentTools.toolNames() + McpClientManager.cachedToolNames(ctx)
```

`AgentTools.toolNames()` returns `BUILTIN_TOOL_NAMES` (`AgentTools.kt:151-161`), which contains exactly 32 names. Since the reported count was exactly 32, no MCP server tools were cached at that moment, so the list is purely the 32 builtins.

### 3.2 Why the chat model cannot use them

The chat panel does not advertise that list. It advertises a separate, hand-maintained five-name list:

```kotlin
// AgentToolWire.kt:179-181
val ALL_TOOLS: List<String> = listOf(
    "run_command", "read_file", "write_file", "list_files", "search_files",
)
```

`protocol(mode)` (`AgentToolWire.kt:145-156`) filters `ALL_TOOLS` by `allowsTool(mode, ...)` and sends names only, with no descriptions and no argument schemas. Then `decide()` rejects any name that fails `allowsTool`, so a model that invents a valid tool name from the executor is refused.

Two further omissions compound it:

- `AgentTools.TOOLS_DESCRIPTION` (the documented tool manual with argument examples) is not included by `ChatSession`; only `AgentToolWire.protocol(mode)` is appended (`ChatSession.kt:331`). Verified by grep: no `TOOLS_DESCRIPTION` reference exists in `chat/panel`.
- MCP tool documentation exists as `McpClientManager.toolDocs` (`McpClientManager.kt:452-458`) and is included by `AgentApiServer` (`:239`), never by `ChatSession`. So MCP tools are invisible to the chat model even though they are executed through the same dispatcher (`AgentTools.kt:217-222`).

So the discrepancy is not registration. Registration, dispatch and approval are all wired. The break is at step 5 of the pipeline, tools exposed to the model, and again at step 9, available name validation.

### 3.3 Pipeline trace

| Step | Location | Status |
|---|---|---|
| 1. Registration | `AgentTools.BUILTIN_TOOL_NAMES` (`:151`), `executeTool` branches (`:173-215`) | 32 registered, all branches present |
| 2. Registry | static list plus `when` dispatch, MCP dynamic | Complete |
| 3. Descriptions | `AgentTools.TOOLS_DESCRIPTION` (`:30-130` region) | Exists, not used by chat |
| 4. Argument schemas | implicit, parsed per branch with `optString` | No schema advertised to the model |
| 5. Tools exposed to model | `AgentToolWire.ALL_TOOLS` (`:179`) | 5 only |
| 6. Mode restrictions | `allowsTool` (`:56-60`) | Applied to the 5, so effectively vacuous for the rest |
| 7. Prompt construction | `ChatSession.kt:331` | Names only, no docs, no MCP |
| 8. Tool-call parsing | `AgentToolWire.CALL_REGEX` (`:40`) | Text blocks only; native `tool_calls` unsupported |
| 9. Name validation | `allowsTool` inside `decide` (`:103-120`) | Rejects everything outside the 5 |
| 10. Argument validation | per-branch `optString` defaults | Loose; missing args become empty strings |
| 11. Dispatcher | `executeTool` (`:170-225`) | Connected for all 32 |
| 12. Execution | per-branch implementations | All present except `plan` (stub) |
| 13. Result delivery | tool result appended as text, loop re-enters | Works for the 5 |
| 14. Error handling | `Unknown tool`, `Error executing`, truncated results | Present |

### 3.4 The 32-tool inventory

Columns: Exposed means advertised in the chat prompt; Exec means a real implementation exists; A/E/G are mode availability as the code stands (N = advertised in that mode, B = blocked). A tool that is not in `ALL_TOOLS` is blocked in every mode regardless of `allowsTool`.

| # | Tool | Purpose | Reg | Exposed | Exec | Dispatcher | A | E | G | Missing dependency | Problem |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | run_command | shell in proot | Yes | Yes | Yes | Yes | B | B | N | proot installed | none functionally |
| 2 | read_file | read text file | Yes | Yes | Yes | Yes | N | N | N | none | none |
| 3 | write_file | write or stage file | Yes | Yes | Yes | Yes | B | N | N | none | none |
| 4 | list_files | list directory | Yes | Yes | Yes | Yes | N | N | N | none | none |
| 5 | search_files | pattern search | Yes | Yes | Yes | Yes | N | N | N | none | linear scan, no ignore rules |
| 6 | git_status | working tree state | Yes | No | Yes | Yes | B | B | B | git in proot | unreachable from chat |
| 7 | git_diff | diff output | Yes | No | Yes | Yes | B | B | B | git in proot | unreachable |
| 8 | git_branch | list/create/switch/merge/delete | Yes | No | Yes | Yes | B | B | B | git in proot | unreachable; destructive actions unapproved |
| 9 | git_commit_push | commit and push | Yes | No | Yes | Yes | B | B | B | git plus credentials | unreachable; needs explicit consent design |
| 10 | git_pull_rebase | pull or rebase | Yes | No | Yes | Yes | B | B | B | git plus credentials | unreachable; conflict handling unproven |
| 11 | save_secret | store a secret | Yes | No | Yes | Yes | B | B | B | none | intentionally blocked in AGENT via allowsTool, but also absent from ALL_TOOLS |
| 12 | get_secret | read a secret | Yes | No | Yes | Yes | B | B | B | none | same |
| 13 | detect_secrets | scan text or file | Yes | No | Yes | Yes | B | B | B | none | unreachable |
| 14 | web_fetch | HTTP GET | Yes | No | Yes | Yes | B | B | B | network | unreachable |
| 15 | web_search | web query | Yes | No | Yes | Yes | B | B | B | network plus backend | unreachable |
| 16 | save_memory | agent memory write | Yes | No | Yes | Yes | B | B | B | none | unreachable |
| 17 | read_memory | memory read | Yes | No | Yes | Yes | B | B | B | none | unreachable |
| 18 | delete_memory | memory delete | Yes | No | Yes | Yes | B | B | B | none | unreachable |
| 19 | list_connectors | list plugin connections | Yes | No | Yes | Yes | B | B | B | connector auth | unreachable |
| 20 | connect_service | start OAuth | Yes | No | Yes | Yes | B | B | B | OAuth app config | unreachable |
| 21 | use_connector | call connector API | Yes | No | Yes | Yes | B | B | B | token | unreachable |
| 22 | request_connector | connector card | Yes | No | Yes | Yes | B | B | B | none | unreachable |
| 23 | create_entity | create record | Yes | No | Yes | Yes | B | B | B | backend config | unreachable |
| 24 | read_entities | read records | Yes | No | Yes | Yes | B | B | B | backend config | unreachable |
| 25 | update_entity | update record | Yes | No | Yes | Yes | B | B | B | backend config | unreachable |
| 26 | delete_entity | delete record | Yes | No | Yes | Yes | B | B | B | backend config | unreachable, destructive |
| 27 | schedule_task | schedule work | Yes | No | Yes | Yes | B | B | B | scheduler | unreachable |
| 28 | list_tasks | list scheduled work | Yes | No | Yes | Yes | B | B | B | scheduler | unreachable |
| 29 | cancel_task | cancel scheduled work | Yes | No | Yes | Yes | B | B | B | scheduler | unreachable |
| 30 | upload_file | upload to URL | Yes | No | Yes | Yes | B | B | B | destination URL | unreachable |
| 31 | install_package | apt/pip/npm install | Yes | No | Yes | Yes | B | B | B | proot | unreachable, high risk |
| 32 | plan | plan steps | Yes | No | Stub only | Yes | B | B | B | real plan store | returns "Plan staging returns with the new chat." (`:177`) |

Beyond these 32, MCP tools (`mcp_<server>_<tool>`) are registered dynamically and executed through the same gate, but they are not in `ALL_TOOLS` either, so they are equally unreachable from the chat model. Anything a shell user sees above 32 is an MCP tool from a configured, enabled server with a populated cache (`McpClientManager.kt:432-438`).

### 3.5 Sophisticated capabilities, present versus usable

| Capability | Present in code | Reachable from chat |
|---|---|---|
| Git status, diff, branch, commit, pull | Yes, tools 6 to 10 | No |
| Symbol search, go to definition, find references | No tool; LSP exists only inside the editor | No |
| LSP diagnostics, compiler errors | Editor squiggles only, never exposed | No |
| Gradle build, parsed build errors | `BuildRunner`, `GradleErrorParser` (not tools) | No |
| Test execution and results | Absent from both | No |
| Multi-file editing | Staged writes, multiple allowed | Partially, via write_file |
| Open buffer, selection, cursor | Selection resolver for model identity only, no editor context | No |
| Terminal session observation | One-shot exec only, no session | No |
| Planning | Stub | No |
| MCP / plugins | Yes | No |
| Memory | Yes | No |
| Connectors, entities, scheduler | Yes | No |

## 4. Persistent chat mode

| Field | Information |
|---|---|
| Current behaviour | Mode resets to Ask on every launch. |
| Desired behaviour | Last selected mode survives navigation, conversation switch, app restart and process death. |
| Root cause | Mode is Compose-local state and is never persisted: `var chatMode by remember { mutableStateOf(ChatMode.ASK) }` (`ChatPanelScreen.kt:110`), and the session default is `internal var mode: ChatMode = ChatMode.ASK` (`ChatSession.kt:284`). `ChatSettingsPrefs` persists selection and context overrides only (`ChatSettingsPrefs.kt:14-15,103-111`); there is no mode key. |
| Evidence | `ChatPanelScreen.kt:110`, `ChatSession.kt:284`, `ChatSettingsPrefs.kt` (no mode reader or writer), `ChatToolbarSpec.kt:25-31` (enum defined, never stored) |
| Existing capability | A working settings store with the same SharedPreferences backend and a proven read/write pattern. |
| Gap | One string key plus load at composition and save on change. |
| Proposed solution | Add `mode` get/set to `ChatSettingsPrefs` (default `ASK`, unknown values treated as `ASK`). Initialise the panel state from prefs, write on every change. Leave provider and model persistence untouched; they keep their own keys and their selection-coherence rules. If per-conversation modes are ever wanted, add a conversation field later, not now. |
| Risk | Very low. A malformed stored value must fall back, never crash. Migration is a no-op because the key is new. |
| Dependencies | None. |
| Approval required | No for persistence itself, but it is bundled with the chat-mode UI, so confirm. |

## 5. Collapsible agent activity and thinking

| Field | Information |
|---|---|
| Current behaviour | Each tool step is its own full-width row, so a five-step task leaves five blocks permanently occupying the conversation. Thinking is a separate `ChatRowModel.Thinking(seconds)` row (`ChatRowModel.kt:10`), and failures are `ChatRowModel.Failed` (`:11`). |
| Desired behaviour | Progress and activity grouped into one compact section, collapsed by default, chevron to expand, activities such as "Searching the project files" visible inside, running versus completed states distinguishable, failures always visible. |
| Root cause | No grouping concept. Step notes are created one per step with only a text payload, and nothing ties consecutive steps of one turn together. |
| Evidence | `ChatSession.kt:607`, `ChatRowModel.kt:8-24`, `ChatListRenderer.kt:63` |
| Existing capability | `AgentToolWire.stepLabel` already produces human-readable step names (`:184-191`), and session state is an immutable value that can be reshaped without touching execution. |
| Gap | A dedicated step row type, a turn-scoped group row, and an expand/collapse state owned by the UI. |
| Proposed solution | 1) Replace the `InfoNote` misuse at the step site with a new `ChatRowModel.Step(label, summary, state)` so honest informational notes keep their own type. 2) Add `ChatRowModel.ActivityGroup(steps, collapsed, running)` and let the renderer draw a single header ("Working, 4 steps" or "4 steps") with a chevron. 3) Collapsed by default; expansion state kept in UI state keyed by group id, cleared when the turn completes. 4) Any `Step` whose execution failed, plus every `Failed` row, is forced visible with its text. No chain-of-thought: only `stepLabel` text, tool summaries and status are shown, which is exactly the current data. |
| Risk | Medium. Grouping changes how the transcript looks and can hide a failure if the rule is wrong, so the failure-visibility rule needs a test. |
| Dependencies | Same row-id work as section 2. Implement both together. |
| Approval required | Yes (design). |

## 6. General chat-history UI redesign

| Field | Information |
|---|---|
| Current behaviour | Rows are rendered by `ChatListRenderer` (`:63`) with `MarkdownView` for assistant text; code blocks render a label, a Copy button and an Insert button (`MarkdownView.kt:125-190`, Copy at 171, Insert at 183). Typography and spacing are uniform and fairly large, and every row is separated the same way. |
| Desired behaviour | Compact, modern phone-sized conversation: smaller readable type, clear hierarchy, tighter grouping of consecutive assistant content, distinct user versus assistant treatment, better code blocks, quieter errors, fewer dividers. Code blocks keep a tiny copy icon and lose the visible Insert button, without losing the underlying insert capability. |
| Root cause | Presentation only. There is no type scale or spacing token set for the chat list; each row type draws itself with local paddings. |
| Evidence | `ChatListRenderer.kt:20,54,63`, `MarkdownView.kt:44-54,125-190`, `ChatPanelScreen.kt:171` (`onInsertCode = { code -> EditorInsertBus.insert(code) }`) |
| Existing capability | Markdown rendering, fenced-block parsing, clipboard copy, and the editor insert bus all work. |
| Gap | A small design system for the chat list, and a decision on Insert. |
| Proposed solution | Introduce a chat typography and spacing token object, apply it in the renderer only, group consecutive assistant rows into one block with paragraph spacing inside, keep user rows as bubbles, render errors as a compact bordered strip rather than a large card. For code: hide the Insert button visually, keep `onInsertCode` plumbing intact so nothing else changes, and keep Copy. Removing `EditorInsertBus` itself would be a functional deletion and is not proposed. |
| Risk | Low functionally, medium aesthetically. Changing row composition can regress the transcript scroll and the auto-scroll anchor (`TranscriptAutoScroll.kt`), which must be re-tested. |
| Dependencies | Do this after the activity grouping, so both land in one renderer pass. |
| Approval required | Yes (design). |

## 7. Response truncation investigation

| Field | Information |
|---|---|
| Current behaviour | Replies sometimes stop mid-answer and are presented as finished, with no indication that the provider stopped early. |
| Desired behaviour | Either no truncation, or an honest, resumable state. |
| Root cause (primary, confirmed) | The engine never reads a stop reason, and the default output ceiling is 4096 tokens. |
| Evidence | `EngineModels.kt:45` `val maxResponseTokens: Int = 4096`; sent to providers at `OpenAIAdapter.kt:30,70`, `AnthropicAdapter.kt:42,87`, `GeminiAdapter.kt:30,66`; `ProviderAdapter.kt:32,35` exposes only `extractDelta` and `isDoneSentinel`; grep for `stop_reason` and `finish_reason` across `engine/` and `app/src/main` returns nothing; `ChatEngineImpl.kt:59-64` emits `Completed` on the `[DONE]` sentinel and `:70-80` emits `Completed` when the stream ends without one (comment: "treat as completed (honest, no invented failure)"); `ChatSession.kt:486` then renders the text as a normal answer and saves it with `complete = true`. |
| How it causes truncation | A provider that hits the output ceiling stops with `finish_reason = "length"` (OpenAI style), `stop_reason = "max_tokens"` (Anthropic) or `finishReason = "MAX_TOKENS"` (Gemini) and closes the stream. The parser sees no error and either the sentinel or EOF, so `Completed` fires and a partial answer is presented as complete. 4096 output tokens is small for code output: a single file write plus explanation routinely exceeds it. |
| Secondary causes | (a) Text-only protocol: if the ceiling lands mid `<tool>` block, `decide()` sees a partial call and either refuses or treats the fragment as an answer (`AgentToolWire.kt:103-120`). (b) `extractDelta` scans raw JSON with string anchors (`OpenAIAdapter.kt:91-97`, similar in Anthropic and Gemini), so a chunk boundary inside an escape sequence can drop characters. Suspected, not proven. (c) `MAX_ROUNDS = 6` stops long multi-step work, but it publishes an explicit note (`AgentToolWire.kt:114-117`), so it is not silent. (d) History trimming is also announced (`EngineEvent.HistoryTrimmed`). (e) Transport is idle-timeout based (120 s between bytes, `ChatTransport.kt:38-46`), so a slow stream does not truncate by wall time. |
| Suspected versus confirmed | Confirmed: missing stop-reason handling plus a 4096 ceiling. Suspected: escape-boundary parse loss. Not a cause: read timeouts, history budget, round cap (all announced). |
| Proposed solution | 1) Extend `ProviderAdapter` with `fun finishReason(data: String): String?` per adapter. 2) Track the last reason in `ChatEngineImpl` and emit `EngineEvent.Truncated(reason)` instead of `Completed` when the reason is length or max_tokens. 3) In `ChatSession`, render a compact "Reply hit the output limit" row with a Continue action that appends a continuation request seeded with the partial text. 4) Raise `maxResponseTokens` only with evidence, ideally per provider from `ModelCatalog`, and only with your approval. 5) Add a parser hardening test with chunk boundaries inside escapes. |
| Risk | Medium. A wrong stop-reason mapping could label complete replies as truncated; per-provider tests are mandatory. Auto-continue is deliberately not proposed: one tap, user-controlled. |
| Dependencies | None blocking; touches engine and chat session. |
| Approval required | Yes for the ceiling change; the honest labelling alone can be approved separately. |

## 8. Archived repository comparison

Archive revision `063c4ee12a34` (2026-10-27), 129 files. It is a different application generation: one flat `src/` module, chat providers in `src/chat/providers/*`, screens in `src/ui/screens/*`, plus `extensions-own/remotion` as an example of an in-tree extension. The current repository is a modular rebuild (`app` plus `engine`) with a new chat panel.

| Area | Current (`dd70884`) | Archive (`063c4ee`) | Classification |
|---|---|---|---|
| Module architecture | `app` and `engine` split, per-provider adapters, URL contracts, budget guard, history budget | Flat `src/`, providers in `src/chat/providers` | Improved in current |
| Provider and model selection | `SelectionResolver` with `Ready/Unavailable/NeedsSetup`, endpoint registry, per-endpoint keys, storage-unreadable protection | `ChatModelSelection.kt`, `ProviderModelCache.kt`, `CustomEndpointStore.kt` | Improved in current |
| Tool protocol | Text `<tool>` blocks only (`AgentToolWire.kt:12-14`) | `NativeToolProtocol.kt` converts text calls to native `tool_calls` and parses streamed `tool_calls` deltas | Present only in archive |
| Tool exposure | 5 tools advertised | Archive has a much larger executor set too, but exposure is governed by its own protocol file | Present in both, gap in current |
| Execution evidence | None; prose can claim success | `ToolExecutionEvidence.kt` prepends a per-turn receipt table and labels unverified prose | Present only in archive |
| Planning | `plan` stub (`AgentTools.kt:174-177`) | `ChatPlanStore.kt`, `ChatPlanCard.kt` | Removed in current |
| Diff review | Staged writes, accept per file | `ChatDiffReviewCard.kt` | Improved in archive for review UI |
| Attachments | System picker, byte-capped, chips | `AttachmentIntake.kt`, `AttachmentTypeSniffer.kt`, `ChatAttachPicker.kt`, PDF and ZIP extractors, search-results attach | Improved in archive |
| Context sources | Multi-root block plus active file (`WorkspaceContextSource.kt:29-93`) | `ChatContextSources.kt`, `ChatContextChip.kt`, `WorkspaceContextProvider.kt` (tree, imports, project type) | Archive richer in UI, current richer in workspace roots |
| Custom agent modes | None | `CustomModeStore.kt` reads `.agent.md` and `.chatmode.md` | Present only in archive |
| Skills | None | `SkillsCatalog.kt` | Present only in archive |
| Chat search and extras | None | `ChatFindBar.kt`, `FollowUps`, `QueueBar`, `SessionIO` | Present only in archive |
| Mode persistence | Not persisted | Not verified in archive either | Unknown |
| Truncation handling | No stop-reason handling | Not verified; likely same family | Unknown |
| MCP | `McpClientManager`, 90 s bound, same approval gate | Not found by name in tree | Improved in current |
| Tests and CI | 115 suites, 1039 tests, guarded | Not verified | Improved in current |

Archive also contains planning documents (`docs/rebuild-plan.md`, `known-problems.md`, `feature-map.md`, `stored-formats.md`, `send-loop.md`). They are documentation, not implementation, and are cited here only as context for why the rebuild happened, not as evidence of behaviour.

Recommendation: treat four archive capabilities as the strongest re-import candidates, each behind approval: native tool calls, execution evidence, contextual attachments (selection and search results), and diff review.

## 9. Attachment picker architecture and redesign

| Field | Information |
|---|---|
| Current behaviour | The composer's plus button opens the Android document picker with `"*/*"` (`ChatPanelScreen.kt:105-107,214`). The chosen URI is read once, capped, staged as bytes, and shown as a chip row (`ChatPanelScreen.kt:158`). |
| Desired behaviour | A small file-manager style sheet: workspace browsing with folders, parent navigation, type-appropriate icons, clear selection, optional multi-select, plus "Add from phone" through the system picker, permission-aware. |
| Root cause | There is no workspace browsing UI at all; the picker is the OS document chooser. |
| Evidence | `ChatPanelScreen.kt:103-107,158,214,555-575` (byte-capped read), `chat/AttachmentSecrets.kt:30-31` (session and global caps) |
| Existing capability | Attachment staging, chip model, removal callback, and per-session and global byte caps all exist. File access inside the project already exists for tools. |
| Gap | Workspace listing, navigation state, icon mapping, multi-select, and a decision on folder-level attachment. |
| Proposed solution | A Compose bottom sheet with a path stack over the active workspace root, `File.listFiles` with the same ignore conventions used elsewhere, MIME by extension, selection set, and a fixed "Add from phone" row that launches the existing picker. Keep staging and caps unchanged. Do not implement folder attachments: the model has no consumer for them. |
| Risk | Low to medium. Large directories must be paged or capped, and the sheet must not run file IO on the main thread. |
| Dependencies | Workspace root access already available through the project layer. |
| Approval required | Yes, and you said you may supply more screenshots. |

## 10. Plugins architecture and design

| Field | Information |
|---|---|
| Current behaviour | Only MCP exists: `McpClientManager` loads server config, caches tool names, produces docs (`:452`), calls tools with a 90 s bound (`:478-484`), and inherits the standard approval gate. There is no Plugins UI, no per-plugin connection state, no plugin activity presentation, and the word Plugin does not appear in the interface. |
| Desired behaviour | Plugins as first-class IDE capabilities: discoverable, installable, authenticated, permissioned, visible in conversation with execution state and results, in all three modes, with the same reusable presentation for every plugin. Naming is Plugins everywhere, never Connectors. |
| Root cause | MCP is plumbing only. Discovery, auth and presentation were never built, and MCP tools are not advertised to the chat model (see section 3). |
| Evidence | `McpClientManager.kt:432-484`, `AgentTools.kt:217-222`, `AgentApiServer.kt:190,239`, absence of any Plugins surface in `chat/panel` |
| Existing capability | A working transport (stdio MCP inside the proot session), a tool naming convention (`mcp_<server>_<tool>`), a cache, docs generation, approval inheritance, and a connector manager for OAuth-style services (`AgentConnectorManager`). |
| Gap | Tool exposure to the model, discovery and install flow, credential storage per plugin, connection state, permission scoping, activity and result presentation, retries, and error surfaces. |
| Proposed solution | Four layers, each separately approvable: (a) exposure, add enabled MCP tool names and docs to the prompt the same way builtin tools are added, with per-mode allowlists; (b) registry UI, a Plugins screen listing installed servers with connection state, enable/disable, and a per-plugin tool list; (c) auth and secrets, per-plugin key slots reusing the encrypted store pattern; (d) presentation, a generic plugin-activity row showing plugin name, tool, state and a collapsible result, reusing the activity group from section 5. Security rules: plugin output is untrusted text, plugin tools never bypass approval, destructive plugin tools require explicit confirmation, and network access stays inside the existing proot and HTTPS constraints. |
| Risk | High. Untrusted tool output is a prompt-injection vector; credential handling per plugin adds surface. This should be last, after tool exposure and the activity UI exist. |
| Dependencies | Section 3 (exposure) and section 5 (activity grouping) are prerequisites. |
| Approval required | Yes, and explicitly deferred by you until last. |

## 11. Recommended implementation order

1. Chat mode persistence (small, isolated, immediate quality-of-life).
2. Truncation honesty: stop-reason parsing plus a truncated state row, no ceiling change yet.
3. Tool exposure fix, staged: advertise the full builtin registry with proper per-mode allowlists and send `TOOLS_DESCRIPTION`, keeping secrets and destructive entity and git-push tools blocked, then re-open the remaining tools in a second, approved step.
4. Activity rows: row identity, `Step` type, collapsible group, dismiss button, failure-visibility rule.
5. Chat history typography pass and code-block control cleanup (with the Insert decision).
6. Continuation action for truncated replies, and optional ceiling tuning per provider.
7. Attachment picker redesign.
8. Plugins: exposure, registry UI, auth, presentation.

Rationale: 1 and 2 are cheap and independent. 3 is the largest capability gain per line changed, but it widens the blast radius, so it goes after the honest failure surface from 2 exists. 4 and 5 share the renderer and should land together. 8 depends on 3 and 5.

## 12. Risks and dependencies

| Work | Main risk | Mitigation |
|---|---|---|
| Mode persistence | None material | Fallback to Ask on unknown value |
| Stop-reason honesty | Mislabel complete replies | Per-provider tests with recorded chunks |
| Tool exposure | Destructive or secret tools reaching the model | Per-mode allowlist, category review, approval gate, staged rollout behind the existing `ChatPermissionStore` levels |
| Activity grouping and dismissal | A failure gets hidden | Type-aware visibility rule plus a renderer test |
| Renderer redesign | Scroll anchoring and transcript restore regressions | Re-run transcript tests, verify auto-scroll anchor |
| Picker | Main-thread IO, huge directories | Paging or caps, IO on dispatcher |
| Plugins | Prompt injection, credential exposure | Untrusted marking, approval, per-plugin secrets, no autonomous install |

Cross-cutting dependencies: any change to `ChatRowModel` affects the renderer, the transcript mapping and the design gallery activity (`chat/ui/ChatDesignGalleryActivity.kt`). Any change to tool exposure affects the system prompt, `AgentToolWire`, and every mode test.

## 13. Affected files and components

| Area | Files |
|---|---|
| Mode persistence | `chat/panel/ChatPanelScreen.kt`, `chat/panel/ChatSettingsPrefs.kt`, `chat/panel/ChatSession.kt` |
| Tool exposure | `chat/panel/AgentToolWire.kt`, `chat/panel/ChatSession.kt`, `agent/AgentTools.kt`, `agent/McpClientManager.kt` |
| Activity rows | `chat/ui/ChatRowModel.kt`, `chat/ui/ChatListRenderer.kt`, `chat/panel/ChatSession.kt`, `chat/ChatTranscript.kt` (mapping only) |
| Truncation | `engine/.../ProviderAdapter.kt`, `OpenAIAdapter.kt`, `AnthropicAdapter.kt`, `GeminiAdapter.kt`, `ChatEngineImpl.kt`, `EngineModels.kt`, `chat/panel/ChatSession.kt`, `chat/panel/ChatStatusMapping.kt` |
| History UI | `chat/ui/ChatListRenderer.kt`, `chat/ui/MarkdownView.kt`, `chat/panel/ChatPanelScreen.kt`, `chat/ui/TranscriptAutoScroll.kt` |
| Picker | `chat/panel/ChatPanelScreen.kt`, new picker sheet file, `chat/AttachmentSecrets.kt` (caps unchanged) |
| Plugins | `agent/McpClientManager.kt`, `chat/panel/AgentToolWire.kt`, `agent/AgentTools.kt`, new Plugins screen, `chat/ui/ChatRowModel.kt` |

## 14. Decisions requiring your approval

1. Whether the dismiss button should be session-only or persisted per transcript. Recommended: session-only.
2. Which of the 32 tools to expose per mode. Recommended first wave: the current five plus `git_status`, `git_diff`, `git_branch` (read and list actions only), `web_fetch`, `read_memory`, `save_memory`, `list_files` and `search_files` already present. Secret, entity, scheduler, upload and install tools stay blocked.
3. Whether secret tools are ever model-callable. Recommended: no.
4. Whether to raise the output ceiling beyond 4096, and to what, per provider. Needs your call.
5. Whether to adopt native `tool_calls` (archive precedent) or stay on the text protocol for now. Recommended: stay text until exposure is fixed, then evaluate.
6. Whether the Insert code button is removed outright or hidden behind an overflow. Recommended: hidden, plumbing kept.
7. Activity grouping: collapsed by default, expanded when any step failed. Confirm.
8. Whether the transcript keeps the existing note roles or gains structured step records for future replay.
9. Picker scope: workspace browsing only, or also folder attach. Recommended: files only.
10. Plugin sequencing: confirm Plugins remain last, after exposure and activity presentation.
