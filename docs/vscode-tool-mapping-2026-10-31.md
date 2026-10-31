# VS Code tool inventory → VN Code mapping (2026-10-31)

Source of truth: `microsoft/vscode-copilot-chat/src/extension/tools/common/toolNames.ts`, read
directly, not from secondary write-ups. Counts below are from the live registry in
`chat/panel/ToolRegistry.kt`: **79 entries, 62 executable, 17 registered-but-not-offered with a
written reason.**

Mode legend: **A** = Ask (read-only), **E** = Edit, **G** = Agent. A tool's modes decide whether
the model is even shown its schema; `ToolRegistry.allows(mode, name)` is the single gate, and
`AgentToolWire.decide` refuses calls that bypass it.

## Executing tools

| VS Code tool | VN Code tool | Modes | Notes |
| --- | --- | --- | --- |
| `read_file` | `read_file` | A E G | Staged-overlay aware |
| `list_dir` | `list_dir`, `list_files` | A E G | Same implementation, both names |
| `grep_search` | `grep_search`, `search_files` | A E G | Regex over file text |
| `file_search` | `file_search` | A E G | Filename match, no content scan |
| `semantic_search` | `semantic_search` | A E G | **Keyword ranking, not an embedding index** and says so in its output |
| `search_workspace_symbols` | `search_workspace_symbols` | A E G | Text-based declaration scan, labelled as such |
| `read_project_structure` | `read_project_structure` | A E G | Depth- and entry-capped tree |
| `get_errors` | `get_errors` | A E G | Real diagnostics via DiagnosticManager, stale marked |
| `get_changed_files` | `get_changed_files` | A E G | Working-tree diff list |
| `create_file` | `create_file` | E G | Refuses to clobber an existing file |
| `replace_string_in_file` | `replace_string_in_file`, `insert_edit_into_file` | E G | Exact-match replacement |
| `multi_replace_string_in_file` | `multi_replace_string_in_file` | E G | Several edits, all or nothing |
| `apply_patch` | `apply_patch` | E G | Strict unified diff; context mismatch refuses the whole patch |
| `create_directory` | `create_directory` | E G | |
| `edit_notebook_file` | `edit_notebook_file` | E G | Add (`index -1`) or replace a cell; clears outputs |
| `create_new_jupyter_notebook` | `create_new_jupyter_notebook` | E G | Minimal nbformat 4 |
| `copilot_getNotebookSummary` | `copilot_getNotebookSummary` | A E G | One line per cell |
| `read_notebook_cell_output` | `read_notebook_cell_output` | A E G | Saved text outputs only |
| `manage_todo_list` | `manage_todo_list` | E G | `.codespace/todos.json`; empty argument reads it back |
| `run_in_terminal` | `run_in_terminal`, `run_command` | G | Approval-gated |
| `get_terminal_output` | `get_terminal_output` | G | The **agent command record**, not the interactive shell |
| `terminal_last_command` | `terminal_last_command` | G | Same source |
| `run_notebook_cell` | `run_notebook_cell` | G | Writes the cell to a temp `.py` and runs `python3`; **no kernel session is kept** |
| `fetch_webpage` | `web_fetch` | G | Network egress, response treated as untrusted |
| `memory` | `save_memory`, `read_memory`, `delete_memory` | save: G, read: A E G, delete: never | Deleting has no undo, so it stays off |
| `install_extension` | `install_package` | G | High blast radius, Agent only |
| (connector consent) | `request_connector` | A E G | Shows the Connect card only; the user finishes the OAuth in the Connectors Hub |

## Registered, deliberately not offered

Each of these has its reason written in code next to the spec, and a test enforces that a
hidden tool carries a non-blank reason.

| VS Code tool | Why not offered |
| --- | --- |
| `get_search_view_results` | There is no persistent search view; our search returns hits inline |
| `github_repo` | Needs a GitHub connector with repo scope plus a repo-context model |
| `run_vscode_command` | VS Code command ids have no mobile equivalent |
| `terminal_selection` | No selection API reaches the tool layer |
| `runSubagent` | No subagent runtime (and the owner forbids delegating their own work) |
| `search_subagent` | Same |
| `execution_subagent` | Same |
| `vscode_get_confirmation` | Superseded by `AgentFlowGate`, which gates the call itself rather than trusting the model to ask |
| `vscode_get_confirmation_with_options` | Same reason |
| `vscode_get_terminal_confirmation` | `run_in_terminal` already passes the same gate |
| `vscode_askQuestions` | Needs a blocking question card in the chat surface |
| `switch_agent` | Mode is a user control; a model that can switch its own mode can grant itself permission |
| `tool_search` | The protocol sends every tool for the mode, so there is nothing to search |
| `resolve_memory_file_uri` | Memories are keyed, not URI-addressed |
| `get_vscode_api` | No extension host to introspect |
| `create_new_workspace` | A project is the workspace; creating one is a UI flow |
| `view_image` | The tool layer cannot put an image into the model's context; returning a path would be a dead end |

## Tests that keep this honest

- `ToolRegistryTest`: every registered tool is exposed or carries a reason; Ask ⊆ Edit ⊆ Agent;
  nothing executable leaks into Ask or Edit; secrets and memory deletion appear in no mode.
- `everySchemaDeclaresTheArgumentsTheExecutorActuallyReads`: catches a schema that names an
  argument the executor does not read (it already caught `install_package` advertising `name`
  while reading `package`).
- `RepoSearchTest`, `IdeWorkflowToolsTest`: symbol search, structure bounds, multi-edit
  atomicity, patch context verification, notebook add/replace/clear, todo status validation.
