# VS Code / Copilot chat: full wiring research, tools and mode gating

Date: 2026-10-31
Purpose: answer the outstanding research item properly, and provide the tool-by-tool and
mode-by-mode comparison against VN Code that items 10 and 16 depend on.

## 0. Sources, and what this document will not claim

Read for this document:

- `code.visualstudio.com/docs/agents/reference/workspace-context` (workspace context, semantic
  search, `#codebase`, fallback behaviour).
- `code.visualstudio.com/blogs/2025/04/07/agentMode` (mode unification, `edit_file`, built-in
  tool categories, approval scoping, undo grouping).
- `code.visualstudio.com/docs/copilot/reference/copilot-vscode-features` (navigation only; its
  section list is the strongest public evidence for which surfaces exist).
- `raw.githubusercontent.com/microsoft/vscode/main/src/vs/platform/undoRedo/common/undoRedo.ts`
  (MIT) for the undo container, summarised in the separate undo document.

Attempted and NOT available (404 at the time of writing): `/docs/agents/reference/agent-tools`,
`/docs/copilot/chat/chat-tools`, `/docs/copilot/agents/agent-tools`,
`/docs/agents/concepts/memory`, `/docs/copilot/concepts/memory`,
`/docs/copilot/concepts/workspace-context`.

Consequences, stated plainly:

- There is **no public, per-tool, per-mode availability table** that I could read. The mode
  statements below are the ones VS Code's own posts and docs make in prose. Anything finer is
  marked INFERENCE and must not be treated as a spec.
- Copilot's prompt assembly, request pipeline and the internal names/schemas of its built-in
  tools are closed. The open repository `microsoft/vscode` contains the editor, the language
  model tool API, and the undo/redo service, not the Copilot agent loop. Nothing below is
  presented as reading that loop's source.
- Where a claim rests on inference from product behaviour rather than a source, it says so.

## 1. How the chat surface is wired (verified, then inferred)

Verified from the two doc sources:

1. **One unified chat experience.** The Chat view and the Edits view were merged; that merge
   brought session history and multi-session support to every mode, and it applies to agent
   mode too.
2. **All three modes became agentic.** Quoting the April 2025 post: both the ask and edit
   experiences moved to "an architecture that, like the agent, utilizes tools ... This allows
   the edit mode to use the `edit_file` tool for improved speed, and the edit and ask mode to
   use `#codebase`, an agentic codebase search." A direct consequence stated in the same post:
   models without tool calling support are no longer offered in edit mode.
3. **Tools are the unit of capability and of permission.** Agent mode can use built-in tools
   contributed by VS Code, tools from MCP servers, and tools from extensions. Built-ins cover
   searching the workspace, applying code changes, running terminal commands, capturing
   compile or lint errors from the editor, and fetching website content (`#fetch` triggers it
   manually).
4. **Approval is per invocation, with scope.** Every tool invocation is shown in the UI and
   needs approval, except read-only built-in tools; a tool can be allowed for the session, the
   workspace, or all future invocations.
5. **Context is explicit as well as automatic.** `#`-mentions name files, folders, symbols,
   tools and context sources; the context picker and drag-and-drop from the Explorer do the
   same thing without typing. Agents use semantic search automatically when it fits, so
   `#codebase` is not mandatory in a prompt.
6. **Semantic search needs an index.** `#codebase` is semantic search and requires a workspace
   index that Copilot builds and maintains automatically; without an index the agent falls back
   to text search, grep, file search and language intelligence.
7. **Undo groups at tool granularity.** The same April 2025 post states the undo action
   "reverts changes up to the last `edit_file` tool call", which is the clearest published
   statement of where an undo boundary sits in agent mode.
8. **Checkpoints** are listed as planned future work in that post, not shipped functionality in
   that post; the current docs navigation does show review-and-revert surfaces. Treat
   checkpoint semantics as unverified here.

INFERENCE (product behaviour, not source): the request pipeline is chat request -> context
assembly from explicit and automatic sources -> model turn -> tool calls -> approval -> tool
result back into the same request -> repeat until the model stops, with the transcript
persisting per session. That shape is consistent with every published description, and it is
the shape VN Code implements, but I have not read Copilot's loop.

## 2. Tool inventory

### 2.1 Built-in VS Code tools (verified categories, not verified names)

The publishing sources name categories rather than a stable API list. What is verified:

| Category | What it does | Source |
|---|---|---|
| Workspace search | find files, text search, semantic `#codebase` search | April 2025 post; workspace-context doc |
| Apply code changes | `edit_file` (named); multi-file edit application | April 2025 post |
| Run terminal commands | execute in the integrated terminal, monitor output | April 2025 post |
| Editor diagnostics | capture compile and lint errors from the editor | April 2025 post |
| Fetch web content | `#fetch` / `#web` fetches a page | April 2025 post |
| Notebooks | create and edit notebooks | April 2025 post |
| Language intelligence | symbols, definitions, references via the language server | workspace-context doc |
| Problems, SCM changes, terminal output as context | attachable context items | workspace-context doc |

### 2.2 Contributed tools (verified)

- **MCP servers**: local stdio and SSE transports; `#`-referenceable; managed from the Tools UI.
- **VS Code extensions**: contribute language model tools, discoverable in the Marketplace via
  the `@tag:language-model-tools` tag.
- **Extensions API**: `vscode.lm` tool registration and `LanguageModelTool` participation;
  workspace edits made through the API are undoable through the undo/redo service.

### 2.3 Mode availability

Verified statements only:

| Mode | Verified tool facts |
|---|---|
| Ask | agentic; uses `#codebase`; models without tool calling excluded from edit (not stated for ask); read-only by nature of the product ("ask" answers rather than changes files) |
| Edit | uses `edit_file`; uses `#codebase`; requires tool-calling models; scoped edits rather than autonomous loops |
| Agent | may use built-in, MCP and extension tools; runs terminal commands; reads diagnostics; loops until done; approval per invocation with session/workspace/always scopes |

INFERENCE: ask mode holds the read-only subset (search, read, fetch, diagnostics) and has no
file-writing or terminal tools; edit mode holds read plus `edit_file` and no terminal; agent mode
holds everything plus MCP and extension tools. This matches the product's own framing
("edit mode when the task has a well-defined scope ... finer control over the number of LLM
requests") but is not stated as a list anywhere I could read.

## 3. VN Code: what is actually registered and exposed

Read from `chat/panel/ToolRegistry.kt` (single source of truth, enforced by a parity test
against `AgentTools.BUILTIN_TOOL_NAMES`) and `agent/AgentTools.kt`.

33 tools registered. Exposure by mode:

| Mode set | Tools |
|---|---|
| Ask + Edit + Agent (read-only) | `read_file`, `list_files`, `search_files`, `git_status`, `git_diff`, `detect_secrets`, `read_memory`, `list_connectors`, `read_entities`, `list_tasks` |
| Edit + Agent (write) | `write_file` |
| Agent only (execute) | `run_command` |
| Never exposed (written reason in the registry) | `git_commit_push`, `git_pull_rebase`, `git_branch`, `save_secret`, `get_secret`, `web_fetch`, `web_search`, `save_memory`, `delete_memory`, `connect_service`, `use_connector`, `request_connector`, `create_entity`, `update_entity`, `delete_entity`, `schedule_task`, `cancel_task`, `upload_file`, `install_package`, `plan` |

That is 13 tools reachable by the model, 20 registered but deliberately hidden, each with a
recorded reason (owner decisions on secrets, history-rewriting git, destructive deletes, and
egress/exfiltration-class operations).

## 4. Comparison

| Area | VS Code implementation | VN Code implementation | Gap | Recommended adaptation |
|---|---|---|---|---|
| Chat request shape | unified chat; all three modes agentic and tool-driven | one loop, modes differ only by tool set | none structurally | keep; it is the same design |
| Mode model | Ask / Edit / Agent dropdown; mode changes the tool set and the model requirements | Ask / Edit / Agent, `ToolRegistry` gates exposure per mode, persisted per session | VS Code additionally refuses non-tool-calling models in edit; VN Code does not | add a capability check so Edit/Agent cannot be selected with a model that cannot call tools, with an honest refusal |
| Built-in tool breadth | search, edit, terminal, diagnostics, fetch, notebooks, language intelligence | read/write/search/git-read/execute + app-domain tools | no diagnostics tool; no `#`-mention context attach; no notebook tool; no language-intelligence tool | add a diagnostics tool first (highest value per line: Compose/Kotlin errors are already collected), then context attach |
| Semantic codebase search | `#codebase` over a Copilot-maintained index, fallback to grep/file search | `search_files` (keyword) + a workspace block budgeted to a quarter of the payload | no index, no embeddings | do NOT build an index on device yet; improve keyword + symbol ranking first and state the limitation |
| Tool approval | per call, read-only auto-allowed, scopes session/workspace/always | approval gate (`AgentToolWire.decide`) + `ChatApprovalCard`, per-session | VS Code has three consent scopes; VN Code has one | add "allow for this session" as a scope, keep the per-call default |
| Extensibility | MCP + extension-contributed tools | MCP panel exists; extension tool contributions parked (XG01-04) | no third-party tools in chat | keep parked; it is a separate owner decision with its own consent design |
| Undo grouping | reverts up to the last `edit_file` call; checkpoint work planned | per tool round `ChangeSet`, per session, now persisted | VS Code groups at the last edit call; VN Code groups per round | keep per round: it matches the published boundary for agent edits and is what our undo can honestly restore |
| Multi-file edits | workspace undo elements span resources, optional `split()` | `ChangeSet` with per-file before/after images | none material | keep |
| Session model | multiple agent sessions per workspace, session history | per-project session index, per-session mode, pins, per-session journal | VS Code warns against sessions editing the same files; VN Code has no such warning | cheap: warn when two sessions hold unsettled change sets over the same path |
| Memory across restarts | not established by any source read; undo elements are closures | transcript replayed into the engine on restore; journal persisted per session | none vs verified VS Code behaviour | keep; do not imitate an unverified behaviour |
| Context attach | `#`-mentions, picker, drag-drop | in-app attachment picker (folders/files) + system picker | no `#`-token addressing, no symbol attach | add `#`-style addressing later; the picker covers the same need first |

## 5. What this means for items 10 and 16

The parity question is not "match VS Code's tool list". It is "does each VN Code mode expose a
tool set that is coherent, honest about refusals, and able to do the job its label promises".
Measured that way:

- **Ask** — coherent: read-only tools only, no writes, no execution. Missing: diagnostics
  reading, which is what most "why is this broken" questions need.
- **Edit** — coherent: read + `write_file`, no execution. Missing: `edit_file`-style targeted
  replacement, which is why edit-mode requests currently rewrite whole files.
- **Agent** — coherent: everything exposed plus execution. Missing: plugins-by-contribution,
  web egress, and secrets (all deliberately blocked with reasons).

## 6. Licensing

`microsoft/vscode` is MIT. Its `undoRedo.ts` is quotable and reusable with the copyright notice
retained; the notes in this repository paraphrase the interface shape rather than copying code.
Copilot Chat itself is a separate, closed product: nothing here is derived from its code, and
this document contains no Copilot source. VS Code documentation text is quoted only in short
factual phrases, with the source named.

## 7. Open questions I could not close

1. The exact built-in tool names and JSON schemas VS Code sends to the model.
2. Which tool subset ask mode holds, stated as a list.
3. Whether `#codebase` indexing is local, remote, or both per workspace type (the doc says
   "parts of the index might be stored on your machine and parts might come from remote
   sources" without a per-case table).
4. Whether undo history survives a restart in current VS Code. The undo/redo service's elements
   are closures, so no serialisation exists there; hot-exit style backup may cover related
   state, and I have no source establishing it either way.
5. Memory: the docs navigation advertises a Memory section; every page path I tried 404s.

Each of these stays open rather than being filled in from recollection.
