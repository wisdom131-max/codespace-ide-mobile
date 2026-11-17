# Project Eight — Integration reliability and security

**Branch:** `project-eight`, based on `main` at `b9b4008`. Project Seven (`project-seven`, tip
`21478b5`) is still unmerged, so this branch is the integrated baseline of Projects One to Six plus
`main`'s Copilot UX; Project Seven's own suite is green on its own branch and is quoted, not merged.

**CI: run `44005876121` on `1cb669b`, 26/26 steps, success.**

**Status: COMPLETE at implementation, test and CI level on `project-eight` (run `44046012394` on `75fee2a`), merged into `main` in merge commit `290b985` and verified locally on the merged tree. Not device-verified, and no live-provider or live-MCP verification was performed.** Ten areas were inspected. Three areas produced confirmed defects, all fixed and
tested here. The remaining areas were audited and produced no confirmed defect in this pass, which is
a statement about the evidence gathered, not a claim that they are safe against everything.

## Confirmed findings

| # | Severity | Area | Component | Finding | Fix | Regression test | Status |
|---|---|---|---|---|---|---|---|
| 8-01 | HIGH | 8.6 File/archive | `ArchiveVerifier.verifyListing` | Only an entry containing `..` was refused. An ABSOLUTE entry (`/data/data/<pkg>/...`), a drive-rooted entry (`C:/...`), a UNC path (`//host/share`) and a control-character name all reached a **VERIFIED** verdict, so the caller was told a hostile archive had been checked. | `unsafePath()` rejects absolute, network-rooted, drive-rooted, climbing and control-character names, and every entry is filtered through it. | `Stage8ArchiveTest.an absolute entry is refused rather than verified`, `a drive-rooted or network entry is refused`, `a climb hidden mid-path is refused` | Fixed, locally tested, CI-green |
| 8-02 | HIGH | 8.6 File/archive | `ArchiveVerifier.parseListing` / `verifyListing` | Listing lines the parser could not read were silently DROPPED, and the archive was still reported **VERIFIED**. A malicious entry written as an unparseable line therefore escaped review and was approved. | Unparsed lines are counted; any skipped line now returns INCONCLUSIVE with the count. Size parsing widened from 2–9 to 1–9 digits so a one-digit size is no longer mistaken for prose. | `Stage8ArchiveTest.an absolute entry is refused rather than verified` | Fixed, locally tested, CI-green |
| 8-03 | LOW | 8.6 File/archive | `ArchiveVerifier.CORRUPT` markers | Common corrupt-archive wording ("not a zip archive", "truncated", "unexpected eof") fell through to INCONCLUSIVE instead of being recognised as a corrupt archive. Not a security hole: the verdict was still not VERIFIED. | Markers extended. | `a corrupt archive is refused and an unreadable one is not called clean` | Fixed, locally tested, CI-green |

## Areas audited with no confirmed defect in this pass

Assertions below are behaviour tests against the production component, not dependency scans.

| Area | What was checked | Evidence | Residual risk |
|---|---|---|---|
| 8.1 Threat model | Trust boundaries read from the code: model output, tool args, archive entries, connector responses, persisted retention, credentials | This report | No formal review by an external party |
| 8.2 Provider reliability | Project Five normalisation, bounded retries, finish reasons, cancellation | Existing provider suites, unchanged by this branch | Live provider behaviour is mocked |
| 8.3 Tool execution | `AgentTools.executeTool` is the only gate; approvals, roles, MCP, prerequisites, duplicate execution | Existing dispatch/journal suites | A future second execution path would not be caught automatically — see deferred item 8-D1 |
| 8.4 MCP and connectors | Forced approval for `mcp_*` and `use_connector` even under AUTO_ALL; no always-allow offer | `Stage8SecurityTest` | Connector output trust is bounded by the tool layer, not re-derived here |
| 8.5 Credentials | Key-shaped secrets removed from message and attachment text, idempotent redaction, credential headers replaced whole | `Stage8SecurityTest` | Redaction is pattern-based; an unusual key shape could pass |
| 8.7 Conversation and memory | Per-session retention, inert historical exchanges, HandoffGuard, corrupt-file recovery | Project Six suites | None new |
| 8.8 Agent and roles | Role intersection, plan approval, bounded recovery | Project Three suites | Malicious-plan corpus is finite |
| 8.9 Android lifecycle | Voice cleanup, stale callback rejection, capture muted while speaking | Project Seven voice suites | Device lifecycle not exercised |
| 8.10 Cross-project | Provider retry with tools, continuation with historical exchanges, archive plus hostile paths | Project Four/Six suites plus this branch | Interaction matrix is sampled, not exhaustive |

## Deferred and open

- **8-D1 (MEDIUM, deferred):** no automated guard fails the build if a second tool-execution path
  appears. A source-scanning architecture test is the right fix and needs the registry's exact call
  shape; it is not in this pass.
- **8-D2 (MEDIUM):** symlink entries inside an archive are not parsed from `tar -tv` output, so a
  symlink that resolves outside the destination is only caught as a count mismatch
  (INCONCLUSIVE), not as a named refusal. Detection bias is safe; the message is not.
- **8-D3 (LOW):** redaction is pattern-based; an unrecognised credential shape survives.
- **Device verification:** none performed for this branch.

## Preservation

No permission, approval, role, MCP, prerequisite or side-effect control was weakened. The only
production changes are the three archive-verifier findings above, each a tightening.

## Checkpoint 2 — execution boundary, archive links, structural redaction

**CI: run `44020458072` on `d7ec25d`, 26/26 steps, success.**

### 8.3 FIXED — the single execution gate is now an enforced invariant

Call graph, read from the source rather than assumed: `AgentTools.executeTool` is the only public entry;
it consults the plan, permission store, approval gate, prerequisite ledger and duplicate guard, then
calls the private `executeToolBody`, which dispatches through one `when` over the tool names. There are
two call sites of the body, both inside the gate file (the normal path and the bounded retry).

`ExecutionBoundaryScan` enforces four things over the real sources and the live `AgentTools.toolNames()`:
the body stays `private`; no other file calls it; no other file declares an `executeTool`; and the
registry and the dispatcher agree in BOTH directions (a name without an arm, or an arm nobody can
reach, fails). Comments are stripped first so documentation about the gate is not mistaken for a call.

Measured on this tree: **122 registry names, 122 dispatch arms, 0 uncovered, 0 unadvertised.**

Negative evidence: five tests inject a bypass and assert the guard fails and names the file - a helper
calling the body directly, a second `executeTool`, an advertised-but-unwired tool, and a wired-but-
unadvertised arm.

Blind spots, stated plainly: it is a source scan, so reflection or a name assembled at runtime would
not be caught, and a tool that executed work outside the gate file through a different function name
would need a matching rule. It catches the ordinary regression, not a determined bypass.

### 8.6 FIXED — archive link targets

Checkpoint 1 disclosed that link entries were not examined. Now `unsafeLink()` reads every `name -> target`
line and refuses: targets that climb (`../`), absolute targets, drive-rooted, network-rooted, targets
with control characters, and link metadata with no readable target. Targets are compared as text and
never resolved, so nothing is followed while verifying. Link lines now parse as entries, so a clean
archive containing only safe relative links can still reach VERIFIED instead of being permanently
INCONCLUSIVE.

**Formats actually tested:** the plain listing format the verifier consumes (`path size` and
`path -> target`). **Not tested:** real ZIP symlink attributes (the ZIP parser used here exposes no link
metadata) or tar's verbose `lrwxrwxrwx` line, which still reads as unparseable and therefore yields
INCONCLUSIVE, not VERIFIED. Hard links and device/fifo entries are not inspected.

### 8.5 FIXED — structural credential shapes

The old tiers only knew issuer prefixes. Added structural rules that apply at both tiers and keep the
name while removing the value: `Bearer`/`Basic` schemes, credential query parameters
(`api_key`, `token`, `access_token`, `refresh_token`, `secret`, `password`, `client_secret`, `key`), and
JSON keys (`api_key`, `token`, `secret`, `password`, `authorization`, `x-api-key`).

Evidence: 8 synthetic cases assert the value never survives while the header name, tool name, status
code, model name and unrelated query parameters do. Idempotence, empty and malformed input are covered,
and ordinary code (`val token = ...`, `basicAuthEnabled()`) is asserted unchanged.

Known blind spots: a bare credential with no prefix, scheme, parameter name or JSON key is
indistinguishable from an ordinary string and is not redacted; base64 payloads are not decoded to look
for secrets; and `CrumbLog` keeps its own narrower rules.

### Deferred issues, status

| Issue | Status |
|---|---|
| Automated guard against a second execution path | FIXED (source-level invariant, 5 negative tests) |
| Archive symlink entries unexamined | PARTIALLY FIXED (listing-level; ZIP link metadata unavailable) |
| Pattern-only redaction | PARTIALLY FIXED (structural shapes added; unlabelled secrets still out of reach) |
| Real ZIP/tar attribute parsing for links | STILL OPEN (needs an archive API that exposes attributes) |
| Runtime proof of link refusal during extraction | STILL OPEN (verifier-level only; extraction path unchanged) |

## Checkpoint 3 - threat model, provider reliability, MCP approval binding

### 8.1 trust boundaries (audited, evidence-based)

| Boundary | Crossing point | Untrusted input | Existing control |
|---|---|---|---|
| Provider to app | stream parsing, tool-call assembly | model text, tool args, finish reason | Project Five normalisation, finish-reason handling, duplicate-reply guards |
| App to tool layer | `AgentTools.executeTool` | tool name + args from the model | single gate, `ExecutionBoundaryScan`, ledger + reconciler, write journal |
| Tool layer to OS | filesystem, archive, shell | paths, archive entries, commands | `unsafePath`, symlink refusal, dispatch guard, containment gate |
| App to external services | MCP, connectors | server tool descriptions, results | forced approval for `mcp_*` and `use_connector`, name+args routing |
| App to storage | sessions, memory, diagnostics | persisted conversation, crumbs | redaction before persistence, bounded storage |
| External content to prompt | research, documents, connector output | page text, extraction output | content is data, never instructions; tool selection comes from the model's own call, not from content |

Residual risk that is NOT fixed here and is not claimed to be: a model can still be persuaded by injected
content to *propose* a harmful call. The boundary above is that the proposal cannot approve itself, cannot
skip the gate, and cannot widen an approval it was not given.

### 8.2 provider and network reliability

Audited: authentication failure, HTTP errors, rate limits, timeouts, connection loss, malformed JSON, empty
responses, interrupted streams, partial tool calls, duplicate events, context-limit and output-limit ends,
cancellation, late events. Existing coverage is strong (Project Five + Project Four). No confirmed defect
found in the retry path itself; behaviour is now pinned by `Stage84ApprovalAndRetrySafetyTest`:
`VERIFIED_FAILED` may retry, `COMPLETED` may not, `UNCERTAIN` may only when read-only.

**Unverified behaviour, stated plainly:** no live provider was contacted. Provider failure paths are
exercised through production components with no network, so "the app handles a 429" is tested at the
classification level, not against a real server.

### 8.4 MCP and connector security

Call graph: model tool call -> `AgentTools.executeTool` -> plan/approval gate -> `dispatchGuard` -> `mcp_*`
branch -> `McpClientManager.callTool` -> server resolution by owning cache entry (prefix collisions already
fixed) -> request. MCP and connector calls are forced through approval: `mustAsk` ignores both the persisted
level and the session set for them, and "Always allow" is not offered.

**8-04 HIGH, FIXED - approval memory was tool-scoped, not agreement-scoped.** Session approvals were keyed
by tool name alone. Approving one `run_command` with "allow for this session" therefore auto-approved every
later command in that session, and approving one `write_file` approved writes to any path. The gate now keys
the session allow on `tool + fingerprint(arguments)`, so the memory covers exactly the call the user saw.
`sessionKey()` falls back to the bare name only when there is no argument summary to bind to.
`AgentFlowGateScopeTest.sessionScopeIsRememberedForThisToolAndTheseArguments` was updated to the stricter
contract; the old assertion encoded the widening.

### Tests

`Stage84ApprovalAndRetrySafetyTest` (10 cases): argument-scoped session allow, cross-tool separation,
blank-summary fallback, forced MCP/connector approvals, no always-allow for them, retry safety per state,
uncertain-note wording, and a synthetic provider error losing its key while keeping its diagnosis.

App: 3,450 tests, 0 failures, 4 skipped. Engine: 278, 0 failures. Dev-debug compile and D8 green.

### Still open

- 8.7 conversation/memory, 8.8 agent roles, 8.9 lifecycle, 8.10 cross-project regression.
- Persisted "Always allow" (`ChatPermissionStore`) is still per tool name; it is an explicit user switch, so
  it was left as designed rather than silently re-scoped. Disclosure, not a fix.
- No live provider or live MCP server was contacted; no device verification.

## Checkpoint 4 - roles, delegation and conversation integrity

**CI: run `44037418472` on `250464a`, 26/26 steps, success.**

### 8.8 FIXED - an explicit tool entry bypassed the read-only rule

`SubagentRoles.scope` added a tool to a role's scope when the name appeared in `role.explicit`, and
did so BEFORE the read-only check. The catalogue is the only thing that kept a read-only role from
being handed a destructive tool, and one edit to `explicit` would have handed `planner` or `review`
`write_file` with no runtime objection. No role lists a write tool today, so nothing was exploitable
as shipped; the mechanism was wrong, not the data.

The rule now lives in one function, `mayInclude(role, meta)`, which every name passes through:

- a writing role may hold anything it is permitted,
- a read-only role may never hold a tool the catalogue marks destructive,
- a read-only role is not handed a tool with no metadata, because it cannot be proved safe.

Withheld tools are still reported in `Scope.withheld` rather than disappearing, so a refusal stays
visible.

### 8.7 AUDITED - conversation integrity, no confirmed defect in this pass

Evidence and residual risk rather than a claim of safety:

- **Continuation.** `HandoffGuard.decide` reuses an empty successor and creates a fresh session once
  the candidate holds a turn, so repeated handoff taps cannot manufacture a chain of empty successors.
  Tested directly, both branches.
- **Historical tool calls.** Continuation carries the prior transcript as data; nothing in the handoff
  path re-enters `AgentTools.executeTool`, and the execution-boundary scan from checkpoint 2 still
  passes, which is what would catch a replay route appearing later.
- **Duplicate replies and stale events.** Delivery and ownership live in the Project Four ledger and
  the generation check in `ChatSession`; both suites still pass unchanged in this checkpoint.
- **Retention.** Summaries and carried facts are bounded by the Project Six limits and are presented as
  context, never as instructions. Not re-tested here beyond the existing suites.

**Residual risk, unverified:** interrupted persistence mid-write is handled by the write journal and
atomic store, but I did not induce a real process kill during a conversation save in this pass, and
no device verification exists. Corrupt-retention recovery was read, not exercised with a damaged file.

### Remaining after this checkpoint

Areas 8.9 (Android lifecycle and resource reliability) and 8.10 (full cross-project audit) are open.
Device verification has not been performed for any checkpoint.

## Checkpoint 5 - lifecycle, resource reliability and the persistence gaps

Scope: Area 8.9, plus the two Area 8.7 test gaps checkpoint 4 disclosed as unverified.

**CI: run `44042056684` on `c8b65ff`, 26/26 steps, success.**

### 8.9 / 8.7 FIXED - a damaged transcript could overwrite the backup that rescued it

| Field | Value |
| --- | --- |
| Entry point | `TranscriptFiles.load` then the next `saveNow` |
| Component | `chat/ChatTranscriptStore.kt` |
| Scenario | The primary transcript is damaged, so load restores the conversation from `.bak`. The damaged primary stays on disk. The next save copies the primary into `.bak` BEFORE writing, so the good backup that had just restored the conversation is replaced by the damaged bytes. If that save then fails in its non-atomic fallback, both copies are lost. |
| Existing protection | Atomic temp-and-rename, a `.bak` rotation, refusal of an unrecognised format, and a skipped-line count for partially readable files. |
| Missing protection | The damaged primary was never retired, so backup rotation could propagate the damage. |
| Severity | MEDIUM (possible loss of the only good copy of a conversation) |
| Fix | On the restore-from-backup path the damaged primary is renamed to `<name>.damaged` (deleted only if the rename fails). It is kept for inspection, is never read again, and can no longer be rotated over a good backup. |
| Regression test | `Stage87PersistenceTest.the_backup_that_restored_a_conversation_is_not_overwritten_by_damage` plus four siblings |
| Status | Fixed, locally tested, CI-green |

### 8.7 gap A CLOSED - interrupted persistence

`Stage87PersistenceTest` drives the real `TranscriptFiles`, not a copy of its rules:

- `an_abandoned_temp_file_is_never_trusted_as_history`: a `.tmp` left by a killed save is not read as the conversation.
- `a_save_into_an_unusable_destination_reports_failure_and_keeps_the_old_copy`: with the destination directory replaced by a file, the save returns a non-blank error rather than claiming success, the earlier copy still loads, and a later save recovers normally.

### 8.7 gap B CLOSED - corrupt-retention recovery

- `a_damaged_transcript_is_not_read_as_valid_history`: undecodable bytes produce a note and the `.bak` copy, never the damage as history.
- `corruption_is_contained_to_the_conversation_that_suffered_it`: an independent healthy conversation loads with no note.
- `restored_history_carries_no_execution_state`: a restored transcript is inert text; the load path holds no reference to the execution gate, so recovery cannot re-run a historical tool call.

### 8.9 audit - no defect confirmed in these paths

| Path | Evidence | Verdict |
| --- | --- | --- |
| Duplicate provider request after UI recreation | The request is owned by `ChatSession`, not by the composable; the send path is guarded by `Continuation.mayStart` and the delivery ledger | No defect reproduced. Unverified on device (activity recreation not exercised on hardware) |
| Stale callbacks after a session switch | Generation checks plus `ReplyOwnership`; existing suites green | No defect reproduced |
| Cancellation reaching the active operation | `session.cancel()` is passed to the voice surface and the Stop control; tool execution cancellation is reported as uncertain, not as success | No defect reproduced |
| Voice resource release | `VoiceModeLayer` holds a `DisposableEffect` on the lifecycle owner; `VoiceSettingsSection` releases its provider on dispose | Audited only. Repeated enter/exit and permission revocation during an active session are NOT verified without a device |
| Archive and file protections | Checkpoints 1 and 2 tests still green (`unsafePath`, symlink targets, skipped-line accounting) | Unchanged, no weakening |
| Memory pressure | Streaming accumulation and tool output retain their existing caps; no new buffer was introduced | No new allocation of unbounded state |

### 8.9 remaining limitations

- Activity recreation, backgrounding during a live stream, and voice microphone/TTS lifecycle are implementation-audited but not device-verified. No JVM test proves them, and none is claimed to.
- A save whose rename fails falls back to `target.writeText`, which is not atomic; it is the last resort after a failed rename and is unchanged by this checkpoint.
- The `.damaged` file is left on disk by design. Nothing prunes it, so a repeatedly damaged conversation will accumulate them.

## Checkpoint 6 - Area 8.10, the full-system audit

**CI: run `44046012394` on `75fee2a`, 26/26 steps, success.**


Scope: all eight projects, all ten Project Eight areas, the eighteen cross-component scenarios.

### What was executed rather than asserted

`Stage810CrossProjectTest` (8 cases) drives the real objects at each seam, not helpers that
echo them: `ToolExchangeLog.statusOf`, `DeliveryLedger`, `HandoffGuard.decide` and
`ExecutionBoundaryScan.scan`. It asserts the properties the whole system leans on -
an uncertain or cancelled exchange is never OK, a refusal is not a failure, a real result
with no success wording still counts, a cancelled execution cannot append an answer, one
execution cannot submit twice, a stale execution cannot own a newer session, an empty
successor is reused while a started one branches, and the boundary scanner still reports a
second call site.

### Confirmed defects in this checkpoint

None. Three test expectations were wrong on first run and were corrected against the real
contracts (`UNSURE: ` is the uncertain prefix, `Decision.Reuse` carries the label, and the
status enum lives in `engine`). No production code was changed, so no fix is claimed.

### Scenario coverage

| # | Scenario | Components | Evidence | Status |
|---|---|---|---|---|
| 1 | Stream ends during a tool call | normaliser, exchange log | `EngineStreamNormaliser`/`ToolResultNormaliser` suites | covered |
| 2 | Duplicate or replayed tool-call chunks | delivery ledger, tool call ledger | `DeliveryLedger` cases here plus `ToolCallLedger` suites | covered |
| 3 | Tool succeeds, stream interrupted | exchange log, delivery | statusOf cases; interruption suites | covered |
| 4 | Uncertain write, provider asks again | `SideEffectRecovery`, journal | `mayRetry(UNCERTAIN, writable) == false` | covered |
| 5 | Context reduction with exchanges present | `ContextPreparer`, summaries | Project Six suites | covered |
| 6 | Continuation with historical tool results | `HandoffGuard`, transcript | reuse/branch case here | covered |
| 7 | Damaged transcript then a later save | `TranscriptFiles` | `Stage87PersistenceTest` | covered |
| 8 | Prerequisite fails after a planning wave | planner, prerequisites | Project Four suites | covered |
| 9 | Role invokes a tool outside its scope | `SubagentRoles` | `Stage88RoleScopeTest` | covered |
| 10 | MCP under AUTO_ALL | `AgentFlowGate` | `Stage8SecurityTest` | covered |
| 11 | Provider error with credential-shaped data | redaction | `Stage85` + structural patterns | covered |
| 12 | File operation interrupted, conversation reopened | transcript store | persistence suite | covered |
| 13 | Output limit with tool activity present | finish reasons, rows | Project Seven suites | covered |
| 14 | Cancel during streaming and tool overlap | ledger, gateway | cancelled-cannot-append case | covered |
| 15 | Stale callback after session replacement | ledger, generation | stale-execution case | covered |
| 16 | Successful output with no success wording | exchange status | OK-without-wording case | covered |
| 17 | Refused tool displayed | bindings, presentation | `Stage711IntegrationTest` | covered |
| 18 | Recovered task with an unfinished step | planner, task_plan | Project Three suites | covered |

### Project status

All eight projects are audited against their implementation with the test evidence above.
Projects One to Seven remain as reported on their own branches; Project Seven's UI, the
30x24dp copy target, the sources row and the voice limitations are unchanged here, since this
branch is the Projects One to Six baseline plus Project Eight. That separation is a fact about
this branch, not a merge: combining Project Seven into main still needs the owner's approval.

### Remaining limitations

- No live-provider, live-MCP or device verification. Every provider and connector test in this
  branch uses deterministic fakes.
- Android lifecycle behaviour (activity recreation, backgrounding, mic/TTS teardown) is
  inspected and component-tested, never exercised on a device.
- The execution-boundary guard is source-level; reflection or a new dispatcher would not be caught.
- Archive link handling is limited to the text listing contract the verifier consumes.
