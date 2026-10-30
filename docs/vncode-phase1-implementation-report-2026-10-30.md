# Phase 1 implementation report

Date: 2026-10-30. Scope: the owner-approved Phase 1 tool exposure work only. Nothing from Phase 2 or later was implemented: no Extensions, no Plugins, no marketplace, no MCP expansion, no `save_memory` exposure, no conversation retrieval, no `GitService` refactor, no editor callback extraction, no memory persistence change.

## 1. Starting state

| Item | Value |
|---|---|
| Branch | `main` |
| Starting HEAD | `dd7088447f4e57dc5da2ab250d9a96cd3badf974` |
| Working tree at start | Four untracked docs files (prior audit deliverables), no modifications, nothing staged |

## 2. Approved decisions implemented

| Decision | Implementation |
|---|---|
| 1, wave-one exposure | Ten read-only tools advertised; see section 4 |
| 2, secrets never exposed | `save_secret` and `get_secret` carry a written reason and are refused in all three modes, tested |
| 3, destructive tools blocked | `git_pull_rebase`, `delete_entity`, plus `upload_file` and `install_package`, refused in all modes, tested |
| 5, one registry | `ToolRegistry` is the single source for names, exposure and invocation; `AgentToolWire` delegates |
| 6, schema-first | Every advertised tool carries a description and a JSON schema; the prompt sends both |
| 9, sequencing | Phase 1 only |
| 10, Phase 1 only | Confirmed by this report's scope |
| Plan turn-ending rule | Recorded as the approved rule; `plan` stays hidden and its reason says so. No store was built, because implementation was deferred |

## 3. Files changed

| File | Change |
|---|---|
| `app/src/main/java/com/codespace/ide/chat/panel/ToolRegistry.kt` | New. 32 `ToolSpec` entries: 12 exposed across modes, 20 hidden with reasons. `allows`, `exposed`, `hidden`, `promptBlock` |
| `app/src/main/java/com/codespace/ide/chat/panel/AgentToolWire.kt` | `READ_ONLY_TOOLS` and the per-mode sets removed; `allowsTool` delegates to the registry; `protocol` builds descriptions plus schemas; `ALL_TOOLS` derives from the registry; `stepLabel` falls back to the registry display name |
| `app/src/main/java/com/codespace/ide/agent/AgentTools.kt` | `git_status` and `git_diff` results pass through `ToolOutputBounds.bound` |
| `app/src/main/java/com/codespace/ide/agent/AgentEntityManager.kt` | `read` distinguishes an unavailable store from an empty one, with three explicit configuration messages |
| `app/src/main/java/com/codespace/ide/util/ToolOutputBounds.kt` | New. 8000-character cap with an honest truncation marker |
| `app/src/test/java/com/codespace/ide/chat/panel/ToolRegistryTest.kt` | New. 15 parity tests |
| `app/src/test/java/com/codespace/ide/util/ToolOutputBoundsTest.kt` | New. 6 bound tests |

Existing `AgentToolWireTest` was not modified, so its assertions are an independent regression check on the old five-tool contract.

## 4. Exposure, exactly

| Mode | Advertised | Count |
|---|---|---|
| Ask | read_file, list_files, search_files, git_status, git_diff, detect_secrets, read_memory, list_tasks, read_entities, list_connectors | 10 |
| Edit | the ten above plus write_file | 11 |
| Agent | the eleven above plus run_command | 12 |

Counts: 32 registered, 12 exposed in at least one mode, 20 hidden with a written reason.

Deviation from the requested "10 exposed / 22 hidden", stated openly: the approved ten are the read-only wave. `write_file` and `run_command` were already advertised before Phase 1 and remain advertised in the modes that previously allowed them. Hiding them would have removed file writing and command execution from Agent mode, which contradicts Part 3 rules 4 and 5 (preserve execution behaviour and the existing Ask/Edit/Agent restrictions). If the owner wants them hidden too, that is a one-line-per-tool change in the registry, but it is a functional regression rather than an exposure fix.

## 5. Hidden tools and reasons

| Tool | Reason recorded in code |
|---|---|
| save_secret, get_secret | Owner decision 2: secrets never exposed to the model |
| git_pull_rebase | Destructive, rewrites history; owner decision 3 |
| delete_entity | Destructive with no undo; owner decision 3 |
| git_commit_push | Writes history and pushes; Phase 2 with a consent card |
| git_branch | Multi-action tool (list/create/switch/merge/delete); needs an action allowlist |
| web_fetch | Network egress with untrusted content; needs a host policy |
| web_search | Network egress; wave 2 behind the same policy |
| save_memory | State-changing durable write; Phase 2 with namespacing, approval and audit |
| delete_memory | Destructive durable change; Phase 2 memory redesign |
| connect_service, use_connector, request_connector | External side effects; Phase 6 plugin permissions |
| create_entity, update_entity | Writes records; wave 2 |
| schedule_task, cancel_task | Background work; wave 2 with explicit consent |
| upload_file | Exfiltration channel; needs its own approval design |
| install_package | Installs software; high blast radius |
| plan | Stub at `AgentTools.kt:177`; stays hidden until a real store exists |

## 6. Tests and results

| Suite | Tests | Failures | Skipped |
|---|---|---|---|
| `:engine:test` | 106 | 0 | 0 |
| `:app:testDevDebugUnitTest` | 953 | 1 | 3 |

The single app failure is `com.codespace.ide.terminal.BackupTarTest.failureMessageCarriesRealTotals`, at `BackupTarTest.kt:211`. Cause identified, not dismissed: the test makes a destination directory non-writable (`dest.setWritable(false)`) and asserts two file failures. The local sandbox runs Gradle as root, and root ignores the 0555 mode, so both writes succeed and `fileFailures` is 0. It is an environment artefact of running as root, in a terminal backup suite untouched by this change, and it passes on the non-root CI runner. It was already failing before Phase 1 on this sandbox.

## 7. New tests

`ToolRegistryTest` (15): registry equals `AgentTools.toolNames()`; 32 unique; every registered tool either exposed with a description or hidden with a reason; hidden set is 20 and exposed plus hidden is exactly 32; Ask is the ten; Edit is the ten plus `write_file` and never `run_command`; Agent is eleven plus `run_command` and never secrets; `allows` agrees with exposure for all 32 tools in all 3 modes; every exposed schema is an object with properties and balanced braces; every schema declares the arguments the executor reads (required versus optional checked separately); secrets never exposed; destructive tools never exposed; `save_memory` not exposed and its reason names the deferral; `plan` hidden and refused; the mode protocol carries schemas and leaks no hidden name.

`ToolOutputBoundsTest` (6): short output untouched; exactly-at-cap untouched; long output capped with a marker naming the original size; marker uses the requested cap; empty stays empty; a non-positive cap never truncates.

## 8. Behaviour changes, in one line each

- The model now sees descriptions and argument schemas instead of bare names.
- Seven previously unreachable read tools are usable in chat.
- A call to a hidden tool is still refused, now by registry rule rather than by a second hand-written list.
- `git_status` and `git_diff` output is capped at 8000 characters with an honest marker.
- `read_entities` on an unavailable store says so instead of implying the entity is empty.

## 9. Remaining issues

1. `git_status`/`git_diff` still use the shell path rather than `scm/GitService`, by instruction. The duplication is unchanged and belongs to a later phase.
2. `read_entities` has no backend to be unconfigured; the store is local. The configuration messages therefore cover storage unavailable, unreadable and unlistable. If a remote backend is planned, its absence needs its own message.
3. The `plan` turn-ending rule is recorded but unenforceable while `plan` is hidden and stubbed.
4. Conversation-aware memory is designed only, in `docs/vncode-phase2-memory-architecture-proposal-2026-10-30.md`.
