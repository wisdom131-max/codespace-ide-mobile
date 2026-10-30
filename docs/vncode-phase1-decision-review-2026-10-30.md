# Phase 1 decision review

Date: 2026-10-30. Decision review only. No production code, test, dependency or build configuration was modified, and nothing was committed or pushed. Companion to `docs/vncode-extensible-tools-and-extensions-research-2026-10-30.md` (494 lines, 25 sections).

Repository state at review time: branch `main`, HEAD `dd7088447f4e57dc5da2ab250d9a96cd3badf974`, three untracked docs files (the two earlier audits and the research report), nothing modified, nothing staged.

## 1. The ten original decisions

Reproduced verbatim from section 25 of the research report.

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

## 2. Classification of each decision

| # | Decision | Recommendation | Phase 1 required | Reason | Risk if wrong | Depends on | Future impact |
|---|---|---|---|---|---|---|---|
| 1 | Two-wave exposure list | Approve wave one as the ten read-only tools in section 4 of this document, and defer wave two entirely | Yes | This is the actual content of Phase 1. Approving the principle without the list would leave the rollout undefined | Exposing too much too fast (git push, entity delete, uploads) or too little (no visible gain, no feedback) | None, but scope interacts with 5 and 6 | Sets the trust precedent every Extension and Plugin tool will inherit |
| 2 | Secrets tools never exposed | Confirm permanently. Both stay registered, callable by shell users through `/tool/`, never advertised | Yes | `allowsTool` already excludes them in AGENT (`AgentToolWire.kt:56-60`) but the exclusion is vacuous today. Once exposure is registry-driven, that guard becomes live and must be deliberate | A model with `get_secret` can read every stored API key into a chat transcript, which is then persisted and sent to a third-party provider | None | Extensions and Plugins will request secret access. A firm builtin precedent is the cheapest possible policy foundation |
| 3 | Keep `git_pull_rebase` and `delete_entity` blocked | Confirm blocked. If ever enabled, require a dedicated consent card naming the repository or the records | Yes | Both are destructive and irreversible at this layer. Rebase rewrites history; entity delete has no undo | Silent history rewrite or data loss on a phone, with no recovery path | None | Defines whether destructive third-party tools are presumptively blocked. Recommend presumption: blocked |
| 4 | Implement `plan` properly | Approve the goal, defer implementation out of Phase 1. Keep the stub honest and keep `plan` hidden until then | No, but decide the turn-ending rule now | Phase 1 is exposure and parity. A half-built plan store touching persistence would enlarge the risk surface for no exposure gain. The rule matters early because it shapes the row model Phase 1's activity rows will use | Shipping a plan tool that does not end the turn makes the model narrate a plan it then executes anyway, which is exactly the misleading behaviour we are removing | 1 (exposure list) | Planning is a built-in Agent capability, not an Extension concern, but Extensions may later contribute plan steps |
| 5 | Single registry plus per-request exposure | Approve. Registry is the only source of truth for listing and invocation | Yes | Without it, Phase 1 is just a longer hand-written list, and the next capability re-opens the same bug. This is the one architectural decision that determines whether the problem recurs | A second parallel list drifts again, which is precisely the defect being fixed | 6 (schemas) practically | Everything downstream depends on it: Extensions register, Plugins register, MCP registers |
| 6 | Schema-first tool definitions | Approve. JSON schema per tool, replacing prose examples as the machine contract | Yes | The model needs argument schemas, not prose. Prose in `TOOLS_DESCRIPTION` is already drifting from the executor | The model guesses argument names, produces malformed calls, and the failure looks like a model weakness rather than a schema gap | None | Extension manifests will carry schema files; deciding the format now avoids two formats |
| 7 | Extension versus Plugin split and naming | Approve the split and the rule that UI and docs say Plugins, never Connectors | No | No Phase 1 code depends on it. But approving it early stops the terminology from spreading in the new chat copy | Inconsistent terminology across UI, docs and code, expensive to unpick later | None | Foundational for both platforms |
| 8 | Android runtime tiering | Approve direction, no commitment to implementation | No | Runtime choice constrains Extension design, not tool exposure | Choosing Node or Electron-style hosting would waste months on a device that cannot carry it | 7 | Determines what Extensions can be installed at all |
| 9 | Phase 1 to Phase 7 sequence | Approve as a sequence, with Phase 1 alone authorised now | Yes, in the sense that the ordering is approved while only Phase 1 is started | Prevents Phase 3 work (LSP extraction) from jumping ahead of Phase 2 contracts and creating rework | Parallel tracks with no shared contract, then a redesign | 10 | The sequence is the long-term plan for both platforms |
| 10 | Start with Phase 1 only | Approve | Yes | Phase 1 is small, testable and reversible, and it delivers the audit's headline defect fix without new architecture | Starting Phase 2 registry and Phase 1 exposure together makes the diff too large to review or revert cleanly | None | Sets the delivery rhythm: small, tested, revertable steps |

### 2.1 Required for Phase 1

Decisions 1, 2, 3, 5, 6, 10, plus the sequencing statement in 9.

### 2.2 Can safely wait until Phase 2 or later

Decision 4 (implementation, not the turn-ending rule), decision 7, decision 8.

### 2.3 Long-term architectural decisions

Decisions 5, 6, 7, 8 stand as architecture. Decisions 2 and 3 are policy precedents that outlive Phase 1 and will bind Extension and Plugin tooling.

## 3. Phase 1 scope, restated for approval

1. Replace the hand-written five-name list with registry-driven exposure.
2. Supply real descriptions and argument schemas per tool.
3. Start with ten read-only tools.
4. Preserve Ask, Edit and Agent restrictions.
5. Add a parity test over registration, exposure and execution.
6. Document an explicit reason for every registered tool that remains unavailable.
7. Preserve existing working functionality.
8. No Extensions, no Plugins, no MCP expansion, no marketplace.

Interpretation I am recommending, stated plainly so it can be corrected: "ten read-only tools" means ten read-only tools visible to the model in total, of which three (`read_file`, `list_files`, `search_files`) are already exposed and seven are newly exposed. This yields the safest possible first wave: no new mutation surface at all, and the user-visible improvement comes from better descriptions and schemas plus seven genuinely useful reads. If you meant ten additional tools, the ten below still work, and I would add `web_fetch` and `lsp_definition` as the extras, both of which need the caveats in section 4.

## 4. The proposed first ten read-only tools

All ten are verified in the current source. Every argument schema below matches the argument reads at the dispatch site in `AgentTools.kt:172-215`, which is also what `TOOLS_DESCRIPTION` (`:34-130`) documents.

| # | Tool | Implementation | Source | Schema | Expected result | Permission | Why first ten | Extraction needed |
|---|---|---|---|---|---|---|---|---|
| 1 | `read_file` | `readFile` | `AgentTools.kt:286`, dispatch `:182` | `{type:"object",required:["path"],properties:{path:{type:"string"}}}` | File text, or an explicit not-found or denied message | None, reads project or guest paths through `resolveToolPath` | Already works and already exposed; keeping it anchors parity for the other nine | No |
| 2 | `list_files` | `listFiles` | `AgentTools.kt:329`, dispatch `:184` | `{required:["path"],properties:{path:{type:"string"}}}` | Entry names for one directory | None | Same: proven, exposed, and the natural pair to `read_file` | No |
| 3 | `search_files` | `searchFiles` | `AgentTools.kt:340`, dispatch `:185` | `{required:["path","pattern"],properties:{path:{type:"string"},pattern:{type:"string"}}}` | Matching lines with file and line | None | Same, and it is the only project-wide text search the agent has | No |
| 4 | `git_status` | `gitStatus` | `AgentTools.kt:437` (shell via `gitRun`, `:391`), dispatch `:189` | `{properties:{repo_dir:{type:"string"}}}` | Porcelain status text | Read-only, no approval | Immediate value for any repo task, zero mutation risk, and it proves the hidden-tool path works end to end | No |
| 5 | `git_diff` | `gitDiff` | `AgentTools.kt:443`, dispatch `:190` | `{properties:{staged:{type:"boolean"},repo_dir:{type:"string"}}}` | Unified diff | Read-only, no approval | The single most requested capability in a coding assistant and already implemented | No, but bound the output in the tool layer, since it currently returns unbounded text |
| 6 | `detect_secrets` | `detectSecrets` | `AgentTools.kt:476`, dispatch `:193` | `{properties:{text:{type:"string"},path:{type:"string"}}}` | List of suspected secrets | Read-only, no approval | Pure function over text or a file, no external dependency, and directly useful before a commit | No |
| 7 | `read_memory` | `AgentMemory.readAll` | `AgentMemory.kt:48`, dispatch `:197` | `{properties:{}}` | Stored agent memory entries | None | Small, self-contained, and makes the agent's own prior notes usable instead of invisible | No |
| 8 | `list_tasks` | `AgentScheduler.listTasks` | `AgentScheduler.kt:115`, dispatch `:212` | `{properties:{}}` | Scheduled task names and cron | None | Lets the agent answer "what is scheduled" without mutating anything | No |
| 9 | `read_entities` | `AgentEntityManager.read` | `AgentEntityManager.kt:46`, dispatch `:204` | `{required:["entity"],properties:{entity:{type:"string"},filter:{type:"string"}}}` | Records as JSON | Read-only, no approval | The only way to inspect app data, and read is the safe half of the entity group | No |
| 10 | `list_connectors` | `AgentConnectorManager.listConnectors` | `AgentConnectorManager.kt:83`, dispatch `:199` | `{properties:{}}` | Connected and available services | None | Read-only inventory of Plugins-to-be, useful before any connector action exists | No |

Considered and deliberately excluded from wave one, with reasons:

- `git_branch`: the same tool performs create, switch, merge and delete (`AgentTools.kt:428-432`). Exposing it is not read-only unless the action is narrowed, which is new code, so it belongs in wave two with an action allowlist.
- `web_fetch`: read-only in the strict sense but it is a network egress channel, and fetched content is untrusted input. It needs a host policy and untrusted-content marking first. Recommended wave two, first item.
- `lsp_definition`, `lsp_symbols`, `lsp_hover`: genuinely valuable but require the editor extraction discussed in section 6, so they cannot ship in a wave that promises zero new implementation.
- `list_open_editors`, editor selection and buffer reads: same extraction problem.
- `get_secret`, `save_secret`: decision 2.
- `use_connector`, `connect_service`, `request_connector`: not read-only.
- `web_search`, `delete_memory`, `schedule_task`, `cancel_task`, `create_entity`, `update_entity`, `delete_entity`, `git_commit_push`, `upload_file`, `install_package`: mutating or externally visible, all wave two or later.

Net effect of wave one: 32 registered, 10 exposed, 22 hidden with a written reason each. Three of the ten are already exposed, so the behavioural delta is seven new read-only capabilities plus real schemas and descriptions for all ten.

## 5. Git and editor findings

### 5.1 Git

Two paths exist and they do not agree.

- Structured: `scm/GitService.kt`, with `status` (`:68`), `add` (`:165`), `unstage` (`:185`), `discardFile` (`:201`), `commit` (`:217`), `push` (`:255`), `pull` (`:283`), `fetch` (`:299`), `branches` (`:314`), `checkout` (`:347`), and branch create, delete and rename (`:354-362`). Returns typed `GitResult` and `ScmRepoState`.
- Shell: `AgentTools.gitRun` (`:391`) feeding `gitStatus` (`:437`), `gitDiff` (`:443`), `gitCommitPush`, `gitPullRebase` and `gitBranch` (`:428-432`). Returns raw text.

Recommendation for Phase 1: unify nothing. Advertise `git_status` and `git_diff` on their existing shell implementation, because the parity guarantee requires that exposure change nothing about behaviour. Introducing the structured path in the same change would mean the exposure PR also alters git semantics, which makes both unreviewable and unrevertable.

Recommendation for Phase 2: for new read tools (`git.log`, `git.changes`), build on `scm/GitService` so new work does not add a third dialect. Migrate the existing five git tools later, deliberately, with their own tests, and only if the structured output is demonstrably better for the model. The duplication is a real finding but not a Phase 1 blocker, and hiding it behind an exposure change would be the wrong kind of efficiency.

### 5.2 Editor intelligence

Go to definition exists as `onLspDefinition` (`CodeEditor.kt:693`, invoked at `:2099-2104`), peek definition at `:618` and `:637`, and workspace symbols as `lspWorkspaceSymbolProvider` (`CodeEditor.kt:655`). Hover exists as `LspHoverHandler`. All of them are Composable parameters, threaded through `CompletionFetchEffect.kt:34` and `:1404`, and none is reachable from outside the UI.

Recommendation for Phase 1: do not extract. Extraction means hoisting those callbacks into a service with a lifecycle independent of a live editor composition, which is real design work and belongs in Phase 3. Advertising them early would also break the parity promise: the tool would exist in the prompt but fail whenever no editor is composed.

Recommendation for Phase 3: build one narrow interface (`LanguageService` with `definition`, `references`, `symbols`, `hover`, `diagnostics`) owned outside Compose, prove it with `definition` alone, then wrap the rest. `find_references` has no wrapper at all today, so it is new protocol work, not extraction.

## 6. Testing and acceptance criteria

| Requirement | Test | Type |
|---|---|---|
| Every registered tool has a known exposure status | Enumerate `BUILTIN_TOOL_NAMES`, assert each appears in exactly one of two sets, exposed or hidden | Unit, pure |
| Every exposed tool has a valid schema | Assert schema parses as JSON, has `type: object`, and declares every argument the dispatch site reads | Unit, per tool |
| Every exposed tool has a real execution route | Assert each exposed name resolves to a branch in `executeTool` and is not the `plan` stub | Unit |
| Mode permissions are enforced per tool | Table test: for each of Ask, Edit, Agent, assert the exact advertised set and that `allowsTool` agrees | Unit, snapshot with explicit lists |
| No forbidden tool becomes accessible | Assert `save_secret`, `get_secret`, `delete_entity`, `git_pull_rebase` are never advertised in any mode, and that a call to each is refused rather than executed | Unit, negative |
| Hidden tools have documented reasons | Assert the hidden map covers all 22 names with a non-blank reason, and fails on any newly added undocumented tool | Unit, completeness |
| Existing behaviour does not regress | Keep the current five-tool transcript, tool-round and staged-write tests green; assert the five still behave identically when they are the only selection | Existing suites plus one new regression |
| Only permitted and relevant tools reach the model | Assert the built prompt contains only advertised names for the mode, contains their schemas, and contains no hidden name string | Unit on prompt construction |
| Staged-write semantics survive | In Edit mode, `write_file` still stages and reports staged, never writes | Existing `EndpointModelSafetyTest` family plus chat staging test |

Acceptance criteria for Phase 1:

1. 32 registered, 10 exposed, 22 hidden, each hidden name carrying a written reason in code.
2. A single test fails if exposure and execution disagree, and it must fail on the pre-Phase-1 tree.
3. The prompt carries names, descriptions and JSON schemas for the ten, and no hidden name appears anywhere in it.
4. Ask exposes only the three read-only file tools; Edit exposes those plus `write_file`; Agent exposes the ten plus `run_command` and `write_file`, matching the approved matrix.
5. No mutating tool is reachable in a mode where it was previously refused.
6. All existing CI suites remain green, with the count reported.

## 7. Risks and unresolved questions

| Risk or question | Impact | Position |
|---|---|---|
| Registry without a discovery layer could still grow into a long prompt | Token bloat as tools reach 50+ | Phase 1 caps at ten; the discovery tools and token budget arrive in Phase 2 |
| `git_diff` and `git_status` return unbounded shell text | Context blowout on large diffs | Add a size bound inside the tool wrapper in Phase 1, it is a few lines and prevents a real failure |
| `read_entities` requires backend configuration | Tool looks broken when unconfigured | Return an honest "not configured" result, and document it; do not hide the tool |
| `list_connectors` surface changes when Plugins land | Churn in a tool the model already uses | Keep the name; Plugins extend what it reports, not what it is called |
| Ten total versus ten additional | Scope ambiguity | Stated interpretation in section 3; correct me if you meant the other |
| Schemas declared by hand can drift from the executor | The same class of bug, moved | The per-tool schema test asserts every argument read at the dispatch site is declared |
| Parity test could be satisfied by a stub | False confidence | The test excludes `plan` explicitly and asserts non-stub results for the ten |
| Approval prompts multiplying in Ask mode | Noise | Ask mode in wave one contains no approval-gated tool, so no new prompts appear |

Unresolved, needing your answer: whether `web_fetch` should be in wave one as an eleventh tool (I recommend no), and whether `git_branch` should be narrowed to `list` and added now (I recommend wave two, with the action allowlist as its own reviewable change).

## 8. What I need from you

Approve decisions 1, 2, 3, 5, 6, 9 and 10 to unblock Phase 1, confirming or editing the ten-tool list in section 4. Decisions 4, 7 and 8 can be deferred; the only part of 4 that matters now is the turn-ending rule, which affects the row model rather than the exposure list.

No implementation has started. Nothing will start until you approve.

## 9. Appendix A: `save_memory` candidate (added 2026-10-30)

This appendix records an additional candidate raised during review. Sections 1 to 8 and decisions 1 to 10 are unchanged, and no decision was renumbered.

### 9.1 Registration and wiring (verified)

| Item | Value |
|---|---|
| Registered name | `save_memory` |
| Registry | `AgentTools.BUILTIN_TOOL_NAMES`, position 16 of 32 (`AgentTools.kt:151-161`) |
| Dispatch branch | `AgentTools.kt:196` |
| Implementation | `AgentMemory.save` (`agent/AgentMemory.kt:36-46`) |
| Sibling tools | `read_memory` (`:197` → `readAll` `:48`), `delete_memory` (`:198` → `delete` `:59`) |
| Model exposure | Not exposed. Absent from `AgentToolWire.ALL_TOOLS` (`:179-181`), so unreachable in Ask, Edit and Agent |
| Schema | `{"type":"object","required":["key","value"],"properties":{"key":{"type":"string"},"value":{"type":"string"}}}`. Both arguments are read with `getString`, which throws when absent, and the throw is caught by the `executeTool` wrapper as an error string |
| Storage | `filesDir/agent_memory/memory.json`, one flat JSON object of key to string, written through `AtomicJson.write` |
| Caps | 256 KB total store (`MAX_STORE_BYTES`, `:27`) |

### 9.2 Does it persist

Yes. This is real persistence, not a confirmation message. `save` reads the map, puts the key, and writes through the atomic writer, returning success only when the write succeeded (`:38-45`). Failures are reported honestly rather than claimed as success, and the same honesty applies to `delete` (`:63-66`).

### 9.3 Asymmetry that matters

Write and read are not symmetric. `save` accepts an arbitrarily large value up to the 256 KB store cap, but `readAll` truncates every value to 200 characters (`:53`, `key: ${map.getString(key).take(200)}`). So an agent can store a detailed decision and then only ever see the first 200 characters of it. Nothing warns about this.

### 9.4 Why Copilot has difficulty using it

Three independent disconnects, none of which is a persistence bug:

1. **Never advertised.** The name is not in the prompt, so the model has no reason to emit it, and `allowsTool` would refuse it if it did.
2. **Never injected.** There is no memory section in the system prompt. `ChatSession.kt:330` composes `ChatModePrompts.instruction(mode)` plus workspace context, and nothing reads `AgentMemory` for the prompt. Memory is therefore invisible unless the model calls `read_memory`, which is also unadvertised.
3. **Truncated and unindexed on read.** Even a successful call returns 200 characters per value with no size hint, so long project context cannot round-trip.

Documentation drift compounds it: `terminal/ProotInstaller.kt:1306` tells the guest shell that memory "writes ~/AGENT_MEMORY.md, injects into AI context", and `:1327` repeats it. Neither statement is true of the current implementation, which writes internal storage and never injects.

### 9.5 Permission, persistence and privacy review

| Concern | Finding |
|---|---|
| Scope | Global to the app. No project or workspace partition, so memories from one project leak into another |
| At rest | Plaintext JSON in internal storage, not encrypted. A value containing a token is stored in the clear |
| Consent | No approval gate on `save`, and currently no exposure, so the question is latent; exposing it without a gate would let any turn write arbitrary text to durable state |
| Deletion | `delete` has no confirmation and no undo. There is no UI listing keys, so a user cannot audit or clear memory from the app today |
| Overwrite | Keys overwrite silently with no version or timestamp, so a corrected decision and an old one are indistinguishable |
| Untrusted content | A value could carry injected instructions that a future read feeds back into the prompt. Memory is a persistence channel for prompt injection |

### 9.6 Recommendation

Treat `save_memory` as candidate 11, state-changing, and keep it out of Phase 1. Phase 1 has no state-changing additions by design, and the tool's gaps are retrieval and governance, not exposure. Exposing it now would give the model a write channel it cannot read back meaningfully, which is worse than the current honest silence.

Preferred path, for Phase 2, as a narrowly scoped and permission-controlled memory operation rather than a bare tool:

1. Namespace keys per project, and cap key and value lengths at write time with an explicit error, not a silent truncation later.
2. Make read symmetrical: return full values up to a documented per-key cap, or return a key index plus targeted reads.
3. Inject a bounded digest (key list, or the N most recent entries) into the system prompt so memory is visible without a tool call.
4. Gate writes behind the existing approval mechanism, and add an in-app surface to list, edit and clear entries.
5. Treat stored values as untrusted text when they re-enter the prompt, and never allow them to authorise actions.

The alternative, Option C, is also defensible: fix the infrastructure without adding a new tool, by injecting a read-only digest and letting the app or the user own writes. I recommend against it only because it removes the agent's ability to record a decision mid-task, which is the actual capability you want.

### 9.7 Proposed tests

1. `save` then `readAll` round-trips a value longer than 200 characters without silent loss, or fails loudly.
2. `save` at the 256 KB boundary returns the honest failure string and leaves the existing store intact.
3. Missing `key` or `value` produces a structured argument error, not a crash and not a partial write.
4. Keys are partitioned per project: a write in project A is not visible in project B.
5. Memory content re-entering the prompt is marked untrusted and cannot trigger a tool call.
6. `delete` removes the key, survives restart, and is reported honestly on write failure.
