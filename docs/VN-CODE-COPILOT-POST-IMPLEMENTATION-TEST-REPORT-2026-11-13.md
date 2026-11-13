# VN Code Copilot - Post-Implementation Test Report (2026-11-13)

## 1. Executive summary

This phase tested the four Dynamic Tool Loading branches (Lean Tool Mode, Tool Relevance, Tool
Groups, On-Demand Loading) as one system rather than four features, then tested that system against
the components it is supposed to cooperate with.

**Final verdict: PASS WITH LIMITATIONS.**

Nine new integrated tests were written; seven passed on first authoring and two failed in ways that
led to two genuine defects being fixed. One further limitation was found that cannot be fixed without
an owner decision on prompt size, and is recorded as **L23** rather than silently changed.

Two defects were fixed in this phase:

1. **A no-op tool load bumped the loadout version.** `ToolLoadout.load` stamped a new version whenever
   anything was *accepted*, including names already loaded. A replayed load (a retry, a continuation,
   a re-executed turn) therefore rebuilt the system instruction for a set that had not changed.
2. **A request that names a capability could fail to surface it.** `ToolRelevance` had no synonym for
   `git status`, `zip`, `unzip`, `archive` or `merge`, so "check git status" and "look inside the zip"
   scored those tools at zero and the single available relevance slot went elsewhere.

One limitation was found and **not** fixed, deliberately:

3. **Each mode's tool target is exactly one above its core set** (ASK 9 core / target 10, AGENT 24 /
   target 25). Relevance can therefore admit at most **one** request-specific tool per turn. The
   10/16/25 targets are the lean-mode contract asserted by `DynamicToolsTest`, so raising them is an
   owner decision about prompt size, not a testing fix. Logged as **L23**.

## 2. Repository and commit tested

- Repository `wisdom131-max/codespace-ide-mobile`, branch `main`.
- Base commit `b356d22` (Item 38 documentation), CI run 43307784571 for the previous commit was green.
- Working tree at test time: `ToolOffer.kt`, `ToolLoadout.kt` modified, one new test file, one new
  documentation pair (this report and the prompt matrix).

## 3. CI runs and job results

| Commit | CI run | Verdict |
|---|---|---|
| `6db186f` (Item 36 fix) | 43305393117 | success (build job success) |
| `b356d22` (Item 38 docs) | 43307784571 | success |
| this phase's commit | see `docs/IMPLEMENTED.md` row and the push message | recorded after push |

Local totals for this phase: **2681 tests, 1 failure, 3 skipped** on `testDevDebugUnitTest`.
The single failure is `BackupTarTest.failureMessageCarriesRealTotals`, the known root-owned sandbox
artefact (**L15**). It is left visible; no assertion was weakened.

## 4. Four-branch Dynamic Tool Loading System

New test class: `DynamicToolLoadingIntegrationTest` (9 tests, all passing).

| Test | Branches exercised | Result |
|---|---|---|
| `researchRequestOffersResearchToolsAndKeepsTheRestLoadable` | LEAN + RELEVANCE + ON-DEMAND + GROUPS | PASS |
| `aRelevanceMatchNeverRemovesTheModesOwnCoreTools` | LEAN + RELEVANCE | PASS |
| `gitInvestigationSurfacesGitToolsAndRefusesTheWriteItCannotRunYet` | LEAN + RELEVANCE + ON-DEMAND + mode enforcement | PASS |
| `archiveThenDocumentIsTwoLoadsAndOneGroup` | GROUPS + ON-DEMAND + LEAN | PASS (after fixing the version defect) |
| `loadingIsIdempotentAndBumpsTheVersionOnceSoContinuationDoesNotDuplicate` | ON-DEMAND + continuation | PASS (after fixing the version defect) |
| `theCapIsEnforcedAndSaysSoRatherThanSilentlyDropping` | ON-DEMAND cap | PASS |
| `unknownAndMalformedLoadRequestsAreRejectedWithoutChangingState` | ON-DEMAND safety | PASS |
| `clearingTheTaskDropsEverythingASessionChangeShouldDrop` | lifecycle | PASS |
| `everyExposedToolHasAGroupAndEveryGroupNameIsACategory` | GROUPS | PASS |

Measured offer sizes (from a probe run, since these numbers are the contract):

| Mode | Core | Target | Exposed | Offered for a typical request |
|---|---|---|---|---|
| ASK | 9 | 10 | 55 | 10 |
| EDIT | - | 16 | - | up to 16 |
| AGENT | 24 | 25 | 113 | 25 |

## 5. Integrated workflow results

Workflow traces below record what actually participated.

### Scenario 1 - Research and file analysis

```
request: "search the docs for how retries work and cite sources"
LEAN       ASK offers 10 of 55 -> note says "the rest can be loaded with tool_search"
RELEVANCE  search_files present; no destructive tool offered
GROUPS     row for json_query has group "data" (a category, never a stage name)
ON-DEMAND  tool_search("json query") finds json_query; load accepted; version +1
RESULT     PASS
```

### Scenario 2 - Git investigation and recovery

```
request: "the branch is broken after a bad merge, check git status and diff"
LEAN/REL   ASK offers git_diff (core). git_status was NOT offered before the synonym fix,
           even though the request named it - RELEVANCE DEFECT, fixed by adding
           "git status" and "merge" synonyms.
ON-DEMAND  load("git_status") accepted in ASK
MODE GUARD load("service_write") in ASK REJECTED (mode enforcement, not model goodwill)
RESULT     PASS after the fix
```

### Scenario 3 - Archive browsing, selective extraction, document analysis

```
request: "look inside the attached zip and summarise the pdf"
LEAN/REL   AGENT offered zip_extract; zip_list lost the single relevance slot (L23)
ON-DEMAND  load("zip_extract") accepted; version delta exactly 1
GROUPS     all zip_* tools share one category ("files"), same group as read_file -
           OBSERVATION, recorded, not a defect: the group is the tool's category
RESULT     PASS WITH LIMITATION (L23)
```

### Scenario 4 - Multi-step agent task and continuation

```
request: load zip_extract + json_query, then a continuation replays the same load
ON-DEMAND  first load: 2 accepted, version v
           replay of the same load: version STILL v (after the fix; before the fix it bumped)
           new tool base64_codec: version v+1 exactly
CAP        13th borrowed tool refused with atCap = true
RESULT     PASS after the fix
```

## 6. Cross-system compatibility

| System | What was exercised | Result |
|---|---|---|
| Thinking Tree | Catalogue groups asserted disjoint from stage names (`plan/research/read/write/verify/thinking/done`) | PASS |
| Tool registry consistency | Every exposed tool has a group; no duplicate rows in any mode | PASS |
| Mode enforcement | A tool a mode does not expose cannot be loaded, even when named | PASS |
| Approval | `service_write` not exposed in ASK; forced approval asserted by `AgentFlowGateScopeTest` | PASS (unit) |
| Facts / long context | `ContextDigestTest` (12) + `SessionHandoffTest` | PASS (unit), not re-run as a live handoff |
| Provider text protocol | `ToolRegistry` specs are ordinary `<tool>` entries; `AgentToolWire` tests unchanged | PASS (unit) |
| External services | `ServiceApiTest` (36) | PASS (stub-tested only) |
| Archives / documents | `ZipEngineTest`, `ArchiveEngineTest`, `RarEngineTest`, `ZstdEngineTest`, `DocTextTest` | PASS (real RAR fixtures unavailable - L2) |

## 7. Provider compatibility results

Not independently re-verified in this phase. The text protocol is provider-independent by
construction (`ToolRegistry` -> `<TOOLS>` block, `AgentToolWire` parsing), and `ProviderRetryTest` (9)
plus `ChatFailureAdviceTest` (4) cover the retry/advice path. No live provider was called; no
fragmented-stream test was re-run beyond the existing `SseParserTest` suite.

Classification: **UNVERIFIED for this phase**, covered earlier by unit tests only.

## 8. Security and reliability results

| Property | Evidence | Result |
|---|---|---|
| Duplicate tool dispatch | `ToolDispatchGuardTest` (ledger short-circuit) | PASS (unit) |
| Duplicate replies | `RequestGate` + `ContinuationDispatchTest` | PASS (unit) |
| No-op load must not change state | new test + fix in `ToolLoadout` | PASS (regression test added) |
| Unknown tool rejection | `unknownAndMalformedLoadRequestsAreRejectedWithoutChangingState` | PASS |
| Load cap | `theCapIsEnforcedAndSaysSoRatherThanSilentlyDropping` | PASS |
| Credential host binding / redirects | `CredentialHostGuardTest`, `ServiceApiTest` | PASS (stub) |
| MCP forced approval | `McpApprovalForcedTest`, `AgentFlowGateScopeTest` | PASS (unit) |
| Archive path containment | `ZipEngineTest.traversalAndAbsoluteNamesAreRefused` | PASS |

Prompt-injection through a retrieved file is covered only by the prompt text in the new matrix
(slot 9 per tool), not by an automated test. That is a gap, recorded below.

## 9. Defects discovered and fixes applied

| # | Defect | Root cause | Fix | Regression test |
|---|---|---|---|---|
| 1 | A replayed load bumped the loadout version, rebuilding the system instruction | `load()` stamped a version when anything was accepted, including already-loaded names | version now stamped only when the loaded SET changes | `loadingIsIdempotentAndBumpsTheVersionOnceSoContinuationDoesNotDuplicate` |
| 2 | A request naming a capability did not surface it (`git status`, `zip`, `archive`, `unzip`, `merge`) | missing synonyms; those tools scored 0 and never won the slot | synonyms added; the phrase form is supported because scoring uses `contains` | `gitInvestigationSurfacesGitToolsAndRefusesTheWriteItCannotRunYet`, `archiveThenDocumentIsTwoLoadsAndOneGroup` |

Both fixes are two-line-scale changes with tests. No security check was relaxed, no assertion was
deleted, and no unrelated editor code was touched.

## 10. Regression test evidence

- `com.codespace.ide.chat.panel.*` (595 tests) - PASS after the fixes.
- Full `testDevDebugUnitTest` - 2681 tests, 1 failure (`BackupTarTest`, L15), 3 skipped.
- New test class: `DynamicToolLoadingIntegrationTest`, 9 tests.

## 11. Limitations register (this phase)

**L23 (new). Tool Relevance can admit one tool per turn.** Each mode's target is core+1, so relevance
has a single slot. A workflow needing two request-specific tools (list then extract) must load the
second through `tool_search`. Consequence: the offered set is predictable and small, but a model that
does not think to search will do the first thing only. Not fixed because 10/16/25 are the lean-mode
contract (`DynamicToolsTest`); raising them is an owner decision about prompt size. Classification:
**PASS WITH LIMITATION, intentional ceiling, workaround exists (tool_search + load).**

**L24 (new). `/tools` groups archives and documents together.** `zip_*` and `read_file` both belong to
the `files` category, so the catalogue does not separate archive tools from document tools. It is the
category, not a stage, so it does not corrupt the Thinking Tree. Classification:
**PASS WITH LIMITATION, cosmetic.**

**L25 (new). Prompt-injection handling for retrieved file content is untested automatically.** The
protocol rule exists in instructions, and the new prompt matrix gives each tool an injection slot, but
no automated test feeds a hostile file through `read_file` and asserts the model resists it. That
requires a live model and is out of scope for a deterministic suite. Classification: **UNVERIFIED.**

Carried forward unchanged: L2 (real RAR fixtures), L5 (verification covers file writes), L15 (root
sandbox), L18 (no live external service), L20 (resolved for GitHub logs), L21 (no token refresh),
L22 (project-scoped credentials).

## 12. Features not tested or not independently verified

- Live providers of any kind (no API key was used; no network call to a model was made).
- Live external services: GitHub, GitLab, Bitbucket, Gitea, Jira, custom REST.
- Real RAR archives (no legally producible fixture).
- Device behaviour of any kind: no APK was built for testing and no phone test was requested.
- The 1200 prompts in `docs/TOOL-PROMPT-MATRIX-2026-11-13.md` were authored from registry metadata.
  They are test cases, not results.
- Cross-session handoff through the real UI (the pure handoff logic is tested; the UI path is not).

## 13. Remaining risks

1. The relevance ceiling (L23) makes tool discovery load-bearing: if `tool_search` regresses, the
   Copilot loses capabilities silently in every mode.
2. Two of the four branches are now covered by an integrated test that depends on registry *contents*
   (`zip_list`, `git_diff`, `json_query`). If a tool is renamed, these tests fail loudly, which is
   correct but means the test data is coupled to the catalogue.
3. Nothing in this phase exercised approval cards end to end through the UI; approval is asserted at
   the gate level only.

## 14. Final verdict

**PASS WITH LIMITATIONS.**

The four Dynamic Tool Loading branches cooperate as designed: a lean, mode-enforced offer; relevance
that can no longer hide a tool a request names; a catalogue grouped by category and never by stage;
and bounded, idempotent on-demand loading that a continuation can replay without duplicating state.
Two defects were found by testing the branches together that no single-branch test caught, and both
are fixed with regression tests.

The verdict is not a clean PASS because: relevance admits one tool per turn (L23), archive and
document tools share a group (L24), prompt-injection resistance is not automatically tested (L25), no
provider or external service was contacted live, and the 1200-prompt matrix is a plan rather than a
result set.
